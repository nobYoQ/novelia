# 来源、版权与第三方声明

Copyright (C) 2026 Novelia Android contributors.

本项目原创代码及文档采用 GNU General Public License version 3 only（SPDX：GPL-3.0-only），完整条款见 [LICENSE](LICENSE)。第三方代码、构建工具和素材保留各自的许可证及版权声明。本项目不提供任何担保，具体以许可证条款为准。

源码仓库：https://github.com/nobYoQ/novelia

发行版与对应源码：https://github.com/nobYoQ/novelia/releases

## 原站与接口

本项目是面向 Novelia 的 Android 客户端。

接口合约依据 [auto-novel/auto-novel](https://github.com/auto-novel/auto-novel) 公开源码（GNU GPL v3）和原站页面核对。原生页面、存储、阅读器和文件处理使用 Kotlin / Android API 实现。`.reference/` 仅供本机核对接口，不是构建输入。后续引入或改编上游实现时，必须记录来源、版本、修改内容并保留原有版权声明。

## 第三方软件

AndroidX / Compose、Kotlin、kotlinx.coroutines、kotlinx.serialization、OkHttp、Okio、Coil、Markwon 等依赖采用 Apache-2.0；Jsoup 采用 MIT；ICU4J 76.1 使用 Unicode-3.0 并附带其他数据许可；CommonMark 使用 BSD-2-Clause。OkHttp 所含 Public Suffix List 数据使用 MPL-2.0，来源见 [OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix) 和 [Public Suffix List](https://publicsuffix.org/list/)。这些第三方组件的许可不因本项目选择 GPL-3.0 而改变。

完整通用条款及来源记录位于 [licenses](licenses/README.md)。构建时还会从实际 Release 依赖图生成包含版本及 JAR/AAR 内原始 LICENSE/NOTICE 的报告，随 APK 打包；可在「我的 → 帮助与关于 → 开源许可证」离线查看。变更依赖后应复核报告，为依赖包未携带的许可证补入完整文本。

## 阅读器点击分区（改编自 FBReaderJ）

`app/src/main/java/cc/novelia/app/reader/ReaderTapZoneMap.kt` 与 `ReaderPageTurningOptions.kt` 改编自 [FBReaderJ](https://github.com/geometer/FBReaderJ) v2.5.10，原作者 FBReader.ORG Limited &lt;contact@fbreader.org&gt;。

- **原始文件**：`org/geometerplus/fbreader/fbreader/TapZoneMap.java`、`org/geometerplus/fbreader/fbreader/options/PageTurningOptions.java`，以及 `assets/default/tapzones/` 下 `right_to_left.xml`、`left_to_right.xml`、`up.xml`、`down.xml` 四套内置分区布局。
- **原始许可**：GNU General Public License version 2 or later（GPL-2.0-or-later）。本项目依据其 "or any later version" 条款按 GPL-3.0-only 使用，原始版权声明保留在改编文件头部。
- **修改内容**：改写为 Kotlin；去掉 `ZLStringOption`、`ZLFile`、SAX 解析依赖，分区表由 XML 资源改为不可变配置；点击动作由字符串改为枚举 `ReaderTapAction`；接入本项目 `ReaderSettings` 持久化。等分网格沿用 FBReader 的归一化命中规则，并扩展为可拖动的行列边界、单击／双击独立判定和相同动作邻格合并。正文覆盖编辑层、教程和点击反馈共享区域几何信息。

GPL-2.0 完整条款见 [licenses/GPL-2.0.txt](licenses/GPL-2.0.txt)。

## ECH 测试分支的本地传输库

本分支从源码构建 `native/ech`，使用基于 [Jissr Bypass v0.1.1](https://github.com/inqadh/jissr-bypass/tree/v0.1.1) 的本地修改版本（Apache-2.0），源码、原始许可证及修改说明保存在 `native/ech/third_party/jissr-bypass`，通过 Go `replace` 固定使用。修改包括隔离网络切换前后的在途 DNS 查询、保留多个 A/AAAA 地址，以及按 DNS TTL 管理解析缓存。未采用上游有整包大小限制的 Android 适配器。

Novelia 自行管理实际 HTTP 连接与 TLS 期限，并实现 JNI 流式上传/下载、取消、有限连接重试、多地址拨号和主机边界。修改后的第三方组件保留原有许可证，与本项目原创实现的许可分别记录。

工具链固定为 Go 1.27.1，使用 Go 标准库 TLS 1.3/ECH 和证书校验；运行时包含 `golang.org/x/net v0.56.0`、`x/sync v0.21.0`、`x/text v0.38.0` 及 `x/mobile 68735029466e` 生成的 JNI 绑定（BSD-3-Clause）。完整版本和完整性校验见 `native/ech/go.mod`、`go.sum` 与 `scripts/build-ech.ps1`；原始许可证合并于 `licenses/ECH-native.txt` 并随 APK 附带。构建用的 `x/tools` 和 `x/mod` 不作为应用运行时库打包。

## 内容与素材

网站上的小说、封面、译文和用户内容属于相应权利人，不属于本项目开源授权范围。测试夹具中的《风与书页》短文为此工程自行编写。
