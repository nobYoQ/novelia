# 来源与依赖

本项目是面向 Novelia 的非官方 Android 客户端，与原站没有官方客户端或品牌授权关系。

接口合约依据 [auto-novel/auto-novel](https://github.com/auto-novel/auto-novel) 公开源码（GNU GPL v3）和原站页面核对。实现使用 Kotlin / Android API，自行编写原生页面、存储、阅读器和文件处理代码。`.reference/` 仅供本机核对接口，不是构建输入。

使用的第三方库及其许可证包括 AndroidX / Compose、Kotlin、kotlinx.coroutines、kotlinx.serialization、OkHttp、Coil、Markwon（Apache 2.0），Jsoup（MIT）和 ICU4J（Unicode License）。正式分发时应携带对应版本的完整许可证及必要声明。

网站上的小说、封面、译文和用户内容属于相应权利人。测试夹具中的《风与书页》短文为此工程自行编写，不随正式应用作为远端内容展示。

互动图标、默认封面与部分状态反馈使用用户提供的 `HoshikawaMidori-1789205072094` 贴纸素材，保留原始 WebP 图像。原文件与应用资源的映射见 [贴纸交互设计](docs/sticker-interactions.md)。贴纸权利归原作者或相应权利人所有；项目未为这些素材声明开源许可证。
