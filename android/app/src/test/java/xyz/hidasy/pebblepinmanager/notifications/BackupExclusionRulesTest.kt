package xyz.hidasy.pebblepinmanager.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupExclusionRulesTest {
    private val expectedExcludedPath = "datastore/${DataStoreRawNotificationRepository.STORE_NAME}.preferences_pb"

    @Test
    fun `manifest wires dataExtractionRules and fullBackupContent while keeping allowBackup enabled`() {
        val manifest = parseXml("src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element

        assertEquals("true", application.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
    }

    @Test
    fun `data_extraction_rules excludes raw notification dev log from cloud backup and device transfer`() {
        val doc = parseXml("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", doc.documentElement.tagName)

        // Do not introduce <include> tags so sent_timeline_pins and user preferences stay backed up by default.
        assertEquals(0, doc.getElementsByTagName("include").length)

        val cloudBackup = doc.getElementsByTagName("cloud-backup").item(0) as Element
        assertExcludesFile(cloudBackup, expectedExcludedPath)

        val deviceTransfer = doc.getElementsByTagName("device-transfer").item(0) as Element
        assertExcludesFile(deviceTransfer, expectedExcludedPath)
    }

    @Test
    fun `backup_rules excludes raw notification dev log on Android 8 to 11 while preserving default backups`() {
        val doc = parseXml("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", doc.documentElement.tagName)

        // Do not introduce <include> tags so sent_timeline_pins and user preferences stay backed up by default.
        assertEquals(0, doc.getElementsByTagName("include").length)

        assertExcludesFile(doc.documentElement, expectedExcludedPath)
    }

    private fun assertExcludesFile(section: Element, expectedPath: String) {
        val excludes = section.getElementsByTagName("exclude")
        val matched = (0 until excludes.length).any { idx ->
            val el = excludes.item(idx) as Element
            el.getAttribute("domain") == "file" && el.getAttribute("path") == expectedPath
        }
        assertTrue(
            "Expected <${section.tagName}> to exclude domain=\"file\" path=\"$expectedPath\"",
            matched,
        )
        // Ensure sent_timeline_pins is never accidentally excluded.
        val excludesPins = (0 until excludes.length).any { idx ->
            val el = excludes.item(idx) as Element
            el.getAttribute("path").contains("sent_timeline_pins")
        }
        assertFalse("sent_timeline_pins must remain backed up normally", excludesPins)
    }

    private fun parseXml(relativePath: String): Document {
        val file = File(relativePath)
        assertTrue("Expected file to exist: ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        return factory.newDocumentBuilder().parse(file)
    }
}
