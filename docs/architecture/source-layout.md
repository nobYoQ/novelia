# 源码导航

[架构目录](README.md) · [文档首页](../README.md)

先按功能找页面，再沿控制器进入数据层。下列 Kotlin 路径均相对于 `app/src/main/java/cc/novelia/app/`；包名与目录一致。

## 工程地图

```text
app/                         Android 应用
  src/main/java/.../app/
    NoveliaApplication.kt    应用服务与后台调度
    MainActivity.kt          导航、主题与外部 Intent
    launcher/                桌面图标和启动入口
    startup/                 启动加载和启动界面
    ui/                      Compose 页面和共享界面组件
    data/                    模型、存储、网络、同步
    reader/                  正文投影、锚点、分页、搜索、TTS
    files/                   导入、下载、导出、EPUB 与文本工具
  src/test/                  JVM 测试
  src/androidTest/           Android 设备测试
benchmark/                   Macrobenchmark 与 Profile 采集
native/ech/                  Go ECH 传输及测试
gradle/                      工具链、原生构建和许可证生成配置
scripts/                     PowerShell 构建与发行工具
docs/                        架构、开发、功能、质量和维护文档
.github/                     工作流、协作指南与安全政策
build.ps1                    根目录统一 Gradle 任务入口
```

源码包不是独立 Gradle 模块。`benchmark` 用来采集性能数据，不是应用运行时库。构建入口和产物目录见[构建指南](../development/getting-started.md)。

根目录保留工程配置、README、许可证、版本号和 `build.ps1`。Debug/Release 快捷打包分别使用 `scripts/build-debug.ps1`、`scripts/build-release.ps1`；协作政策在 `.github/`，正式发布操作在 `docs/maintenance/releasing.md`。

本地原始素材放 `.local/assets/`，工作笔记放 `.local/notes/`；外部参考仓库继续放 `.reference/`。这些目录均被 Git 忽略，不作为构建输入。安装包、日志和验证记录分别放 `outputs/packages/`、`outputs/logs/`、`outputs/qa/<任务>/`，历史材料放 `outputs/archive/`。2026-10-11 整理前平铺在 outputs 根部的材料保存在 `outputs/archive/layout-20261011/`。

`.gradle/`、`.gradle-home/`、`.kotlin/`、`build/` 等工具目录按既有约定保留，可在编辑器中排除显示。`.android/` 包含固定测试签名，不能作为普通缓存删除。

## 按界面找代码

| 目录 | 主要入口与用途 |
| --- | --- |
| [ui/shelf](../../app/src/main/java/cc/novelia/app/ui/shelf) | `AdaptiveLibrary`、`ShelfScreen`、`CloudShelf`、`HistoryScreen`：书架、收藏、历史和分卷 |
| [ui/discover](../../app/src/main/java/cc/novelia/app/ui/discover) | `DiscoverScreen`、`RankScreen`、`SearchAssistantPanel`、`KeywordLibraryScreen` |
| [ui/book](../../app/src/main/java/cc/novelia/app/ui/book) | `BookScreen`、`WenkuEditor`、`GlossaryScreen`：详情和资料维护 |
| [ui/reader](../../app/src/main/java/cc/novelia/app/ui/reader) | `ReaderScreen`、`ReaderPreferences`、`EInkPage`：阅读界面与布局 |
| [ui/community](../../app/src/main/java/cc/novelia/app/ui/community) | 社区列表、文章编辑、论坛回复、守则和处罚记录 |
| [ui/account](../../app/src/main/java/cc/novelia/app/ui/account) | 个人页、原站/论坛登录及镜像表单 |
| [ui/settings](../../app/src/main/java/cc/novelia/app/ui/settings) | 设置、恢复、原站同步、WebDAV、网络诊断 |
| [ui/notes](../../app/src/main/java/cc/novelia/app/ui/notes) | 书签与笔记 |
| [ui/downloads](../../app/src/main/java/cc/novelia/app/ui/downloads)、[ui/tools](../../app/src/main/java/cc/novelia/app/ui/tools) | 下载管理与文件工具 |
| [ui/navigation](../../app/src/main/java/cc/novelia/app/ui/navigation) | 控制器、根标签切换、登录续接 |
| [ui/components/base](../../app/src/main/java/cc/novelia/app/ui/components/base)、[ui/theme](../../app/src/main/java/cc/novelia/app/ui/theme) | 通用控件、容器、弹层、主题和动效 |
| [ui/components/book](../../app/src/main/java/cc/novelia/app/ui/components/book)、[ui/components/keywords](../../app/src/main/java/cc/novelia/app/ui/components/keywords) | 跨页面复用的书籍展示、同步状态和关键词编辑 |
| [ui/components/documents](../../app/src/main/java/cc/novelia/app/ui/components/documents)、[ui/components/media](../../app/src/main/java/cc/novelia/app/ui/components/media) | 系统文档交互、导入结果、插图与文字展示 |
| [ui/markdown](../../app/src/main/java/cc/novelia/app/ui/markdown)、[ui/web](../../app/src/main/java/cc/novelia/app/ui/web) | Markdown 编辑/渲染与网页兜底 |
| [ui/about](../../app/src/main/java/cc/novelia/app/ui/about)、[ui/feedback](../../app/src/main/java/cc/novelia/app/ui/feedback) | 关于、许可证、贴纸与操作反馈 |

