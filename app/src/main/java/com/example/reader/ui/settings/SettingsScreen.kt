package com.example.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.ui.theme.ThemeState
import com.example.reader.util.FolderCoverStrategy
import com.example.reader.util.VideoCoverStrategy
import com.example.reader.util.VideoPlayerPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(LocalContext.current.applicationContext as android.app.Application)
    )
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Theme section
            SettingsSectionHeader("theme")
            ThemeOption("light", ThemeState.ThemeMode.LIGHT, state.themeMode) {
                viewModel.setThemeMode(it)
            }
            ThemeOption("dark", ThemeState.ThemeMode.DARK, state.themeMode) {
                viewModel.setThemeMode(it)
            }
            ThemeOption("AMOLED black", ThemeState.ThemeMode.AMOLED_BLACK, state.themeMode) {
                viewModel.setThemeMode(it)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Home folder sort
            SettingsSectionHeader("home sort")
            SortModePicker(
                currentMode = state.folderSortMode,
                currentOrder = state.folderSortOrder,
                onModeChange = { viewModel.setFolderSortMode(it) },
                onOrderToggle = {
                    val newOrder = if (state.folderSortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                    viewModel.setFolderSortOrder(newOrder)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Image folder sort
            SettingsSectionHeader("image sort")
            SortModePicker(
                currentMode = state.imageSortMode,
                currentOrder = state.imageSortOrder,
                onModeChange = { viewModel.setImageSortMode(it) },
                onOrderToggle = {
                    val newOrder = if (state.imageSortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                    viewModel.setImageSortOrder(newOrder)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Video folder sort
            SettingsSectionHeader("video sort")
            SortModePicker(
                currentMode = state.videoSortMode,
                currentOrder = state.videoSortOrder,
                onModeChange = { viewModel.setVideoSortMode(it) },
                onOrderToggle = {
                    val newOrder = if (state.videoSortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                    viewModel.setVideoSortOrder(newOrder)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Video player preference
            SettingsSectionHeader("video player")
            CoverStrategyPicker(
                label = "open videos with",
                currentStrategy = state.videoPlayerPreference.name,
                options = listOf(
                    "IN_APP" to "in-app player",
                    "SYSTEM_DEFAULT" to "system default player",
                    "SYSTEM_CHOOSER" to "system player (ask every time)"
                ),
                onSelect = { viewModel.setVideoPlayerPreference(VideoPlayerPreference.valueOf(it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Grid columns
            SettingsSectionHeader("grid columns")
            ColumnPicker(
                label = "columns per row",
                value = state.gridColumns,
                options = listOf(3, 4, 5),
                onSelect = { viewModel.setGridColumns(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Folder cover strategy
            SettingsSectionHeader("folder cover")
            CoverStrategyPicker(
                label = "folder cover strategy",
                currentStrategy = state.folderCoverStrategy.name,
                options = listOf(
                    "LATEST" to "latest media",
                    "EARLIEST" to "earliest media",
                    "RANDOM" to "random",
                    "BY_SORT" to "by sort"
                ),
                onSelect = { viewModel.setFolderCoverStrategy(FolderCoverStrategy.valueOf(it)) }
            )

            // Cover sort picker (only visible when BY_SORT is selected)
            if (state.folderCoverStrategy == FolderCoverStrategy.BY_SORT) {
                SortModePicker(
                    currentMode = state.coverSortMode,
                    currentOrder = state.coverSortOrder,
                    onModeChange = { viewModel.setCoverSortMode(it) },
                    onOrderToggle = {
                        val newOrder = if (state.coverSortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                        viewModel.setCoverSortOrder(newOrder)
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Video cover strategy
            SettingsSectionHeader("video cover")
            CoverStrategyPicker(
                label = "video frame strategy",
                currentStrategy = state.videoCoverStrategy.name,
                options = listOf(
                    "EXACT_1S" to "1st second",
                    "MID_FRAME" to "middle frame",
                    "CLOSEST_KEYFRAME" to "keyframe (fast)"
                ),
                onSelect = { viewModel.setVideoCoverStrategy(VideoCoverStrategy.valueOf(it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About
            SettingsRow(
                title = "about",
                subtitle = "version and open source licenses"
            ) {
                onAbout()
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun ThemeOption(
    label: String,
    mode: ThemeState.ThemeMode,
    current: ThemeState.ThemeMode,
    onSelect: (ThemeState.ThemeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (mode == current) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SortModePicker(
    currentMode: SortMode,
    currentOrder: SortOrder,
    onModeChange: (SortMode) -> Unit,
    onOrderToggle: () -> Unit
) {
    val modeNames = mapOf(
        SortMode.NAME to "by name",
        SortMode.DATE to "by date",
        SortMode.SIZE to "by size"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("sort by", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text("${modeNames[currentMode]} ${if (currentOrder == SortOrder.DESC) "\u2193" else "\u2191"}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(modeNames[mode] ?: mode.name) },
                        onClick = { onModeChange(mode); expanded = false },
                        leadingIcon = if (mode == currentMode) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(if (currentOrder == SortOrder.DESC) "desc \u2193" else "asc \u2191")
                    },
                    onClick = { onOrderToggle(); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ColumnPicker(
    label: String,
    value: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        options.forEach { opt ->
            FilterChip(
                selected = opt == value,
                onClick = { onSelect(opt) },
                label = { Text("$opt") },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CoverStrategyPicker(
    label: String,
    currentStrategy: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(options.find { it.first == currentStrategy }?.second ?: currentStrategy)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = { onSelect(value); expanded = false },
                        leadingIcon = if (value == currentStrategy) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }
}
