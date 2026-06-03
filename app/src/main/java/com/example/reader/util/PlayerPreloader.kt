package com.example.reader.util

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * ADR-001: 高性能视频预加载与资源调度器。
 *
 * ── 双槽流水线 ──
 *   Hot Slot:  预取"下一帧"播放器，可被新 prewarm 随意抢占。
 *   Active Slot: 标记 UI 正在使用的 sessionId，严禁预加载逻辑触碰。
 *
 *   take() 匹配 Hot → promotion 到 Active；不匹配 → Cold（冷启动）。
 *
 * ── 非对称 EMA 背压 ──
 *   跟踪解码延迟，α_Attack=0.5（快涨）/ α_Decay=0.05（慢降）。
 *   墙钟空闲 > 30s 时回归基线，模拟硬件冷却过程。
 *
 * ── Layer 1: Player 复用 ──
 *   同格式切换走 stop()+setMediaItem()+prepare()，避免 HAL 重新握手。
 *
 * ── 设备退化 ──
 *   isLowRamDevice=true 时跳过预热，退回纯冷启动。
 */
sealed interface TakeResult {
    /** 预热完成，首帧已解码 */
    data class Ready(val player: ExoPlayer) : TakeResult
    /** 正在热机但未就绪，UI 应显示缓冲动画 */
    data class InProgress(val player: ExoPlayer) : TakeResult
    /** 无可用预热，调用方自行冷启动 */
    data object Cold : TakeResult
}

object PlayerPreloader {

    // ── 常量 ──

    private const val BASELINE_DEBOUNCE = 100L        // 初始 debounce 阈值 (ms)
    private const val MAX_DEBOUNCE_MS = 2_000L        // 动态背压上限 (ms)
    private const val COOLING_THRESHOLD_MS = 30_000L  // 墙钟衰减触发空闲时长 (ms)
    private const val STALE_TIMEOUT_MS = 30_000L      // 槽位泄露超时 (ms)
    private const val ALPHA_ATTACK = 0.5              // 延迟激增时快速响应
    private const val ALPHA_DECAY = 0.05              // 延迟恢复时缓慢降回

    // ── 槽位状态机 ──

    private sealed class Slot {
        data object Empty : Slot()
        data class Preparing(
            val sessionId: String,
            val player: ExoPlayer,
            val startedAt: Long = System.currentTimeMillis()
        ) : Slot()
        data class Consumed(
            val sessionId: String,
            val player: ExoPlayer,
            val consumedAt: Long = System.currentTimeMillis()
        ) : Slot()
    }

    /** 被 UI 持有的播放器 —— 预加载逻辑绝不触碰 */
    @Volatile
    private var activeSlot: Slot = Slot.Empty

    /** 正在预热的下一帧播放器 —— 可被随时抢占 */
    @Volatile
    private var hotSlot: Slot = Slot.Empty

    private var isLowRamDevice: Boolean? = null

    // ── 非对称 EMA ──

    private var emaSmoothed = BASELINE_DEBOUNCE.toDouble()
    private var lastEmaUpdateMs = 0L

    // ══════════════════════════════════════════════════
    //  公共 API
    // ══════════════════════════════════════════════════

    /**
     * 预热一个视频到 Hot Slot。
     * Low-RAM 设备静默跳过。当前 Hot Slot 与 uri 相同 → 无操作。
     * Hot Slot 正在 Preparing 且 sessionId 不同 → 复用 Player 实例换源。
     *
     * 永不触碰 Active Slot。
     */
    fun prewarm(context: Context, uri: String, videoWidth: Int = 0, videoHeight: Int = 0) {
        if (isLowRamDevice ?: checkIsLowRam(context)) return

        cleanupStaleHot()

        val newUri = Uri.parse(uri)

        when (val current = hotSlot) {
            is Slot.Preparing -> {
                if (current.sessionId == uri) return
                // Layer 1: Player 复用 —— 同格式下避免解码器重建
                current.player.stop()
                current.player.clearMediaItems()
                current.player.setMediaItem(MediaItem.fromUri(newUri))
                current.player.prepare()
                hotSlot = Slot.Preparing(uri, current.player)
            }
            is Slot.Empty, is Slot.Consumed -> {
                val player = VideoPlayerFactory.create(context, videoWidth, videoHeight, checkIsLowRam(context)).apply {
                    setMediaItem(MediaItem.fromUri(newUri))
                    prepare()
                    playWhenReady = false
                }
                hotSlot = Slot.Preparing(uri, player)
            }
        }
    }

