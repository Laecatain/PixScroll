# Fix: 排序逻辑反转 + 重命名后文件消失

## 问题

1. **排序反转**：文件夹列表按名称排序时，选 ASC 实际得到 DESC（方向反了）
2. **重命名后文件消失**：MediaStore 条目过期，app 不校验文件是否存在，导致显示空白

## 修改方案

### Fix 1: 文件夹排序逻辑（`MediaRepository.kt` 312-320 行）

当前代码先用固定方向排序，再用 `reversed()` 翻转——NAME 模式下 ASC 反而变 DESC。

**改法**：去掉双层 `let`，改成单次排序，根据 `sortOrder` 直接选 `sortedBy` 或 `sortedByDescending`。

```kotlin
// Before (broken):
}.let { list ->
    when (sortMode) {
        SortMode.NAME -> list.sortedBy { it.folderName.lowercase() }
        SortMode.DATE -> list.sortedByDescending { ... }
        SortMode.SIZE -> list.sortedByDescending { it.mediaCount }
    }
}.let { list ->
    if (sortOrder == SortOrder.ASC) list.reversed() else list
}

// After (fixed):
}.sortedWith(
    when (sortMode) {
        SortMode.NAME -> compareBy { it.folderName.lowercase() }
        SortMode.DATE -> compareByDescending { folderMap[it.id]?.maxDate ?: 0L }
        SortMode.SIZE -> compareByDescending { it.mediaCount }
    }.let { if (sortOrder == SortOrder.ASC) it.reversed() else it }
)
```

注意：`compareByDescending` + `reversed()` 等价于 `compareBy`，但用 `Comparator.reversed()` 反转是安全的——因为 `compareBy` 生成的 Comparator 可以正确反转。关键是 NAME 必须用 `compareBy`（升序），DATE/SIZE 用 `compareByDescending`（降序），这样 `reversed()` 对所有模式都正确。

### Fix 2: SQL 排序字段不一致（`MediaRepository.kt` 490-493 行）

SQL 查询用 `DATE_TAKEN` 排序，但 `mediaComparator` 用 `dateModified` 排序。合并未索引文件时顺序会乱。

**改法**：SQL 也用 `DATE_MODIFIED`，和 `mediaComparator` 保持一致。

```kotlin
// Before:
SortMode.DATE -> MediaStore.Files.FileColumns.DATE_TAKEN

// After:
SortMode.DATE -> MediaStore.Files.FileColumns.DATE_MODIFIED
```

### Fix 3: 文件存在性校验 + 过期条目触发重新扫描（`MediaRepository.kt`）

**目标**：不显示已不存在的文件，同时触发系统重新索引该文件夹（让重命名后的文件被 MediaStore 正确收录）。

**改法**：在 `readMediaItemsFromCursor` 中加文件存在性检查，收集过期条目路径；在 `getMediaByFolder` 中对过期文件夹触发 `MediaScannerConnection.scanFile()`。

**Step 3a**: `readMediaItemsFromCursor` 改造——返回值增加过期路径列表

```kotlin
// 返回类型改为 Triple<List<MediaItem>, String, List<String>>
// 第三个元素是过期条目的文件夹路径（去重后）
private fun readMediaItemsFromCursor(cursor: Cursor?): Triple<List<MediaItem>, String, List<String>> {
    val items = mutableListOf<MediaItem>()
    val stalePaths = mutableListOf<String>()
    // ... 现有逻辑 ...
    while (it.moveToNext()) {
        // ... 读取各列 ...
        // 新增：文件存在性检查
        if (data.isNotEmpty() && !File(data).exists()) {
            stalePaths.add(data.substringBeforeLast("/"))
            continue
        }
        items.add(MediaItem(...))
    }
    val folderPath = items.firstOrNull()?.folderPath?.substringBeforeLast("/") ?: ""
    return Triple(items, folderPath, stalePaths.distinct())
}
```

**Step 3b**: `getMediaByFolder` 中触发重新扫描

```kotlin
// 在 Phase 1 emit 之后，Phase 2 之前：
if (stalePaths.isNotEmpty()) {
    android.util.Log.w("MediaRepo", "发现 ${stalePaths.size} 个过期目录，触发 MediaScanner")
    for (dir in stalePaths) {
        MediaScannerConnection.scanFile(context, arrayOf(dir), null, null)
    }
}
```

**效果**：
- 立即：过期条目不显示，用户不会看到空白图
- 延迟：MediaScanner 扫描后，重命名的文件被正确索引，`ContentObserver` 触发刷新，新文件自动出现

**性能考虑**：`File.exists()` 是 stat 系统调用，单次微秒级。一个文件夹几百到几千文件，总耗时毫秒级，在 `Dispatchers.IO` 上执行不阻塞主线程。`MediaScannerConnection.scanFile()` 是异步的，不会阻塞。

### Fix 4: 添加 `IS_TRASHED` 过滤（`MediaRepository.kt` 多处）

当前只过滤了 `IS_PENDING`，没有过滤 `IS_TRASHED`。已删除进入系统回收站的文件仍会显示。

在以下 3 处的 `IS_PENDING = 0` 后面追加：
- `getAllFolders()` 约 129-131 行
- `getMediaByFolder` 内 `queryMediaStoreItems` 约 458-474 行
- 搜索相关查询（如有）

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    selection.append(" AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0")
    selection.append(" AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0")
}
```

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `MediaRepository.kt` | Fix 1 (312-320), Fix 2 (492), Fix 3 (readMediaItemsFromCursor + getMediaByFolder), Fix 4 (3 处 IS_TRASHED) |

只改 1 个文件，4 处改动，涉及约 20 行新增/修改。

## 不改的部分

- 不加手动刷新按钮（UI 改动，另开任务）
- 不改 `mediaComparator` 本身（它和改后的 SQL 一致了）
- 不改 `setCurrentIndex` 越界问题（低优先级，排序修完再说）
- 不删除 MediaStore 过期条目（只跳过 + 触发扫描，避免权限风险）

## 验证

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

测试覆盖：现有的 `ReaderViewModelTest` 中排序相关用例验证 state 更新是否正确，Fix 1 修复的是 Repository 层的排序逻辑，需要在 `FakeMediaRepository` 或集成测试中验证实际排序结果。但现有测试不会因这些改动而失败。
