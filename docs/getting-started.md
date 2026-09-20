# 环境搭建与构建

[返回开发手册](README.md)

## 开发环境

| 工具/配置 | 本仓库基线 | 定义位置 |
| --- | --- | --- |
| JDK | 推荐 17；Java/Kotlin 目标字节码 17 | [app/build.gradle.kts](../app/build.gradle.kts) |
| Android SDK | Platform 36；构建工具基线 35.0.0 | [应用配置](../app/build.gradle.kts)、[发布指南](../RELEASING.md) |
| Android 运行环境 | API 26 及以上；benchmark 模块 API 28 及以上 | [app](../app/build.gradle.kts)、[benchmark](../benchmark/build.gradle.kts) |
| Gradle Wrapper | 8.13，包含分发包 SHA-256 校验 | [gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) |
| Android Gradle Plugin | 8.13.2 | [根构建文件](../build.gradle.kts) |
| Kotlin / Compose 编译插件 | 2.2.21 | [根构建文件](../build.gradle.kts) |
| Compose BOM | 2025.12.00 | [应用依赖](../app/build.gradle.kts) |
| Windows 命令环境 | PowerShell 7 | [build.ps1](../build.ps1) |

当前没有版本目录 `libs.versions.toml`，依赖版本直接维护在两个模块和根目录的 Gradle Kotlin 脚本中。`compileSdk`、`targetSdk` 都是 36；`minSdk` 与编译 SDK 不是同一个概念。应用脚本未显式固定 `buildToolsVersion`，工具选择由当前 AGP 决定，签名工具示例统一采用 SDK Build Tools 35.0.0。

首次构建需要访问 Google Maven、Maven Central、Gradle Plugin Portal 和 Gradle 分发服务。可用依赖仓库见 [settings.gradle.kts](../settings.gradle.kts)。不要把个人代理、仓库凭据或机器路径提交到项目。

## 从源码启动

Fork 本仓库后克隆自己的 Fork，或克隆项目仓库。在 Android Studio 中打开仓库根目录，安装所需 SDK，选择 Gradle JDK，然后等待同步。命令行开发需要让 `local.properties` 中的 `sdk.dir` 指向本机 SDK；Android Studio 可生成该文件。

示例 `local.properties`（路径必须按本机修改，文件已被忽略）：

```properties
sdk.dir=D\:/Android/sdk
```

Windows 推荐在仓库根目录执行：

```powershell
./build.ps1
```

默认执行 `:app:assembleDebug` 和 `:app:testDebugUnitTest`。提交前常用检查：

```powershell
./build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
```

脚本优先采用 `JAVA_HOME/bin/java.exe`，如果变量已设置但无效会立即报错。未设置时依次查找脚本中列出的 Android Studio JBR 路径，再查找 PATH。它将 `GRADLE_USER_HOME` 和 `ANDROID_USER_HOME` 指向当前仓库的 `.gradle-home/`、`.android/`，工作目录切换到仓库根并在结束时还原。该缓存与直接调用 Wrapper 的默认用户缓存可能不同。

`JAVA_HOME` 必须指向 JDK 根目录。需要临时指定 JDK 时，在当前 PowerShell 会话中按本机路径配置；不必改构建脚本：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./build.ps1
```

已有完整依赖缓存时可用离线模式；首次构建不能依靠离线模式下载缺失依赖：

```powershell
./build.ps1 -Offline
```

Linux / macOS 直接使用仓库 Wrapper。`sh` 调用也适用于未保留执行位的源码 ZIP：

```sh
sh ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

标准 Windows Wrapper 入口为 `./gradlew.bat`；它要求调用环境已配置 JDK/SDK，不执行 `build.ps1` 的 JBR 查找和缓存目录设置。仓库不需要 `.reference/`、`resource/`、`artifacts/` 等本地忽略目录才能构建。

## 运行与构建产物

选择 Android Studio 的 `app` 配置启动模拟器或专用测试设备；命令行可在确认目标设备后安装 Debug 包：

```powershell
adb devices
adb -s '<设备序列号>' install -r app/build/outputs/apk/debug/app-debug.apk
```

`<设备序列号>` 是待替换占位符。连接多台设备时必须指定目标。若设备上已有同包名但不同签名的版本，覆盖安装会失败；按 [排障指南](troubleshooting.md) 先备份资料，避免用卸载或清数据来试错。

| 产物 | 位置 |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/`，默认是未签名产物 |
| JVM HTML 报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint HTML 报告 | `app/build/reports/lint-results-debug.html` |
| 生成的完整许可说明 | `app/build/generated/openSourceAssets/open-source/NOTICE.txt` |
| Release R8 映射 | `app/build/outputs/mapping/release/mapping.txt` |

构建目录是生成文件，实际 APK 文件名以该目录的 `output-metadata.json` 为准。版本号统一来自 [version.properties](../version.properties)；应用包名为 `cc.novelia.app`，Debug 未设置包名后缀，不能与同包名正式版作为两个独立应用共存。

## 构建变体与参数

```powershell
# 不需要签名凭据的 Release 检查
./build.ps1 -Tasks @(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')

# 限定原生库 ABI 的本地构建示例
./build.ps1 -Tasks @(':app:assembleDebug', '-PtargetAbi=arm64-v8a')

# 单独重生成第三方声明
./build.ps1 -Tasks @(':app:generateOpenSourceNotices')
```

Release 开启 R8 压缩和资源收缩。`targetAbi` 只接受 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`；未传时不额外限制 ABI。`universal` 是发布附件脚本的参数值，不是 Gradle `targetAbi` 的合法值。

普通贡献和测试不需要原站账号或发布私钥。正式分发使用 `-PreleaseSigning=true` 及环境变量；本地测试的 `-PlocalReleaseSigning=true` 使用 Debug 签名，两者互斥。不要把测试签名包当正式发行，详细步骤只在 [RELEASING.md](../RELEASING.md) 维护。

## 常见工作入口

环境就绪后先阅读 [架构](architecture.md)，选择一项小改动，按 [开发流程](development.md) 修改，并用 [测试指南](testing.md) 选择检查范围。JDK、SDK、下载依赖、签名或安装问题见 [排障](troubleshooting.md)。
