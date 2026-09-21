# Novelia 项目文档

本目录按功能和维护职责组织 Android 客户端的使用边界、实现流程与开发说明。内容以当前工作树为准，不代表某次发行已经通过测试。版本、依赖和用户可见变化分别以 [version.properties](../version.properties)、[Gradle 配置](../app/build.gradle.kts) 和 [CHANGELOG.md](../CHANGELOG.md) 为准。

Novelia 是原站的非官方 Android 客户端，提供阅读、收藏、社区和文件工具，不包含翻译中心、译文生成或翻译服务密钥配置。原站 API、内容和服务端权限不由本仓库控制。产品介绍与下载入口见 [项目首页](../README.md)。

## 文档分类

| 分类 | 阅读内容 | 入口 |
| --- | --- | --- |
| 开发入门 | 环境、构建、开发约定与扩展步骤 | [development](development/README.md) |
| 架构与界面基础 | 生命周期、源码目录、路由、状态和共享交互 | [architecture](architecture/README.md) |
| 业务功能 | 书架、发现、书籍、阅读、社区、文件、设置与笔记 | [features](features/README.md) |
| 数据与恢复 | 状态归属、文件布局、兼容、备份和恢复 | [data](data/README.md) |
| 网络与认证 | 登录、会话隔离、API、缓存和云端同步 | [network](network/README.md) |
| 质量验证 | 回归测试、性能、Profile 和安全隐私边界 | [quality](quality/README.md) |
| 维护与排障 | 发布、故障定位及文档维护规则 | [maintenance](maintenance/README.md) |

每个分类目录有独立索引；专题页顶部可以返回总目录。文档源码链接相对于所在文件解析，文中的构建与测试命令均在**仓库根目录**执行。

## 推荐阅读路径

首次开发：

1. [环境搭建与构建](development/getting-started.md)：准备 JDK、SDK 和本地构建入口。
2. [源码目录导航](architecture/source-layout.md) → [架构与状态流](architecture/architecture.md)：找到模块和数据所有者。
3. [日常开发与扩展](development/development.md)：确定页面、字段或接口的修改范围。
4. 按下表选择业务专题，再按 [测试与验收](quality/testing.md) 选择验证范围。

定位功能：

| 我要了解或修改 | 主要文档 | 关联边界 |
| --- | --- | --- |
| 收藏、文件夹、分卷、历史、更新提醒 | [书架与阅读资料管理](features/library.md) | [同步](network/network-and-sync.md)、[存储](data/data-and-storage.md) |
| 搜索、书源、筛选、标签和排行榜 | [发现与搜索](features/discovery.md) | [界面状态](architecture/ui-and-navigation.md) |
| 目录、继续阅读、更新摘要、编辑与术语 | [书籍详情与文库](features/book-details.md) | [阅读器](features/reader.md)、[认证](network/authentication.md) |
| 双语、分页、定位、搜索、插图与朗读 | [阅读器开发](features/reader.md) | [设置与笔记](features/settings-and-notes.md) |
| 帖子、评论、Markdown 和草稿 | [社区与内容编辑](features/community.md) | [导航与渲染](architecture/ui-and-navigation.md) |
| 本地文件、下载、导出和文本工具 | [文件与下载](features/files-and-downloads.md) | [资料恢复](data/backup-and-recovery.md) |
| 外观、电子纸、屏蔽、缓存和笔记 | [设置、笔记与个人数据](features/settings-and-notes.md) | [数据范围](data/data-and-storage.md) |
| 登录、退出、401 或切换账号 | [账号与登录](network/authentication.md) | [网络与同步](network/network-and-sync.md) |
| 换机、备份预览、恢复冲突或损坏书库 | [备份与恢复流程](data/backup-and-recovery.md) | [文件存储](data/data-and-storage.md) |
| 编译、安装、回归或性能问题 | [排障](maintenance/troubleshooting.md)、[测试](quality/testing.md) | [性能测量](quality/performance.md) |

## 经常需要区分的概念

- **本地书架与云端收藏**分别存储；退出登录不会删除本机书籍、笔记和阅读进度。
- **云端读到的章节与本机精确位置**精度不同；列表百分比是展示估计，不是可跨布局复用的阅读锚点。
- **章节缓存、下载文件与导入文档**具有不同生命周期；清缓存不能等同于删除用户文件。
- **普通设置 JSON 与阅读资料 ZIP**覆盖不同数据；换机迁移应阅读备份专题。
- **内存更新与磁盘提交**不是同一个时刻；依赖持久状态的后台任务和恢复提交有显式边界。
- **客户端按钮权限与原站授权**不是同一层判断；界面显示可操作仍需正确处理服务端拒绝。

## 协作与维护入口

[贡献指南](../CONTRIBUTING.md)规定代码协作；[安全政策](../SECURITY.md)规定漏洞报告；[手动发布指南](../RELEASING.md)规定正式签名和附件流程；[来源与素材声明](../NOTICE.md)及[许可证目录](../licenses/README.md)记录授权边界。

当前没有 CI，检查需手动执行并记录。贴纸公开分发授权仍待确认，具体发行前待办见 [发布与维护](maintenance/release-and-maintenance.md)。新增或迁移文档时遵循 [文档维护规则](maintenance/documentation.md)，同步维护目录索引、引用和行为说明。
