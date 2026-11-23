package xyz.hidasy.pebblepinmanager.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreAppSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `defaults to watch mode and persists runWithoutWatch toggle`() = runTest {
        val file = File(tempFolder.newFolder(), "app_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreAppSettingsRepository(dataStore = dataStore)

        assertFalse(repo.getSettings().runWithoutWatch)
        assertFalse(repo.observeSettings().first().runWithoutWatch)

        repo.setRunWithoutWatch(true)
        assertTrue(repo.getSettings().runWithoutWatch)
        assertTrue(repo.observeSettings().first().runWithoutWatch)

        repo.setRunWithoutWatch(false)
        assertFalse(repo.getSettings().runWithoutWatch)
    }
}
