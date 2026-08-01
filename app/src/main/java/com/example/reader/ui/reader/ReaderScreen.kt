package com.example.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.reader.data.model.MediaItem

@Composable
fun ReaderScreen(
    parentId: Long,
    initialIndex: Int = 0,
    mediaType: Int? = null,
    onBack: () -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.Factory(
            LocalContext.current.applicationContext as Application,
            parentId,
            initialIndex,
            mediaType
        )
    )
) {
    val state by viewModel.state.collectAsState()
    val onIndexChange = remember(viewModel) { viewModel::setCurrentIndex }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    if (state.error != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(state.error ?: "", color = Color.Gray)
            }
        }
        return
    }

    if (state.mediaItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("该文件夹中没有图片或视频", color = Color.White)
        }
        return
    }

    when (state.currentMode) {
        ReaderMode.ContinuousScroll -> ContinuousScrollReader(
            mediaItems = state.mediaItems,
            initialIndex = state.currentIndex,
            onBack = onBack,
            onSwitchMode = { viewModel.switchMode(ReaderMode.Pager) },
            onVideoClick = onVideoClick,
            onIndexChange = onIndexChange,
            videoCoverStrategy = viewModel.videoCoverStrategy
        )
        ReaderMode.Pager -> PagerReader(
            mediaItems = state.mediaItems,
            initialIndex = state.currentIndex,
            onBack = onBack,
            onSwitchMode = { viewModel.switchMode(ReaderMode.ContinuousScroll) },
            onVideoClick = onVideoClick,
            onIndexChange = onIndexChange,
            videoCoverStrategy = viewModel.videoCoverStrategy
        )
    }
}