## 按数据职责找代码

| 目录 | 负责什么 |
| --- | --- |
| [data/model](../../app/src/main/java/cc/novelia/app/data/model) | 跨功能使用的 `BookRef`、`LibraryState`、阅读设置及小说/文库 DTO |
| [data/community](../../app/src/main/java/cc/novelia/app/data/community) | 原站评论、论坛 API、社区模型、规则、链接与回复缓存 |
| [data/storage](../../app/src/main/java/cc/novelia/app/data/storage) | `LocalStore`、异步写盘、JSON 编解码、损坏恢复 |
| [data/documents](../../app/src/main/java/cc/novelia/app/data/documents)、[data/backup](../../app/src/main/java/cc/novelia/app/data/backup) | 本地正文分块、来源哈希索引、ZIP 备份与合并恢复 |
| [data/auth](../../app/src/main/java/cc/novelia/app/data/auth) | 令牌加密、认证流程、会话和来源绑定 |
| [data/network](../../app/src/main/java/cc/novelia/app/data/network) | 小说 API、书源线路、HTTP/ECH 传输、请求合并和诊断 |
| [data/cache](../../app/src/main/java/cc/novelia/app/data/cache)、[data/chapters](../../app/src/main/java/cc/novelia/app/data/chapters) | 有界缓存、章节请求、离线批次、译文新鲜度 |
| [data/library](../../app/src/main/java/cc/novelia/app/data/library) | 收藏展示、分卷关系、阅读位置/历史及续读规则 |
| [data/sync](../../app/src/main/java/cc/novelia/app/data/sync) | 原站云端写入队列和后台重放 |
| [data/webdav](../../app/src/main/java/cc/novelia/app/data/webdav) | WebDAV 协议、资料投影、冲突合并和调度 |
| [data/catalog](../../app/src/main/java/cc/novelia/app/data/catalog) | 书源标识、链接、搜索表达式、关键词词典 |
| [data/updates](../../app/src/main/java/cc/novelia/app/data/updates) | 书籍更新检查、更新状态和系统通知 |
| [data/appupdate](../../app/src/main/java/cc/novelia/app/data/appupdate) | 应用发行信息、版本检查、APK 下载和安装协调 |
| [data/markdown](../../app/src/main/java/cc/novelia/app/data/markdown) | 不依赖 UI 的链接及编辑模板规则 |

`data/storage/StorageFormat.kt` 提供共用的 `appJson` 和 `hashName`。新的持久化代码应复用它们，避免相同资料出现多套序列化规则。

## 文件处理

| 目录 | 负责什么 |
| --- | --- |
| [files/importing](../../app/src/main/java/cc/novelia/app/files/importing) | URI 有界读取、导入去重、下载后导入和批次状态 |
| [files/exporting](../../app/src/main/java/cc/novelia/app/files/exporting) | 原文件/文本导出、下载归档和待分享文件 |
| [files/epub](../../app/src/main/java/cc/novelia/app/files/epub) | EPUB 解析、空白处理、压缩和正文恢复 |
| [files/downloads](../../app/src/main/java/cc/novelia/app/files/downloads) | 下载文件管理、下载与书架的关联操作 |

`files/DocumentTools.kt` 保留通用文本与格式处理入口；`files/DownloadWorker.kt` 保留现有 WorkManager 类名。`ui/components/documents/` 只协调页面与系统文档选择器，实际读取在 `files/importing/DocumentAccess.kt`。

## 三处容易找错

**阅读器有两个目录。** `ui/reader/` 管 Compose、布局和页面生命周期；顶层 `reader/` 管投影、锚点、分页算法、搜索和朗读。修改文字算法优先从后者入手。

**下载页面不执行下载。** `ui/downloads/` 收集选项、展示任务；`files/DownloadWorker.kt` 管任务和文件提交；`data/network/DownloadTransport.kt` 管独立传输调度。

**旧 Worker 类名是兼容入口。** `data/CloudSyncWorker.kt` 和 `data/UpdateWorker.kt` 保留旧安装已入队任务的反射类名。真实逻辑在 `data/sync/`、`data/updates/`。移动后台类时检查 [LegacyWorkerCompatibilityTest](../../app/src/test/java/cc/novelia/app/data/compat/LegacyWorkerCompatibilityTest.kt)，不能只更新 imports。

## 新文件与测试放置

页面和专用组件放所属功能包；通用控件放 `ui/components/base/`，共享业务组件放 `ui/components/` 对应功能子包。模型、I/O 和可独立测试的规则分别靠近对应数据职责。文件名可用 `XxxScreen.kt` 表达独立页面，无需为了每个私有 Composable 拆文件。

JVM 测试在 [app/src/test](../../app/src/test)，按 `data/`、`files/`、`reader/`、`ui/`、`launcher/`、`startup/` 跟随被测职责组织，根包不再平铺测试。设备测试在 [app/src/androidTest](../../app/src/androidTest)，按 `ui/`、`data/`、`integration/`、`performance/` 组织。运行时用文件里的完整 package 和类名，见[测试指南](../quality/testing.md)。

移动源码时还要检查导航、Manifest、旧数据引用、Worker 名称和 [Baseline Profile](../quality/baseline-profiles.md) 描述符。移动文档时更新相对链接。
