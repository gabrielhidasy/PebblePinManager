package xyz.hidasy.pebblepinmanager.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreRawNotificationRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists raw notifications newest-first, deduplicates identical snapshots, caps size, and clears`() = runTest {
        val file = File(tempFolder.newFolder(), "raw_notifs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreRawNotificationRepository(
            dataStore = dataStore,
            maxStoredRecords = 3,
        )

        assertTrue(repo.getNotifications().isEmpty())

        val first = RawNotificationRecord(
            id = "raw-1",
            capturedAtMillis = 1_000L,
            sourcePackage = "com.google.android.gm",
            appLabel = "Gmail",
            notificationKey = "0|com.google.android.gm|1|null|1000",
            title = "Delta Air Lines",
            text = "Flight DL123 departs 16:30",
            bigText = "Flight DL123 departs 16:30\nGate B12",
            eventTimeMillis = 1_000L,
            postTimeMillis = 1_000L,
            pipelineOutcome = "Ignored",
        )
        val second = first.copy(
            id = "raw-2",
            capturedAtMillis = 2_000L,
            title = "Amazon",
            text = "Package out for delivery",
            bigText = null,
            notificationKey = "0|com.amazon|2|null|1000",
            sourcePackage = "com.amazon",
            appLabel = "Amazon",
            postTimeMillis = 2_000L,
        )

        repo.recordNotification(first)
        repo.recordNotification(second)

        val stored = repo.observeNotifications().first()
        assertEquals(listOf("raw-2", "raw-1"), stored.map { it.id })

        // Re-snapshotting identical payload refreshes timestamp/outcome instead of creating duplicates
        val duplicateSnapshot = first.copy(
            id = "raw-1-snap",
            capturedAtMillis = 2_500L,
            pipelineOutcome = "Active snapshot",
        )
        repo.recordAll(listOf(duplicateSnapshot))
        val afterDedupe = repo.getNotifications()
        assertEquals(2, afterDedupe.size)
        assertEquals("raw-1-snap", afterDedupe.first().id)
        assertEquals("Active snapshot", afterDedupe.first().pipelineOutcome)

        // Cap enforcement keeps only the 3 newest distinct notifications
        val third = second.copy(
            id = "raw-3",
            capturedAtMillis = 3_000L,
            title = "Calendar Reminder",
            postTimeMillis = 3_000L,
        )
        val fourth = second.copy(
            id = "raw-4",
            capturedAtMillis = 4_000L,
            title = "Gate Change",
            postTimeMillis = 4_000L,
        )
        repo.recordAll(listOf(third, fourth))

        val capped = repo.getNotifications()
        assertEquals(listOf("raw-4", "raw-3", "raw-1-snap"), capped.map { it.id })

        repo.deleteNotification("raw-3")
        assertEquals(listOf("raw-4", "raw-1-snap"), repo.getNotifications().map { it.id })

        repo.clearAll()
        assertTrue(repo.getNotifications().isEmpty())
    }
}
