<div align="center">

<!-- TODO: 替换为项目 logo -->
<!-- <img src="logo.svg" alt="PixScroll" width="160" /> -->

# PixScroll

> **像素滚动** — 图片在指尖无缝流淌，一滑到底的本地媒体阅读器

[![GitHub Stars](https://img.shields.io/github/stars/Laecatain/PixScroll?style=social)](https://github.com/Laecatain/PixScroll/stargazers)
[![GitHub Downloads](https://img.shields.io/github/downloads/Laecatain/PixScroll/total?color=blue&label=Downloads)](https://github.com/Laecatain/PixScroll/releases/latest)
[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)]()
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.03-4285F4?logo=googlechrome)]()
[![License](https://img.shields.io/badge/License-Non--Commercial-blue)](#许可证)

<p align="right"><a href="README_EN.md">English</a></p>

<!-- TODO: 替换为截图/GIF -->
<!-- <p>
  <img src="screenshots/folder_list.png" width="180" />
  <img src="screenshots/reader.png" width="180" />
  <img src="screenshots/video_player.png" width="180" />
</p> -->

[<img src="https://img.shields.io/badge/⬇_下载_APK_(GitHub)-181717?style=for-the-badge&logo=github&logoColor=white" height="48">](https://github.com/Laecatain/PixScroll/releases/latest)

</div>

---

## 功能

📂 **文件夹浏览** — 打开即用，自动按文件夹整理图片和视频

📖 **双模式阅读器**
- 上下连续滚动，看长图漫画一滑到底
- 左右翻页，一张一张慢慢看
- 双指缩放 · 双击复位 · 进度滑块快速跳转

🎬 **视频播放** — 全屏沉浸，双击快进快退，长按 3 倍速

🔍 **全局搜索** — 输入关键词，跨文件夹找图找视频

🎨 **三套主题** — 亮色 / 暗色 / 纯黑 (AMOLED 省电)

⚡ **秒开** — 首次扫描后缓存，下次打开瞬间加载

🗂️ **不漏文件** — 系统扫描 + 文件系统双重检测，隐藏文件夹也能找到

↕️ **排序** — 名称 / 日期 / 大小，升序降序，记住你的选择

## 支持格式

| 图片 | 视频 |
|------|------|
| JPEG, PNG, WebP, HEIC, AVIF, GIF, SVG, BMP, TIFF | MP4, MKV, AVI, MOV, FLV, RMVB, 3GP, WebM |

## 常见问题

**为什么叫 PixScroll？**
Pix (Pixel) + Scroll — 像素在指尖滚动，一滑到底的体验。

**和其他相册应用有什么不同？**
专注本地浏览，不联网、不上传。双模式阅读器（连续滚动 + 翻页）是特色，看漫画长图特别顺手。

**支持哪些 Android 版本？**
Android 8.0 (API 26) 及以上，覆盖 95%+ 的活跃设备。

<details>
<summary>开发构建</summary>

```bash
bash install.sh              # 构建并安装到手机
./gradlew assembleDebug      # 仅构建
./gradlew testDebugUnitTest  # 运行测试
```

要求：JDK 17 · Android SDK API 34 · `JAVA_HOME` + `ANDROID_HOME`

</details>

## 许可证

个人非商业使用
