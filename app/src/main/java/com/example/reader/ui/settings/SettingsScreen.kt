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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            SettingsSectionHeader("主题模式")
            ThemeOption("浅色", ThemeState.ThemeMode.LIGHT, state.themeMode) {
                viewModel.setThemeMode(it)
            }
            ThemeOption("深色", ThemeState.ThemeMode.DARK, state.themeMode) {
                viewModel.setThemeMode(it)
            }
            ThemeOption("AMOLED 黑色", ThemeState.ThemeMode.AMOLED_BLACK, state.themeMode) {
                viewModel.setThemeMode(it)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Sort section
            SettingsSectionHeader("默认排序")
            SortModePicker(
                currentMode = state.sortMode,
                currentOrder = state.sortOrder,
                onModeChange = { viewModel.setSortMode(it) },
                onOrderToggle = {
                    val newOrder = if (state.sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                    viewModel.setSortOrder(newOrder)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Grid columns
            SettingsSectionHeader("网格列数")
            ColumnPicker(
                label = "每行列数",
                value = state.gridColumns,
                options = listOf(3, 4, 5),
                onSelect = { viewModel.setGridColumns(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About
            SettingsRow(
                title = "关于",
                subtitle = "版本信息与开源许可"
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
        SortMode.NAME to "按名称",
        SortMode.DATE to "按日期",
        SortMode.SIZE to "按大小"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("排序方式", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        var expanded by androidx.compose.runtime.mutableStateOf(false)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text("${modeNames[currentMode]} ${if (currentOrder == SortOrder.DESC) "↓" else "↑"}")
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
                        Text(if (currentOrder == SortOrder.DESC) "降序 ↓" else "升序 ↑")
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
