package com.example.reader.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.reader.util.settingsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.reader.ui.folderlist.FolderListScreen
import com.example.reader.ui.folderlist.FolderListViewModel
import com.example.reader.ui.mediagrid.MediaGridScreen
import com.example.reader.ui.player.VideoPlayerScreen
import com.example.reader.ui.reader.ReaderScreen
import com.example.reader.ui.search.SearchScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.reader.ReaderApp
import com.example.reader.ui.settings.SettingsScreen
import com.example.reader.util.VideoCoverStrategy
import com.example.reader.util.VideoPlayerPreference
import com.example.reader.util.UnsupportedVideoUriException
import com.example.reader.util.launchSystemPlayer
import com.example.reader.util.probeLongestEdge
import com.example.reader.util.rememberVideoPlayerPreference
import com.example.reader.util.shouldAutoRouteToSystem
import com.example.reader.util.shouldOpenInApp
import com.example.reader.util.shouldShowChooser

/** Auto-route threshold (px on longest edge). Videos larger than this bypass
 *  the user's video-player preference when the auto-route toggle is on. */
private const val AUTO_ROUTE_THRESHOLD_PX = 1440

object Routes {
    const val FOLDER_LIST = "folder_list"
    const val MEDIA_GRID = "media_grid/{parentId}?type={type}"
    const val READER = "reader/{parentId}/{initialIndex}?type={type}"
    const val VIDEO_PLAYER = "video_player/{videoUri}?thumbnailPath={thumbnailPath}"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val ABOUT = "about"

    fun mediaGrid(parentId: Long, mediaType: Int = 0) = "media_grid/$parentId?type=$mediaType"
    fun reader(parentId: Long, initialIndex: Int = 0, mediaType: Int = 0) = "reader/$parentId/$initialIndex?type=$mediaType"
    fun videoPlayer(videoUri: String, thumbnailPath: String? = null) =
        "video_player/${Uri.encode(videoUri)}?thumbnailPath=${thumbnailPath?.let { Uri.encode(it) } ?: ""}"
}

private fun NavHostController.safePopBackStack() {
    if (previousBackStackEntry != null) {
        popBackStack()
    }
}

