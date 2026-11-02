# 音声书架 / YsPlayer
纯粹的VibeCoding产物
面向本地音声收藏的原生 Android 播放器。最低 Android 10（API 29）；Kotlin、Jetpack Compose、Room、Media3。核心播放流程离线运行。

## 功能

- 使用系统目录选择器授权文件夹，按 RJ 编号与目录结构整理作品。
- 作品封面列表 / 双列视图、手选封面、搜索、收藏、待整理与最近收听。
- 声优、社团与中文标签筛选，支持多人署名和自定义播放列表。
- 后台 / 熄屏播放、系统媒体控制、变速、循环与睡眠定时。
- LRC / SRT / VTT / ASS 字幕；全文浏览、点击时间跳转、编码与时间偏移；可选悬浮字幕。
- 可选 DLsite 信息补全：本地缓存、串行请求、至少 10 秒查询间隔，单作品失败跳过。
- 目录增加 RJ 前缀后可刷新修正索引；详情可移除作品，源文件保留。

自动补全默认开启，可在设置中关闭。联网时会向 DLsite 发送作品编号和普通网络请求信息；详见 [隐私说明](PRIVACY.md)。补全不是下载音频的功能，不要求账户或 Cookie。项目与 DLsite 无官方关联。

## 使用

选择具体音声子目录导入。系统对存储根目录和 Download 根目录的授权有限制。目录改名后点击刷新；失去授权时重新选择目录。桌面歌词需主动授予悬浮窗权限。

作品详情右上角垃圾桶只删除库索引和相关列表条目，保留音频、图片和字幕；文件仍在授权目录中时，再次扫描可导入。清除应用数据和卸载会丢失本地设置及历史，请先做好个人数据安排。

## 构建

需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。Android Studio 打开项目根目录并生成本机 local.properties，也可配置 ANDROID_HOME。不要提交本机 SDK 路径。

    ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

Windows 使用 gradlew.bat。正式签名及 GitHub 发布步骤见 [发布指南](docs/发布指南.md)。

固定工具版本：Gradle 8.11.1、AGP 8.10.1、Kotlin 2.1.21。首次构建需要下载 Maven 依赖。仓库保留 Gradle Wrapper、数据库 schema 和虚构测试样本。

依赖升级后，重新导出并审查第三方清单：

    ./gradlew :app:exportReleaseDependencies
    python tools/prepare_licenses.py

该维护脚本联网下载许可原文与准确版本源码；普通构建使用已提交的许可资产，无需运行它。

## 开源许可

项目原创代码采用 [Apache-2.0](LICENSE)，第三方组件保留各自许可，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。字幕编码检测库 juniversalchardet 1.0.3 采用 MPL 1.1，其原版源码、原始 Maven POM、许可和构建说明已随 APK 与源码发行包提供，可在“设置 → 开源许可与隐私说明”中离线导出。

不随项目提供音声、作品封面、字幕或真实收藏库。请只导入有权使用的内容。测试与文档示例使用虚构数据；第三方源码中的原作者版权和署名按其许可证保留。

## 验证范围

自动测试使用 Robolectric API 29 / 35，覆盖扫描、数据库升级、字幕、元数据缓存与限速、作品库 UI 及许可源码包。自动测试不能替代真机的长时间后台播放、实际文件提供器、网络可达性和厂商省电策略验证。
