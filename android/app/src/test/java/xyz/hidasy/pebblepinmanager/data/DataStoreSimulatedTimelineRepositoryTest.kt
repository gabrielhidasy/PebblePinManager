package xyz.hidasy.pebblepinmanager.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.hidasy.pebblepinmanager.domain.EventSource
import java.io.File

class DataStoreSimulatedTimelineRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `inserts, updates in place, sorts chronologically, deletes, and logs actions`() = runTest {
        val file = File(tempFolder.newFolder(), "sim_timeline.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreSimulatedTimelineRepository(dataStore = dataStore)

        val laterPin = SimulatedTimelinePin(
            id = "ppm-later",
            watchappUuid = "e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042",
            title = "Evening Dinner",
            startTimeMillis = 2_000_000L,
            payloadJson = """{"id":"ppm-later"}""",
            createdAtMillis = 100L,
            updatedAtMillis = 100L,
        )
        val earlierPin = SimulatedTimelinePin(
            id = "ppm-earlier",
            watchappUuid = "e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042",
            title = "Morning Standup",
            startTimeMillis = 1_000_000L,
            source = EventSource.NOTIFICATION.name,
            sourceKey = "0|com.calendar|1",
            payloadJson = """{"id":"ppm-earlier"}""",
            createdAtMillis = 200L,
            updatedAtMillis = 200L,
        )

        assertEquals(SimulatedTimelineActionType.INSERT, repo.recordUpsert(laterPin, timestampMillis = 100L))
        assertEquals(SimulatedTimelineActionType.INSERT, repo.recordUpsert(earlierPin, timestampMillis = 200L))

        val afterInserts = repo.observeSnapshot().first()
        assertEquals(listOf("ppm-earlier", "ppm-later"), afterInserts.activePins.map { it.id })
        assertEquals(2, afterInserts.actionLog.size)
        assertEquals(SimulatedTimelineActionType.INSERT, afterInserts.actionLog[0].type)
        assertEquals("ppm-earlier", afterInserts.actionLog[0].pinId)

        // Update earlierPin in place: createdAtMillis should be preserved while title & updatedAtMillis change
        val updateType = repo.recordUpsert(
            earlierPin.copy(title = "Morning Standup (Moved)", updatedAtMillis = 300L),
            timestampMillis = 300L,
        )
        assertEquals(SimulatedTimelineActionType.UPDATE, updateType)
        val updatedPin = repo.getSnapshot().activePins.first { it.id == "ppm-earlier" }
        assertEquals("Morning Standup (Moved)", updatedPin.title)
        assertEquals(200L, updatedPin.createdAtMillis)
        assertEquals(300L, updatedPin.updatedAtMillis)

        // Delete existing pin
        val existed = repo.recordDelete(
            pinId = "ppm-earlier",
            watchappUuid = "e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042",
            timestampMillis = 400L,
        )
        assertTrue(existed)
        val afterDelete = repo.getSnapshot()
        assertEquals(listOf("ppm-later"), afterDelete.activePins.map { it.id })
        assertEquals(SimulatedTimelineActionType.DELETE, afterDelete.actionLog.first().type)
        assertEquals("Morning Standup (Moved)", afterDelete.actionLog.first().title)

        // Deleting missing pin returns false and still records DELETE in log
        val existedAgain = repo.recordDelete(
            pinId = "ppm-missing",
            watchappUuid = "e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042",
            timestampMillis = 500L,
        )
        assertFalse(existedAgain)
        assertEquals("ppm-missing", repo.getSnapshot().actionLog.first().pinId)

        // Clear action log keeps active pins
        repo.clearActionLog()
        assertEquals(1, repo.getSnapshot().activePins.size)
        assertTrue(repo.getSnapshot().actionLog.isEmpty())

        // Clear all returns cleared active pin IDs and empties everything
        val clearedIds = repo.clearAll()
        assertEquals(listOf("ppm-later"), clearedIds)
        assertTrue(repo.getSnapshot().activePins.isEmpty())
        assertTrue(repo.getSnapshot().actionLog.isEmpty())
    }
}
