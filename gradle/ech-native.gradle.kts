import java.io.File
import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

// AAR generation belongs to the Gradle dependency graph, including IDE builds.
val echVersionsFile = rootProject.file("gradle/ech-native.properties")
val echVersions = Properties().apply { echVersionsFile.inputStream().use { load(it) } }
val echGoVersion = echVersions.getProperty("goVersion")
val echMobileVersion = echVersions.getProperty("mobileVersion")
val echNdkVersion = echVersions.getProperty("ndkVersion")
val echAndroidApi = echVersions.getProperty("androidApi")
val echRoot = rootProject.file("native/ech")
val echTools = rootProject.file("outputs/ech-tools")
val echWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val echExeSuffix = if (echWindows) ".exe" else ""
fun echHostEnvironment(name: String): String? = System.getenv().entries
    .firstOrNull { it.key.equals(name, ignoreCase = echWindows) }?.value
val echExplicitGoHome = providers.gradleProperty("echGoHome")
    .orElse(providers.environmentVariable("NOVELIA_GO_HOME")).orNull
val echGo = providers.provider {
    val localGo = echTools.resolve("go/bin/go$echExeSuffix")
    when {
        echExplicitGoHome != null -> rootProject.file(echExplicitGoHome).resolve("bin/go$echExeSuffix")
        localGo.isFile -> localGo
        else -> echHostEnvironment("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "go$echExeSuffix") }.firstOrNull { it.isFile } ?: localGo
    }
}
@Suppress("UNCHECKED_CAST")
val echSdk = (extra["echAndroidSdkDirectory"] as Provider<Directory>).map { it.asFile }
val echNdk = echSdk.map { it.resolve("ndk/$echNdkVersion") }
val echSources = fileTree(echRoot) {
    include("**/*.go", "go.mod", "go.sum")
    exclude("build/**")
}
val echScript = rootProject.file("gradle/ech-native.gradle.kts")
val echBootstrap = rootProject.file("scripts/bootstrap-ech-go.ps1")
val echAar = echRoot.resolve("build/novelia-ech.aar")
val echSourcesJar = echRoot.resolve("build/novelia-ech-sources.jar")
val echJavaHome = File(System.getProperty("java.home"))
val echOffline = gradle.startParameter.isOffline

fun runEchCommand(command: List<String>, environment: Map<String, String> = emptyMap()): String {
    val process = ProcessBuilder(command).directory(echRoot).redirectErrorStream(true).apply {
        val childEnvironment = environment()
        // ProcessBuilder's map is case-sensitive even on Windows. Avoid duplicate Path/PATH
        // entries: Go's subprocess lookup may otherwise read the inherited, unmodified value.
        val overriddenNames = environment.keys + setOf("GOROOT", "GOBIN")
        childEnvironment.keys.filter { existing ->
            overriddenNames.any { it.equals(existing, ignoreCase = echWindows) }
        }.forEach(childEnvironment::remove)
        childEnvironment.putAll(environment)
    }.start()
    val output = StringBuilder()
    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEach { line -> logger.lifecycle(line); output.append(line).append('\n') }
    }
    check(process.waitFor() == 0) {
        "ECH command failed: ${command.first()}. ${if (echOffline) "Offline mode is enabled; populate the Go/module cache with an online build first." else "See the command output above."}"
    }
    return output.toString()
}

fun echEnvironment(): Map<String, String> {
    val go = echGo.get()
    check(go.isFile) {
        "ECH requires Go $echGoVersion. Install that version and set -PechGoHome=<Go directory> or NOVELIA_GO_HOME; Windows can also use scripts/build-ech.ps1."
    }
    val version = runEchCommand(listOf(go.absolutePath, "version"), mapOf(
        "GOTOOLCHAIN" to "local", "GOPROXY" to "off", "GOSUMDB" to "off"
    )).trim()
    check(version.startsWith("go version go$echGoVersion ")) {
        "ECH requires Go $echGoVersion, found: $version. Set -PechGoHome=<Go $echGoVersion directory>."
    }
    val module = echRoot.resolve("go.mod").readText()
    check(Regex("(?m)^go\\s+${Regex.escape(echGoVersion)}\\s*$").containsMatchIn(module)) {
        "ECH go.mod and gradle/ech-native.properties must pin the same Go version."
    }
    check(Regex("(?m)^\\s*golang.org/x/mobile\\s+${Regex.escape(echMobileVersion)}(?:\\s|$)").containsMatchIn(module)) {
        "ECH go.mod and gradle/ech-native.properties must pin the same gomobile version."
    }
    return mapOf(
        "GOPATH" to echTools.resolve("gopath").absolutePath,
        "GOCACHE" to echTools.resolve("cache").absolutePath,
        "GOMODCACHE" to echTools.resolve("modules").absolutePath,
        "GOTOOLCHAIN" to "local",
        "GOFLAGS" to "-mod=readonly",
        "GOPROXY" to if (echOffline) "off" else "https://proxy.golang.org",
        "GOSUMDB" to if (echOffline) "off" else "sum.golang.org",
        "JAVA_HOME" to echJavaHome.absolutePath,
        "PATH" to listOf(go.parent, echTools.resolve("gopath/bin").absolutePath,
            echJavaHome.resolve("bin").absolutePath, echHostEnvironment("PATH").orEmpty()).joinToString(File.pathSeparator)
    )
}

