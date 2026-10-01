package cc.novelia.app.data.network

import org.junit.Assert.*
import org.junit.Test

class EchForumProbeTest {
    @Test fun acceptsPublicApiShapesWithAdditionalBusinessFields() {
        assertEquals(1, EchForumProbe.categoryCount("""[{"id":1,"slug":"announcements","tags":[]}]"""))
        assertEquals(1, EchForumProbe.postCount("""{"total":1,"items":[{"id":9,"title":"公告","authorUsername":"fixture","content":""}]}"""))
    }

    @Test fun anEmptyValidResponseIsStillAWorkingConnection() {
        assertEquals(0, EchForumProbe.categoryCount("[]"))
        assertEquals(0, EchForumProbe.postCount("""{"total":0,"items":[]}"""))
    }

    @Test fun rejectsHtmlAndJsonErrorObjectsInsteadOfReportingSuccess() {
        for (body in listOf("<html>Unavailable</html>", """{"error":"unavailable"}""")) {
            assertTrue(runCatching { EchForumProbe.categoryCount(body) }.isFailure)
            assertTrue(runCatching { EchForumProbe.postCount(body) }.isFailure)
        }
    }
}
