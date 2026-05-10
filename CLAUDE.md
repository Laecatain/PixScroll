# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Build + install to phone (auto-detects device, falls back to build-only)
bash /e/playground/install.sh

# Build only
/d/Android/gradle/gradle-8.7/bin/gradle assembleDebug -p /e/playground

# Run all tests (JVM, no device needed)
/d/Android/gradle/gradle-8.7/bin/gradle testDebugUnitTest -p /e/playground

# Run a single test class
/d/Android/gradle/gradle-8.7/bin/gradle testDebugUnitTest -p /e/playground --tests "*FolderListViewModelTest*"
```

Requires `JAVA_HOME=/d/Android/jdk` and `ANDROID_HOME=/d/Android/sdk`. JDK 17 Temurin, SDK API 34.

## Architecture

Kotlin + Jetpack Compose (Material 3), MVVM, no DI framework.

**Data flow**: ViewModel → `StateFlow<State>` → Composable collects via `collectAsState()`.

### Navigation

7 routes in `navigation/NavGraph.kt`:

```
folder_list → media_grid/{parentId} → reader/{parentId}/{initialIndex}
                                     → video_player/{videoUri}
folder_list → search
folder_list → settings → about
```

Search results navigate to `reader/{item.parentId}/0`. MediaItem carries `parentId` for this purpose.

### Key layers

- `data/repository/MediaRepository.kt` — Interface + `AndroidMediaRepository`. Unified `MediaStore.Files` query, PARENT-based grouping, `IS_PENDING` filter (API 29+), `.nomedia` detection with cached hidden parents. `SortMode`/`SortOrder` are top-level enums. Methods: `getAllFolders()`, `getMediaByFolder()` both accept sort params; `searchMedia()` does DISPLAY_NAME LIKE query.
- `util/AppSettings.kt` — Top-level `Context.dataStore` delegate (`preferencesDataStore`), `PreferenceKeys` object (THEME_MODE, SORT_MODE, SORT_ORDER, GRID_COLUMNS), suspend save helpers.
- `ui/theme/ThemeState.kt` — Singleton `object` with 3-mode `ThemeMode` enum (LIGHT/DARK/AMOLED_BLACK). `cycle()` advances through modes. `onModeChanged: (ThemeMode) -> Unit` callback set by `ReaderApp.onCreate()` for DataStore persistence. `init(mode)` used at startup to restore saved mode.
- `ui/folderlist/` — `FolderListViewModel(MediaRepository, Application?=null)` with `sortMode`/`sortOrder` mutable state. Reads defaults from DataStore when Application is non-null (null in tests skips DataStore). `updateSortMode()`/`updateSortOrder()`/`toggleSortOrder()` reload folders.
- `ui/reader/` — `ReaderViewModel(MediaRepository, parentId, initialIndex=0, Application?=null)`. Two reader modes in `ReaderState` (ContinuousScroll/Pager). Reads sort defaults from DataStore when Application is non-null. `loadMedia()` re-queries with current sort params.
- `ui/mediagrid/` — Reuses `ReaderViewModel` for data. Grid columns read from DataStore via `runBlocking { … }` at composition time.
- `ui/search/` — `SearchViewModel(MediaRepository)`, debounced search with `Job.cancel()` on each keystroke. `SearchScreen` with auto-focused OutlinedTextField + 3-column result grid.
- `ui/settings/` — `SettingsViewModel(Application)` reads/writes all DataStore preferences. `SettingsScreen` with theme picker, sort defaults, grid columns (3/4/5 FilterChip), about link.
- `ui/player/` — ExoPlayer with lifecycle-aware pause/resume via `DisposableEffect`.

### Reader zoom

Container-level zoom (not per-item). `graphicsLayer` on the entire `LazyColumn`/`HorizontalPager`. Custom `awaitPointerEventScope` gesture handler — single-finger scroll passes through to the scroll container; pinch-zoom and zoomed-in pan are consumed at the container level. `userScrollEnabled = !isZoomed`.

### Slider / scroll-to-item

`ContinuousScrollReader` uses `animateScrollToItem()` with `isDragging`/`isAnimatingScroll` flags. `LaunchedEffect` syncs `sliderValue` to `visibleIndex` only when not actively dragging. See `slider-performance-research.md` for deeper analysis (LazyLayoutCacheWindow, RecyclerView comparison).

## Key Patterns

- ViewModels extend `ViewModel` (not `AndroidViewModel`), accept `MediaRepository` via constructor, use inner `Factory` class for Android creation from composables.
- `Application?` with default `null` on ViewModel constructors — non-null for production (reads DataStore), null for tests (skips DataStore).
- `CancellationException` is always rethrown before `Exception` catch in ViewModel coroutines.
- Coil `AsyncImage` with `VideoFrameDecoder` for all image/video thumbnail loading. `ContentScale.FillWidth` in scroll reader, `Fit` in pager reader, `Crop` in grids.
- `MediaItem.uri` is nullable (`Uri?`) — test convenience and robustness.
- `MediaFolder.id` is MediaStore `PARENT` — unique per storage volume, used as Compose `LazyVerticalGrid` key.
- `ThemeState` initialization: `ReaderApp.onCreate()` does `runBlocking { dataStore.data.first() }` to read saved theme synchronously before first composition, then sets `onModeChanged` callback for async writes on each toggle.
- Navigation uses `Uri.encode()` for video URIs, string-based routes with `NavType.LongType`/`IntType` arguments.

## Testing

- `FakeMediaRepository` — in-memory implementation with `folders`/`mediaItems`/`searchResults` lists, injectable `foldersError`/`mediaError`/`searchError` for error tests.
- Tests use `Dispatchers.Unconfined` for synchronous coroutine execution on JVM.
- `returnDefaultValues = true` in testOptions for Android SDK stubs.
- `Turbine` available as test dependency for Flow testing, though current tests use `state.value` directly.
- ViewModels with `Application? = null` can be constructed without Application in tests.

## Agent Automation

### 分级流水线

| 改动类型 | plan | code-review | security-review | 示例 |
|----------|------|-------------|-----------------|------|
| 修 typo、改字符串、格式化 | 跳过 | 跳过 | 跳过 | 改文案、format 代码 |
| 单函数 bug 修复 | 跳过 | 审 | 跳过 | 修 crash、修逻辑 |
| 新功能、UI 改动 | 必须 | 审 | 跳过 | 加进度条、改布局 |
| 涉及 IO/URI/权限/用户输入 | 必须 | 审 | 审 | 改文件读取、权限处理 |

- 所有审查通过 + `assembleDebug` 成功后，**自动执行 `git add` + `git commit`**。
- 任何一步失败则停止，不提交。
- **不确定就升级**：拿不准该不该审 → 审；拿不准该不该 plan → plan。
