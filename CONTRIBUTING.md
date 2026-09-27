# 参与贡献

客户端问题请提交到 [Issues](https://github.com/nobYoQ/novelia/issues)；原站内容、账号与服务端权限问题应联系原站。较大的功能或架构调整请先开 Issue 讨论范围。

首次参与请从 [项目文档](docs/README.md) 开始；架构、业务功能、扩展步骤和测试选择均在 `docs/` 按职责分类维护。新增或移动说明时遵循 [文档分类与维护规则](docs/maintenance/documentation.md)，同步更新分类索引和相对链接。

## 开发环境

- JDK 17（推荐的协作基线）、Android SDK Platform 36、Build Tools 35.0.0。
- 使用仓库的 Gradle Wrapper；不要升级本地全局 Gradle 来解决项目问题。
- 在 Android Studio 安装 SDK；脚本可自动查找并同步本机 `local.properties` 的 `sdk.dir`。不要提交该文件。
- Windows 使用 PowerShell 7；`build.ps1` 自动查找 JDK/SDK，检测失败时填写其顶部“手动环境配置区”，或设置环境变量。优先级与检查命令见 [环境搭建](docs/development/getting-started.md#自动检测与手动配置)。

Windows 检查命令：

```powershell
./build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
```

Linux / macOS 检查命令（也适用于没有保留执行位的 ZIP 源码）：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

常规构建不需要发布证书或原站账号。当前暂未配置 CI，提交者运行检查并在 PR 中写明结果；维护者合并前复验，不能把未运行的测试标为通过。

## 修改与测试

- 外部贡献者从 Fork 创建功能分支，通过 PR 合入 `main`；一个 PR 聚焦一个问题。
- Kotlin 遵循现有风格与 `.editorconfig`，UTF-8 无 BOM；避免无关的全文件重排。
- 源码按 [目录导航与归档规则](docs/architecture/source-layout.md) 放入所属功能包；独立页面使用独立文件，通用组件进入 `ui/components/`，数据层按职责归档，测试与对应包保持一致。同步维护 package、引用、测试类名筛选和文档链接。
- 移动 Worker 时保留已排队任务的旧类名兼容，移动或拆分源码时检查性能 Profile 中的描述符；不能只修改 imports 就认为升级与性能采集产物也已同步。
- 数据存储、备份、账号隔离、文件解析和阅读进度变更应补充有意义的回归测试。
- UI 变更提供复现步骤和截图，说明普通模式、电子纸、减少动效及大屏布局的检查情况。
- JVM 报告：`app/build/reports/tests/testDebugUnitTest/index.html`；Lint 报告：`app/build/reports/lint-results-debug.html`。
- 设备测试使用 `:app:connectedDebugAndroidTest`，只在专用测试设备或模拟器运行；不要使用存有真实账号与阅读数据的主力设备。
- 联网站点测试默认跳过。仅在明确需要时选择对应测试类，传入 `live=true` 或 `liveSite=true`；不要把生产站点请求设为普通贡献检查的必经步骤。
- 性能测量参见 [benchmark/README.md](benchmark/README.md)，不根据单次模拟器数据宣称性能提升。

## 提交 PR

说明问题、最终行为、验证结果以及未验证的范围，关联 Issue。用户可见变更写入 [CHANGELOG.md](CHANGELOG.md) 的“未发布”部分；普通贡献不自行升版，由维护者发布时统一修改 `version.properties`。

依赖更新应说明用途、版本与许可，复核生成的开源声明。不要提交 APK、构建缓存、密钥、账号会话、真实用户备份或未经授权的小说/图片。

贡献的原创代码和文档按本项目 GPL-3.0-only 许可提供；只提交你有权提供的内容。外部代码注明来源和许可证，保留版权声明。贴纸不在项目 GPL 授权范围内，见 [NOTICE.md](NOTICE.md)。无需转让版权。

交流遵循 [行为准则](CODE_OF_CONDUCT.md)，安全漏洞按 [安全政策](SECURITY.md) 私密报告。
