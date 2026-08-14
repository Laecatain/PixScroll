# PixScroll

**English** | **[中文](README.md)**

> **Pixel + Scroll** — your images flow seamlessly, a local media reader with zero friction.

[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)](https://developer.android.com/about/versions/nougat)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.03-4285F4?logo=googlechrome)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Non--Commercial-blue)](#license)

<!-- TODO: Add screenshots / GIF here -->
<!-- ![Folder List](screenshots/folder_list.png) ![Reader](screenshots/reader.png) ![Video Player](screenshots/video_player.png) -->

**[⬇ Download Latest Release](https://github.com/Laecatain/PixScroll/releases/latest)**

---

## Features

- **Folder browsing** — TabRow tabs to switch images/videos, auto-scan device storage
- **Dual-mode reader**
  - **Continuous Scroll** — vertical continuous scrolling, ideal for long images & manga, images centered in viewport
  - **Pager** — swipe left/right, page by page browsing
  - Pinch zoom, double-tap reset, progress slider navigation
- **Video player** — ExoPlayer (Media3) with minimal custom controls
  - Immersive fullscreen playback
  - Double-tap left/right to seek ±10s
  - Long-press for 3× speed
  - Custom VideoFrameDecoder for video thumbnails
- **Hybrid media engine** — two-phase scan: MediaStore results first, then FileTreeWalk supplements unindexed files (jpg/png/webp/heic/avif/mp4/mkv/...)
- **Global search** — debounced input, cross-folder search for images and videos
- **Persistent sorting** — sort by name/date/size, ascending/descending, persisted via DataStore
- **Theme switching** — Light / Dark / AMOLED Black — three modes
- **Instant cold start** — JSON-cached folder list and .nomedia scan results
- **Performance**
  - Coil 3-level cache + Precision.EXACT + Generation ID concurrency control
  - Dual-slot video preload + zero-exit SurfaceView management
- **Zero DI framework** — manual ViewModel Factory, lightweight & simple

## Tech Stack

| Layer | Library |
|---|---|
| Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (BOM 2025.03.00) + Material 3 |
| Architecture | MVVM (ViewModel + `StateFlow`) |
| Navigation | Navigation Compose 2.7.7 |
| Image Loading | Coil 2.6.0 (coil-compose, coil-video, `VideoFrameDecoder`) |
| Video Playback | Media3 ExoPlayer 1.3.1 |
| Storage | DataStore Preferences 1.1.1 |
| Build | Gradle 8.7 + AGP 8.4.0 |
| Testing | JUnit 4, Turbine 1.1.0, kotlinx-coroutines-test |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

## Architecture

MVVM unidirectional data flow: ViewModel → `StateFlow<State>` → Composable `collectAsState()`

```
com.example.reader/
├── ReaderApp.kt              # Application, Coil ImageLoaderFactory
├── MainActivity.kt            # Single Activity, permission handling
├── navigation/NavGraph.kt     # 7 routes
├── data/
│   ├── model/                 # MediaItem, MediaFolder
│   └── repository/            # MediaRepository (interface + impl)
├── ui/
│   ├── folderlist/            # Folder list screen
│   ├── mediagrid/             # Media grid (reuses ReaderViewModel)
│   ├── reader/                # Reader screen + ViewModel + 2 modes
│   ├── search/                # Global search
│   ├── settings/              # Settings screen
│   ├── player/                # Video player
│   ├── common/FastScroller.kt # Grid fast scroller
│   └── theme/ThemeState.kt    # Theme singleton
└── util/
    ├── AppSettings.kt         # DataStore preferences
    ├── FolderCache.kt         # Folder list cache
    ├── MediaDimensionsCache.kt# Media dimensions cache
    ├── SliderUtils.kt         # Slider utilities
    └── PermissionHelper.kt    # Runtime permissions
```

## Build

### Prerequisites

- JDK 17 (Temurin)
- Android SDK API 34
- `JAVA_HOME` and `ANDROID_HOME` environment variables set

### Commands

```bash
# Build & install to connected device
bash install.sh

# Build APK only
./gradlew assembleDebug

# Run all unit tests
./gradlew testDebugUnitTest

# Run specific test
./gradlew testDebugUnitTest --tests "*ReaderViewModelTest*"
```

## License

Personal Non-Commercial
