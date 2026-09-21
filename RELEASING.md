# 手动发布到 GitHub Releases

本项目通过 [GitHub Releases](https://github.com/nobYoQ/novelia/releases) 分发 APK，安装包不提交到 Git。当前不配置 CI 或自动发布工作流；以下操作由维护者在可信设备执行。

日常本地打包使用 `./build-debug.ps1` 或 `./build-release.ps1`，详见 [本地构建指南](docs/development/getting-started.md)。本地 Release 默认使用 Debug 测试证书，输出到 `outputs/packages/`，允许未提交改动且无需标签；它不等于本页的正式发行流程。正式附件仍由 `scripts/prepare-release.ps1` 准备。

## 首次发布前

- [ ] 确认贴纸的公开分发授权或完成替换。它们不适用本项目 GPL-3.0；此事项仍待处理。
- [ ] 核对准备公开的源码和 Git 历史；旧历史中存在 `releases/Novelia-0.1.0-debug.apk`。忽略规则不会清除历史。普通旧安装包可保留历史；若因凭据或素材授权必须清理，应先备份并另行制定历史重写方案，不能直接强推。
- [ ] 按 [仓库设置清单](.github/REPOSITORY_SETUP.md) 核对 GitHub 权限与安全设置。
- [ ] 使用 Android Studio 的 Generate Signed App Bundle or APK 创建或选定长期发布证书。密钥库放在仓库外，并做好独立加密备份。
- [ ] 记录证书的公开 SHA-256 指纹，用于后续每次发行校验。
- [ ] 在测试设备验证新装、覆盖升级、阅读资料备份/恢复、电子纸及大屏的关键流程。

历史本地包使用测试证书。换用新证书时，同包名通常不能直接覆盖安装；应明确告知用户先通过应用的“阅读资料备份”导出 ZIP、保存其他需要的文件，再卸载旧版、安装新版并恢复。先在测试设备验证备份内容，勿声称全部数据会自动迁移。不要为了兼容旧测试包而公开分发 Debug 签名包。

## 版本与源码

1. 修改根目录 `version.properties`：`versionName` 使用 `X.Y.Z` 或 `X.Y.Z-beta.1`；`versionCode` 每次公开发包都递增，所有 ABI 使用同一版本码。
2. 当前历史基线是 `0.1.8 / 11`。本轮准备变更仍在“未发布”；首次发布这些变更建议升为 `0.1.9 / 12`，不要把旧的 0.1.8 附件静默替换。
3. 把 `CHANGELOG.md` 中待发布内容归入新版本并填写真实发布日期，更新 README 展示版本。
4. 运行检查、审查改动并提交，确认工作区干净。为该提交创建 `vX.Y.Z` 标签（含预发布后缀时必须一致），再切换到该提交构建。
5. 发行附件、标签和公开源码必须对应同一个提交。发布后不移动标签，不覆盖已分发的同名包。

## 配置签名

直接调用 Gradle 或通过 `build.ps1` 执行 Release 任务时默认未签名，便于贡献者检查。根目录 `build-release.ps1` 默认显式开启本地测试签名，可通过 `-Unsigned` 改为未签名。只有显式使用 `-PreleaseSigning=true` 才读取以下进程环境变量：

| 环境变量 | 内容 |
| --- | --- |
| `NOVELIA_KEYSTORE_PATH` | 仓库外密钥库的绝对路径 |
| `NOVELIA_KEYSTORE_PASSWORD` | 密钥库密码 |
| `NOVELIA_KEY_ALIAS` | 密钥别名 |
| `NOVELIA_KEY_PASSWORD` | 密钥密码 |

通过本机凭据管理器或安全的进程环境注入，不要把真实值写入命令历史、源码、截图、Issue 或聊天；不要把密钥作为发行附件。签名变量不要持久化到公共开发配置。构建结束后清除当前进程变量，不共享签名构建的缓存，不对签名任务使用 `--scan` 或调试日志。

`-PlocalReleaseSigning=true` 仅保留给本地测试，与正式签名选项互斥。正式附件准备脚本会拒绝 Android Debug 证书，并检查证书指纹。

## 准备附件

Windows PowerShell 7 示例；把工具路径和公开证书指纹换成你的实际值，先按上文配置签名环境：

```powershell
$apkSigner = 'D:/Android/sdk/build-tools/35.0.0/apksigner.bat'
$certificateFingerprint = '<发布证书的 SHA-256 指纹>'
./scripts/prepare-release.ps1 -ApkSignerPath $apkSigner -CertificateSha256 $certificateFingerprint -Abi arm64-v8a
```

脚本要求版本对应标签已存在且指向 HEAD、工作区干净，运行 Release 单元测试、Lint 和构建，再验证 APK 签名、版本及证书。已缓存依赖时可加 `-Offline`。支持 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 和 `universal`；较老设备是否支持某个 ABI，应以实际安装验证为准。

输出目录为 `outputs/releases/vX.Y.Z-ABI/`，构建日志默认写入 `outputs/logs/`。脚本拒绝覆盖已存在的输出目录，不会创建提交、标签、推送或上传。

| 附件 | 用途 |
| --- | --- |
| `Novelia-X.Y.Z-ABI.apk` | 可安装的正式签名 APK |
| `Novelia-X.Y.Z-source.zip` | 与二进制对应提交的源码和构建脚本 |
| `OPEN_SOURCE_NOTICES.txt` | 与 APK 内相同的完整许可和依赖声明 |
| `SHA256SUMS-ABI.txt` | 附件的 SHA-256 校验值 |
| `Novelia-X.Y.Z-ABI-metadata.json` | 提交、版本、ABI 与证书公开指纹 |
| `CHANGELOG.md` | 更新记录 |
| `Novelia-X.Y.Z-ABI-mapping.zip` | R8 混淆映射；维护者长期归档，可选择公开 |

同时发布多个 ABI 时，分别生成附件；脚本为 APK、校验文件、元数据和映射添加 ABI 名称以避免覆盖。相同源码 ZIP、更新记录和许可证只需上传一份；保留文件原名以便校验。GitHub 自动生成的 Source code 档案也应保留。

Linux / macOS 可使用 `sh ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease -PreleaseSigning=true -PtargetAbi=arm64-v8a` 构建，然后按相同要求验证签名、对应源码和附件校验值。上面的 PowerShell 附件工具以 Windows 为验证环境。

## 上传与验收

1. 推送已审查的提交和对应版本标签到 GitHub；不要推送密钥或本地 `outputs/`。
2. 在 Releases 选择 **Draft a new release**，选中已经推送的准确标签。
3. 填写版本变化、最低 Android 版本（API 26 / Android 8.0）、ABI、签名迁移说明、已验证及未验证范围。测试版本勾选 Pre-release。
4. 上传 APK、对应源码、许可证、校验文件和版本元数据；归档混淆映射。
5. 从草稿重新下载 APK，检查 SHA-256 与签名，并在专用设备上验证安装及数据迁移后再发布。
6. 建议在首次发布前开启不可变发行版；先把所有附件放入草稿，再发布。不要把 Actions 临时产物链接作为长期下载地址。

SHA-256 用于发现文件损坏；只有来自可信仓库的校验信息和发布证书才能帮助确认来源。源码 GPL 授权不包含贴纸或网站小说内容。

参考：[GitHub 管理发行版](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)。
