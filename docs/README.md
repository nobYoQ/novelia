# Novelia 开发文档

Novelia 是轻小说机翻机器人的非官方 Android 客户端，使用 Kotlin 和 Jetpack Compose。它把在线小说、本地文件、阅读器和社区放在一个应用里；翻译生成和内容服务由原站提供，本仓库不包含服务端。

这里面向需要修改和维护客户端的开发者。产品介绍和下载见[项目首页](../README.md)，构建版本以 [version.properties](../version.properties) 为准。

## 第一次接触项目

建议先读三篇：

1. [环境搭建与构建](development/getting-started.md)：把项目跑起来，了解本地包与正式发行包的区别。
2. [架构与状态流](architecture/architecture.md)：认识应用服务、页面控制器和本地存储。
3. [源码导航](architecture/source-layout.md)：按要改的功能找到代码，再用[测试指南](quality/testing.md)选择验证范围。

所有命令默认在仓库根目录执行。Windows 示例使用 PowerShell 7。

## 按任务查阅

| 你要做什么 | 从这里开始 |
| --- | --- |
| 配环境、构建 APK、调整版本 | [构建指南](development/getting-started.md) |
| 新增页面、偏好或 API | [开发流程](development/development.md)、[界面与导航](architecture/ui-and-navigation.md) |
| 改书架、收藏、历史或更新提醒 | [书架](features/library.md) |
| 改搜索、筛选、标签库或排行榜 | [发现与搜索](features/discovery.md) |
| 改目录、继续阅读、文库资料或术语 | [书籍详情](features/book-details.md) |
| 改排版、翻页、双语、定位或朗读 | [阅读器](features/reader.md) |
| 改帖子、回复、Markdown 或草稿 | [社区](features/community.md)、[论坛接口](network/forum-api-preview.md) |
| 改文件导入、下载、导出或转换 | [文件与下载](features/files-and-downloads.md) |
| 改设置、书签或笔记 | [设置与笔记](features/settings-and-notes.md) |
| 改持久化、迁移或备份恢复 | [数据存储](data/data-and-storage.md)、[备份与恢复](data/backup-and-recovery.md) |
| 排查登录、请求或原站同步 | [认证](network/authentication.md)、[网络与同步](network/network-and-sync.md) |
| 改镜像线路、ECH 或诊断 | [书源线路](development/book-source-mirrors.md)、[网络诊断](network/network-diagnostics.md) |
| 改 WebDAV 多设备同步 | [WebDAV](network/webdav-sync.md) |
| 做测试、性能分析或发布 | [测试](quality/testing.md)、[性能](quality/performance.md)、[发布](../RELEASING.md) |
| 定位故障 | [排障指南](maintenance/troubleshooting.md) |

完整分类：[开发](development/README.md) · [架构](architecture/README.md) · [功能](features/README.md) · [数据](data/README.md) · [网络](network/README.md) · [质量](quality/README.md) · [维护](maintenance/README.md)。

## 开发前先分清三件事

**书目、正文和缓存各有生命周期。** 收藏保存书籍信息；下载得到文件；导入才建立本地文档。取消收藏、删除文档和清缓存的影响不同。

**原站同步、WebDAV 和备份用途不同。** 原站同步处理账号收藏和章节历史；WebDAV 合并选定的网络书籍阅读资料；阅读资料 ZIP 负责迁移本地小说等设备资料。三者都不会替用户迁移登录凭据。

**内存中的新状态可能还没写入磁盘。** 页面通过 `LocalStore.update` 修改状态；后台任务和恢复等流程需要明确的持久化边界。请求同时要绑定发起时的账号和书源，不能在完成时随手换成“当前用户”。

## 文档依据与维护

本轮按 **2026-10-06、源码提交 `24a3125`** 核对当前客户端行为。服务端在线状态、GitHub 后台设置和实际设备验收不由这次源码核对证明。已知的正式发布脚本问题见[发布指南](../RELEASING.md#准备附件)。

功能说明描述当前实现；旧论坛部署适配记录单独放在[历史记录](maintenance/history/forum-adaptation.md)，其中的测试结果只属于对应日期。后续修改请同步相关专题，写法见[文档维护](maintenance/documentation.md)。

协作规则见[贡献指南](../CONTRIBUTING.md)，漏洞报告见[安全政策](../SECURITY.md)，来源与许可见 [NOTICE](../NOTICE.md) 和[许可证目录](../licenses/README.md)。
