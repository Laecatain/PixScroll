# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Build + install to phone (auto-detects device, falls back to build-only)
bash /e/PixScroll/install.sh

# Build only (use gradlew if available, else gradle directly)
./gradlew assembleDebug

# Run all tests (JVM, no device needed)
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "*ReaderViewModelTest*"
```

Requires `JAVA_HOME=/d/Android/jdk` and `ANDROID_HOME=/d/Android/sdk`. JDK 17 Temurin, SDK API 34 (compileSdk), minSdk 26, targetSdk 34.

## Architecture

Kotlin + Jetpack Compose (Material 3), MVVM, no DI framework. Compose BOM 2025.03.00. ExoPlayer (Media3) for video, Coil for async image loading + `VideoFrameDecoder` for thumbnails.

**Data flow**: ViewModel → `StateFlow<ReaderState>` → Composable collects via `collectAsState()`. One-way data flow: UI calls ViewModel methods → ViewModel updates `MutableStateFlow`.

**Storage**: DataStore Preferences (sort prefs, theme, grid columns) + JSON file cache (folder list, hidden parents, media dimensions) + video thumbnail cache (MD5-keyed JPGs in `cacheDir/thumbnails/`) in `cacheDir`.

### Package Layout

```
com.example.reader/
├── ReaderApp.kt               # Application, Coil ImageLoaderFactory, theme init
├── MainActivity.kt             # Single activity, sets content with NavGraph
├── navigation/NavGraph.kt      # 7 routes, Routes object, safePopBackStack helper
├── data/
│   ├── model/MediaItem.kt      # uri, name, mimeType, size, parentId, width/height, aspectRatio
│   ├── model/MediaFolder.kt    # id(PARENT), folderName, coverImageUri, mediaCount
│   └── repository/MediaRepository.kt  # Interface + AndroidMediaRepository, SortMode/SortOrder enums
├── ui/
│   ├── folderlist/             # FolderListScreen + FolderListViewModel, cache-first init
│   ├── mediagrid/              # MediaGridScreen, reuses ReaderViewModel, FastScroller
│   ├── reader/                 # ReaderScreen + ReaderViewModel + ContinuousScrollReader + PagerReader
│   ├── search/                 # SearchScreen + SearchViewModel, debounce 300ms, folder cache + filename index
│   ├── settings/               # SettingsScreen + SettingsViewModel, all DataStore prefs
│   ├── player/                 # VideoPlayerScreen, ExoPlayer lifecycle-aware
│   ├── common/FastScroller.kt  # Right-side drag scroller for LazyVerticalGrid
│   └── theme/ThemeState.kt     # Singleton, LIGHT/DARK/AMOLED_BLACK, DataStore persistence
└── util/
    ├── AppSettings.kt          # DataStore delegate, PreferenceKeys, AppSettingsData, save helpers
    ├── CustomVideoFrameDecoder.kt # Coil Decoder for videos, 3-layer defense, MediaMetadataRetriever
    ├── FolderCache.kt          # JSON I/O for folder list + .nomedia hidden parents
    ├── MediaDimensionsCache.kt # JSON-persisted URI→(width,height) map, BitmapFactory bounds decode
    ├── PermissionHelper.kt     # READ_MEDIA_IMAGES/VIDEO (33+) | READ_EXTERNAL_STORAGE (legacy)
    ├── ThumbnailManager.kt     # Disk cache for video thumbnails (MD5-keyed JPGs, cacheDir/thumbnails/)
    └── ThumbnailBackfillManager.kt # Phase 3 batch backfill: pre-generates thumbnails on folder enter
```

### Navigation

7 routes in `NavGraph.kt`, defined via `Routes` object:

```
folder_list → media_grid/{parentId} → reader/{parentId}/{initialIndex}
                                     → video_player/{videoUri}
folder_list → search
folder_list → settings → about
```

Search navigates to `reader/{item.parentId}/0`. Video URIs are `Uri.encode()`'d. `safePopBackStack()` guards against empty backstack (prevents double-back-to-exit crash).

### Permissions (AndroidManifest)

- `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (API 33+)
- `READ_EXTERNAL_STORAGE` with `maxSdkVersion=32` (legacy)
- Activity sets `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` for manual rotation handling

