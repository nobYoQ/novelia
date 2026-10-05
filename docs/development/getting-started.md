# 环境搭建与构建

[开发目录](README.md) · [文档首页](../README.md)

目标是先得到一个可安装的测试包。普通开发不需要原站账号或发布证书；镜像入口口令是可选配置。

## 开发环境

| 工具 | 当前项目配置 | 来源 |
| --- | --- | --- |
| JDK | 推荐 17；Windows 检测脚本接受 17–23；字节码目标为 17 | [应用构建](../../app/build.gradle.kts)、[环境检测](../../scripts/build-environment.ps1) |
| Android SDK | compileSdk / targetSdk 36，minSdk 26 | [应用构建](../../app/build.gradle.kts) |
| Gradle / AGP | Wrapper 8.13 / AGP 8.13.2 | [Wrapper](../../gradle/wrapper/gradle-wrapper.properties)、[根构建](../../build.gradle.kts) |
| Kotlin / Compose | Kotlin 插件 2.2.21，Compose BOM 2025.12.00 | [根构建](../../build.gradle.kts)、[应用依赖](../../app/build.gradle.kts) |
| ECH 原生库 | Go 1.27.1、NDK 28.2.13676358，gomobile 版本也已锁定 | [原生工具链配置](../../gradle/ech-native.properties) |
| Windows 命令环境 | PowerShell 7 | [build.ps1](../../build.ps1) |

用 Android Studio 打开仓库根目录，在 SDK Manager 安装 Platform 36 和上述 NDK。应用没有显式固定 `buildToolsVersion`，构建工具由 AGP 选择；发布文档中的 35.0.0 是签名工具路径示例。

**Go 和 NDK 是应用构建依赖。** Gradle 会从 [native/ech](../../native/ech) 构建 ECH AAR，应用内关闭 ECH 并不会跳过编译。Windows x64 缺少指定 Go 时可自动下载并校验固定版本；其他平台预装该版本，或用 `NOVELIA_GO_HOME` / `-PechGoHome=<目录>` 指定。JDK、SDK 和 NDK 需要自行准备。

首次构建会访问 Gradle/Maven 仓库和 Go 下载、模块服务。离线模式要求这些依赖及工具已准备完毕。具体原生构建任务见 [ech-native.gradle.kts](../../gradle/ech-native.gradle.kts)。

## 从源码启动

在仓库根目录执行：

```powershell
# 检查 JDK、SDK 和缓存目录；不启动 Gradle
./build.ps1 -CheckEnvironment

# 构建可安装的 Debug 包
./build-debug.ps1

# 提交前增加 JVM 单元测试和 Lint
./build-debug.ps1 -Verify
```

环境检查只覆盖脚本的 JDK/SDK 等配置，不能证明 Go、NDK、SDK 组件和全部依赖已经齐备。真正构建遇到缺项时，按错误提示补齐。

APK 归档到 `outputs/packages/debug/`，日志在 `outputs/logs/`。脚本不自动安装。也可以在 Android Studio 选择 `app`，运行到模拟器或测试设备。

## 选择构建入口

| 命令 | 结果 |
| --- | --- |
| `./build-debug.ps1` | Debug APK |
| `./build-release.ps1 -Verify` | 启用 R8 和资源收缩的本地 Release；使用 Debug 测试证书，并运行 Release 单元测试、Lint |
| `./build-release.ps1 -Unsigned` | 未签名 Release，不能直接安装 |
| `./build.ps1 -Tasks @(...)` | 执行指定 Gradle 任务，不整理 APK 归档 |
| `./scripts/prepare-release.ps1 ...` | 正式签名与发行附件，前提和现存限制见[发布指南](../../RELEASING.md) |

