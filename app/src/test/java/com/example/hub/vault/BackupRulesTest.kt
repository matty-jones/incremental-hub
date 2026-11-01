package com.example.hub.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cloud Auto Backup and device transfer must not restore Room pointers or
 * WebView profiles independently. Display preferences are the only included
 * file. The .ihub archive remains the portable recovery contract.
 */
class BackupRulesTest {

    @Test
    fun cloudBackupAndDeviceTransferWhitelistDisplayPreferencesOnly() {
        val rules = read("app/src/main/res/xml/backup_rules.xml", "src/main/res/xml/backup_rules.xml")
        val extraction = read(
            "app/src/main/res/xml/data_extraction_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml",
        )
        val manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertFalse(manifest.contains("android:allowBackup=\"false\""))

        listOf(rules, extraction).forEach { xml ->
            assertTrue(xml.contains("hub_display_preferences.xml"))
            assertTrue(xml.contains("domain=\"database\" path=\"hub_database\""))
            assertTrue(xml.contains("hub_database-wal"))
            assertTrue(xml.contains("hub_database-shm"))
            assertTrue(xml.contains("restore-journals"))
            assertTrue(xml.contains("app_webview"))
            assertTrue(xml.contains("app_webview_hub-game-shark-incremental"))
            assertTrue(xml.contains("app_webview_hub-game-trimps"))
            assertTrue(xml.contains("app_webview_hub-game-synergism"))
            assertTrue(xml.contains("app_webview_hub-game-fe000000"))
            assertFalse(xml.contains("domain=\"database\" path=\"hub_database\"") && xml.contains("<include domain=\"database\""))
            assertFalse(xml.contains("<include domain=\"root\""))
            assertFalse(xml.contains("<include domain=\"file\""))
        }
        assertTrue(extraction.contains("<cloud-backup>"))
        assertTrue(extraction.contains("<device-transfer>"))
        assertTrue(extraction.contains("<include domain=\"sharedpref\" path=\"hub_display_preferences.xml\" />"))
        val includes = Regex("<include ").findAll(extraction).count()
        assertTrue(includes == 2)
    }

    private fun read(vararg paths: String): String {
        val file = paths.map { File(it) }.firstOrNull { it.isFile }
            ?: error("missing ${paths.toList()}")
        return file.readText()
    }
}
