# 环境搭建与构建

[返回开发入门索引](README.md) · [文档总目录](../README.md)

## 开发环境

| 工具/配置 | 本仓库基线 | 定义位置 |
| --- | --- | --- |
| JDK | 推荐 17；Java/Kotlin 目标字节码 17 | [app/build.gradle.kts](../../app/build.gradle.kts) |
| Android SDK | Platform 36；构建工具基线 35.0.0 | [应用配置](../../app/build.gradle.kts)、[发布指南](../../RELEASING.md) |
| Android 运行环境 | API 26 及以上；benchmark 模块 API 28 及以上 | [app](../../app/build.gradle.kts)、[benchmark](../../benchmark/build.gradle.kts) |
| Gradle Wrapper | 8.13，包含分发包 SHA-256 校验 | [gradle-wrapper.properties](../../gradle/wrapper/gradle-wrapper.properties) |
| Android Gradle Plugin | 8.13.2 | [根构建文件](../../build.gradle.kts) |
| Kotlin / Compose 编译插件 | 2.2.21 | [根构建文件](../../build.gradle.kts) |
| Compose BOM | 2025.12.00 | [应用依赖](../../app/build.gradle.kts) |
| Windows 命令环境 | PowerShell 7 | [build.ps1](../../build.ps1) |

当前没有版本目录 `libs.versions.toml`，依赖版本直接维护在两个模块和根目录的 Gradle Kotlin 脚本中。`compileSdk`、`targetSdk` 都是 36；`minSdk` 与编译 SDK 不是同一个概念。应用脚本未显式固定 `buildToolsVersion`，工具选择由当前 AGP 决定，签名工具示例统一采用 SDK Build Tools 35.0.0。

首次构建需要访问 Google Maven、Maven Central、Gradle Plugin Portal 和 Gradle 分发服务。可用依赖仓库见 [settings.gradle.kts](../../settings.gradle.kts)。不要把个人代理、仓库凭据或机器路径提交到项目。

## 从源码启动

Fork 本仓库后克隆自己的 Fork，或克隆项目仓库。在 Android Studio 中打开仓库根目录，安装所需 SDK，选择 Gradle JDK，然后等待同步。命令行开发需要让 `local.properties` 中的 `sdk.dir` 指向本机 SDK；Android Studio 可生成该文件。

示例 `local.properties`（路径必须按本机修改，文件已被忽略）：

```properties
sdk.dir=D\:/Android/sdk
```

Windows 推荐在仓库根目录执行：

```powershell
./build-debug.ps1
./build-release.ps1
```

前者生成 Debug APK，后者生成使用 Debug 测试证书签名、启用 R8 和资源收缩的本地 Release APK，均可用于本机安装测试。默认只执行对应 `assemble`；提交前可同时运行该变体的 JVM 单元测试和 Lint：

```powershell
./build-debug.ps1 -Verify
./build-release.ps1 -Verify
```

两个根目录入口通过 [build-package.ps1](../../scripts/build-package.ps1) 复用 [build.ps1](../../build.ps1)。后者优先采用 `JAVA_HOME/bin/java.exe`，如果变量已设置但无效会立即报错。未设置时依次查找脚本中列出的 Android Studio JBR 路径，再查找 PATH。它将 `GRADLE_USER_HOME` 和 `ANDROID_USER_HOME` 指向当前仓库的 `.gradle-home/`、`.android/`，工作目录切换到仓库根并在结束时还原。该缓存与直接调用 Wrapper 的默认用户缓存可能不同。

`JAVA_HOME` 必须指向 JDK 根目录。需要临时指定 JDK 时，在当前 PowerShell 会话中按本机路径配置；不必改构建脚本：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./build-debug.ps1
```

已有完整依赖缓存时可用离线模式；首次构建不能依靠离线模式下载缺失依赖：

```powershell
./build-debug.ps1 -Offline
```

Linux / macOS 直接使用仓库 Wrapper。`sh` 调用也适用于未保留执行位的源码 ZIP：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

标准 Windows Wrapper 入口为 `./gradlew.bat`；它要求调用环境已配置 JDK/SDK，不执行 `build.ps1` 的 JBR 查找和缓存目录设置。仓库不需要 `.reference/`、`resource/`、`artifacts/` 等本地忽略目录才能构建。

## 本地打包参数与产物

| 参数 | 支持入口 | 默认值与用途 |
| --- | --- | --- |
| `-Abi` | Debug / Release | `universal`；可选 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` |
| `-Offline` | Debug / Release | 关闭；仅使用已有 Gradle 依赖缓存 |
| `-Verify` | Debug / Release | 关闭；增加对应变体的 JVM 单元测试和 Lint，不运行设备测试 |
| `-Unsigned` | Release | 关闭；生成不可直接安装的未签名 Release APK |