Debug 和本地 Release 都支持 `-Abi`、`-Offline` 和 `-Verify`。默认 ABI 为 `universal`，也可选 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`：

```powershell
./build-release.ps1 -Abi arm64-v8a -Verify
./build-debug.ps1 -Offline
```

`-Verify` 不运行设备测试；原生 AAR 构建自身依赖 Go 单元测试。Go 的真实网络测试另有显式开关，见[测试指南](../quality/testing.md)。

应用包名统一为 `cc.novelia.app`，Debug 没有独立后缀。安装到已有同包名应用的设备时，需要签名兼容且版本满足升级要求。不同签名之间迁移前先[备份阅读资料](../data/backup-and-recovery.md)。

## 自动检测与手动配置

[build-environment.ps1](../../scripts/build-environment.ps1) 的选择顺序如下：

| 配置 | 优先顺序 |
| --- | --- |
| JDK | `build.ps1` 手动配置 → `JAVA_HOME` / `JDK_HOME` / `STUDIO_JDK` → PATH → 常见 Android Studio/JDK 安装目录 |
| SDK | 手动配置 → `local.properties` 的 `sdk.dir` → `ANDROID_HOME` / `ANDROID_SDK_ROOT` → 默认目录 → PATH 中的 adb |
| Gradle 缓存 | 手动配置 → `GRADLE_USER_HOME` → 项目 `.gradle-home/` |
| Android 用户目录 | 手动配置 → `ANDROID_USER_HOME` → 项目 `.android/` |

通常设置环境变量即可：

```powershell
$env:JAVA_HOME = 'C:/Tools/jdk-17'
$env:ANDROID_HOME = 'C:/Tools/Android/Sdk'
./build-debug.ps1
```

如果已有 `local.properties`，它的 SDK 路径优先于环境变量。也可在 `build.ps1` 顶部填写 `$ManualJavaHome`、`$ManualAndroidSdk` 等变量；路径指向安装根目录，留空表示自动选择。无效的手动配置会直接报错。

构建前脚本同步 `sdk.dir`，保留文件其他内容；不要提交个人路径。更换 `ANDROID_USER_HOME` 可能改变 Debug 证书位置，进而影响覆盖安装。脚本和 IDE 使用不同 Gradle 缓存时，IDE 构建成功也不表示脚本可以离线构建。

## 直接运行 Gradle

聚焦单个任务时使用通用入口：

```powershell
./build.ps1 -Tasks @(':app:testDebugUnitTest', '--tests', 'cc.novelia.app.ApiContractTest')
./build.ps1 -Tasks @(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')
./build.ps1 -Tasks @(':app:generateOpenSourceNotices')
```

不传 `-Tasks` 时，`build.ps1` 默认执行 Debug 构建与 JVM 单元测试。直接执行 `assembleRelease` 默认未签名；测试签名和正式签名选项互斥。

Linux / macOS 准备好同版本工具链后使用 Wrapper：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 的标准入口是 `./gradlew.bat`，它不会执行 PowerShell 脚本的环境自动检测。`.reference/` 和 `resource/` 等本地参考目录不是构建依赖。

## 产物在哪里

| 内容 | 位置 |
| --- | --- |
| 本地 APK 和 SHA-256 | `outputs/packages/debug/`、`outputs/packages/release-local/`、`outputs/packages/release-unsigned/` |
| 构建日志 | `outputs/logs/` |
| 正式发行附件 | `outputs/releases/` |
| Go 工具链与缓存 | `outputs/ech-tools/` |
| Gradle 原始 APK | `app/build/outputs/apk/` |
| JVM 测试报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |
| Release R8 映射 | `app/build/outputs/mapping/release/mapping.txt` |
| 生成的许可证 | `app/build/generated/openSourceAssets/open-source/NOTICE.txt` |

归档 APK 命名为 `Novelia-<版本>-<模式>-<ABI>.apk`，旁边有 `.sha256`，Release 另存映射文件。重复本地构建会覆盖同版本、模式和 ABI 的归档，日志另存。`-LogPath` 只能指定仓库 `outputs/` 内的路径。

## 修改版本号并重新编译

修改 [version.properties](../../version.properties) 的 `versionName` 和 `versionCode`，再运行所需打包命令。版本名支持 `X.Y.Z` 和预发布后缀；版本码是每次公开发包递增的正整数，各 ABI 共用。

通常无需先 `clean`，本地构建也无需提交或打标签。APK 元数据和归档名从同一配置读取。正式发行才要求干净提交、匹配标签和长期证书；普通贡献不自行升版。

环境或安装问题见[排障指南](../maintenance/troubleshooting.md)，环境就绪后继续读[架构](../architecture/architecture.md)。
