# GitHub 仓库设置清单

仓库文件无法代替 GitHub 后台设置；此清单不表示相关功能已经启用。由有管理权限的维护者在首次公开和发行前逐项核对。

- [ ] About 填写非官方 Android 客户端定位；添加 `android`、`kotlin`、`jetpack-compose`、`reader` 等相关主题。
- [ ] 开启 Issues；确认默认分支为 `main`，贡献指南、安全政策和模板在默认分支可见。
- [ ] 限制 `main` 的强制推送和删除；外部贡献通过 PR 合并。多人维护后启用至少一位审批者和必要的 CODEOWNERS 审查；只有一位维护者时不要配置无人可满足的审批规则。
- [ ] 允许 GitHub Actions 运行 [Preview APK](workflows/preview-apk.yml) 及其依赖的 Actions；允许发布任务使用 `contents: write`。确认默认分支推送后会创建或更新 `preview` Pre-release，其他分支仍有 Actions 下载产物；合并前按照 CONTRIBUTING.md 手动复验。
- [ ] 在原签名机器运行 `scripts/configure-preview-signing.ps1`，设置加密的 `NOVELIA_PREVIEW_KEYSTORE_BASE64` 仓库 Secret。核对公开指纹、私密备份原 `.android/debug.keystore`；没有该 Secret 时预览构建会停止，不会生成替代密钥。具体操作见[共用签名配置](../docs/development/getting-started.md#配置共用签名维护者首次设置)。
- [ ] 保护 `v*` 正式发布标签，限制其创建、更新和删除权限。允许工作流更新预览专用 `preview` 标签（或 `NOVELIA_PREVIEW_TAG` 指定标签）；正式版本禁止覆盖。
- [ ] 开启可用的 Dependency graph、Dependabot alerts、Secret scanning 和 Push protection。当前没有自动依赖更新配置。
- [ ] 开启 Private vulnerability reporting，确认 SECURITY.md 的私密入口可用。
- [ ] 收紧协作者权限，维护者启用双因素认证，定期移除不再使用的访问权限。
- [ ] 滚动预览需要可修改的 Release，核对 **Settings → General → Releases → Enable release immutability** 未启用。该设置会锁定新发布的标签和附件，无法用于同一 Pre-release 的持续替换；已不可变的 Release 不会被脚本修改。
- [ ] 公开前核对素材授权、源码和历史内容；公开仓库可能被永久复制或 Fork。

素材授权依据应记录在 NOTICE.md 及对应许可文件中，逐项核对实际状态。预览构建以外的检查仍需人工执行和记录。发布证书由维护者在本机安全创建、保管和备份；不在仓库中生成或存储。

参考：[GitHub 仓库最佳实践](https://docs.github.com/en/repositories/creating-and-managing-repositories/best-practices-for-repositories)。
