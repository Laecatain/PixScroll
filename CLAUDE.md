# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Build (use absolute path — no gradlew wrapper)
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

**Key layers**:

- `data/repository/MediaRepository.kt` — Interface + `AndroidMediaRepository` implementation. Unified `MediaStore.Files` query, PARENT-based grouping (not BUCKET_DISPLAY_NAME), `IS_PENDING` filter (API 29+), `.nomedia` detection, safe cursor access. `SortMode`/`SortOrder` are top-level enums.
- `ui/folderlist/` — `FolderListViewModel(MediaRepository)` extends `ViewModel`. State: `FolderListState` (folders, isLoading, error). Factory pattern for Android instantiation.
- `ui/reader/` — `ReaderViewModel(MediaRepository, parentId: Long)` extends `ViewModel`. Two reader modes, state: `ReaderState` (mediaItems, currentMode, currentIndex, isLoading, error, folderName).
- `ui/player/` — ExoPlayer with lifecycle-aware pause/resume via `DisposableEffect`.

**Reader zoom**: Container-level zoom (not per-item). `graphicsLayer` on the entire `LazyColumn`/`HorizontalPager`. Custom `awaitPointerEventScope` gesture handler — single-finger scroll passes through to the scroll container; pinch-zoom and zoomed-in pan are consumed at the container level. `userScrollEnabled = !isZoomed`.

**Navigation**: `reader/{parentId}` (LongType) → `video_player/{videoUri}`. Folder paths URI-encoded.

## Key Patterns

- ViewModels extend `ViewModel` (not `AndroidViewModel`), accept `MediaRepository` via constructor, use inner `Factory` class for Android creation from composables.
- `CancellationException` is always rethrown before `Exception` catch in ViewModel coroutines.
- Coil `AsyncImage` for all image loading. `ContentScale.FillWidth` in scroll reader, `Fit` in pager reader.
- `MediaItem.uri` is nullable (`Uri?`) — test convenience and robustness.
- `MediaFolder.id` is MediaStore `PARENT` — unique per storage volume, used as Compose `LazyVerticalGrid` key.

## Testing

- `FakeMediaRepository` — in-memory implementation, injectable `foldersError`/`mediaError` for error tests.
- Tests use `Dispatchers.Unconfined` for synchronous coroutine execution on JVM.
- `returnDefaultValues = true` in testOptions for Android SDK stubs.
- `Turbine` available as test dependency for Flow testing, though current tests use `state.value` directly.

## Agent Automation

需求出来 → `/plan`（出方案）→ `/tdd`（写测试先）→ `/code-review`（写完审）→ `/security-review`（提交前审）→ 构建通过 → 自动提交

- 所有审查通过 + `assembleDebug` 成功后，**自动执行 `git add` + `git commit`**，无需等待用户确认提交。
- 任何一步失败则停止，不提交。
- **必须 plan**：新功能、跨文件改动、多种实现方式可选、改错后重做成本高的事。不确定就问。
- **可跳过 plan**：单文件小改、修 bug、格式化、已有明确参照的做法。做错了重来成本低的事。
