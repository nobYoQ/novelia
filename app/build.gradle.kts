import java.security.KeyStore
import java.security.MessageDigest
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

// 本机沿用已有测试密钥；CI 从 Secret 恢复同一密钥，不随 Android 用户目录变化。
val previewKeystore = rootProject.file(
    providers.environmentVariable("NOVELIA_PREVIEW_KEYSTORE_PATH").orNull ?: ".android/debug.keystore"
)
if (localReleaseSigning) {
    require(previewKeystore.isFile) {
        "Missing shared preview keystore. Restore the existing key; do not generate a replacement."
    }
    val expected = rootProject.file("gradle/preview-signing.sha256").readText().trim()
    require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid preview certificate fingerprint." }
    val keystore = KeyStore.getInstance(previewKeystore, "android".toCharArray())
    require(keystore.isKeyEntry("androiddebugkey")) { "Preview signing key is missing." }
    val certificate = keystore.getCertificate("androiddebugkey")
    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(actual == expected) { "Preview certificate mismatch. Restore the original shared key." }
}

android {
    namespace = "cc.novelia.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "cc.novelia.app"
        buildConfigField("String", "MIRROR_ACCESS_TOKEN", "\"$mirrorAccessToken\"")
        // 滚动预览的版本名可能不变，记录公开构建身份以避免重复更新或回退。
        val previewRun = if(providers.environmentVariable("GITHUB_WORKFLOW").orNull == "Preview APK")
            providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toLongOrNull() ?: 0L else 0L
        val sourceCommit = providers.environmentVariable("GITHUB_SHA").orNull
            ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }.orEmpty()
        buildConfigField("long", "PREVIEW_RUN_NUMBER", "${previewRun}L")
        val previewAttempt = providers.environmentVariable("GITHUB_RUN_ATTEMPT").orNull?.toLongOrNull() ?: 1L
        buildConfigField("long", "PREVIEW_RUN_ATTEMPT", "${previewAttempt}L")
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
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
    signingConfigs.getByName("debug") {
        storeFile = previewKeystore
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
        debug {
            // 大书库设备测试安装为独立应用，避免启动或升级用户已有的阅读书库。
            if(providers.gradleProperty("isolatedLibraryTests").orNull == "true") applicationIdSuffix = ".librarytest"
        }
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
apply(from = rootProject.file("gradle/launcher-icons.gradle.kts"))

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/openSourceAssets"))
// JVM 和 Android 解析测试使用同一份公开守则样本。
android.sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
tasks.named("preBuild") { dependsOn("generateOpenSourceNotices") }

// 大书库测试及 Files.createTempDirectory 的夹具均留在项目内。
tasks.withType<Test>().configureEach {
    val testTemp = layout.buildDirectory.dir("tmp/$name/fixtures")
    systemProperty("java.io.tmpdir", testTemp.get().asFile.absolutePath)
    doFirst { testTemp.get().asFile.mkdirs() }
}
