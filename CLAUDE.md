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