### Key Layers

- **`AndroidMediaRepository`** — Unified `MediaStore.Files` query, `PARENT`-based grouping, `IS_PENDING` filter (API 29+). Multi-phase `getMediaByFolder()`: Phase 1 (MediaStore), Phase 2 (FileTreeWalk, maxDepth=4), Phase 3 (optional thumbnail backfill). `.nomedia` detection. **Search**: in-memory `allFoldersCache` + filename index (see docs/plan-space-for-time-optimization.md).
- **`ReaderViewModel`** — Shared by reader and media grid. Dual-init coroutines: (1) load sort prefs from DataStore then `loadMedia()`, (2) listen for external sort changes via `settingsFlow().drop(1)`. Index correction on Phase 2 merge preserves the user's current position by URI matching.
- **`FolderListViewModel`** — Cache-first init: reads `FolderCache` synchronously as `StateFlow` initial value (avoids Loading flash). `skipNextLoading` flag prevents first `loadFolders()` from overwriting cached success.
- **`ThemeState`** — Singleton with callback `onModeChanged` set by `ReaderApp.onCreate()` for persistence. `init(mode)` called via `runBlocking { dataStore.data.first() }` at startup.
- **`AppSettings`** — Top-level `Context.dataStore` delegate. `settingsFlow()` maps `DataStore<Preferences>` to `AppSettingsData`. Separate save helpers for each key.
- **`FolderCache`** — JSON file I/O in `cacheDir` for folder list + `.nomedia` hidden parents. Written after each query, read synchronously at startup.
- **`MediaDimensionsCache`** — JSON file mapping URI→(width,height). Populated by `BitmapFactory.Options.inJustDecodeBounds` when MediaStore lacks dimension data (unindexed files).
- **`ThumbnailManager`** — Disk cache for video thumbnails (300px JPEG, MD5-keyed, `cacheDir/thumbnails/`).
- **`ThumbnailBackfillManager`** — Phase 3 batch backfill in `ReaderViewModel.loadMedia()`; visible-item priority, max 50/batch.

### Reader Zoom

Container-level zoom (not per-item). `graphicsLayer` on the entire `LazyColumn`/`HorizontalPager`. Custom `awaitPointerEventScope` handler: `event.zoomChange()` for pinch, `event.panChange()` for two-finger pan. Single-finger scroll passes through. `userScrollEnabled = !isZoomed`. Scale clamped 1f–3f (ContinuousScroll) or 1f–5f (Pager). `detectTapGestures` for toggle toolbar + double-tap zoom reset.

### ContinuousScrollReader Strategy

- **Cold start**: `rememberLazyListState(initialFirstVisibleItemIndex = safeInitial, initialFirstVisibleItemScrollOffset = centeringOffset)` for zero-frame target + vertical centering.
- **Hot start**: `LaunchedEffect(mediaItems)` waits for `totalItemsCount > 0` via `snapshotFlow`, then `animateScrollToItem`. Hybrid jump: |target−cur| > `LONG_JUMP_THRESHOLD` (10) → `scrollToItem(mid)` + `animateScrollToItem(target)`.
- **Content padding**: `PaddingValues(vertical = screenHeightDp)` on LazyColumn so short items center vertically via scroll offset.
- **Center-detection index**: Uses `snapshotFlow` on `layoutInfo.visibleItemsInfo`, finds the item closest to viewport center (not `firstVisibleItemIndex`).
- **dataSetKey**: `mediaItems.firstOrNull()?.parentId ?: -1L` as `remember` key for `sliderValue`/`visibleIndex`. Phase 2 data merge doesn't reset visible position.
- **Triple state lock**: `!isDragged && !isScrolling && !isUserInteracting` guards slider→index write-back, preventing bidirectional binding conflicts.
- **Aspect ratio placeholders**: `ImageWithAspectPlaceholder` wraps `AsyncImage` in `Box` with `Modifier.aspectRatio(ratio)`. `DEFAULT_ASPECT_RATIO = 16f/9f`.
- **Hybrid jump with offset**: `calculateCenteringOffset()` computes scroll offset to vertically center short images, top-align tall ones.