```powershell
# 面向 ARM64 设备的本地 Release 包，并执行检查
./build-release.ps1 -Abi arm64-v8a -Verify

# 依赖已缓存时，生成未签名 Release 包
./build-release.ps1 -Unsigned -Offline
```

脚本从 [version.properties](../../version.properties) 读取版本，根据 APK 元数据收集当次产物。文件名格式为 `Novelia-<版本>-<模式>-<ABI>.apk`，校验文件追加 `.sha256`；Release 的 R8 映射使用相同主文件名并追加 `-mapping.txt`。

| 模式 | 入口 | 归档目录 |
| --- | --- | --- |
| `debug` | `build-debug.ps1` | `outputs/packages/debug/` |
| `release-local` | `build-release.ps1` | `outputs/packages/release-local/` |
| `release-unsigned` | `build-release.ps1 -Unsigned` | `outputs/packages/release-unsigned/` |

构建日志写入 `outputs/logs/build-<模式>-<ABI>-<时间戳>.log`。直接调用 `build.ps1` 时，默认日志为 `outputs/logs/build-gradle-<时间戳>.log`，仍可通过 `-LogPath` 指定其他位置。本地脚本允许工作区有未提交修改，无需版本标签；重复构建会覆盖同版本、模式和 ABI 的归档文件，日志另存。它们不安装应用、不生成正式发行附件，也不上传文件。需要保留某次本地安装包时应另行归档。

正式分发使用 [prepare-release.ps1](../../scripts/prepare-release.ps1)：要求干净工作区、匹配版本的标签和正式证书，输出到 `outputs/releases/`，详见 [发布指南](../../RELEASING.md)。本地 Release 的测试证书不适合公开发行。

`outputs/` 由脚本自动创建并整体忽略，是收集安装包、日志和正式附件的统一目录。Gradle 中间文件与原始测试报告仍位于各模块的 `build/`，具体路径见下表。清理旧日志、测试截图或临时夹具前，应确认没有需要保留的发行映射和验证记录；不要将依赖缓存、`local.properties`、签名材料或尚未提交的源码当作临时产物删除。

## 运行与 Gradle 构建产物

选择 Android Studio 的 `app` 配置启动模拟器或专用测试设备；命令行可在确认目标设备后安装 Debug 包：

```powershell
adb devices
adb -s '<设备序列号>' install -r app/build/outputs/apk/debug/app-debug.apk
```

`<设备序列号>` 是待替换占位符。连接多台设备时必须指定目标。若设备上已有同包名但不同签名的版本，覆盖安装会失败；按 [排障指南](../maintenance/troubleshooting.md) 先备份资料，避免用卸载或清数据来试错。

| 产物 | 位置 |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/`；是否签名取决于本次构建参数 |
| JVM HTML 报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint HTML 报告 | `app/build/reports/lint-results-debug.html` |
| 生成的完整许可说明 | `app/build/generated/openSourceAssets/open-source/NOTICE.txt` |
| Release R8 映射 | `app/build/outputs/mapping/release/mapping.txt` |

构建目录是生成文件，实际 APK 文件名以该目录的 `output-metadata.json` 为准。版本号统一来自 [version.properties](../../version.properties)；应用包名为 `cc.novelia.app`，Debug 未设置包名后缀，不能与同包名正式版作为两个独立应用共存。

## 构建变体与参数

[build.ps1](../../build.ps1) 保留通用 Gradle 入口；不带参数时执行 `:app:assembleDebug` 和 `:app:testDebugUnitTest`，不归档 APK。通过 `-Tasks` 可执行任意所需任务：

```powershell
# 不需要签名凭据的 Release 检查
./build.ps1 -Tasks @(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')

# 限定原生库 ABI 的本地构建示例
./build.ps1 -Tasks @(':app:assembleDebug', '-PtargetAbi=arm64-v8a')

# 单独重生成第三方声明
./build.ps1 -Tasks @(':app:generateOpenSourceNotices')
```

Release 开启 R8 压缩和资源收缩。`targetAbi` 接受 `universal`、`arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`；`universal` 表示不额外限制 ABI。本地打包脚本始终显式传入该属性，覆盖机器级的隐式 ABI 设置，保证归档文件名与构建目标一致。

普通贡献和测试不需要原站账号或发布私钥。直接运行 Gradle 或通过 `build.ps1` 执行 `assembleRelease` 时默认未签名；`build-release.ps1` 则默认传入 `-PlocalReleaseSigning=true`，使用 Debug 签名。正式分发使用 `-PreleaseSigning=true` 及环境变量，两种签名选项互斥。正式步骤只在 [RELEASING.md](../../RELEASING.md) 维护。

## 常见工作入口

环境就绪后先阅读 [架构](../architecture/architecture.md)，选择一项小改动，按 [开发流程](development.md) 修改，并用 [测试指南](../quality/testing.md) 选择检查范围。JDK、SDK、下载依赖、签名或安装问题见 [排障](../maintenance/troubleshooting.md)。
