package com.example.reader.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.reader.ui.folderlist.FolderListScreen
import com.example.reader.ui.mediagrid.MediaGridScreen
import com.example.reader.ui.player.VideoPlayerScreen
import com.example.reader.ui.reader.ReaderScreen

object Routes {
    const val FOLDER_LIST = "folder_list"
    const val MEDIA_GRID = "media_grid/{parentId}"
    const val READER = "reader/{parentId}/{initialIndex}"
    const val VIDEO_PLAYER = "video_player/{videoUri}"

    fun mediaGrid(parentId: Long) = "media_grid/$parentId"
    fun reader(parentId: Long, initialIndex: Int = 0) = "reader/$parentId/$initialIndex"
    fun videoPlayer(videoUri: String) = "video_player/${Uri.encode(videoUri)}"
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.FOLDER_LIST) {
        composable(Routes.FOLDER_LIST) {
            FolderListScreen(
                onFolderClick = { folder ->
                    navController.navigate(Routes.mediaGrid(folder.id))
                }
            )
        }

        composable(
            route = Routes.MEDIA_GRID,
            arguments = listOf(navArgument("parentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val parentId = backStackEntry.arguments?.getLong("parentId") ?: return@composable
            MediaGridScreen(
                parentId = parentId,
                onImageClick = { index ->
                    navController.navigate(Routes.reader(parentId, index))
                },
                onVideoClick = { item ->
                    item.uri?.let { uri ->
                        navController.navigate(Routes.videoPlayer(uri.toString()))
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("parentId") { type = NavType.LongType },
                navArgument("initialIndex") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { backStackEntry ->
            val parentId = backStackEntry.arguments?.getLong("parentId") ?: return@composable
            val initialIndex = backStackEntry.arguments?.getInt("initialIndex") ?: 0
            ReaderScreen(
                parentId = parentId,
                initialIndex = initialIndex,
                onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() }
            )
        }
    }
}
