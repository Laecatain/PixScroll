# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew assembleDebug                          # Build debug APK
./gradlew assembleRelease                        # Build release APK
./gradlew lint                                   # Run lint checks
```

Requires JDK 17 and Android SDK 34. Open in Android Studio to build/run on device or emulator.

## Architecture

Kotlin + Jetpack Compose (Material 3), MVVM pattern, no DI framework.

**Data flow**: ViewModel → `StateFlow<State>` → Composable collects via `collectAsState()`.

**Key layers**:

- `data/repository/MediaRepository.kt` — All data access via `ContentResolver` querying unified `MediaStore.Files`. Groups by `PARENT` (unique per storage volume). Supports `SortMode` (NAME/DATE/SIZE), `.nomedia` filtering, `IS_PENDING` exclusion (API 29+). Returns `Flow<List<T>>` with `flowOn(Dispatchers.IO)`.
- `ui/folderlist/` — Folder grid screen. State: `FolderListState` (folders, isLoading, error).
- `ui/reader/` — Image reader with two modes. State: `ReaderState` (mediaItems, currentMode, currentIndex, isLoading, error).
- `ui/player/` — Video player using Media3 ExoPlayer with lifecycle-aware pause/resume.
- `util/PermissionHelper.kt` — Permission list varies by SDK level (33+ uses `READ_MEDIA_*`, below uses `READ_EXTERNAL_STORAGE`).

**Reader modes** (switched at runtime via `ReaderScreen`):

| Mode | Component | Key detail |
|------|-----------|------------|
| Continuous vertical scroll | `ContinuousScrollReader` | `LazyColumn` with zero spacing between images; pinch-to-zoom + double-tap zoom; scroll disabled when zoomed |
| Horizontal pager | `PagerReader` | `HorizontalPager`; same zoom semantics |

**Navigation**: `navigation/NavGraph.kt` using Navigation Compose. Routes: `folder_list` → `reader/{parentId}` (LongType) → `video_player/{videoUri}`.

## Key Patterns

- ViewModels extend `AndroidViewModel` and construct `MediaRepository(application.contentResolver)` directly — no DI factory.
- All ViewModel state flows use `MutableStateFlow` + `asStateFlow()`.
- `try-catch` in ViewModel `load*()` methods updates state with `error` field on failure; UI shows error + retry button.
- `DisposableEffect` is used for ExoPlayer lifecycle cleanup.
- Coil `AsyncImage` for all image loading. Content scale: `FillWidth` in scroll reader, `Fit` in pager reader.
- Video thumbnails display a play button overlay; tap navigates to `VideoPlayerScreen`.
