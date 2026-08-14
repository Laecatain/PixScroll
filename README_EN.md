<div align="center">

<!-- TODO: Replace with project logo -->
<!-- <img src="logo.svg" alt="PixScroll" width="160" /> -->

# PixScroll

> **Pixel + Scroll** — your images flow seamlessly, a local media reader with zero friction

[![GitHub Stars](https://img.shields.io/github/stars/Laecatain/PixScroll?style=social)](https://github.com/Laecatain/PixScroll/stargazers)
[![GitHub Downloads](https://img.shields.io/github/downloads/Laecatain/PixScroll/total?color=blue&label=Downloads)](https://github.com/Laecatain/PixScroll/releases/latest)
[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)]()
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.03-4285F4?logo=googlechrome)]()
[![License](https://img.shields.io/badge/License-Non--Commercial-blue)](#license)

<p align="right"><a href="README.md">中文</a></p>

<!-- TODO: Replace with screenshots/GIF -->
<!-- <p>
  <img src="screenshots/folder_list.png" width="180" />
  <img src="screenshots/reader.png" width="180" />
  <img src="screenshots/video_player.png" width="180" />
</p> -->

[<img src="https://img.shields.io/badge/⬇_Download_APK_(GitHub)-181717?style=for-the-badge&logo=github&logoColor=white" height="48">](https://github.com/Laecatain/PixScroll/releases/latest)

</div>

---

## Features

🔒 **Fully offline** — no internet, no uploads, no tracking. Your photos stay on your phone.

📂 **Folder browsing** — open and go, images & videos auto-organized by folder

📖 **Dual-mode reader**
- Continuous vertical scroll — perfect for long images & manga
- Page-by-page swipe — browse one at a time
- Pinch zoom · Double-tap reset · Progress slider for quick jump

🎬 **Video playback** — immersive fullscreen, double-tap to seek, long-press 3× speed

🔍 **Global search** — type a keyword, find images & videos across all folders

🎨 **Three themes** — Light / Dark / AMOLED Black (battery-friendly)

⚡ **Instant open** — cached after first scan, loads in a flash next time

🗂️ **Never misses files** — dual detection: system scan + filesystem walk, finds hidden folders too

↕️ **Sorting** — name / date / size, ascending or descending, remembers your choice

## Supported Formats

| Images | Videos |
|--------|--------|
| JPEG, PNG, WebP, HEIC, AVIF, GIF, SVG, BMP, TIFF | MP4, MKV, AVI, MOV, FLV, RMVB, 3GP, WebM |

## FAQ

**Why "PixScroll"?**
Pix (Pixel) + Scroll — pixels flowing under your fingertips, scroll all the way down.

**How is it different from other gallery apps?**
Focused on local browsing — no internet, no uploads. The dual-mode reader (continuous scroll + pager) is the standout feature, especially great for manga and long images.

**Which Android versions are supported?**
Android 8.0 (API 26) and above, covering 95%+ of active devices.

<details>
<summary>Development</summary>

```bash
bash install.sh              # Build & install to phone
./gradlew assembleDebug      # Build only
./gradlew testDebugUnitTest  # Run tests
```

Requires: JDK 17 · Android SDK API 34 · `JAVA_HOME` + `ANDROID_HOME`

</details>

## License

Personal Non-Commercial
