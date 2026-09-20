import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// Use the actual release graph, including transitive libraries; no account or new plugin is required.
val runtimeLibraries = providers.provider { configurations.getByName("releaseRuntimeClasspath") }
val noticeAssets = layout.buildDirectory.dir("generated/openSourceAssets/open-source")
tasks.register("generateOpenSourceNotices") {
    group = "documentation"
    description = "Bundle project license, full license texts, resolved dependencies and upstream notices."
    inputs.files(runtimeLibraries)
    inputs.files(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
    inputs.dir(rootProject.file("licenses"))
    outputs.dir(noticeAssets)
    doLast {
        val output = noticeAssets.get().asFile.apply { mkdirs() }
        val components = runtimeLibraries.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
        val report = StringBuilder("Novelia — 开源许可证与第三方声明\n\n")
        report.append(rootProject.file("NOTICE.md").readText()).append("\n\n")
        report.append("=== 项目许可证：GPL-3.0-only ===\n\n")
            .append(rootProject.file("LICENSE").readText()).append("\n\n")
        rootProject.file("licenses").listFiles()!!.filter { it.extension == "txt" }.sortedBy { it.name }.forEach {
            report.append("=== ").append(it.name).append(" ===\n\n").append(it.readText()).append("\n\n")
        }
        report.append("=== Release 依赖清单（包括传递依赖；R8 可能移除未使用代码） ===\n\n")
        components.sortedBy { it.displayName }.forEach { component ->
            report.append(component.displayName).append('\n')
        }
        report.append('\n')
        val noticeName = Regex("(?i)(license|notice|copying|copyright)([._-].*)?")
        runtimeLibraries.get().resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() + it.name }.forEach { artifact ->
            if (artifact.file.extension in setOf("aar", "jar")) ZipFile(artifact.file).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.forEach { entry ->
                    if (noticeName.matches(entry.name.substringAfterLast('/'))) {
                        report.append("=== ${artifact.moduleVersion.id} / ${entry.name} ===\n\n")
                            .append(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }).append("\n\n")
                    } else if (entry.name == "classes.jar" || (entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))) {
                        ZipInputStream(zip.getInputStream(entry)).use { nested ->
                            var child = nested.nextEntry
                            while (child != null) {
                                if (!child.isDirectory && noticeName.matches(child.name.substringAfterLast('/'))) {
                                    report.append("=== ${artifact.moduleVersion.id} / ${entry.name} / ${child.name} ===\n\n")
                                        .append(nested.readBytes().toString(Charsets.UTF_8)).append("\n\n")
                                }
                                child = nested.nextEntry
                            }
                        }
                    }
                }
            }
        }
        output.resolve("NOTICE.txt").writeText(report.toString().replace("\r\n", "\n"), Charsets.UTF_8)
    }
}