val prepareEchGo = tasks.register("prepareEchGo") {
    group = "build setup"
    description = "Locate pinned Go, or bootstrap its verified Windows archive when missing."
    onlyIf { !echGo.get().isFile }
    doLast {
        check(echWindows && echExplicitGoHome == null) {
            "ECH requires Go $echGoVersion. Install it and set -PechGoHome=<Go directory> or NOVELIA_GO_HOME."
        }
        val pwsh = File(echHostEnvironment("ProgramFiles").orEmpty(), "PowerShell/7/pwsh.exe")
        check(pwsh.isFile) { "Install Go $echGoVersion or PowerShell 7 to bootstrap the Windows ECH toolchain." }
        runEchCommand(listOf(pwsh.absolutePath, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", echBootstrap.absolutePath) +
            if (echOffline) listOf("-Offline") else emptyList())
    }
}

fun org.gradle.api.Task.echInputs() {
    dependsOn(prepareEchGo)
    inputs.files(echVersionsFile, echScript, echBootstrap).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(providers.provider { listOf(echGo.get()).filter { it.isFile } })
        .withPropertyName("goExecutable").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("goPath", echGo.map { it.absolutePath })
    inputs.property("host", "${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
    inputs.property("javaVersion", System.getProperty("java.version"))
    inputs.property("javaVendor", System.getProperty("java.vendor"))
}

val testEchNative = tasks.register("testEchNative") {
    group = "verification"
    description = "Run native ECH unit tests (live-network tests remain opt-in)."
    echInputs()
    inputs.files(echSources).withPathSensitivity(PathSensitivity.RELATIVE)
    val result = echRoot.resolve("build/tests-passed.txt")
    outputs.file(result)
    doLast {
        runEchCommand(listOf(echGo.get().absolutePath, "test", "./..."), echEnvironment())
        result.parentFile.mkdirs()
        result.writeText("Go $echGoVersion unit tests passed\n", Charsets.UTF_8)
    }
}

val installEchMobile = tasks.register("installEchMobile") {
    group = "build setup"
    description = "Build the gomobile/gobind versions locked in native/ech/go.mod."
    echInputs()
    inputs.files(echRoot.resolve("go.mod"), echRoot.resolve("go.sum")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.files(echTools.resolve("gopath/bin/gomobile$echExeSuffix"), echTools.resolve("gopath/bin/gobind$echExeSuffix"))
    doLast {
        runEchCommand(listOf(echGo.get().absolutePath, "install", "golang.org/x/mobile/cmd/gomobile", "golang.org/x/mobile/cmd/gobind"), echEnvironment())
    }
}

val buildEchNative = tasks.register("buildEchNative") {
    group = "build"
    description = "Build the ECH Android AAR for all supported ABIs with the pinned NDK."
    echInputs()
    dependsOn(testEchNative, installEchMobile)
    inputs.files(echSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(installEchMobile).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("sdkPath", echSdk.map { it.absolutePath })
    inputs.files(echNdk.map { it.resolve("source.properties") }).optional().withPathSensitivity(PathSensitivity.NONE)
    outputs.files(echAar, echSourcesJar)
    doLast {
        val ndk = echNdk.get()
        check(ndk.resolve("source.properties").isFile) {
            "ECH requires Android NDK $echNdkVersion. Install it from Android Studio SDK Manager or sdkmanager \"ndk;$echNdkVersion\"."
        }
        val revision = Properties().apply { ndk.resolve("source.properties").inputStream().use { load(it) } }.getProperty("Pkg.Revision")
        check(revision == echNdkVersion) { "Expected Android NDK $echNdkVersion, found $revision." }
        val environment = echEnvironment() + mapOf("ANDROID_HOME" to echSdk.get().absolutePath, "ANDROID_NDK_HOME" to ndk.absolutePath)
        echAar.parentFile.mkdirs()
        runEchCommand(listOf(echTools.resolve("gopath/bin/gomobile$echExeSuffix").absolutePath,
            "bind", "-target=android", "-androidapi=$echAndroidApi", "-javapkg=cc.novelia.nativeech",
            "-ldflags=-s -w", "-o", echAar.absolutePath, "."), environment)
    }
}

dependencies.add("implementation", files(echAar).builtBy(buildEchNative))
tasks.named("check") { dependsOn(testEchNative) }
tasks.named<Delete>("clean") { delete(echRoot.resolve("build")) }
