# 第三方软件说明

本项目原创代码使用 Apache-2.0；该声明不重新授权第三方软件。完整许可原文与上游包内声明见 [随应用分发的声明](app/src/main/assets/legal/THIRD_PARTY_NOTICES.txt)，应用设置中也可离线阅读。

## 字幕编码检测：juniversalchardet 1.0.3

该库用于判断字幕的字符编码，不参与音频解码。此发行明确按 MPL 1.1 分发该库；不把它改成 Apache-2.0，不修改其 Java 源码。原作者及源文件的多许可声明均保留。

- [MPL 1.1 全文](app/src/main/assets/legal/MPL-1.1.txt)
- [原版源码 JAR 与 SHA-256](third_party/README.md)
- [可导出的完整源码包](app/src/main/assets/legal/juniversalchardet-1.0.3-source.zip)：含原版 sources.jar、原始 Maven POM、许可和构建说明。
- APK 自带同一源码包，用户可在设置中导出，不依赖维护者网站或账号。

发布者应保留本文件、许可资产和对应版本源码归档；修改或替换该库后须重新检查许可、更新源码包和说明。Android 构建会将字节码转换为 DEX 并可能优化，不是对上游源文件的修改。

## 正式版解析到的运行时组件

以下清单包含直接与传递依赖，不含仅用于测试或构建的工具。相同许可原文按内容合并，上游内嵌声明保持完整。

| 坐标 | 许可 | 来源 |
| --- | --- | --- |
| androidx.activity:activity-compose:1.10.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.activity:activity-ktx:1.10.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.activity:activity:1.10.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.annotation:annotation-experimental:1.4.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.annotation:annotation-jvm:1.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.appcompat:appcompat-resources:1.6.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.arch.core:core-common:2.2.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.arch.core:core-runtime:2.2.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.autofill:autofill:1.0.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.collection:collection-jvm:1.5.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.collection:collection-ktx:1.5.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.animation:animation-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.animation:animation-core-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.foundation:foundation-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.foundation:foundation-layout-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.material3:material3-android:1.3.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.material:material-icons-core-android:1.7.8 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.material:material-icons-extended-android:1.7.8 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.material:material-ripple-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.runtime:runtime-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.runtime:runtime-saveable-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-geometry-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-graphics-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-text-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-tooling-preview-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-unit-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.compose.ui:ui-util-android:1.8.2 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.concurrent:concurrent-futures:1.1.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.core:core-ktx:1.13.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.core:core-viewtree:1.0.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.core:core:1.13.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.customview:customview-poolingcontainer:1.0.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.documentfile:documentfile:1.1.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.emoji2:emoji2:1.4.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.exifinterface:exifinterface:1.3.7 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.graphics:graphics-path:1.0.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.interpolator:interpolator:1.0.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-common-java8:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-common-jvm:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-livedata-core:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-process:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-runtime-android:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-runtime-compose-android:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-runtime-ktx-android:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-viewmodel-android:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-viewmodel-compose-android:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.9.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.media3:media3-common:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-container:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-database:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-datasource:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-decoder:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-exoplayer:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-extractor:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media3:media3-session:1.8.0 | Apache-2.0 | [上游](https://github.com/androidx/media) |
| androidx.media:media:1.7.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.profileinstaller:profileinstaller:1.4.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.room:room-common-jvm:2.7.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.room:room-ktx:2.7.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.room:room-runtime-android:2.7.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.savedstate:savedstate-android:1.3.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.savedstate:savedstate-ktx:1.3.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.sqlite:sqlite-android:2.5.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.sqlite:sqlite-framework-android:2.5.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.startup:startup-runtime:1.1.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.tracing:tracing:1.0.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.vectordrawable:vectordrawable-animated:1.1.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.vectordrawable:vectordrawable:1.1.0 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| androidx.versionedparcelable:versionedparcelable:1.1.1 | Apache-2.0 | [上游](https://android.googlesource.com/platform/frameworks/support/) |
| com.google.accompanist:accompanist-drawablepainter:0.32.0 | Apache-2.0 | [上游](https://github.com/google/accompanist) |
| com.google.guava:failureaccess:1.0.2 | Apache-2.0 | [上游](https://github.com/google/guava) |
| com.google.guava:guava:33.3.1-android | Apache-2.0 | [上游](https://github.com/google/guava) |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | [上游](https://github.com/google/guava) |
| com.googlecode.juniversalchardet:juniversalchardet:1.0.3 | MPL-1.1 | [上游](https://code.google.com/archive/p/juniversalchardet/) |
| com.squareup.okhttp3:okhttp:4.12.0 | Apache-2.0 | [上游](https://github.com/square/okhttp) |
| com.squareup.okio:okio-jvm:3.9.0 | Apache-2.0 | [上游](https://github.com/square/okio) |
| io.coil-kt:coil-base:2.7.0 | Apache-2.0 | [上游](https://github.com/coil-kt/coil) |
| io.coil-kt:coil-compose-base:2.7.0 | Apache-2.0 | [上游](https://github.com/coil-kt/coil) |
| io.coil-kt:coil-compose:2.7.0 | Apache-2.0 | [上游](https://github.com/coil-kt/coil) |
| io.coil-kt:coil:2.7.0 | Apache-2.0 | [上游](https://github.com/coil-kt/coil) |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.0 | Apache-2.0 | [上游](https://github.com/JetBrains/kotlin) |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0 | Apache-2.0 | [上游](https://github.com/JetBrains/kotlin) |
| org.jetbrains.kotlin:kotlin-stdlib:2.1.21 | Apache-2.0 | [上游](https://github.com/JetBrains/kotlin) |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2 | Apache-2.0 | [上游](https://github.com/Kotlin/kotlinx.coroutines) |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2 | Apache-2.0 | [上游](https://github.com/Kotlin/kotlinx.coroutines) |
| org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3 | Apache-2.0 | [上游](https://github.com/Kotlin/kotlinx.serialization) |
| org.jetbrains:annotations:23.0.0 | Apache-2.0 | [上游](https://github.com/JetBrains/java-annotations) |
| org.jsoup:jsoup:1.18.3 | MIT | [上游](https://github.com/jhy/jsoup) |
| org.jspecify:jspecify:1.0.0 | Apache-2.0 | [上游](https://github.com/jspecify/jspecify) |

构建工具和 Gradle Wrapper 保留上游许可声明；测试依赖按各自上游许可使用。请勿删除上游版权或把第三方作者姓名当作个人信息清除。
