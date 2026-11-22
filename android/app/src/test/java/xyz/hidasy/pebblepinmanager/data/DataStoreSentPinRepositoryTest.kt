package xyz.hidasy.pebblepinmanager.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSentPinRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists, updates in place, queries by sourceKey, and deletes sent pins`() = runTest {
        val file = File(tempFolder.newFolder(), "test_pins.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreSentPinRepository(dataStore = dataStore, maxStoredPins = 3)

        val first = SentPinRecord(
            id = "ppm-1",
            title = "First Event",
            description = "Notes",
            startTimeMillis = 1_700_000_000_000L,
            sentAtMillis = 100L,
        )
        val second = SentPinRecord(
            id = "ppm-2",
            title = "Second Event",
            startTimeMillis = 1_700_000_500_000L,
            sourceKey = "notif-key-42",
            sentAtMillis = 200L,
        )

        repo.saveSentPin(first)
        repo.saveSentPin(second)

        val list = repo.observeSentPins().first()
        assertEquals(listOf("ppm-2", "ppm-1"), list.map { it.id })
        assertEquals("ppm-2", repo.getPinBySourceKey("notif-key-42")?.id)

        // Update ppm-1 in place (supports #4 edit flow)
        repo.saveSentPin(first.copy(title = "Updated First", sentAtMillis = 300L))
        val updated = repo.observeSentPins().first()
        assertEquals(listOf("ppm-1", "ppm-2"), updated.map { it.id })
        assertEquals("Updated First", repo.getPinById("ppm-1")?.title)

        // Delete ppm-2
        repo.deletePinRecord("ppm-2")
        assertNull(repo.getPinById("ppm-2"))
        assertEquals(1, repo.observeSentPins().first().size)
    }

    @Test
    fun `round-trips custom iconUri, durationMinutes, and backgroundColor through DataStore`() = runTest {
        val file = File(tempFolder.newFolder(), "appearance_pins.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreSentPinRepository(dataStore = dataStore)

        val record = SentPinRecord(
            id = "ppm-styled",
            title = "Album Listening Party",
            description = "Living room speakers",
            startTimeMillis = 1_700_000_000_000L,
            durationMinutes = 75,
            iconUri = "system://images/AUDIO_CASSETTE",
            backgroundColor = "#AA55FF",
            sentAtMillis = 500L,
        )

        repo.saveSentPin(record)

        val restored = repo.getPinById("ppm-styled")
        assertEquals("system://images/AUDIO_CASSETTE", restored?.iconUri)
        assertEquals(75, restored?.durationMinutes)
        assertEquals("#AA55FF", restored?.backgroundColor)

        val event = restored!!.toTimelineEvent()
        assertEquals("system://images/AUDIO_CASSETTE", event.iconUri)
        assertEquals(75, event.durationMinutes)
        assertEquals("#AA55FF", event.backgroundColor)
    }
}