    /**
     * 取走预热的播放器。
     * 优先匹配 Hot Slot → promotion（Hot → Active）；
     * 不匹配或已消费 → Cold。
     */
    fun take(uri: String): TakeResult {
        // 已在 Active（同一 URI 重复 take）→ 冷启动
        if (activeSlot is Slot.Consumed && (activeSlot as Slot.Consumed).sessionId == uri) {
            return TakeResult.Cold
        }

        // 尝试 Hot Slot
        if (hotSlot is Slot.Preparing) {
            val preparing = hotSlot as Slot.Preparing
            if (preparing.sessionId == uri) {
                val player = preparing.player
                val isReady = player.playbackState == Player.STATE_READY
                // promotion
                activeSlot = Slot.Consumed(uri, player)
                hotSlot = Slot.Empty
                return if (isReady) TakeResult.Ready(player)
                       else TakeResult.InProgress(player)
            }
        }

        cleanupStaleHot()
        return TakeResult.Cold
    }

    /** UI 层归还 Active Slot。由 DisposableEffect.onDispose 调用。
     *
     *  两阶段释放策略：
     *    1. 立即 stop() → 切断音频，防止音轨残留
     *    2. postDelayed 300ms → release()，避开返回动画黄金时段 */
    fun notifyReleased(uri: String) {
        val currentSlot = activeSlot
        if (currentSlot is Slot.Consumed && currentSlot.sessionId == uri) {
            val playerToRelease = currentSlot.player
            // 阶段 1: 立即停止音频播放，防止音轨残留
            playerToRelease.stop()
            // 立即释放状态机槽位，允许新任务进入
            activeSlot = Slot.Empty
            // 阶段 2: 异步清理重型资源，避开 UI 动画黄金期
            Handler(Looper.getMainLooper()).postDelayed({
                playerToRelease.clearMediaItems()
                playerToRelease.release()
            }, 300)
        }
    }

    /** 释放 Hot Slot（Grid 离开 composition 时由 DisposableEffect 调用） */
    fun release() {
        if (hotSlot is Slot.Preparing) {
            (hotSlot as Slot.Preparing).player.apply {
                stop()
                clearMediaItems()
                release()
            }
            hotSlot = Slot.Empty
        }
    }

    /**
     * 记录一次解码延迟，更新非对称 EMA。
     * 可由外部在解码完成后调用，为后续请求提供动态背压参考。
     */
    fun recordDecodeLatency(latencyMs: Double) {
        val now = System.currentTimeMillis()

        // 墙钟衰减通道：idle > 30s 直接回归基线，避免噪声累积
        if (lastEmaUpdateMs > 0) {
            val idleMs = now - lastEmaUpdateMs
            when {
                idleMs > COOLING_THRESHOLD_MS -> {
                    emaSmoothed = BASELINE_DEBOUNCE.toDouble()
                }
                idleMs > 1_000 -> {
                    val weight = (idleMs - 1_000).toFloat() / (COOLING_THRESHOLD_MS - 1_000)
                    emaSmoothed = emaSmoothed * (1 - weight) + BASELINE_DEBOUNCE * weight
                }
            }
        }

        // 非对称 EMA：快涨慢降
        val alpha = if (latencyMs > emaSmoothed) ALPHA_ATTACK else ALPHA_DECAY
        emaSmoothed = alpha * latencyMs + (1 - alpha) * emaSmoothed
        lastEmaUpdateMs = now
    }

    /** 当前 EMA 推选的 debounce 阈值 */
    fun currentDebounceMs(): Long =
        emaSmoothed.coerceIn(BASELINE_DEBOUNCE.toDouble(), MAX_DEBOUNCE_MS.toDouble()).toLong()

    // ── 内部 ──

    private fun checkIsLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return (am?.isLowRamDevice ?: false).also { isLowRamDevice = it }
    }

    /** 释放长期未被 take 的 Hot 槽位 */
    private fun cleanupStaleHot() {
        if (hotSlot is Slot.Preparing) {
            val p = hotSlot as Slot.Preparing
            if (System.currentTimeMillis() - p.startedAt > STALE_TIMEOUT_MS) {
                p.player.release()
                hotSlot = Slot.Empty
            }
        }
    }
}
