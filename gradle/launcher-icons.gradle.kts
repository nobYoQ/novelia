import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonSlurper
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

// Applied scripts have their own classpath. Reuse the root plugin versions and resolved artifacts.
buildscript {
    dependencies {
        classpath(files(rootProject.buildscript.configurations.getByName("classpath")))
    }
}

data class BundledLauncherIcon(val id: String, val title: String, val drawable: String, val hidden: Boolean) {
    // Android 持久化启动主题的资源名，必须和已发布的图标 id 一样保持稳定。
    val splashTheme get() = "Theme.Novelia.Launcher.$id"
}

object LauncherIconCatalog {
    val splashVariants = mapOf("" to "Theme.Novelia", "Light" to "Theme.Novelia.Light",
        "Dark" to "Theme.Novelia.Dark", "Amoled" to "Theme.Novelia.Amoled", "SystemAmoled" to "Theme.Novelia.SystemAmoled",
        "Monochrome" to "Theme.Novelia.Monochrome")

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
                LauncherIconCatalog.splashVariants.forEach { (variant, parent) ->
                    val name = icon.splashTheme + if(variant.isEmpty()) "" else ".$variant"
                    appendLine("    <style name=\"$name\" parent=\"$parent\">")
                    appendLine("        <item name=\"android:windowSplashScreenAnimatedIcon\">@drawable/${icon.drawable}</item>")
                    appendLine("        <item name=\"android:windowSplashScreenBackground\">?android:colorBackground</item>")
                    appendLine("        <item name=\"android:windowLightNavigationBar\">?android:windowLightStatusBar</item>")
                    appendLine("    </style>")
                }
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
            // 显式资源引用也保护各配色变体，避免 Release 资源收缩删除下次启动使用的主题。
            LauncherIconCatalog.splashVariants.keys.forEach { variant ->
                val suffix = if(variant.isEmpty()) "" else ".$variant"
                alias.child("meta-data", mapOf("name" to "novelia.launcher.splashTheme$suffix", "resource" to "@style/${icon.splashTheme}$suffix"))
            }
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
extensions.configure<ApplicationExtension> {
    sourceSets.getByName("main").res.srcDir("launcher-icons/res")
}
