<div align="center">

<!-- TODO: 替换为项目 logo -->
<!-- <img src="logo.svg" alt="PixScroll" width="160" /> -->

# PixScroll

[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)](https://developer.android.com/about/versions/nougat)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.03-4285F4?logo=googlechrome)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Non--Commercial-blue)](#license)
[![Release](https://img.shields.io/github/v/release/Laecatain/PixScroll)](https://github.com/Laecatain/PixScroll/releases/latest)

**[English](#english)** | **[中文](#中文)**

**[⬇ 下载 APK / Download APK](https://github.com/Laecatain/PixScroll/releases/latest)**

</div>

<!-- TODO: 替换为截图/GIF -->
<!-- <div align="center">
  <img src="screenshots/folder_list.png" width="200" />
  <img src="screenshots/reader.png" width="200" />
  <img src="screenshots/video_player.png" width="200" />
</div> -->

---

<a id="中文"></a>

## 中文

> **像素滚动** — 图片在指尖无缝流淌，一滑到底的本地媒体阅读器

### 功能

📂 **文件夹浏览** — 自动扫描设备存储，图片/视频分类展示

📖 **双模式阅读器**
- **滚动模式** — 上下连续滚动，适合长图和漫画
- **翻页模式** — 左右翻页，逐页浏览
- 双指缩放 · 双击复位 · 进度滑块

🎬 **视频播放** — 沉浸全屏，双击快进/快退，长按 3 倍速

🔍 **全局搜索** — 跨文件夹搜索图片和视频

🎨 **三套主题** — 亮色 / 暗色 / 纯黑 (AMOLED)

⚡ **冷启动秒开** — 缓存文件夹列表，无需每次重新扫描

🗂️ **混合扫描** — MediaStore 优先 + 文件系统补充，不漏掉任何文件

↕️ **排序** — 名称 / 日期 / 大小，升序 / 降序，自动持久化

### 支持格式

| 图片 | 视频 |
|------|------|
| JPEG, PNG, WebP, HEIC, AVIF, GIF, SVG, BMP, TIFF | MP4, MKV, AVI, MOV, FLV, RMVB, 3GP, WebM |

### 开发

```bash
bash install.sh          # 构建并安装到手机
./gradlew assembleDebug  # 仅构建
./gradlew testDebugUnitTest  # 运行测试
```

要求：JDK 17 · Android SDK API 34 · `JAVA_HOME` + `ANDROID_HOME`

**[⬆ English](#english)**

---

<a id="english"></a>

## English

> **Pixel + Scroll** — your images flow seamlessly, a local media reader with zero friction

### Features

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

### Supported Formats

| Images | Videos |
|--------|--------|
| JPEG, PNG, WebP, HEIC, AVIF, GIF, SVG, BMP, TIFF | MP4, MKV, AVI, MOV, FLV, RMVB, 3GP, WebM |

### Development

```bash
bash install.sh          # Build & install to phone
./gradlew assembleDebug  # Build only
./gradlew testDebugUnitTest  # Run tests
```

Requires: JDK 17 · Android SDK API 34 · `JAVA_HOME` + `ANDROID_HOME`

**[⬆ 中文](#中文)**

---

## License / 许可证

Personal Non-Commercial / 个人非商业使用
