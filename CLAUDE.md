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

Requires `JAVA_HOME=/d/Android/jdk` and `ANDROID_HOME=/d/Android/sdk`. JDK 17 Temurin, SDK API 34. minSdk 26.

## Architecture

Kotlin + Jetpack Compose (Material 3), MVVM, no DI framework. Compose BOM 2025.03.00.

**Data flow**: ViewModel → `StateFlow<State>` → Composable collects via `collectAsState()`.

### Navigation

7 routes in `navigation/NavGraph.kt`:

```
folder_list → media_grid/{parentId} → reader/{parentId}/{initialIndex}
                                     → video_player/{videoUri}
folder_list → search
folder_list → settings → about
```

Search results navigate to `reader/{item.parentId}/0`. `MediaItem` carries `parentId` for this purpose.

### Key layers

- `data/repository/MediaRepository.kt` — Interface + `AndroidMediaRepository`. Unified `MediaStore.Files` query, PARENT-based grouping, `IS_PENDING` filter (API 29+), `.nomedia` detection with cached hidden parents. `SortMode`/`SortOrder` are top-level enums. **Hybrid Media Engine**: `getMediaByFolder()` does dual-phase scan — Phase 1 emits MediaStore results immediately, Phase 2 does `FileTreeWalk` fallback on the target folder to discover unindexed files (jpg/png/webp/heic/avif/etc.), merges deduped by absolute path, re-sorts, and re-emits.
- `util/AppSettings.kt` — Top-level `Context.dataStore` delegate (`preferencesDataStore`), `PreferenceKeys` object (THEME_MODE, SORT_MODE, SORT_ORDER, GRID_COLUMNS), suspend save helpers.
- `util/FolderCache.kt` — JSON file I/O for folder list + `.nomedia` hidden parents. `saveFolders()`/`loadFolders()` use `JSONArray`/`JSONObject` serialization. Written after each `getAllFolders()` query, read synchronously in `FolderListViewModel.init()` as StateFlow initial value.
- `util/PermissionHelper.kt` — Reads `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (API 33+) or `READ_EXTERNAL_STORAGE` (legacy).
- `ui/theme/ThemeState.kt` — Singleton `object` with 3-mode `ThemeMode` enum (LIGHT/DARK/AMOLED_BLACK). `cycle()` advances through modes. `onModeChanged: (ThemeMode) -> Unit` callback set by `ReaderApp.onCreate()` for DataStore persistence. `init(mode)` used at startup via `runBlocking { dataStore.data.first() }`.
- `ui/folderlist/` — `FolderListViewModel` reads cache synchronously in `init` as `MutableStateFlow` initial value to avoid Loading flash. `FolderUiState` sealed interface (Success/Loading/Error). `skipNextLoading` flag ensures first `loadFolders()` call doesn't overwrite cached Success.
- `ui/reader/` — `ReaderViewModel(MediaRepository, parentId, initialIndex=0, Application?=null)`. Two reader modes in `ReaderState`. `switchMode()` only toggles mode; index sync happens via `onIndexChange` callback from both readers, wired in `ReaderScreen` to `setCurrentIndex()`.
- `ui/mediagrid/` — Reuses `ReaderViewModel` for data. Grid columns read from DataStore via `runBlocking { … }` at composition time.
- `ui/search/` — `SearchViewModel(MediaRepository)`, debounced search with `Job.cancel()` on each keystroke. `SearchScreen` with auto-focused OutlinedTextField + 3-column result grid.
- `ui/settings/` — `SettingsViewModel(Application)` reads/writes all DataStore preferences. `SettingsScreen` with theme picker, sort defaults, grid columns (3/4/5 FilterChip), about link.
- `ui/player/` — ExoPlayer with lifecycle-aware pause/resume via `DisposableEffect`.
- `ui/reader/SliderUtils.kt` — `clampSliderTarget(value, itemCount)` utility for rounding and clamping slider values to valid item indices.

### Reader zoom

Container-level zoom (not per-item). `graphicsLayer` on the entire `LazyColumn`/`HorizontalPager`. Custom `awaitPointerEventScope` gesture handler: `event.zoomChange()` detects pinch, `event.panChange()` detects two-finger pan. Single-finger scroll passes through to the scroll container. `userScrollEnabled = !isZoomed`. Scale clamped to 1f–3f (ContinuousScroll) or 1f–5f (Pager).

### ContinuousScrollReader strategy

- **Cold start**: `rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)` for zero-frame target positioning.
- **Hot start**: `LaunchedEffect(initialIndex) { snapshotFlow { totalItemsCount } }` gate waits for data, then `animateScrollToItem`. Hybrid jump: |target-cur| > 10 → `scrollToItem(mid)` + `animateScrollToItem(target)`.
- **contentPadding**: `PaddingValues(vertical = screenHeightDp)` on LazyColumn so items center in viewport via scroll offset.
- **Aspect ratio placeholders**: `ImageWithAspectPlaceholder` wraps `AsyncImage` in a `Box` with `Modifier.aspectRatio(ratio)`. `DEFAULT_ASPECT_RATIO = 16f/9f` when `item.aspectRatio == 0f` (unindexed files, or no width/height metadata).
- **Index sync**: `LaunchedEffect(listState) { snapshotFlow { firstVisibleItemIndex }.distinctUntilChanged() }` updates local `visibleIndex` and calls `onIndexChange` callback for ViewModel sync.

### Slider

State lock via `MutableInteractionSource.collectIsDraggedAsState()` + `listState.isScrollInProgress` / `pagerState.isScrollInProgress`. Slider only writes back to `visibleIndex` when `!isDragged && !isScrolling`. `onValueChangeFinished` uses the same hybrid jump strategy as hot start (LONG_JUMP_THRESHOLD = 10, OFFSET = 3).

## Key Patterns

- ViewModels extend `ViewModel` (not `AndroidViewModel`), accept `MediaRepository` via constructor, use inner `Factory` class for Android creation from composables.
- `Application?` with default `null` on ViewModel constructors — non-null for production (reads DataStore), null for tests (skips DataStore).
- `CancellationException` is always rethrown before `Exception` catch in ViewModel coroutines.
- Coil `AsyncImage` with `VideoFrameDecoder` for all image/video thumbnail loading. `ContentScale.FillWidth` in scroll reader, `Fit` in pager reader, `Crop` in grids.
- `MediaItem.uri` is nullable (`Uri?`) — test convenience and robustness.
- `MediaItem.folderPath` stores the full filesystem absolute path (from `DATA` column or `file.absolutePath` for unindexed files).
- `MediaFolder.id` is MediaStore `PARENT` — unique per storage volume, used as Compose `LazyVerticalGrid` key.
- Navigation uses `Uri.encode()` for video URIs, string-based routes with `NavType.LongType`/`IntType` arguments.

## Testing

3 test files, all JVM unit tests:

- `FakeMediaRepository` — in-memory implementation with `folders`/`mediaItems`/`searchResults` lists, injectable `foldersError`/`mediaError`/`searchError` for error tests.
- Tests use `Dispatchers.Unconfined` for synchronous coroutine execution on JVM. `FakeMediaRepository` methods may throw synchronously; callers wrap in try-catch.
- `returnDefaultValues = true` in testOptions for Android SDK stubs.
- `Turbine` available as test dependency for Flow testing.
- `ReaderViewModelTest` (17 tests) — covers initialIndex, setCurrentIndex edge cases, mode switch preserves currentIndex, sort mode/order.
- `FolderListViewModelTest` (5 tests) — uses `is FolderUiState.Success` pattern matching. Test with null Application skips DataStore.
- `SliderUtilsTest` (9 tests) — `clampSliderTarget` edge cases (normal, bounds, single, empty, large list).

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
