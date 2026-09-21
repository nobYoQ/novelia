# 发布与维护

[返回发布与维护索引](README.md) · [文档总目录](../README.md)

本页帮助开发者理解从 PR 到发行的职责与产物。逐步操作以根目录 [RELEASING.md](../../RELEASING.md) 为准，避免复制两套签名指令。当前采用手动检查和 GitHub Releases，没有 CI 或自动发布。

## GitHub 协作

| 入口 | 用途 | 维护依据 |
| --- | --- | --- |
| Issue 表单 | 缺陷的复现条件、功能建议的动机与范围 | [.github/ISSUE_TEMPLATE](../../.github/ISSUE_TEMPLATE) |
| Pull Request | 问题、最终行为、验证、未验证项和关联 Issue | [PR 模板](../../.github/pull_request_template.md) |
| CODEOWNERS | 特定路径的维护责任提示 | [.github/CODEOWNERS](../../.github/CODEOWNERS) |
| Security 私密报告 | 漏洞协调，避免公开敏感细节 | [SECURITY.md](../../SECURITY.md) |
| Releases | 正式 APK、对应源码、许可和校验附件 | [RELEASING.md](../../RELEASING.md) |

仓库中的模板和 CODEOWNERS 不等于 GitHub 后台保护已开启。维护者按 [.github/REPOSITORY_SETUP.md](../../.github/REPOSITORY_SETUP.md) 核对权限、合并规则和私密报告入口；尚未配置 CI 时不应要求一个不存在的状态检查。

## 版本与变更记录

[version.properties](../../version.properties) 是版本名称和 Android 版本码的单一配置来源。当前基线为 `0.1.8 / 11`。发行时维护者统一选择版本、递增 `versionCode`、整理 [CHANGELOG.md](../../CHANGELOG.md) 并更新项目首页展示版本。各 ABI 使用同一版本码。

标签格式为 `v<versionName>`，预发布后缀也须匹配。已经公开的版本标签和附件不静默替换；修复后发布新版本，让用户能区分产物。发行二进制、对应源码、版本元数据和标签应关联同一个提交。

## 构建到附件的流程

日常安装测试使用根目录的 [build-debug.ps1](../../build-debug.ps1) 或 [build-release.ps1](../../build-release.ps1)，参数见 [本地构建指南](../development/getting-started.md)。本地脚本允许未提交改动和无标签构建，将 APK、校验文件及 Release 映射归档到 `artifacts/packages/`；默认本地 Release 使用 Debug 测试证书，不能作为正式发行附件。

正式发行则采用以下流程：

```mermaid
flowchart LR
    PR[审查并完成检查] --> Commit[提交并确认工作区干净]
    Commit --> Tag[创建匹配版本的标签]
    Tag --> Build[正式签名 Release 构建]
    Build --> Verify[校验版本 / 签名 / 证书]
    Verify --> Bundle[源码 / 许可 / 哈希 / 元数据]
    Bundle --> Draft[GitHub Release 草稿]
    Draft --> Device[下载复验 / 安装与迁移]
    Device --> Publish[发布]
```

[prepare-release.ps1](../../scripts/prepare-release.ps1) 将本地准备过程具体化：检查干净工作区和指向 HEAD 的版本标签，运行 Release 构建/单元测试/Lint，检查 APK 版本、证书公开指纹与签名，生成附件并再次检查源码状态。它拒绝 Debug 证书及已存在的输出目录，不替维护者提交、打标签、推送或上传。

脚本的 `-Abi` 支持四种具体 ABI 和 `universal`。Gradle 的 `targetAbi` 同样接受这五种值，省略或使用 `universal` 均不增加单 ABI 过滤；当前正式附件脚本在 `universal` 时省略该属性，日常本地打包脚本则显式传入。详细参数及签名环境变量见 [发布指南](../../RELEASING.md)。

## 产物与长期留存

| 产物 | 作用 |
| --- | --- |
| 正式签名 APK | 用户安装；后续同包名覆盖升级依赖签名兼容 |
| 精确提交的源码 ZIP | 对应构建源码、脚本与许可；与标签和元数据核对 |
| `OPEN_SOURCE_NOTICES.txt` | APK 内相同的完整第三方及项目许可说明 |
| `SHA256SUMS-ABI.txt` | 发现附件损坏或与已知产物不一致，不独立证明可信来源 |
| ABI 版本元数据 JSON | 记录提交、版本、ABI 和证书公开指纹 |
| `CHANGELOG.md` | 说明变化、迁移与限制 |
| R8 mapping ZIP | 维护者长期归档，用于解释对应版本的混淆堆栈，可选择公开 |

产物在忽略目录 `releases/` 生成，上传为 GitHub Release 附件，不提交到源码树。多 ABI 共享的源码、声明和更新记录上传一份即可；校验文件引用的名称应保持不变。

正式签名材料是长期维护资产，不是发行附件。直接调用 Gradle 或通过 `build.ps1` 执行 Release 任务时默认未签名；本地 `build-release.ps1` 默认采用测试签名。只有维护者通过正式发行流程显式开启正式签名。旧本地测试包可能与新正式包证书不同，需要先导出并验证阅读资料，再按发行说明迁移，不能假定直接覆盖安装。

## 第三方许可维护

[gradle/open-source-notices.gradle.kts](../../gradle/open-source-notices.gradle.kts) 解析实际 `releaseRuntimeClasspath`，汇总坐标、版本和 JAR/AAR 中的 LICENSE/NOTICE，并合入项目 LICENSE、NOTICE 与 `licenses/` 中的文本。生成文件进入 APK 的 `assets/open-source/NOTICE.txt`，由 [OpenSourceLicensesScreen.kt](../../app/src/main/java/cc/novelia/app/ui/about/OpenSourceLicensesScreen.kt) 离线显示。

更新依赖后重新生成并人工复核，检查未携带全文的依赖是否需要补充许可证文本和来源。清单包含传递依赖，不代表 R8 最终保留了每个类；也不意味着外部素材已获得授权。维护方法见 [licenses/README.md](../../licenses/README.md)。

## 当前公开发行前待办

- 贴纸及相关形象的公开分发授权仍未确认，公开包含这些素材的源码和 APK 前需获得适当授权或完成替换，详见 [NOTICE.md](../../NOTICE.md)。代码及文档的 GPL-3.0-only 不能替代这项授权。
- GitHub 后台设置需要有权限的维护者核对；本地清单不证明服务器端已应用。
- 长期发布证书、备份与公开指纹需由维护者配置，实际签名与安装升级必须验收。
- 核对准备公开的 Git 历史，尤其旧安装包和其他历史附件；忽略规则不会删除已有历史。
- 本轮未引入 CI，维护者继续按 [测试指南](../quality/testing.md) 记录人工执行结果。

## 发布后的维护

根据 Issue 的版本、设备、模式和复现条件分类处理。修复数据损坏、会话隔离或恢复相关问题时，优先保留最小回归夹具，再实现修复。获取崩溃堆栈时使用对应版本 R8 映射并脱敏。

紧急修复仍需明确版本、对应源码和验证范围；不以覆盖旧附件代替新发行。原站 API 变化可参考 [网络文档](../network/network-and-sync.md) 逐层定位，不应为了让页面恢复显示而关闭认证或文件校验。安全报告按 [安全政策](../../SECURITY.md) 处理。
