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
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/openSourceAssets"))
tasks.named("preBuild") { dependsOn("generateOpenSourceNotices") }
