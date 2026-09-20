# Novelia Android

面向 [轻小说机翻机器人](https://n.novelia.cc/) 的非官方原生安卓客户端，当前版本 `0.1.8`。使用 Kotlin、Jetpack Compose 与 Material 3，支持 Android 8.0（API 26）及以上。

## 下载与反馈

- [GitHub 发行版](https://github.com/nobYoQ/novelia/releases)：下载 APK，查看版本说明和 SHA-256 校验文件。若没有已发布版本，请按下文自行构建。
- [问题反馈与功能建议](https://github.com/nobYoQ/novelia/issues)：客户端问题请在本项目反馈；原站内容和账号问题请联系原站。
- [更新记录](CHANGELOG.md) · [贡献指南](CONTRIBUTING.md) · [安全政策](SECURITY.md) · [手动发布指南](RELEASING.md)
- [开发手册](docs/README.md)：环境搭建、架构、模块实现、测试、性能、发布与排障；[源码目录导航](docs/source-layout.md) 按界面、数据职责和测试类型定位代码。

当前正在准备首次 GitHub 发行，源码与文档已采用 GPL-3.0；贴纸公开分发授权仍待确认，详见 [来源与素材声明](NOTICE.md)。既有本地测试包与未来正式发布证书可能不同，升级前请先备份阅读资料并阅读发行说明。

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
./build.ps1
./build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
./build.ps1 -Tasks @(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')
```

`build.ps1` 优先使用 `JAVA_HOME`，未设置时依次尝试 Android Studio JBR 和 PATH；无效的 `JAVA_HOME` 会报错。Gradle 缓存放入本项目 `.gradle-home`。在 `local.properties` 中配置 Android SDK，例如 `sdk.dir=D\:/Android/sdk`。该文件不应提交。

标准 Gradle Wrapper 也已提供；可在配置好 JDK/SDK 的环境中使用 `gradlew`。当前固定 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21、Compose BOM 2025.12.00。

Linux / macOS（`sh` 调用也适用于没有执行位的源码 ZIP）：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。默认 Release 未签名，不能直接安装；正式签名、旧版迁移和发行附件准备见 [RELEASING.md](RELEASING.md)。版本号和版本码统一在 `version.properties` 维护。

`releases/`、构建输出、APK/AAB 安装包、签名旁文件及安装包校验文件由 `.gitignore` 排除，不纳入源码提交。正式安装包上传为 GitHub Release 附件。

## 验证

JVM 测试覆盖书源链接、查询参数、序列化、译文对齐与回退、EPUB 阅读顺序/插图/路径防护、文本编码、SRT 时间轴及文本换行整理。

设备测试可使用 Android Studio，或在安装两个 APK 后运行：

```powershell
adb shell am instrument -w -r -e class cc.novelia.app.ui.reader.AppFlowTest cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner
```

外部站点测试默认跳过。明确需要只读联调时添加 `-e live true`，选择 `cc.novelia.app.integration.LiveReadOnlyTest`、`cc.novelia.app.integration.DownloadLiveTest` 或 `cc.novelia.app.integration.AuthPageTest`。它们读取公开接口、已有内容文件与认证表单，不填写凭据、发布帖子、评论或修改云端收藏。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `data` | 模型、网络、会话、存储、同步等 13 个职责包，根目录保留旧后台任务兼容入口 |
| `ui` | 按界面及共享职责划分的 17 个子包，详见 [源码目录导航](docs/source-layout.md) |
| `reader` | 段落投影、译文回退、前台 TTS 服务 |
| `files` | EPUB/TXT/SRT、图片压缩、后台下载 |
| `src/test` | 无账号的 JVM 测试；数据层测试按对应职责分包 |
| `src/androidTest` | 按界面、备份、站点联调和性能划分的 15 个测试目录 |

界面代码位于 `app/src/main/java/cc/novelia/app/ui/`：书架在 `shelf/`，发现在 `discover/`，书籍详情在 `book/`，阅读器在 `reader/`，社区在 `community/`，账号与设置分别在 `account/`、`settings/`。页面专用组件和展示逻辑与所属界面放在一起，跨界面组件、导航、主题、Markdown 与反馈各有独立目录。

数据层位于同级 `data/`：领域模型在 `model/`，API 在 `network/`，本地持久化在 `storage/`，云端写入在 `sync/`，更新检查在 `updates/`。设备测试的 package 与所属目录一致，例如阅读流程测试为 `cc.novelia.app.ui.reader.AppFlowTest`；详见 [测试指南](docs/testing.md)。

轻量状态使用原子 JSON 文件持久化；章节、书籍元数据、原始文件、插图、下载分别存放。账号访问令牌使用 Android Keystore 加密；密码在原站认证页中处理。普通设置导出不包含访问令牌、认证 Cookie 或待同步操作。

云端写入失败时，收藏/阅读历史等幂等操作可以进入账号隔离的待同步列表；帖子和评论不会自动重发。云端和本机章节进度不同会提供选择。退出登录不会删除本地文件。

## 许可证与范围

原创代码和文档采用 [GPL-3.0-only](LICENSE)。第三方依赖和素材保留各自权利，见 [NOTICE.md](NOTICE.md) 与 [许可证来源](licenses/README.md)。完整许可证随 APK 提供，可在「我的 → 帮助与关于 → 开源许可证」离线查看。

客户端不提供小说内容的再授权，也不是原站官方客户端。原站 API 可能变化；真实账号写入的生产端到端验收仍需在明确授权下进行。当前未配置 CI，贡献与发行检查按文档手动执行。
