<div align="center">

<!-- TODO: Replace with project logo -->
<!-- <img src="logo.svg" alt="PixScroll" width="160" /> -->

# PixScroll

**Pixel + Scroll** — your images flow seamlessly, a local media reader with zero friction

[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)](https://developer.android.com/about/versions/nougat)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.03-4285F4?logo=googlechrome)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Non--Commercial-blue)](#license)
[![Release](https://img.shields.io/github/v/release/Laecatain/PixScroll)](https://github.com/Laecatain/PixScroll/releases/latest)

**English** | **[中文](README.md)**

**[⬇ Download APK](https://github.com/Laecatain/PixScroll/releases/latest)**

</div>

<!-- TODO: Replace with screenshots/GIF -->
<!-- <div align="center">
  <img src="screenshots/folder_list.png" width="200" />
  <img src="screenshots/reader.png" width="200" />
  <img src="screenshots/video_player.png" width="200" />
</div> -->

---

## Features

📂 **Folder browsing** — auto-scan device storage, images & videos organized by folder

📖 **Dual-mode reader**
- **Continuous scroll** — vertical scrolling, perfect for long images & manga
- **Pager** — swipe left/right, page by page
- Pinch zoom · Double-tap reset · Progress slider

🎬 **Video playback** — immersive fullscreen, double-tap to seek, long-press 3× speed

🔍 **Global search** — search images & videos across all folders

🎨 **Three themes** — Light / Dark / AMOLED Black

⚡ **Instant cold start** — cached folder list, no re-scan on launch

🗂️ **Hybrid scanning** — MediaStore first + filesystem supplement, never misses a file

↕️ **Sorting** — name / date / size, ascending / descending, auto-persisted

## Supported Formats

| Images | Videos |
|--------|--------|
| JPEG, PNG, WebP, HEIC, AVIF, GIF, SVG, BMP, TIFF | MP4, MKV, AVI, MOV, FLV, RMVB, 3GP, WebM |

## Download

[<img src="https://img.shields.io/badge/Download-APK-blue?style=for-the-badge&logo=android" height="40">](https://github.com/Laecatain/PixScroll/releases/latest)

## Development

### Prerequisites

- JDK 17 (Temurin)
- Android SDK API 34
- `JAVA_HOME` + `ANDROID_HOME`

### Build

```bash
# Build & install to phone
bash install.sh

# Build only
./gradlew assembleDebug

# Run tests
./gradlew testDebugUnitTest
```

## License

Personal Non-Commercial
