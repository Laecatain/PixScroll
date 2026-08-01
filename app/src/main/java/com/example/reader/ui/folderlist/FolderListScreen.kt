package com.example.reader.ui.folderlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.ui.common.FastScroller
import com.example.reader.ui.common.FolderGridCard
import com.example.reader.ui.theme.ThemeState
import com.example.reader.util.ThumbnailManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onFolderClick: (MediaFolder, Int) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    viewModel: FolderListViewModel = viewModel(
        factory = FolderListViewModel.Factory(LocalContext.current.applicationContext as Application)
    )
) {
    val state by viewModel.state.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumbnailManager = remember { ThumbnailManager(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图库") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
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
                                        viewModel.updateSortMode(mode)
                                        showSortMenu = false
                                    },
                                    leadingIcon = if (mode == viewModel.sortMode) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(if (viewModel.sortOrder == SortOrder.DESC) "降序 ↓" else "升序 ↑")
                                },
                                onClick = {
                                    viewModel.toggleSortOrder()
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = { ThemeState.cycle() }) {
                        Icon(
                            if (ThemeState.isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "切换主题",
                            tint = if (ThemeState.isDark) Color.White else Color.DarkGray
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            is FolderUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is FolderUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", style = MaterialTheme.typography.bodyLarge)
                        Text(s.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.retry() }) {
                            Text("重试")
                        }
                    }
                }
            }
            is FolderUiState.Success -> {
                // remember(s.folders) 避免每次重组重新 .filter() 创建新 list 引用
                val imageFolders = remember(s.folders) { s.folders.filter { it.hasImages } }
                val videoFolders = remember(s.folders) { s.folders.filter { it.hasVideos } }
                val tabs = listOf("图片", "视频")
                val pagerState = rememberPagerState(pageCount = { tabs.size })
                val pagerScope = rememberCoroutineScope()
                val imageGridState = rememberLazyGridState()
                val videoGridState = rememberLazyGridState()

                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(title) }
                            )
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f)
                    ) { page ->
                        val folders = if (page == 0) imageFolders else videoFolders
                        val gridState = if (page == 0) imageGridState else videoGridState
                        val clickMediaType = if (page == 0) 1 else 3 // IMAGE / VIDEO

                        if (folders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (page == 0) "暂无图片" else "暂无视频",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(folders, key = { it.id }) { folder ->
                                        FolderGridCard(
                                            folder = folder,
                                            thumbnailManager = thumbnailManager,
                                            videoCoverStrategy = viewModel.videoCoverStrategy,
                                            onClick = { onFolderClick(folder, clickMediaType) }
                                        )
                                    }
                                }
                                FastScroller(
                                    gridState = gridState,
                                    itemCount = folders.size,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
