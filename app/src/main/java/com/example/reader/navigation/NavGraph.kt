package com.example.reader.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.reader.ui.folderlist.FolderListScreen
import com.example.reader.ui.mediagrid.MediaGridScreen
import com.example.reader.ui.player.VideoPlayerScreen
import com.example.reader.ui.reader.ReaderScreen
import com.example.reader.ui.search.SearchScreen
import com.example.reader.ui.settings.SettingsScreen

object Routes {
    const val FOLDER_LIST = "folder_list"
    const val MEDIA_GRID = "media_grid/{parentId}?type={type}"
    const val READER = "reader/{parentId}/{initialIndex}?type={type}"
    const val VIDEO_PLAYER = "video_player/{videoUri}"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val ABOUT = "about"

    fun mediaGrid(parentId: Long, mediaType: Int = 0) = "media_grid/$parentId?type=$mediaType"
    fun reader(parentId: Long, initialIndex: Int = 0, mediaType: Int = 0) = "reader/$parentId/$initialIndex?type=$mediaType"
    fun videoPlayer(videoUri: String) = "video_player/${Uri.encode(videoUri)}"
}

private fun NavHostController.safePopBackStack() {
    if (previousBackStackEntry != null) {
        popBackStack()
    }
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.FOLDER_LIST) {
        composable(Routes.FOLDER_LIST) {
            FolderListScreen(
                onFolderClick = { folder, mediaType ->
                    navController.navigate(Routes.mediaGrid(folder.id, mediaType))
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
            val mediaType = backStackEntry.arguments?.getInt("type") ?: 0
            MediaGridScreen(
                parentId = parentId,
                mediaType = mediaType,
                onImageClick = { index ->
                    navController.navigate(Routes.reader(parentId, index, mediaType))
                },
                onVideoClick = { item ->
                    item.uri?.let { uri ->
                        navController.navigate(Routes.videoPlayer(uri.toString()))
                    }
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
            val mediaType = backStackEntry.arguments?.getInt("type") ?: 0
            ReaderScreen(
                parentId = parentId,
                initialIndex = initialIndex,
                mediaType = mediaType,
                onBack = { navController.safePopBackStack() },
                onVideoClick = { item ->
                    item.uri?.let { uri ->
                        navController.navigate(Routes.videoPlayer(uri.toString()))
                    }
                }
            )
        }

        composable(
            route = Routes.VIDEO_PLAYER,
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("videoUri") ?: return@composable
            VideoPlayerScreen(
                videoUri = Uri.parse(uriString),
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
            SearchScreen(
                onImageClick = { item ->
                    navController.navigate(Routes.reader(item.parentId, 0))
                },
                onVideoClick = { item ->
                    item.uri?.let { uri ->
                        navController.navigate(Routes.videoPlayer(uri.toString()))
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
