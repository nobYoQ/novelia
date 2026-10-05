import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonSlurper
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("androidx.baselineprofile")
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
// 镜像入口口令由打包环境注入，仓库和构建日志不包含值；使用者无需手动配置。
val mirrorProperties = Properties().apply {
    val config = rootProject.file(".env.mirror")
    if(config.isFile) config.inputStream().use { load(it) }
}
val mirrorAccessToken = providers.environmentVariable("NOVELIA_MIRROR_ACCESS_TOKEN").orNull
    ?: mirrorProperties.getProperty("NOVELIA_MIRROR_ACCESS_TOKEN", "")
require(mirrorAccessToken.length <= 4096 && mirrorAccessToken.all { it.code in 0x21..0x7e && it !in "\";,\\" }) {
    "Invalid mirror access token format."
}
// 正式发行证书与本地测试签名互斥；两者都关闭时生成未签名 Release。
val releaseSigning = providers.gradleProperty("releaseSigning").orNull == "true"
val localReleaseSigning = providers.gradleProperty("localReleaseSigning").orNull == "true"
require(!(releaseSigning && localReleaseSigning)) { "Choose releaseSigning or localReleaseSigning, not both." }
fun signingEnvironment(name: String): String = providers.environmentVariable(name).orNull
    ?.takeIf { it.isNotBlank() } ?: error("Missing signing environment variable: $name")

