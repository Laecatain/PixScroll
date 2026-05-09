package com.example.reader.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.reader.ui.folderlist.FolderListScreen
import com.example.reader.ui.player.VideoPlayerScreen
import com.example.reader.ui.reader.ReaderScreen

object Routes {
    const val FOLDER_LIST = "folder_list"
    const val READER = "reader/{folderPath}"
    const val VIDEO_PLAYER = "video_player/{videoUri}"

    fun reader(folderPath: String) = "reader/${Uri.encode(folderPath)}"
    fun videoPlayer(videoUri: String) = "video_player/${Uri.encode(videoUri)}"
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.FOLDER_LIST) {
        composable(Routes.FOLDER_LIST) {
            FolderListScreen(
                onFolderClick = { folder ->
                    navController.navigate(Routes.reader(folder.folderPath))
                }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("folderPath") { type = NavType.StringType })
        ) {
            ReaderScreen(
                onBack = { navController.popBackStack() },
                onVideoClick = { item ->
                    navController.navigate(Routes.videoPlayer(item.uri.toString()))
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
