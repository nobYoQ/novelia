# Novelia Android

面向 [轻小说机翻机器人](https://n.novelia.cc/) 的非官方原生安卓客户端，当前版本 `0.1.4`。使用 Kotlin、Jetpack Compose 与 Material 3，支持 Android 8.0（API 26）及以上。

## 0.1.4 更新

- 适配原站剧透、评分和折叠语法，支持正文图片长按放大。
- 帖子和评论新增 Markdown 快捷工具栏，改善键盘遮挡与正文滚动，移除实时预览。
- 修复链接打开、WebView 定位和逐级返回，新增原生帖内目录跳转。

交互和验证说明见 [帖子与评论 Markdown](docs/markdown-editing.md)。

## 使用

安装测试 APK 后，可直接以游客身份发现作品、阅读已有章节、导入本地文件和浏览社区。底部四个入口为「书架、发现、社区、我的」。

- **书架**：本地收藏、云端收藏、EPUB/TXT/SRT 文件、收藏夹、阅读历史、置顶、批量整理和更新检查。
- **发现**：六类书源、文库、组合筛选、高级搜索表达式、保存搜索、排行榜、书源链接识别和系统分享接收。
- **阅读器**：中/日/中日/日中、Sakura/GPT/有道已有译文及回退、字号行距、纸张/深色主题、简繁显示、插图、书签笔记、缓存和系统朗读。
- **文库**：分卷、符合条件的已有译文下载；有权限的用户可新建/编辑条目、维护出版卷目、上传 EPUB/TXT 和维护术语。
- **社区**：三类论坛、Markdown 正文、评论与楼中楼、文章收藏、草稿、发布与编辑、用户屏蔽。
- **我的**：下载、笔记、普通设置备份、屏蔽、个人术语表、文件工具及帮助。

本版本不包含翻译中心、译文生成、翻译队列或翻译服务密钥配置。

首次登录使用原站统一认证页面，可登录、注册和找回密码。如果认证完成后未自动返回，点击右上角「完成登录」。账号角色和注册时间要求沿用原站，最终由服务器校验。

长按阅读器段落可选择文字、分享或添加笔记。点击正文可收起/展开工具栏。下载完成后，可在下载管理中「导入阅读」、「打开」或「导出文件」。本地源文件会保留一份独立副本，可从书架管理菜单导出。

「我的收藏」中的文库小说支持挂载本地分卷：点击「挂载分卷」，或在管理菜单中选择「管理挂载分卷」，勾选已下载导入的文件。挂载后可在文库下方折叠／展开分卷并直接阅读，展开状态会保留；展开、收起和列表移动动画遵循「减少动态效果」设置。分卷管理菜单可更换所属文库或取消挂载，文件及阅读进度会保留；「本地文件」仍可查看所有分卷。搜索分卷名会显示所属文库和匹配分卷。新下载任务会记录来源，导入时自动挂载到已收藏的对应文库；旧任务及外部导入文件可手动挂载。

展开文库后，可拖动分卷左侧的排序手柄调整顺序，拖到列表边缘会自动滚动；点击手柄也可选择上移或下移。顺序按文库保存，重启后保留，新挂载的分卷追加到手动顺序末尾。搜索和批量整理期间隐藏排序手柄，清空搜索或退出整理后即可排序。

在「我的 → 阅读与外观」开启「电子纸阅读模式」后，全应用的列表、设置表单和面板改为按屏翻动：滑动过程中保持画面静止，松手后跳转一屏，也可点击底部「上一屏／下一屏」。横向筛选按屏切换，取消滚动惯性及边缘拉伸；面板直接打开，分卷排序显示上下按钮，阅读数值设置显示加减按钮，插图提供分步移动，内置原站网页也使用按屏滚动。关闭模式恢复普通交互，原有主题和手动排序保留；单书电子纸设置只影响当前阅读器。

长按阅读器插图可全屏查看，支持双指缩放、拖动和双击还原。缺少封面的作品会显示贴纸默认封面；「我的」页贴纸可点击互动，下载、导入等状态也有对应的轻量反馈。详见 [贴纸与插图交互说明](docs/sticker-interactions.md)。

阅读设置提供「连续滚动」与「自动分页」两种分页模式；自动分页下可分别设置上下、左右翻页手势。「显示翻页按钮」适用于两种模式，分别显示上一屏／下一屏或上一页／下一页。「电子纸阅读模式」保留主题并关闭过渡，首次开启使用自动分页，关闭恢复开启前的翻页设置，再次开启沿用上次电子纸设置；「阅读主题」包含互斥的「黑白」选项。支持音量键、方向键及 Page Up / Page Down。云端收藏新增多收藏夹下的「全部收藏」及原站完整筛选；筛选区向下浏览时平滑收起，点击摘要展开，动画受减少动态效果控制，自动收起开关位于「我的 → 阅读与外观」。详见 [电子纸与云端收藏](docs/eink-and-cloud-favorites.md)。

## 构建

所需环境：JDK 17、Android SDK Platform 36、Build Tools 35.0.0，首次构建需要连接 Google Maven、Maven Central 和 Gradle 分发服务。

用 Android Studio 打开本目录，或使用 PowerShell 7：

```powershell
./build.ps1
./build.ps1 -Tasks @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
./build.ps1 -Tasks @('assembleRelease', 'testReleaseUnitTest', 'lintRelease')
```

`build.ps1` 优先使用已配置的 JDK 或 Android Studio JBR，将 Gradle 缓存放入本项目 `.gradle-home`。在 `local.properties` 中配置 Android SDK，例如 `sdk.dir=D\:/Android/sdk`。该文件不应提交。

标准 Gradle Wrapper 也已提供；可在配置好 JDK/SDK 的环境中使用 `gradlew`。当前固定 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21、Compose BOM 2025.12.00。

输出位置：

- 首版交付包：`releases/Novelia-0.1.0-debug.apk`，同目录提供 SHA-256 校验文件。
- 本地自用 Release：`releases/Novelia-0.1.5-release.apk`，启用 R8 优化与混淆，使用与 Debug 包相同的本地测试证书签名，可直接安装。
- 未签名 Release：`releases/Novelia-0.1.5-release-unsigned.apk`；同目录保留校验文件和混淆映射。
- 应用：`app/build/outputs/apk/debug/app-debug.apk`
- 设备测试包：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- JVM 测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- 静态检查：`app/build/reports/lint-results-debug.html`

`releases/`、构建输出、APK/AAB 安装包、签名旁文件及安装包校验文件仅保留在本地，由 `.gitignore` 排除，不纳入源码提交。首次克隆仓库需自行构建；分发安装包时应使用独立的发布附件。

Debug APK 使用**开发测试签名**。正式商店分发前需配置自己的发布签名、应用标识/品牌授权与域名 App Links 验证；本仓库没有发布私钥。

Gradle 的 Release 构建当前未配置签名，原始输出 `app/build/outputs/apk/release/app-release-unsigned.apk` 不能直接安装。本地交付目录中的 `Novelia-0.1.5-release.apk` 已单独使用本地测试证书签名，可用于本地自用并覆盖同证书的旧版本安装，保留应用数据；正式发布时应改用自己的发布证书。混淆映射为 `app/build/outputs/mapping/release/mapping.txt`。

## 验证

JVM 测试覆盖书源链接、查询参数、序列化、译文对齐与回退、EPUB 阅读顺序/插图/路径防护、文本编码、SRT 时间轴及 OCR 整理。

设备测试可使用 Android Studio，或在安装两个 APK 后运行：

```powershell
adb shell am instrument -w -r -e class cc.novelia.app.AppFlowTest cc.novelia.app.test/androidx.test.runner.AndroidJUnitRunner
```

外部站点测试默认跳过。明确需要只读联调时添加 `-e live true`，选择 `cc.novelia.app.LiveReadOnlyTest`、`cc.novelia.app.DownloadLiveTest` 或 `cc.novelia.app.AuthPageTest`。它们读取公开接口、已有内容文件与认证表单，不填写凭据、发布帖子、评论或修改云端收藏。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `data` | API 合约、会话、安全存储、本地状态、链接、搜索、更新检查 |
| `ui` | Material 3 主题、导航和各模块页面 |
| `reader` | 段落投影、译文回退、前台 TTS 服务 |
| `files` | EPUB/TXT/SRT、图片压缩、后台下载 |
| `src/test` | 无账号的 JVM 测试 |
| `src/androidTest` | 模拟器流程与可选真实站点测试 |

轻量状态使用原子 JSON 文件持久化；章节、书籍元数据、原始文件、插图、下载分别存放。账号访问令牌使用 Android Keystore 加密；密码在原站认证页中处理。普通设置导出不包含访问令牌、认证 Cookie 或待同步操作。

云端写入失败时，收藏/阅读历史等幂等操作可以进入账号隔离的待同步列表；帖子和评论不会自动重发。云端和本机章节进度不同会提供选择。退出登录不会删除本地文件。

## 已知边界

- 原站未提供稳定的第三方接口契约。接口依据公开源码核对，页面权限由服务器执行。
- **登录后的真实账号写入尚未进行生产端到端验收**；没有在原站创建测试账号、帖子、评论或文件。登录、收藏、编辑与上传页面已接入接口，但仍需使用你自己的账号验证。
- 原站扩展负责的书源抓取/验证码处理、日亚一键导入仍应在原站完成。App 支持已收录作品、手动文库资料维护和明确的错误反馈。
- 本地 EPUB 使用原生流式正文与插图展示，不复现所有出版商 CSS、固定版式或脚本。EPUB 转 TXT 会有意省略插图。
- 下载暂停后重新下载；不宣称服务器支持断点续传。大型任务可能受 Android 后台调度限制，可在下载列表重试。
- 书架检查约每六小时执行，受网络、电量和系统调度影响；不是服务器实时推送。
- 朗读依赖系统提供的中文/日文语音包；缺失时会提示。拒绝通知权限不影响阅读和文件保存。
- 原站短篇/长篇榜单抓取可能返回空列表。此状态与网络失败分别展示。
- 管理员控制台、翻译中心及相关生成流程不在此版本内。

原始规划见 [android-app-plan.md](docs/android-app-plan.md)，版本验收说明见 [v0.1-verification.md](docs/v0.1-verification.md)。

本轮性能、缓存与动效改进及缓存预算见 [performance-optimization.md](docs/performance-optimization.md)。
