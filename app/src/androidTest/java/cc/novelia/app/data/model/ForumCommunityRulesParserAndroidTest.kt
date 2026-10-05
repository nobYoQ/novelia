package cc.novelia.app.data.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 必须在 Android 的 ICU 正则实现上验证，JVM 测试无法发现初始化时的正则语法错误。 */
@RunWith(AndroidJUnit4::class)
class ForumCommunityRulesParserAndroidTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun deployedRulesInitializeAndParseOnAndroid() {
        assertEquals("/assets/index-deployed.js", ForumCommunityRulesParser.entryPath(
            """<script type="module" src="/assets/index-deployed.js"></script>"""))
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(sha, ForumCommunityRulesParser.commitSha("""const repository={commitSha:'$sha'};"""))
        val blocks = ForumCommunityRulesParser.parse(fixture("forum-community-rules-20261004.vue"))
        assertEquals(bundledForumCommunityRules.blocks, blocks)
        assertEquals(10, blocks.last().table!!.rows.size)
        assertEquals(6, ForumCommunityRulesParser.parse(fixture("forum-community-rules.vue")).size)
    }

    @Test fun changedPermissionValuesStillParseAndUnknownExpressionsAreRejected() {
        val source = fixture("forum-community-rules-20261004.vue")
        val changed = ForumCommunityRulesParser.parse(source.replace(
            "allowed: [true, true, false]", "allowed: [false, true, false]"))
        assertEquals(listOf(false, true, false), changed.last().table!!.rows[1].allowed)
        for (unsupported in listOf(
            source.replace("allowed: [true, true, true]", "allowed: [true, true, isAllowed()]"),
            source.replace("v-if=\"allowed\"", "v-if=\"!allowed\""),
            source.replace("</script>", "permissions.reverse();</script>")
        )) {
            assertNotEquals(source, unsupported)
            assertTrue(runCatching { ForumCommunityRulesParser.parse(unsupported) }.isFailure)
        }
    }
}
