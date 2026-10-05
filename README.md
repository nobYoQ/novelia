# Novelia Android

面向 [轻小说机翻机器人](https://n.novelia.cc/) 的非官方原生安卓客户端，当前源码配置版本为 [0.2.3-ech.5](version.properties)。使用 Kotlin、Jetpack Compose 与 Material 3，支持 Android 8.0（API 26）及以上。

当前版本包含实验性 ECH 网络传输，并保留后续独立论坛接入所需的域名支持与匿名 API 诊断。开关位于「我的 → 设置 → ECH 连接测试」；适配范围、构建要求与后续接入说明见 [ECH 连接测试版](docs/network/ech-test.md)。

当前 `codex/forum-api-preview` 分支适配独立论坛测试站，支持新版帖子、评论和独立论坛登录。接口依据与验证方式见 [独立论坛 API 预适配](docs/network/forum-api-preview.md)。

## 下载与反馈

- [GitHub 发行版](https://github.com/nobYoQ/novelia/releases)：下载 APK，查看版本说明和 SHA-256 校验文件。若没有已发布版本，请按下文自行构建。
- [问题反馈与功能建议](https://github.com/nobYoQ/novelia/issues)：客户端问题请在本项目反馈；原站内容和账号问题请联系原站。
- [发行说明](https://github.com/nobYoQ/novelia/releases) · [贡献指南](CONTRIBUTING.md) · [安全政策](SECURITY.md) · [手动发布指南](RELEASING.md)
- [项目文档](docs/README.md)：按开发、架构、业务功能、数据、网络、质量和维护分类；[业务功能索引](docs/features/README.md) 按操作查流程，[源码目录导航](docs/architecture/source-layout.md) 按界面、数据职责和测试类型定位代码。

源码与文档采用 GPL-3.0-only，第三方依赖和素材保留各自的许可与权利，详见 [来源与素材声明](NOTICE.md)。公开版本以 GitHub Releases 为准；本地测试包与正式发行包的证书可能不同，升级前请先备份阅读资料并阅读发行说明。

## 使用

安装 APK 后，可直接以游客身份发现作品、阅读已有章节、导入本地文件和浏览社区。底部四个入口为「书架、发现、社区、我的」。

- **书架**：本地收藏、云端收藏、EPUB/TXT/SRT 文件、收藏夹、阅读历史、置顶、批量整理和更新检查。
- **发现**：六类书源、文库、组合筛选、高级搜索表达式、保存搜索、排行榜、书源链接识别和系统分享接收。
- **阅读器**：中/日/中日/日中、Sakura/GPT/有道已有译文及回退、字号行距、纸张/深色主题、简繁显示、插图、书签笔记、缓存和系统朗读。
- **文库**：分卷、符合条件的已有译文下载；有权限的用户可新建/编辑条目、维护出版卷目、上传 EPUB/TXT 和维护术语。
- **社区**：三类论坛、Markdown 正文、评论与楼中楼、文章收藏、草稿、发布与编辑、用户屏蔽。
- **我的**：下载、笔记、普通设置备份、屏蔽、个人术语表、文件工具及帮助。

本版本不包含翻译中心、译文生成、翻译队列或翻译服务密钥配置。

首次登录使用原站统一认证页面，可登录、注册和找回密码。如果认证完成后未自动返回，点击右上角「完成登录」。账号角色和注册时间要求沿用原站，最终由服务器校验。


## 构建

所需环境：JDK 17（推荐协作基线）、Android SDK Platform 36、Build Tools 35.0.0，首次构建需要连接 Google Maven、Maven Central 和 Gradle 分发服务。

用 Android Studio 打开本目录，或使用 PowerShell 7：

```powershell
./build-debug.ps1
./build-release.ps1
```

这两个入口分别生成 Debug 包和使用测试证书签名的本地 Release 包，后者保留 R8 压缩与资源收缩。默认生成通用 APK；可加 `-Abi arm64-v8a` 选择设备架构，已有缓存时加 `-Offline`，需要同时运行对应单元测试和 Lint 时加 `-Verify`。例如：

```powershell
./build-release.ps1 -Abi arm64-v8a -Verify -Offline
```

APK 与 SHA-256 校验文件输出到 `outputs/packages/debug/` 或 `outputs/packages/release-local/`，Release 同时保留 R8 映射；日志在 `outputs/logs/`。这些入口允许未提交的本地改动，重复构建会覆盖同版本、模式和 ABI 的产物，不会自动安装或上传。完整参数与未签名构建见 [环境搭建与构建](docs/development/getting-started.md)。

构建入口复用 `build.ps1`，自动从环境变量、PATH、Android Studio 和常见安装目录查找 JDK，从 `local.properties`、环境变量及默认目录查找 Android SDK。换电脑后无须沿用原机器盘符；自动检测失败时，填写 `build.ps1` 顶部带中文注释的“手动环境配置区”。运行 `./build.ps1 -CheckEnvironment` 可只检查路径。构建时自动同步被 Git 忽略的 `local.properties`，保留其中其他配置；缓存默认使用项目 `.gradle-home/`、`.android/`，也可沿用对应环境变量或手动指定。完整优先级见 [环境配置说明](docs/development/getting-started.md#自动检测与手动配置)。

标准 Gradle Wrapper 也已提供；可在配置好 JDK/SDK 的环境中使用 `gradlew`。当前固定 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21、Compose BOM 2025.12.00。ECH 本地库已接入 Gradle 依赖图，需要 SDK 内的 NDK `28.2.13676358`；Windows x64 可自动引导校验过的 Go `1.27.1`，Linux/macOS 需预装该版本 Go（或设置 `NOVELIA_GO_HOME`）。详见 [ECH 构建说明](docs/network/ech-test.md#构建与测试)。

Linux / macOS（`sh` 调用也适用于没有执行位的源码 ZIP）：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

仍可用 `./build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')` 自选 Gradle 任务；不带参数的 `build.ps1` 保持执行 Debug 构建和单元测试。直接调用 Gradle 或通过 `build.ps1` 执行 `assembleRelease` 时默认未签名。正式 GitHub 发行使用独立的 [发布流程](RELEASING.md)，不要上传本地测试签名包。版本号和版本码统一在 `version.properties` 维护。

修改版本后打包：编辑根目录 `version.properties` 的 `versionName`（例如 `0.2.3`）和递增的 `versionCode`（例如由 `14` 改为 `15`），保存后运行 `./build-release.ps1` 或 `./build-debug.ps1`，APK 内版本和归档文件名会自动更新。具体示例见 [修改版本号并重新编译](docs/development/getting-started.md#修改版本号并重新编译)。

仓库根目录的 `outputs/` 是脚本产物的唯一出口，包含安装包、日志、正式附件及 ECH 工具链；Gradle 中间文件仍使用各模块的 `build/`。这些目录、APK/AAB 安装包、签名旁文件及安装包校验文件由 `.gitignore` 排除，不纳入源码提交。正式附件准备脚本写入 `outputs/releases/`，正式安装包上传为 GitHub Release 附件；旧 `artifacts/`、`releases/` 仅保留忽略规则，脚本不再写入。

## 验证

JVM 测试覆盖书源链接、查询参数、序列化、译文对齐与回退、EPUB 阅读顺序/插图/路径防护、文本编码、SRT 时间轴及文本换行整理。

设备测试可使用 Android Studio，或在安装两个 APK 后运行：

```powershell
adb shell am instrument -w -r -e class cc.novelia.app.ui.reader.AppFlowTest cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner
```

外部站点测试默认跳过。明确需要只读联调时添加 `-e live true`，选择 `cc.novelia.app.integration.LiveReadOnlyTest`、`cc.novelia.app.integration.DownloadLiveTest`、`cc.novelia.app.integration.AuthPageTest` 或 `cc.novelia.app.integration.ForumLinksLiveTest`。它们读取公开接口、已有内容文件、认证表单或帖子链接，不填写凭据、发布帖子、评论或修改云端收藏。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `data` | 模型、网络、会话、存储、同步等 14 个职责包，根目录保留旧后台任务兼容入口 |
| `ui` | 按界面及共享职责划分的 17 个子包，详见 [源码目录导航](docs/architecture/source-layout.md) |
| `reader` | 段落投影、译文回退、前台 TTS 服务 |
| `files` | EPUB/TXT/SRT、图片压缩、后台下载 |
| `src/test` | 无账号的 JVM 测试；数据层测试按对应职责分包 |
| `src/androidTest` | 按界面、导航、备份、站点联调和性能分类的设备测试 |

界面代码位于 `app/src/main/java/cc/novelia/app/ui/`：书架在 `shelf/`，发现在 `discover/`，书籍详情在 `book/`，阅读器在 `reader/`，社区在 `community/`，账号与设置分别在 `account/`、`settings/`。页面专用组件和展示逻辑与所属界面放在一起，跨界面组件、导航、主题、Markdown 与反馈各有独立目录。

数据层位于同级 `data/`：领域模型在 `model/`，API 在 `network/`，本地持久化在 `storage/`，云端写入在 `sync/`，更新检查在 `updates/`。设备测试的 package 与所属目录一致，例如阅读流程测试为 `cc.novelia.app.ui.reader.AppFlowTest`；详见 [测试指南](docs/quality/testing.md)。

轻量状态使用原子 JSON 文件持久化；章节、书籍元数据、原始文件、插图、下载分别存放。账号访问令牌使用 Android Keystore 加密；密码在原站认证页中处理。普通设置导出不包含访问令牌、认证 Cookie 或待同步操作。

云端写入失败时，收藏/阅读历史等幂等操作可以进入账号隔离的待同步列表；帖子和评论不会自动重发。云端和本机章节进度不同会提供选择。退出登录不会删除本地文件。

## 许可证与范围

原创代码和文档采用 [GPL-3.0-only](LICENSE)。第三方依赖和素材保留各自权利，见 [NOTICE.md](NOTICE.md) 与 [许可证来源](licenses/README.md)。完整许可证随 APK 提供，可在「我的 → 帮助与关于 → 开源许可证」离线查看。

客户端不提供小说内容的再授权，也不是原站官方客户端。原站 API 可能变化；真实账号写入的生产端到端验收仍需在明确授权下进行。当前未配置 CI，贡献与发行检查按文档手动执行。
