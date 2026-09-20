# GitHub 仓库设置清单

仓库文件无法代替 GitHub 后台设置；此清单不表示相关功能已经启用。由有管理权限的维护者在首次公开和发行前逐项核对。

- [ ] About 填写非官方 Android 客户端定位；添加 `android`、`kotlin`、`jetpack-compose`、`reader` 等相关主题。
- [ ] 开启 Issues；确认默认分支为 `main`，贡献指南、安全政策和模板在默认分支可见。
- [ ] 限制 `main` 的强制推送和删除；外部贡献通过 PR 合并。多人维护后启用至少一位审批者和必要的 CODEOWNERS 审查；只有一位维护者时不要配置无人可满足的审批规则。
- [ ] 当前不配置 CI，不添加不存在的必需状态检查；合并前按照 CONTRIBUTING.md 手动复验。
- [ ] 保护 `v*` 发布标签，限制其创建、更新和删除权限。禁止覆盖已经公开的版本。
- [ ] 开启可用的 Dependency graph、Dependabot alerts、Secret scanning 和 Push protection。本轮不创建自动依赖更新配置或 Actions 工作流。
- [ ] 开启 Private vulnerability reporting，确认 SECURITY.md 的私密入口可用。
- [ ] 收紧协作者权限，维护者启用双因素认证，定期移除不再使用的访问权限。
- [ ] 开启 Release immutability（如可用），以后先准备完整草稿再发布。
- [ ] 公开前核对素材授权、源码和历史内容；公开仓库可能被永久复制或 Fork。

贴纸授权及 CI 是明确保留的待办。发布证书由维护者在本机安全创建、保管和备份；不在仓库中生成或存储。

参考：[GitHub 仓库最佳实践](https://docs.github.com/en/repositories/creating-and-managing-repositories/best-practices-for-repositories)。
