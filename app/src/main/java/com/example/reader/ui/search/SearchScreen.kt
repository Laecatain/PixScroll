package com.example.reader.ui.search

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.ui.common.AsyncGridImage
import com.example.reader.ui.common.FolderGridCard
import com.example.reader.util.ThumbnailManager
import com.example.reader.util.VideoCoverStrategy
import com.example.reader.util.dataStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onFolderClick: (MediaFolder) -> Unit,
    onImageClick: (MediaItem) -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.Factory(LocalContext.current.applicationContext as Application)
    )
) {
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val thumbnailManager = remember { ThumbnailManager(context) }
    val videoCoverStrategy = remember {
        try {
            val prefs = runBlocking { context.dataStore.data.first() }
            VideoCoverStrategy.valueOf(prefs[com.example.reader.util.PreferenceKeys.VIDEO_COVER_STRATEGY] ?: "EXACT_1S")
        } catch (_: Exception) { VideoCoverStrategy.EXACT_1S }
    }
    val totalResultCount = state.folderResults.size + state.videoResults.size + state.imageResults.size
    val selectedResultsCount = when (state.selectedCategory) {
        SearchCategory.FOLDERS -> state.folderResults.size
        SearchCategory.VIDEOS -> state.videoResults.size
        SearchCategory.IMAGES -> state.imageResults.size
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = { viewModel.onQueryChange(it) },
                            placeholder = { Text("搜索文件夹、视频或图片...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.clearSearch() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清除")
                                    }
                                }
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
                if (state.query.isNotBlank() || state.hasSearched) {
                    SearchCategoryChips(
                        state = state,
                        onCategorySelected = viewModel::selectCategory,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    )
                }
            }
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
                    Text(state.error ?: "")
                }
            }
            !state.hasSearched -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "输入关键词搜索文件夹、视频或图片",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            totalResultCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未找到匹配内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            selectedResultsCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (state.selectedCategory) {
                            SearchCategory.FOLDERS -> "未找到匹配的文件夹"
                            SearchCategory.VIDEOS -> "未找到匹配的视频"
                            SearchCategory.IMAGES -> "未找到匹配的图片"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            state.selectedCategory == SearchCategory.FOLDERS -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.folderResults, key = { it.id }) { folder ->
                        FolderGridCard(
                            folder = folder,
                            thumbnailManager = thumbnailManager,
                            videoCoverStrategy = videoCoverStrategy,
                            onClick = { onFolderClick(folder) }
                        )
                    }
                }
            }
            else -> {
                val mediaResults = if (state.selectedCategory == SearchCategory.VIDEOS) {
                    state.videoResults
                } else {
                    state.imageResults
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(mediaResults, key = { it.uri ?: it.name }) { item ->
                        SearchResultCell(
                            item = item,
                            thumbnailManager = thumbnailManager,
                            videoCoverStrategy = videoCoverStrategy,
                            onClick = {
                                if (item.isVideo) onVideoClick(item)
                                else onImageClick(item)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCategoryChips(
    state: SearchState,
    onCategorySelected: (SearchCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchCategory.entries.forEach { category ->
            FilterChip(
                selected = state.selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { Text("${category.label} ${state.resultCount(category)}") }
            )
        }
    }
}

private val SearchCategory.label: String
    get() = when (this) {
        SearchCategory.FOLDERS -> "文件夹"
        SearchCategory.VIDEOS -> "视频"
        SearchCategory.IMAGES -> "图片"
    }

private fun SearchState.resultCount(category: SearchCategory): Int = when (category) {
    SearchCategory.FOLDERS -> folderResults.size
    SearchCategory.VIDEOS -> videoResults.size
    SearchCategory.IMAGES -> imageResults.size
}

@Composable
private fun SearchResultCell(item: MediaItem, thumbnailManager: ThumbnailManager?, videoCoverStrategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncGridImage(
            item = item,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            thumbnailManager = thumbnailManager,
            videoCoverStrategy = videoCoverStrategy
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
