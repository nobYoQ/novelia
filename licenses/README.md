# 第三方许可证维护

本目录保存不能仅靠依赖包内声明完整提供的通用许可证与数据许可。保留文本中的原始版权声明，不把项目的 GPL-3.0 套用到第三方组件。

| 文件 | 原始来源 | 用途 |
| --- | --- | --- |
| `Apache-2.0.txt` | https://www.apache.org/licenses/LICENSE-2.0.txt | AndroidX、Kotlin、Square、Coil、Markwon 等 |
| `ICU-76.1.txt` | https://github.com/unicode-org/icu/blob/release-76-1/LICENSE | ICU4J 76.1：Unicode-3.0 及附带数据声明 |
| `MPL-2.0.txt` | https://github.com/spdx/license-list-data/blob/main/text/MPL-2.0.txt | OkHttp 的 Public Suffix List 数据 |
| `ECH-native.txt` | Go 1.27.1、Jissr Bypass v0.1.1 及 `native/ech/go.mod` 固定的 Go 模块 | ECH 本地库及 JNI 的 Apache-2.0 / BSD-3-Clause 原文 |

Jsoup 的 MIT 和 CommonMark 的 BSD 原文，以及其他库附带的 LICENSE/NOTICE，直接取自 Gradle 实际解析的 JAR/AAR。完整结果位于 `app/build/generated/openSourceAssets/open-source/NOTICE.txt`，打包为 APK 内 `assets/open-source/NOTICE.txt`。根目录 `LICENSE` 和 `NOTICE.md` 也会并入该文件。

手动更新依赖后运行 `:app:generateOpenSourceNotices`，检查版本、来源及完整许可是否匹配。任务直接使用已解析的依赖包，支持 Gradle 离线缓存，不额外下载许可证。生成的清单使用 Release 依赖图，包含传递依赖，不包含仅用于测试、调试或构建插件的依赖。R8 可能移除其中未使用的代码。

构建脚本见 `gradle/open-source-notices.gradle.kts`。自动收集是材料整理步骤，不能替代引入新库时的许可审查。分发被修改的第三方组件时还需保留其改动说明及相应源码。
