package com.example.reader.ui.mediagrid

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.ui.common.FastScroller
import com.example.reader.ui.reader.ReaderViewModel
import com.example.reader.util.PreferenceKeys
import com.example.reader.util.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(
    parentId: Long,
    mediaType: Int = 0,
    onImageClick: (index: Int) -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.Factory(
            LocalContext.current.applicationContext as Application,
            parentId,
            mediaType = mediaType
        )
    )
) {
    val state by viewModel.state.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // Read grid columns from DataStore
    val context = LocalContext.current
    val gridColumns = remember {
        try {
            runBlocking {
                val prefs = context.dataStore.data.first()
                prefs[PreferenceKeys.GRID_COLUMNS] ?: 3
            }
        } catch (_: Exception) { 3 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.folderName.ifEmpty { "媒体列表" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            val sortModes = listOf(
                                SortMode.DATE to "按日期",
                                SortMode.NAME to "按名称",
                                SortMode.SIZE to "按大小"
                            )
                            sortModes.forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setSortMode(mode)
                                        showSortMenu = false
                                    },
                                    leadingIcon = if (mode == state.sortMode) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.sortOrder == SortOrder.DESC) "降序 ↓" else "升序 ↑")
                                },
                                onClick = {
                                    viewModel.toggleSortOrder()
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(state.error ?: "加载失败")
                }
            }
            state.mediaItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("该文件夹中没有图片或视频")
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(gridColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(state.mediaItems, key = { _, item -> item.uri ?: item.name }) { index, item ->
                            MediaGridCell(
                                item = item,
                                onClick = {
                                    if (item.isVideo) onVideoClick(item)
                                    else onImageClick(index)
                                }
                            )
                        }
                    }
                    FastScroller(
                        gridState = gridState,
                        itemCount = state.mediaItems.size,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGridCell(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (item.isVideo) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White)
                }
            }
        }
    }
}
