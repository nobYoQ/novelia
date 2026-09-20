package cc.novelia.app.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetiredModelsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun upgradeReclaimsModelFilesAndLeavesOtherDataUntouched() {
        val root = temporary.newFolder("no-backup")
        val model = File(root, "ocr-models/pack-example/rec/inference.onnx")
        model.parentFile!!.mkdirs()
        model.writeText("old model")
        File(root, "ocr-models/active").writeText("pack-example")
        val unrelated = File(root, "library-draft.txt").apply { writeText("保留我的校对文字", Charsets.UTF_8) }
        removeRetiredModels(root)
        assertFalse(File(root, "ocr-models").exists())
        assertEquals("保留我的校对文字", unrelated.readText(Charsets.UTF_8))
        assertTrue(root.isDirectory)
    }

    @Test fun repeatedCleanupAndFreshInstallsDoNotCreateOrRemoveOtherDirectories() {
        val root = temporary.newFolder("fresh")
        repeat(2) { removeRetiredModels(root) }
        assertTrue(root.isDirectory)
        assertEquals(0, root.listFiles()!!.size)
        val absent = File(root, "absent")
        removeRetiredModels(absent)
        assertFalse(absent.exists())
    }
}
