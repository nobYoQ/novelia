# 0.1.8 发布复验

2026-09-20 将版本号更新为 `0.1.8`、版本码更新为 `11`，交付此前已完成的阅读、交互、存储与动效优化，不包含 OCR。用户更新报告见 [0.1.8 更新记录](release-notes-0.1.8.md)。

## 验证

- 升版后执行 `assembleRelease`、`testReleaseUnitTest` 和 `lintRelease`，全部通过；246 项 Release 单元测试无失败、错误或跳过。
- Release Lint 为 0 错误、17 个警告。安装包启用 R8 和资源压缩，确认为非 debuggable。
- 73 项独立 Android 功能用例在升版前通过，覆盖手机、大屏与系统动画关闭场景；此次升版仅修改版本字段及文档，未重复宣称设备回归结果来自 0.1.8 包。
- 核验 APK 的包名 `cc.novelia.app`、版本号 `0.1.8`、版本码 `11`、ARM64 架构、签名和 16 KiB ZIP 对齐；确认携带最新采集的 Baseline Profile，不含 OCR 模型及识别引擎。

## 安装包

[Novelia-0.1.8-release-arm64.apk](../releases/Novelia-0.1.8-release-arm64.apk)，14,806,279 字节（约 14.81 MB）。与原 0.1.7 发布包使用相同的本地测试证书，可覆盖安装同签名旧版；历史 APK 保留。

SHA-256：`6938cf4829536f2996e5f0538ec67c61cb6df0958ac0543d27bb911ff792a3e5`。

APK、校验文件及对应混淆映射位于 `releases/`，仅保留在本地，不纳入源码提交。构建日志为 `artifacts/release-0.1.8-build.log`。
