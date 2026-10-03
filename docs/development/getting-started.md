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

Fork 本仓库后克隆自己的 Fork，或克隆项目仓库。在 Android Studio 中打开仓库根目录，安装所需 SDK，选择 Gradle JDK，然后等待同步。Windows 脚本会自动查找已安装的 JDK 和 Android SDK；没有安装环境时，先通过 Android Studio / SDK Manager 或 JDK 安装程序准备环境。检测逻辑不会自动安装 JDK 或 SDK，也不扫描整个磁盘。

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

## 自动检测与手动配置

两个根目录入口通过 [build-package.ps1](../../scripts/build-package.ps1) 复用 [build.ps1](../../build.ps1)，正式附件脚本也使用同一个构建入口。检测逻辑位于 [build-environment.ps1](../../scripts/build-environment.ps1)，手动配置集中在 **根目录 `build.ps1` 顶部的“手动环境配置区”**，无需分别修改 Debug / Release 脚本。

| 配置 | 从高到低的选择顺序 |
| --- | --- |
| JDK | 手动配置 → `JAVA_HOME` → `JDK_HOME` → `STUDIO_JDK` → PATH 的 `java.exe` → 注册表和默认位置的 Android Studio JBR/JRE → 用户 `.jdks` 与 Program Files 下常见 JDK 厂商目录 |
| Android SDK | 手动配置 → `local.properties` 的 `sdk.dir` → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%/Android/Sdk` → PATH 的 `adb.exe` 所属 SDK |
| Gradle 缓存 | 手动配置 → `GRADLE_USER_HOME` → 当前项目 `.gradle-home/` |
| Android 用户目录 | 手动配置 → `ANDROID_USER_HOME` → 当前项目 `.android/` |

自动候选无效时跳过并继续查找；手动填写的 JDK/SDK 无效时直接报错，避免悄悄使用其他安装。JDK 检查实际版本、运行能力和 `javac.exe`：当前组合支持 JDK 17–23，推荐 17 或 21，范围依据 [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)。SDK 检测确认安装根目录，Platform 36 和 Build Tools 等组件是否齐全由实际 Gradle 构建检查。

可先执行只读检查，不启动 Gradle、不下载依赖，也不修改 `local.properties`：

```powershell
./build.ps1 -CheckEnvironment
```

自动检测失败或希望固定环境时，修改 `build.ps1` 中以下变量。下面只是填写示例，请换成自己的目录；留空 `''` 表示自动选择：

```powershell
# JDK 根目录，不要写到 bin 或 java.exe
$ManualJavaHome = 'C:/Tools/jdk-21'
# SDK 根目录，不要写到 platform-tools 或 build-tools
$ManualAndroidSdk = 'C:/Tools/Android/Sdk'
# 可选：缓存目录；通常不需要改
$ManualGradleUserHome = ''
$ManualAndroidUserHome = ''
```

路径可以包含空格和中文，也可使用相对于 `build.ps1` 所在目录的路径。不要把个人目录修改提交到仓库；需要保持工作区干净时，可改用环境变量：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:ANDROID_HOME = 'C:/Tools/Android/Sdk'
./build-debug.ps1
```

已有有效 `local.properties` 的 SDK 路径优先于环境变量；需要换 SDK 时修改该文件，或填写 `$ManualAndroidSdk`。构建前脚本只同步 `sdk.dir`，保留其他属性和注释；文件已被 Git 忽略。构建过程中统一设置 JDK/SDK 环境和 Gradle daemon JDK，并在成功或失败后还原进程环境变量及工作目录。默认缓存与直接调用 Wrapper 的用户缓存可能不同；更换 `ANDROID_USER_HOME` 还会改变默认 Debug 测试证书的位置，原有测试包可能因签名不同无法覆盖安装。

已有完整依赖缓存时可用离线模式；首次构建不能依靠离线模式下载缺失依赖：

```powershell
./build-debug.ps1 -Offline
```

Linux / macOS 直接使用仓库 Wrapper。`sh` 调用也适用于未保留执行位的源码 ZIP：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

标准 Windows Wrapper 入口为 `./gradlew.bat`；它要求调用环境已配置 JDK/SDK，不执行 `build.ps1` 的自动检测和缓存目录设置。PowerShell 入口面向 Windows，也可在已配置环境的 Windows 构建机上使用；Linux / macOS 使用上述 Wrapper 命令。仓库不需要 `.reference/`、`resource/`、`artifacts/` 等本地忽略目录才能构建。

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

构建日志写入 `outputs/logs/build-<模式>-<ABI>-<时间戳>.log`。直接调用 `build.ps1` 时，默认日志为 `outputs/logs/build-gradle-<时间戳>.log`；`-LogPath` 仅允许指定仓库根目录 `outputs/` 内的位置，例如 `-LogPath outputs/logs/custom.log`，相对路径始终按仓库根目录解析，与调用时的工作目录无关。本地脚本允许工作区有未提交修改，无需版本标签；重复构建会覆盖同版本、模式和 ABI 的归档文件，日志另存。它们不安装应用、不生成正式发行附件，也不上传文件。需要保留某次本地安装包时应另行归档。

正式分发使用 [prepare-release.ps1](../../scripts/prepare-release.ps1)：要求干净工作区、匹配版本的标签和正式证书，输出到 `outputs/releases/`，详见 [发布指南](../../RELEASING.md)。本地 Release 的测试证书不适合公开发行。

## 修改版本号并重新编译

只需修改根目录 [version.properties](../../version.properties) 的两项，不必修改 Gradle 文件或打包脚本。例如从 `0.2.2 / 14` 升为：

```properties
versionName=0.2.3
versionCode=15
```

- `versionName` 是展示版本，使用 `X.Y.Z`，也支持 `0.2.0-beta.1` 这样的预发布名称。
- `versionCode` 是 Android 判断更新先后的正整数，每次对外发包递增，所有 ABI 使用相同值；不要重复使用旧版本码。

保存后在 PowerShell 7 执行：

```powershell
# 通用 Debug 包
./build-debug.ps1
# 或：ARM64 本地 Release 包，同时跑单元测试与 Lint
./build-release.ps1 -Abi arm64-v8a -Verify
```

通常不需要先 `clean`，无需提交或打标签即可本地编译。APK 内版本、APK 文件名和校验文件自动采用新值，例如 `outputs/packages/release-local/Novelia-0.2.3-release-local-arm64-v8a.apk`。脚本会核对 APK 元数据，发现版本不一致时不会整理产物。这里的数值只是示例，不会自动修改仓库版本；正式发布还需同步发行说明、版本展示、标签并使用长期发布证书，按 [发布流程](../../RELEASING.md) 执行。

仓库根目录的 `outputs/` 由脚本自动创建并整体忽略，是脚本产物的唯一出口：安装包位于 `outputs/packages/`，日志位于 `outputs/logs/`，正式附件位于 `outputs/releases/`，ECH 的 Go 工具链及缓存位于 `outputs/ech-tools/`。旧 `artifacts/` 的验证记录归档到 `outputs/archive/artifacts/`。新增脚本也应遵循这一约定。

Gradle 中间文件与原始测试报告仍位于各模块的 `build/`，具体路径见下表。清理旧日志、测试截图或临时夹具前，应确认没有需要保留的发行映射和验证记录；不要将依赖缓存、`local.properties`、签名材料或尚未提交的源码当作临时产物删除。

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
