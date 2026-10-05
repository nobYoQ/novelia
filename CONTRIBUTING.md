# 参与贡献

客户端缺陷和建议提交到 [Issues](https://github.com/nobYoQ/novelia/issues)。较大的功能或架构调整先讨论范围；原站内容、账号和服务端权限问题联系原站。漏洞按 [SECURITY.md](SECURITY.md) 私密报告。

## 开始开发

从[构建指南](docs/development/getting-started.md)准备环境：JDK、Android SDK，以及 ECH 构建所需的固定 Go/NDK。使用仓库 Wrapper，Windows 命令使用 PowerShell 7。普通开发不需要发布证书或原站账号。

```powershell
./build-debug.ps1 -Verify
```

这会构建 Debug、运行 JVM 单元测试和 Lint。Release、设备、论坛/ECH 联调及性能检查按[测试指南](docs/quality/testing.md)选择。仓库没有 CI，提交者记录实际执行结果，维护者按改动复验。

## 实现约定

- 从 Fork 建立功能分支，一个 PR 聚焦一个问题；普通贡献不自行升版。
- 按[源码导航](docs/architecture/source-layout.md)放文件，保持 package 与目录一致，使用 UTF-8 无 BOM 和现有 Kotlin 风格。
- 涉及状态、账号、备份、解析或阅读位置的改动，增加能复现问题的回归用例。
- UI 改动检查电子纸、减少动效、窄/宽屏和大字号；使用项目共享组件。
- 移动 Worker 保留旧任务类名兼容，移动源码检查 Profile 描述符。
- 更新相关功能文档；新增偏好或 API 的检查范围见[开发流程](docs/development/development.md)。

设备测试使用专用模拟器或设备，避免真实账号与阅读资料。联网用例默认关闭，真实发帖、上传、删除不属于普通自动回归。

## 提交 PR

说明具体问题、改后行为、执行的命令和结果，以及未验证范围。UI 附必要截图，性能改动附同条件前后数据，数据改动说明旧版本兼容。模板见 [.github/pull_request_template.md](.github/pull_request_template.md)。

用户可见变化由维护者发行时汇总到 GitHub Release 正文，仓库不维护独立 CHANGELOG。文档写法见[维护规则](docs/maintenance/documentation.md)，不要把历史测试数字当成当前状态。

不提交 APK、缓存、签名材料、令牌、真实备份或无权提供的小说/图片。依赖更新需复核生成的开源声明；来源及授权记录在 [NOTICE.md](NOTICE.md) 与[许可证目录](licenses/README.md)。原创代码和文档按 GPL-3.0-only 贡献，无需转让版权；第三方素材保留各自授权要求。

交流遵循[行为准则](CODE_OF_CONDUCT.md)。