### PagerReader Strategy

- `HorizontalPager` with `rememberPagerState(initialPage)`. State lock identical to ContinuousScroll (drag + scroll guard).
- `snapshotFlow { pagerState.currentPage }` syncs to ViewModel.
- Slider value range `0f..(totalCount-1)`. Hybrid jump uses `scrollToPage(mid) → animateScrollToPage(target)`.
- `ContentScale.Fit` for full-page image display.

### Slider

State lock via `MutableInteractionSource.collectIsDraggedAsState()` + `listState.isScrollInProgress` / `pagerState.isScrollInProgress`. Slider only writes back when `!isDragged && !isScrolling`. `onValueChangeFinished` applies `clampSliderTarget(value, itemCount)` (round + coerceIn), then hybrid jump.

### FastScroller (MediaGrid)

Right-side drag scroller for `LazyGridState`. `Animatable` alpha: 0.8 while dragging/scrolling, 300ms fade-out after 1.5s idle. Page number bubble on drag. No rendering when `itemCount ≤ 1`.

## Space-for-Time Optimizations

Video thumbnail batch pre-generation (Phase 3) + search caching (folder list + filename index). See [docs/plan-space-for-time-optimization.md](docs/plan-space-for-time-optimization.md) for full strategy.

## Key Patterns

- ViewModels extend `ViewModel` (not `AndroidViewModel`), accept `MediaRepository` + `Application?` (null for tests skips DataStore). Inner `Factory` class for `ViewModelProvider`.
- `CancellationException` always rethrown before `Exception` catch in coroutines.
- Coil `AsyncImage` with `VideoFrameDecoder` for image/video thumbnails. `ContentScale.FillWidth` in scroll reader, `Fit` in pager, `Crop` in grids.
- `MediaItem.uri` nullable (`Uri?`) — test convenience. `parentId` used for back-navigation from search results.
- `MediaItem.folderPath` = full absolute path from `DATA` column (indexed) or `file.absolutePath` (unindexed).
- `MediaFolder.id` = MediaStore `PARENT`, used as `LazyVerticalGrid` key.
- `dataSetKey` (parentId) as `remember` key for reader slider state — stabilizes across Phase 1→Phase 2 emission.
- Navigation `Uri.encode()` for video URIs, string routes with `NavType.LongType`/`IntType`.
- Immutable state copy: `_state.value = _state.value.copy(field = newValue)`.

## Testing

3 test files, all JVM unit tests (`testDebugUnitTest`):

| File | Tests | Coverage |
|------|-------|----------|
| `ReaderViewModelTest` | 17 | initialIndex, setCurrentIndex edge cases, mode switch preserves index, sort mode/order |
| `FolderListViewModelTest` | 5 | `is FolderUiState.Success` pattern matching, null Application skips DataStore |
| `SliderUtilsTest` | 9 | `clampSliderTarget` edge cases (bounds, single, empty, large list) |

Test infrastructure:
- `FakeMediaRepository` — in-memory with injectable `foldersError/mediaError/searchError` for error cases
- `Dispatchers.Unconfined` for synchronous JVM coroutines
- `returnDefaultValues = true` in `testOptions` for Android SDK stubs
- `Turbine` for `StateFlow` testing
- ViewModels construct with `application = null` to skip DataStore in tests

## Agent Automation

| Change Type | Plan | Code Review | Security Review |
|-------------|------|-------------|-----------------|
| Typo/string/format | Skip | Skip | Skip |
| Single-function bug fix | Skip | Required | Skip |
| New feature / UI change | Required | Required | Skip |
| Involves IO/URI/permissions/input | Required | Required | Required |

- Pipeline: All reviews pass + `assembleDebug` succeeds → auto `git add` + `git commit`
- Any step fails → stop, no commit
- When uncertain: escalate (code review if unsure, plan if unsure)
