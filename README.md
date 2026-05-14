# 阅读器 / Reader

> 一个本地的图片和视频阅读器，基于 Jetpack Compose + Material 3 构建。
> A local image & video reader built with Jetpack Compose and Material 3.

## Features

- **文件夹浏览** — 自动扫描设备存储，按文件夹分组展示图片和视频
- **双模式阅读器**
  - **滚动模式** (ContinuousScroll) — 上下连续滚动，适合长图/漫画
  - **翻页模式** (Pager) — 左右翻页，逐页浏览
  - 支持双指缩放、双击复位、滑块导航
- **视频播放器** — ExoPlayer (Media3)，沉浸模式，双击快进/快退 (±10s)，长按 3 倍速
- **全局搜索** — 防抖输入，跨文件夹搜索
- **混合媒体引擎** — 双阶段扫描：先展示 MediaStore 结果，后台 FileTreeWalk 补充未索引文件
- **排序持久化** — 按名称/日期/大小排序，支持升序/降序，DataStore 持久化
- **主题切换** — 亮色 / 暗色 / 纯黑 (AMOLED) 三模式
- **无 DI 框架** — 手动 ViewModel Factory，轻量简洁

## Screenshots

<!-- TODO: Add screenshots -->

## Tech Stack

| Layer | Library |
|---|---|
| Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (BOM 2025.03.00) + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Navigation | Navigation Compose 2.7.7 |
| Image Loading | Coil 2.6.0 (coil-compose, coil-video) |
| Video Playback | Media3 ExoPlayer 1.3.1 |
| Storage | DataStore Preferences 1.1.1 |
| Build | Gradle 8.7 + AGP 8.4.0 |
| Testing | JUnit 4, Turbine 1.1.0, kotlinx-coroutines-test |
| Min SDK | 26 |
| Target SDK | 34 |

## Architecture

MVVM 单向数据流：ViewModel → `StateFlow<State>` → Composable `collectAsState()`。

```
com.example.reader/
├── ReaderApp.kt              # Application，Coil ImageLoaderFactory
├── MainActivity.kt            # 单 Activity，权限处理
├── navigation/NavGraph.kt     # 7 个路由
├── data/
│   ├── model/                 # MediaItem, MediaFolder
│   └── repository/            # MediaRepository (接口 + AndroidMediaRepository 实现)
├── ui/
│   ├── folderlist/            # 文件夹列表
│   ├── mediagrid/             # 媒体网格 (复用 ReaderViewModel)
│   ├── reader/                # 阅读器 (ReaderScreen + ViewModel + 两种模式)
│   ├── search/                # 全局搜索
│   ├── settings/              # 设置页
│   ├── player/                # 视频播放器
│   ├── common/FastScroller.kt # 网格快速滚动条
│   └── theme/ThemeState.kt    # 主题单例
└── util/
    ├── AppSettings.kt         # DataStore 偏好设置
    ├── FolderCache.kt         # 文件夹列表缓存
    ├── MediaDimensionsCache.kt# 媒体尺寸缓存
    ├── SliderUtils.kt         # 滑块辅助工具
    └── PermissionHelper.kt    # 运行时权限
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

个人非商业使用 / Personal Non-Commercial
