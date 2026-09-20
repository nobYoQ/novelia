package cc.novelia.app.data.compat

import android.content.Context
import androidx.work.WorkerParameters
import cc.novelia.app.data.sync.CloudSyncWorker
import cc.novelia.app.data.updates.UpdateWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWorkerCompatibilityTest {
    @Test fun legacyCloudSyncWorkerRetainsItsImplementationAndReflectionConstructor() {
        val legacyClass = Class.forName("cc.novelia.app.data.CloudSyncWorker")

        assertTrue(CloudSyncWorker::class.java.isAssignableFrom(legacyClass))
        val constructor = legacyClass.getConstructor(Context::class.java, WorkerParameters::class.java)
        assertEquals(legacyClass, constructor.declaringClass)
    }

    @Test fun legacyUpdateWorkerRetainsItsImplementationAndReflectionConstructor() {
        val legacyClass = Class.forName("cc.novelia.app.data.UpdateWorker")

        assertTrue(UpdateWorker::class.java.isAssignableFrom(legacyClass))
        val constructor = legacyClass.getConstructor(Context::class.java, WorkerParameters::class.java)
        assertEquals(legacyClass, constructor.declaringClass)
    }
}