android {
    namespace = "cc.novelia.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "cc.novelia.app"
        buildConfigField("String", "MIRROR_ACCESS_TOKEN", "\"$mirrorAccessToken\"")
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt().also { require(it > 0) }
        versionName = appVersion.getProperty("versionName").also {
            require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) { "Invalid versionName" }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        providers.gradleProperty("targetAbi").orNull?.let { requestedAbi ->
            require(requestedAbi in setOf("universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")) { "Unsupported targetAbi" }
            if (requestedAbi != "universal") {
                ndk { abiFilters += requestedAbi }
            }
        }
    }
    if (releaseSigning) {
        signingConfigs.create("distribution") {
            storeFile = file(signingEnvironment("NOVELIA_KEYSTORE_PATH")).also {
                require(it.isFile) { "Release keystore does not exist." }
            }
            storePassword = signingEnvironment("NOVELIA_KEYSTORE_PASSWORD")
            keyAlias = signingEnvironment("NOVELIA_KEY_ALIAS")
            keyPassword = signingEnvironment("NOVELIA_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigning) {
                signingConfig = signingConfigs.getByName("distribution")
            } else if (localReleaseSigning) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    baselineProfile(project(":benchmark"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.9.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.ibm.icu:icu4j:76.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

baselineProfile { automaticGenerationDuringBuild = false }

extra["echAndroidSdkDirectory"] = androidComponents.sdkComponents.sdkDirectory
apply(from = rootProject.file("gradle/ech-native.gradle.kts"))
apply(from = rootProject.file("gradle/open-source-notices.gradle.kts"))
data class BundledLauncherIcon(val id: String, val title: String, val drawable: String, val hidden: Boolean) {
    // Android 持久化启动主题的资源名，必须和已发布的图标 id 一样保持稳定。
    val splashTheme get() = "Theme.Novelia.Launcher.$id"
}

object LauncherIconCatalog {
    fun read(catalog: File, iconResources: File): List<BundledLauncherIcon> {
        val entries = JsonSlurper().parse(catalog, "UTF-8") as? List<*>
            ?: error("launcher-icons/icons.json must contain an array.")
        val icons = entries.map { value ->
            val row = value as? Map<*, *> ?: error("Each launcher icon must be an object.")
            val id = row["id"] as? String ?: error("Missing launcher icon id.")
            val title = row["title"] as? String ?: error("Missing launcher icon title: $id")
            val drawable = row["drawable"] as? String ?: error("Missing launcher icon drawable: $id")
            require(id.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid launcher icon id: $id" }
            require(drawable.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid launcher drawable: $drawable" }
            require(title.isNotBlank()) { "Empty launcher icon title: $id" }
            require(row["hidden"] == null || row["hidden"] is Boolean) { "hidden must be a boolean: $id" }
            if (id == "default") {
                require(drawable == "ic_launcher" && row["hidden"] != true) { "Keep the default icon visible with ic_launcher." }
            } else {
                val files = iconResources.resolve("drawable-nodpi").listFiles().orEmpty()
                    .filter { it.nameWithoutExtension == drawable && it.extension in setOf("png", "webp", "xml") }
                require(files.size == 1) { "Provide one PNG, WebP or drawable XML in launcher-icons/res/drawable-nodpi for $drawable." }
            }
            BundledLauncherIcon(id, title, drawable, row["hidden"] == true)
        }
        require(icons.count { it.id == "default" } == 1) { "Keep exactly one default launcher icon." }
        require(icons.map { it.id }.distinct().size == icons.size) { "Launcher icon IDs must be unique." }
        return icons
    }
}

/** 给每个入口生成可由系统持久化的 Android 12 启动主题。 */
abstract class LauncherSplashResourcesTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iconResources: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val icons = LauncherIconCatalog.read(catalog.get().asFile, iconResources.get().asFile)
        val output = outputDirectory.file("values-v31/launcher_splash_themes.xml").get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<resources>")
            icons.forEach { icon ->
                appendLine("    <style name=\"${icon.splashTheme}\" parent=\"Theme.Novelia\">")
                appendLine("        <item name=\"android:windowSplashScreenAnimatedIcon\">@drawable/${icon.drawable}</item>")
                appendLine("        <item name=\"android:windowSplashScreenBackground\">#F7FAF5</item>")
                appendLine("    </style>")
            }
            appendLine("</resources>")
        }, Charsets.UTF_8)
    }
}

/** 在合并后的 Manifest 登记图标，不改写源码；Debug、Release 和基准构建共用一份清单。 */
abstract class LauncherIconManifestTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputManifest: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iconResources: DirectoryProperty

    @get:OutputFile abstract val outputManifest: RegularFileProperty

    @TaskAction fun generate() {
        val icons = LauncherIconCatalog.read(catalog.get().asFile, iconResources.get().asFile)

        val androidNs = "http://schemas.android.com/apk/res/android"
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val manifest = builder.parse(inputManifest.get().asFile)
        val app = manifest.getElementsByTagName("application").item(0) as Element
        val target = "cc.novelia.app.launcher.LauncherEntryActivity"
        require((0 until app.childNodes.length).any {
            val node = app.childNodes.item(it)
            node is Element && node.tagName == "activity" && node.getAttributeNS(androidNs, "name") == target
        }) { "LauncherEntryActivity must precede its launcher aliases." }
        fun Element.attribute(name: String, value: String) = setAttributeNS(androidNs, "android:$name", value)
        fun Element.child(tag: String, attributes: Map<String, String>): Element =
            (appendChild(manifest.createElement(tag)) as Element).also { node ->
                attributes.forEach { (key, value) -> node.attribute(key, value) }
            }
        icons.forEach { icon ->
            val alias = app.child("activity-alias", mapOf(
                "name" to "cc.novelia.app.launcher.Icon_${icon.id}",
                "targetActivity" to target,
                "enabled" to (icon.id == "default").toString(),
                "exported" to "true",
                "icon" to "@drawable/${icon.drawable}"
            ))
            alias.child("intent-filter", emptyMap()).apply {
                child("action", mapOf("name" to "android.intent.action.MAIN"))
                child("category", mapOf("name" to "android.intent.category.LAUNCHER"))
            }
            // 前缀保证 true/false 等合法 ID 也以字符串保存，不被资源编译器解释成布尔值。
            alias.child("meta-data", mapOf("name" to "novelia.launcher.id", "value" to "icon:${icon.id}"))
            alias.child("meta-data", mapOf("name" to "novelia.launcher.title", "value" to "title:${icon.title}"))
            alias.child("meta-data", mapOf("name" to "novelia.launcher.hidden", "value" to icon.hidden.toString()))
            alias.child("meta-data", mapOf("name" to "novelia.launcher.splashTheme", "resource" to "@style/${icon.splashTheme}"))
        }
        val output = outputManifest.get().asFile.apply { parentFile.mkdirs() }
        TransformerFactory.newInstance().apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            .newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
            .transform(DOMSource(manifest), StreamResult(output))
    }

}

extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
    val splashResources = tasks.register<LauncherSplashResourcesTask>("generate${variantName}LauncherSplashResources") {
        catalog.set(layout.projectDirectory.file("launcher-icons/icons.json"))
        iconResources.set(layout.projectDirectory.dir("launcher-icons/res"))
        outputDirectory.set(layout.buildDirectory.dir("generated/launcherSplash/${variant.name}/res"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(splashResources, LauncherSplashResourcesTask::outputDirectory)
    val generate = tasks.register<LauncherIconManifestTask>("generate${variantName}LauncherIcons") {
        catalog.set(layout.projectDirectory.file("launcher-icons/icons.json"))
        iconResources.set(layout.projectDirectory.dir("launcher-icons/res"))
    }
    variant.artifacts.use(generate)
        .wiredWithFiles(LauncherIconManifestTask::inputManifest, LauncherIconManifestTask::outputManifest)
        .toTransform(SingleArtifact.MERGED_MANIFEST)
}
android.sourceSets.getByName("main").res.srcDir("launcher-icons/res")
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/openSourceAssets"))
// JVM 和 Android 解析测试使用同一份公开守则样本。
android.sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
tasks.named("preBuild") { dependsOn("generateOpenSourceNotices") }