@Composable
fun NavGraph(navController: NavHostController) {
    val context = LocalContext.current
    val folderListViewModel: FolderListViewModel = viewModel(
        factory = FolderListViewModel.Factory(context.applicationContext as ReaderApp)
    )

    // Real-time subscription to the video-player preference — changes apply immediately
    // without needing to pop back to FOLDER_LIST.
    val videoPlayerPreference by rememberVideoPlayerPreference()
    // Boolean field — no parse step needed, so no try/catch (IllegalArgumentException)
    // is required here (unlike rememberVideoPlayerPreference).
    val autoRouteHighRes by produceState(false) {
        context.applicationContext.settingsFlow().collect { value = it.autoRouteHighResToSystem }
    }

    NavHost(navController = navController, startDestination = Routes.FOLDER_LIST) {
        composable(Routes.FOLDER_LIST) {
            FolderListScreen(
                viewModel = folderListViewModel,
                onFolderClick = { folder, mediaType ->
                    navController.navigate(Routes.mediaGrid(folder.id, mediaType)) {
                        popUpTo(Routes.FOLDER_LIST)
                    }
                },
                onSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onSearch = {
                    navController.navigate(Routes.SEARCH)
                }
            )
        }

        composable(
            route = Routes.MEDIA_GRID,
            arguments = listOf(
                navArgument("parentId") { type = NavType.LongType },
                navArgument("type") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { backStackEntry ->
            val parentId = backStackEntry.arguments?.getLong("parentId") ?: return@composable
            val rawMediaType = backStackEntry.arguments?.getInt("type") ?: 0
            val mediaType = rawMediaType.takeIf { it != 0 }
            // Scope bound to this destination — coroutines cancel when user navigates away,
            // so an in-flight IO probe cannot dispatch a system player after the user backed out.
            val localScope = rememberCoroutineScope()
            MediaGridScreen(
                parentId = parentId,
                mediaType = mediaType,
                onImageClick = { index ->
                    navController.navigate(Routes.reader(parentId, index, rawMediaType))
                },
                onVideoClick = { path, thumbnailPath ->
                    launchOpenVideo(localScope, context, videoPlayerPreference, autoRouteHighRes, Uri.parse(path), thumbnailPath, navController)
                },
                onBack = { navController.safePopBackStack() }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("parentId") { type = NavType.LongType },
                navArgument("initialIndex") { type = NavType.IntType; defaultValue = 0 },
                navArgument("type") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { backStackEntry ->
            val parentId = backStackEntry.arguments?.getLong("parentId") ?: return@composable
            val initialIndex = backStackEntry.arguments?.getInt("initialIndex") ?: 0
            val localScope = rememberCoroutineScope()
            val rawMediaType = backStackEntry.arguments?.getInt("type") ?: 0
            val mediaType = rawMediaType.takeIf { it != 0 }
            ReaderScreen(
                parentId = parentId,
                initialIndex = initialIndex,
                mediaType = mediaType,
                onBack = { navController.safePopBackStack() },
                onVideoClick = { item ->
                    item.uri?.let { uri ->
                        launchOpenVideo(localScope, context, videoPlayerPreference, autoRouteHighRes, uri, item.thumbnailPath, navController)
                    }
                }
            )
        }

        composable(
            route = Routes.VIDEO_PLAYER,
            arguments = listOf(
                navArgument("videoUri") { type = NavType.StringType },
                navArgument("thumbnailPath") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("videoUri") ?: return@composable
            val thumbnailPath = backStackEntry.arguments?.getString("thumbnailPath")?.ifEmpty { null }
            VideoPlayerScreen(
                videoUri = Uri.parse(uriString),
                thumbnailPath = thumbnailPath,
                onBack = { navController.safePopBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.safePopBackStack() },
                onAbout = { navController.navigate(Routes.ABOUT) }
            )
        }

        composable(Routes.SEARCH) {
            val localScope = rememberCoroutineScope()
            SearchScreen(
                videoCoverStrategy = folderListViewModel.videoCoverStrategy,
                onFolderClick = { folder ->
                    navController.navigate(Routes.mediaGrid(folder.id, 0))
                },
                onImageClick = { item ->
                    navController.navigate(
                        Routes.reader(
                            item.parentId,
                            0,
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                        )
                    )
                },
                onVideoClick = { item ->
                    item.uri?.let { uri ->
                        launchOpenVideo(localScope, context, videoPlayerPreference, autoRouteHighRes, uri, item.thumbnailPath, navController)
                    }
                },
                onBack = { navController.safePopBackStack() }
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.safePopBackStack() })
        }
    }
}

/**
 * Fire-and-forget launcher for [openVideo]. Bound to the supplied scope (per
 * destination, so the coroutine cancels when the user navigates away from the
 * screen that originated the tap). Catches and logs any non-cancellation
 * throwable so a stray failure does not propagate to the global handler.
 */
private fun launchOpenVideo(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    preference: VideoPlayerPreference,
    autoRouteHighRes: Boolean,
    uri: Uri,
    thumbnailPath: String?,
    navController: NavHostController,
) {
    scope.launch {
        try {
            openVideo(context, preference, autoRouteHighRes, uri, thumbnailPath, navController)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("NavGraph", "openVideo failed for $uri", e)
        }
    }
}

/**
 * Single decision point for all video-tap call sites.
 *
 * Auto-route gate (opt-in via settings): if enabled and the video's longest
 * edge exceeds [AUTO_ROUTE_THRESHOLD_PX], bypass the user's preference and
 * launch the system player directly (default or chooser, matching the user's
 * SYSTEM_* preference — the auto-route toggle only changes *which* player, not
 * *how* it is dispatched). This is the workaround for ExoPlayer choking on
 * QHD/4K content that the OEM's stock player handles.
 *
 * Honors [VideoPlayerPreference] for the non-auto-route path:
 * IN_APP → navigate to in-app player; SYSTEM → fire Intent.ACTION_VIEW.
 *
 * Falls back to in-app on: no system player, unsupported URI scheme, or
 * auto-route probe failure (so the user is never stranded).
 *
 * Suspend because the metadata probe runs on Dispatchers.IO (MediaMetadataRetriever
 * can stall on bad files / slow SD cards — review C1). Call sites use
 * [launchOpenVideo] which wraps in a per-destination scope + try/catch.
 *
 * Requires an Activity `context` — startActivity() from a non-Activity
 * context needs FLAG_ACTIVITY_NEW_TASK and would still break the chooser UX.
 */
private suspend fun openVideo(
    context: Context,
    preference: VideoPlayerPreference,
    autoRouteHighRes: Boolean,
    uri: Uri,
    thumbnailPath: String?,
    navController: NavHostController
) {
    // Auto-route check first — short-circuits user preference for high-res videos.
    // Force showChooser = false regardless of the user's SYSTEM_CHOOSER preference —
    // the auto-route toggle exists to skip ExoPlayer; popping a chooser would defeat
    // that. The user can disable auto-route at any time to get their chooser back.
    if (autoRouteHighRes) {
        val longestEdge = withContext(Dispatchers.IO) { probeLongestEdge(context, uri) }
        if (shouldAutoRouteToSystem(autoRouteHighRes, longestEdge, AUTO_ROUTE_THRESHOLD_PX)) {
            try {
                launchSystemPlayer(context, uri, showChooser = false)
                Toast.makeText(context, "高分辨率视频自动使用系统播放器", Toast.LENGTH_SHORT).show()
                return
            } catch (e: ActivityNotFoundException) {
                Log.w("NavGraph", "auto-route failed: no system player, falling through")
            } catch (e: UnsupportedVideoUriException) {
                Log.w("NavGraph", "auto-route failed: unsupported scheme, falling through")
            }
        }
    }
    // Normal path — honor user preference
    if (shouldOpenInApp(preference)) {
        navController.navigate(Routes.videoPlayer(uri.toString(), thumbnailPath))
        return
    }
    try {
        launchSystemPlayer(context, uri, showChooser = shouldShowChooser(preference))
    } catch (e: ActivityNotFoundException) {
        Log.w("NavGraph", "no system video player installed, falling back to in-app")
        navController.navigate(Routes.videoPlayer(uri.toString(), thumbnailPath))
    } catch (e: UnsupportedVideoUriException) {
        Log.w("NavGraph", "unsupported URI scheme (${e.uri.scheme}), falling back to in-app")
        navController.navigate(Routes.videoPlayer(uri.toString(), thumbnailPath))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName ?: "1.0"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
        ) {
            Text("Reader", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("版本 $versionName", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "基于 Jetpack Compose + Material 3 的本地图片和视频阅读器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "开源许可: Apache 2.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
