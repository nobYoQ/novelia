# 手动发布

本项目通过 [GitHub Releases](https://github.com/nobYoQ/novelia/releases) 手动分发正式 APK。[Preview APK](.github/workflows/preview-apk.yml) 在默认分支构建成功后自动更新测试签名的 `preview` Pre-release，下载和配置见 [Actions 构建指南](docs/development/getting-started.md#github-actions-自动预览包)。普通本地测试使用 `build-debug.ps1` / `build-release.ps1`；以下正式证书、版本标签和发行附件流程仍手动执行。

`preview` 是滚动预览专用标签，允许随成功构建移动并替换同名预览附件。已有其他标签的 Pre-release 可通过 `NOVELIA_PREVIEW_TAG` 仓库变量指定；目标必须是可修改的预发布，自动发布脚本拒绝替换普通正式 Release 和不可变 Release。

## 发布前准备

确认长期发布证书及备份、公开 SHA-256 指纹、素材授权和 GitHub 仓库设置。相关入口：[NOTICE](NOTICE.md)、[仓库设置清单](.github/REPOSITORY_SETUP.md)、[测试指南](docs/quality/testing.md)。

本地 Release 默认使用 Debug 证书。它可以检查 R8 后的行为，但不能作为正式发行包。更换签名时，同包名通常无法覆盖安装；先验证[阅读资料备份](docs/data/backup-and-recovery.md)，再制定用户迁移说明。

检查准备公开的 Git 历史。`.gitignore` 不移除已提交内容；若涉及凭据或未授权素材，另行处理范围与历史，不直接强推。

## 版本与源码

1. 修改 [version.properties](version.properties)：版本名支持 `X.Y.Z` 和预发布后缀，版本码每次公开发包递增，各 ABI 共用。
2. 汇总变化、迁移影响、已验证和未验证范围，准备 Release 正文。仓库不维护独立 CHANGELOG，也不在多篇文档中重复硬编码当前版本。
3. 完成检查、审查并提交，确认工作区干净。
4. 为该提交创建与版本名一致的 `v<versionName>` 标签，从同一提交构建。

APK、源码、元数据和标签必须对应同一个提交。正式版本发布后不移动标签，不静默覆盖旧附件。

## 签名配置

直接执行 Gradle `assembleRelease` 默认未签名；`build-release.ps1` 默认使用本地测试签名。正式构建显式使用 `-PreleaseSigning=true`，读取以下进程环境变量：

| 变量 | 用途 |
| --- | --- |
| `NOVELIA_KEYSTORE_PATH` | 仓库外密钥库路径 |
| `NOVELIA_KEYSTORE_PASSWORD` | 密钥库密码 |
| `NOVELIA_KEY_ALIAS` | 密钥别名 |
| `NOVELIA_KEY_PASSWORD` | 密钥密码 |

通过本机安全方式注入，不把真实值写入命令历史、源码或聊天。构建结束后清理进程变量，不公开签名缓存或使用 build scan。测试签名 `-PlocalReleaseSigning=true` 与正式签名互斥。

## 准备附件

**已知阻塞：** [prepare-release.ps1](scripts/prepare-release.ps1) 仍执行 `Copy-Item CHANGELOG.md`，但仓库没有该文件，因此会在收集附件阶段失败。正式使用前需要修正脚本的附件清单；本轮仅更新文档，未修改脚本。普通本地打包不依赖它。

修正该问题后，按脚本的正式前提执行，例如：

```powershell
$apkSigner = 'C:/Tools/Android/Sdk/build-tools/35.0.0/apksigner.bat'
$certificateFingerprint = '<发布证书的 SHA-256 指纹>'
./scripts/prepare-release.ps1 -ApkSignerPath $apkSigner -CertificateSha256 $certificateFingerprint -Abi arm64-v8a
```

工具路径是示例，使用实际安装位置。脚本要求工作区干净、版本标签指向 HEAD，执行 Release 单元测试、Lint 和构建，再检查 APK 版本、签名及证书指纹；拒绝 Debug 证书。完整缓存下可加 `-Offline`。

默认 ABI 为 `arm64-v8a`，也支持 `armeabi-v7a`、`x86_64`、`x86`、`universal`。正式脚本选择 universal 时不显式传 targetAbi，应确认外部 Gradle 配置没有留下单 ABI 限制。

输出到 `outputs/releases/v<版本>-<ABI>/`，不覆盖已有目录，不自动提交、打标签、推送或上传。上述失败可能留下部分附件，不能把目录存在当成准备成功。

| 产物 | 用途 |
| --- | --- |
| `Novelia-<版本>-<ABI>.apk` | 正式签名安装包 |
| `Novelia-<版本>-source.zip` | 精确提交的源码和构建脚本 |
| `OPEN_SOURCE_NOTICES.txt` | 与 APK 内相同的第三方及项目许可 |
| `SHA256SUMS-<ABI>.txt` | 附件校验 |
| `Novelia-<版本>-<ABI>-metadata.json` | 提交、版本、ABI、证书公开指纹 |
| `Novelia-<版本>-<ABI>-mapping.zip` | R8 映射，维护者长期归档 |

多 ABI 的共用源码和许可上传一份即可，保留校验文件引用的原名。Release 正文另填变化和迁移信息。

Linux/macOS 可直接使用 Wrapper 完成正式签名构建，但仍需逐项准备和验证对应源码、证书、映射及校验附件；PowerShell 附件工具以 Windows 为目标环境。

## 上传与验收

推送已审查提交及标签，创建对应标签的 Release 草稿，填写版本变化、最低 Android 8.0/API 26、ABI、迁移步骤和验证范围。上传正式附件并归档映射。

从草稿重新下载 APK，核对哈希和签名，在专用设备完成新装、覆盖升级或换签名恢复测试，再发布；测试版本标记预发布。发布后的修复使用新版本，不替换同名旧包。

SHA-256 只能帮助核对文件，来源还需结合可信仓库和证书。依赖与素材维护见[发布维护](docs/maintenance/release-and-maintenance.md)。
