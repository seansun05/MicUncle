# MicUncle

MicUncle 是一个基于 Android 的 K 歌应用原型，使用 Kotlin 和 Jetpack Compose 构建，适合本地点歌、视频伴唱和家庭 KTV 场景。

## 功能简介

- 首页、点歌页、收藏页、拼音搜索页
- 当前播放歌曲与已点歌曲队列管理
- 原唱 / 伴唱切换
- 静音、暂停 / 继续、重唱、切歌
- 小窗视频预览与全屏播放控制
- 自动扫描本地歌曲目录并加入歌库
- 针对不同媒体格式在 ExoPlayer 与 VLC 之间切换播放后端

## 技术栈

- Kotlin
- Jetpack Compose
- Android ViewModel
- Media3 ExoPlayer
- VLC Android SDK
- Coil

## 运行环境

- Android Studio
- JDK 11
- Android SDK 36
- 最低支持 Android 12（API 31）

## 本地运行

1. 使用 Android Studio 打开项目。
2. 等待 Gradle 同步完成。
3. 连接 Android 设备或启动模拟器。
4. 运行 `app` 模块。

## 歌曲资源说明

当前 GitHub 仓库 **不包含任何歌曲视频资源**，这是有意处理的。

如果你要本地体验完整功能，可以自行准备媒体文件：

- 方式一：放入本地目录 `Download/BaiduNetdisk/`
- 方式二：自行在本地开发环境补充 `app/src/main/assets/videos/` 目录中的资源

建议命名格式：

- `歌手-歌名.mp4`
- `歌手-歌名.mkv`
- `歌手-歌名-国语-流行.mpg`

应用会根据文件名自动解析歌手和歌名。

## 项目结构

```text
app/src/main/java/com/sean/micuncle/
├── MainActivity.kt
├── data/
├── player/
└── ui/
    ├── home/
    └── theme/
```

## 当前特性

- 支持资源目录扫描与本地目录扫描
- 支持收藏与已点状态管理
- 支持视频 Surface 渲染与全屏控制
- 支持部分格式使用 VLC 兜底播放
- 播放期间保持屏幕常亮

## 注意事项

- `local.properties`、构建产物、IDE 配置和本地媒体资源均不应提交到 GitHub。
- 本仓库当前更偏向原型和家庭场景使用，不是完整商用版本。

## License

MIT
