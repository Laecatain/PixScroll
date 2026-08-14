# PixScroll

**[English](README_EN.md)** | **中文**

> **像素滚动** — 图片在指尖无缝流淌，一滑到底的本地媒体阅读器。

[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)](https://developer.android.com/about/versions/nougat)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.03-4285F4?logo=googlechrome)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Non--Commercial-blue)](#license)

<!-- TODO: 添加截图 / GIF -->
<!-- ![文件夹列表](screenshots/folder_list.png) ![阅读器](screenshots/reader.png) ![视频播放器](screenshots/video_player.png) -->

**[⬇ 下载最新 Release](https://github.com/Laecatain/PixScroll/releases/latest)**

---

## 功能

- **文件夹分类浏览** — TabRow 横向标签切换图片/视频，自动扫描设备存储
- **双模式阅读器**
  - **滚动模式** (ContinuousScroll) — 上下连续滚动，适合长图/漫画，图片居中显示
  - **翻页模式** (Pager) — 左右翻页，逐页浏览
  - 支持双指缩放、双击复位、进度滑块导航
- **视频播放器** — ExoPlayer (Media3) 极简自定义控件层
  - 沉浸模式全屏播放
  - 双击左侧/右侧快退/快进 ±10s
  - 长按 3 倍速播放
  - 自定义视频帧解码器 (VideoFrameDecoder) 用于缩略图
- **混合媒体引擎** — 双阶段扫描：先展示 MediaStore 结果，后台 FileTreeWalk 补充未索引文件 (jpg/png/webp/heic/avif/mp4/mkv/...)
- **全局搜索** — 防抖输入，跨文件夹搜索图片和视频
- **排序持久化** — 按名称/日期/大小排序，支持升序/降序，DataStore 持久化
- **主题切换** — 亮色 / 暗色 / 纯黑 (AMOLED) 三模式
- **冷启动秒开** — JSON 缓存文件夹列表和 .nomedia 扫描结果
- **性能优化**
  - Coil 三级缓存 + Precision.EXACT + Generation ID 并发控制
  - 双槽视频预加载 + 零延迟退出的 SurfaceView 管理
- **无 DI 框架** — 手动 ViewModel Factory，轻量简洁

## 技术栈

| 层级 | 库 |
|---|---|
| 语言 | Kotlin 2.0.0 |
| UI | Jetpack Compose (BOM 2025.03.00) + Material 3 |
| 架构 | MVVM (ViewModel + `StateFlow`) |
| 导航 | Navigation Compose 2.7.7 |
| 图片加载 | Coil 2.6.0 (coil-compose, coil-video, `VideoFrameDecoder`) |
| 视频播放 | Media3 ExoPlayer 1.3.1 |
| 存储 | DataStore Preferences 1.1.1 |
| 构建 | Gradle 8.7 + AGP 8.4.0 |
| 测试 | JUnit 4, Turbine 1.1.0, kotlinx-coroutines-test |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |

## 架构

MVVM 单向数据流：ViewModel → `StateFlow<State>` → Composable `collectAsState()`

```
com.example.reader/
├── ReaderApp.kt              # Application，Coil ImageLoaderFactory
├── MainActivity.kt            # 单 Activity，权限处理
├── navigation/NavGraph.kt     # 7 个路由
├── data/
│   ├── model/                 # MediaItem, MediaFolder
│   └── repository/            # MediaRepository (接口 + 实现)
├── ui/
│   ├── folderlist/            # 文件夹列表
│   ├── mediagrid/             # 媒体网格 (复用 ReaderViewModel)
│   ├── reader/                # 阅读器 (ReaderScreen + ViewModel + 两种模式)
│   ├── search/                # 全局搜索
│   ├── settings/              # 设置页
│   ├── player/                # 视频播放器
│   ├── common/FastScroller.kt # 网格快速滚动条
│   └── theme/ThemeState.kt    # 主题单例
└── util/
    ├── AppSettings.kt         # DataStore 偏好设置
    ├── FolderCache.kt         # 文件夹列表缓存
    ├── MediaDimensionsCache.kt# 媒体尺寸缓存
    ├── SliderUtils.kt         # 滑块辅助工具
    └── PermissionHelper.kt    # 运行时权限
```

## 构建

### 环境要求

- JDK 17 (Temurin)
- Android SDK API 34
- 设置 `JAVA_HOME` 和 `ANDROID_HOME` 环境变量

### 命令

```bash
# 构建并安装到连接的设备
bash install.sh

# 仅构建 APK
./gradlew assembleDebug

# 运行所有单元测试
./gradlew testDebugUnitTest

# 运行单个测试
./gradlew testDebugUnitTest --tests "*ReaderViewModelTest*"
```

## 许可证

个人非商业使用
