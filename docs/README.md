# Novelia 开发手册

本手册面向参与 Novelia Android 开发、评审和发布的贡献者。内容依据当前工作树实现整理，版本基线为 `0.1.8 / 11`；包含尚未发布的 GitHub 开源准备变更。版本与依赖的最终依据分别是 [version.properties](../version.properties) 和 [Gradle 配置](../app/build.gradle.kts)，本文档不是某次发行的测试通过证明。

Novelia 是原站的非官方 Android 客户端。客户端提供阅读、收藏、社区和文件工具，不包含翻译中心、译文生成或翻译服务密钥配置；原站 API、内容和服务端权限不由本仓库控制。产品介绍见 [项目首页](../README.md)。

## 推荐阅读顺序

首次参与开发，依次阅读「环境搭建 → 源码目录 → 架构 → 开发流程 → 测试」，然后按修改范围选择模块文档。

| 文档 | 解决的问题 |
| --- | --- |
| [环境搭建与构建](getting-started.md) | 从干净检出到本地运行；Debug / Release 一键打包、Gradle、自选参数和输出 |
| [源码目录导航与归档规则](source-layout.md) | 17 个 UI 子包、13 个数据职责包、设备测试分类及文件放置约定 |
| [架构与代码地图](architecture.md) | 两个 Gradle 模块、应用生命周期、状态流和功能入口 |
| [日常开发与扩展](development.md) | 如何添加页面、偏好、API、书源及依赖；PR 维护要求 |
| [界面与导航](ui-and-navigation.md) | 路由、Compose 状态、自适应布局、主题、动效与 Markdown |
| [网络、认证与同步](network-and-sync.md) | API 合约、会话绑定、缓存失效、离线待同步操作与重试 |
| [数据、存储与备份](data-and-storage.md) | 文件布局、模型兼容、原子写入、恢复和备份边界 |
| [阅读器](reader.md) | 章节加载、译文投影、阅读锚点、分页、搜索、插图和朗读 |
| [文件与下载](files-and-downloads.md) | EPUB/TXT/SRT、后台下载、导出、图片压缩和解析限制 |
| [测试与验收](testing.md) | JVM、设备、只读联调、修改范围与测试的对应关系 |
| [性能测量](performance.md) | 数据场景、Macrobenchmark、Baseline Profile 和结果解释 |
| [性能 Profile 维护](baseline-profiles.md) | 类与文件迁移后的旧规则处理、重新采集和当前覆盖范围 |
| [安全与隐私开发约束](security-and-privacy.md) | 会话、WebView、文件、日志、导出和权限的审查点 |
| [发布与维护](release-and-maintenance.md) | GitHub 协作、版本管理、手动发布、许可证与发布阻塞项 |
| [排障指南](troubleshooting.md) | 环境、网络、同步、文件、阅读器和安装故障定位 |

## 按任务查找

| 我准备修改…… | 先读 | 修改后重点验证 |
| --- | --- | --- |
| 筛选、列表、搜索 | [界面](ui-and-navigation.md)、[开发流程](development.md) | 分页去重、恢复状态、筛选变化、窄屏和电子纸 |
| 登录、收藏、阅读历史 | [网络与同步](network-and-sync.md)、[安全](security-and-privacy.md) | 401、退出再登录、账号切换、离线重试 |
| 存储字段或备份格式 | [数据与备份](data-and-storage.md) | 旧数据缺省值、损坏恢复、取消导入、恢复后引用完整性 |
| 阅读排版或进度 | [阅读器](reader.md) | 切译文/字号/模式后的锚点、插图、跨章、搜索和朗读 |
| EPUB、下载和导出 | [文件与下载](files-and-downloads.md) | 格式错误、路径逃逸、容量限制、取消和文件残留 |
| 主题、动效或大屏 | [界面](ui-and-navigation.md)、[测试](testing.md) | 电子纸、减少动效、字体缩放、横屏、系统返回 |
| 依赖或发版配置 | [发布](release-and-maintenance.md)、[环境](getting-started.md) | Debug/Release 构建、许可清单、正式签名与版本 |

## 协作入口与文档维护

- [CONTRIBUTING.md](../CONTRIBUTING.md)：贡献约定和提交 PR 的要求。
- [SECURITY.md](../SECURITY.md)：漏洞报告方式；不要在公开 Issue 中披露凭据和用户数据。
- [RELEASING.md](../RELEASING.md)：正式签名、附件生成和上传的操作依据。
- [CHANGELOG.md](../CHANGELOG.md)：用户可见变化；开发手册不替代发行说明。
- [NOTICE.md](../NOTICE.md) 与 [licenses](../licenses/README.md)：代码、依赖、内容和素材的授权范围。

代码变更影响命令、数据格式、路由、权限或开发约束时，在同一个 PR 更新对应文档；只修改实际改变的章节。文档用 UTF-8 无 BOM，使用相对链接指向源码，不把本机绝对路径或忽略目录里的文件作为必要依赖。配置常量避免在多篇文档重复维护；引用它们的定义处。

当前没有 CI，检查需手动执行并记录。贴纸分发授权仍未确认，是公开包含相应素材的源码及 APK 前的待办，详见 [发布边界](release-and-maintenance.md)。
