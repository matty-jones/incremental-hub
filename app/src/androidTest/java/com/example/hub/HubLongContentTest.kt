package com.example.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import com.example.hub.session.DiagnosticEvent
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import com.example.hub.ui.DiagnosticsPanel
import com.example.hub.ui.RecoveryOverlay
import com.example.hub.ui.VaultHost
import com.example.hub.ui.theme.HubTheme
import com.example.hub.vault.RoomVaultIndex
import com.example.hub.vault.SnapshotDeletion
import com.example.hub.vault.VaultAssessment
import com.example.hub.vault.VaultCatalog
import com.example.hub.vault.VaultProtection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Short viewports stand in for the folded Pixel Fold. Controls below the first
 * screen are reached by scrolling, then clicked.
 */
@RunWith(AndroidJUnit4::class)
class HubLongContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<VaultProbeActivity>()

    @Test
    fun vaultRestoreConfirmationStaysReachableBelowTheFold() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE)
        val db = database()
        val now = System.currentTimeMillis()
        val payload = shark(feature = 4, prestige = 20)
        val confirmed = booleanArrayOf(false)
        try {
            runBlocking { db.gameInstanceDao().upsert(
                GameInstance(
                    gameId = "shark-incremental",
                    profileName = "hub-game-shark-incremental",
                    sessionIdentity = "session",
                    lastPlayedAtMillis = now,
                    lastCaptureAtMillis = now,
                    snapshotPayload = payload,
                    lineageId = "lineage-shark",
                    highWaterSnapshotId = "hw",
                ),
            )
            db.snapshotDao().insert(snapshot("hw", payload, 2, now))
            db.snapshotDao().insert(snapshot("copy", payload, 1, now))
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 1.8f),
                ) {
                    HubTheme {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(420.dp),
                        ) {
                            VaultHost(
                                gameId = "shark-incremental",
                                catalog = VaultCatalog(
                                    RoomVaultIndex(db.snapshotDao(), db.gameInstanceDao()),
                                    emptyApi(),
                                ),
                                protection = VaultProtection(
                                    snapshots = db.snapshotDao(),
                                    instances = db.gameInstanceDao(),
                                    api = emptyApi(),
                                    room = db,
                                ),
                                deletion = SnapshotDeletion(
                                    snapshots = db.snapshotDao(),
                                    instances = db.gameInstanceDao(),
                                    outbox = db.syncOutboxDao(),
                                    room = db,
                                ),
                                plan = VaultAssessment(
                                    snapshotId = "copy",
                                    lineageId = "lineage-shark",
                                    capturedAtMillis = now,
                                    classification = ProgressionClass.REGRESSED,
                                    ready = true,
                                    allowed = false,
                                    requiresBranch = true,
                                    proposeWorkstationPointer = false,
                                    moveHighWater = false,
                                    localBranch = false,
                                    sourceDetail = "feature 4",
                                    currentDetail = "feature 9 " + "milestone ".repeat(40),
                                    unorderedNote = null,
                                    message = "This snapshot is behind the protected pointer. " +
                                        "line ".repeat(30),
                                ),
                                onStageRestore = {},
                                onConfirmRestore = { confirmed[0] = true },
                                onBack = {},
                            )
                        }
                    }
                }
            }
            waitForTag("vault-timeline")
            val listed = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < listed &&
                compose.onAllNodesWithTag("vault-entry-copy").fetchSemanticsNodes().isEmpty()
            ) {
                compose.onNodeWithTag("vault-timeline").performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            waitForTag("vault-entry-copy")
            compose.onNodeWithTag("vault-entry-copy").performClick()
            waitForTag("vault-restore-confirmation")
            compose.onNodeWithTag("vault-restore-confirmation").assertIsDisplayed()
            compose.onNodeWithTag("vault-delete").assertIsNotDisplayed()
            compose.onNodeWithTag("vault-protect").performScrollTo().performClick()
            compose.onNodeWithTag("vault-delete").performScrollTo().performClick()
            compose.onNodeWithTag("vault-delete-dialog").assertIsDisplayed()
            compose.onNodeWithText("Workstation copy", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag("vault-delete-keep").performClick()
            compose.onNodeWithTag("vault-restore-branch").assertIsDisplayed().performClick()
            assertTrue(confirmed[0])
        } finally {
            db.close()
            context.deleteDatabase(DATABASE)
        }
    }

    @Test
    fun recoveryDetailScrollsToClose() {
        var closed = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.6f),
            ) {
                HubTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                        ) {
                            RecoveryOverlay(
                                gameId = "shark-incremental",
                                launchUrl = "https://mrredshark77.github.io/shark-incremental/",
                                detail = "storage hash " + "a".repeat(180) + "\n" + "detail line\n".repeat(24),
                                onRetry = {},
                                onClose = { closed = true },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("recovery-close").performScrollTo().performClick()
        assertTrue(closed)
    }

    @Test
    fun diagnosticsScrollsToTheLatestEvent() {
        compose.setContent {
            HubTheme {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    DiagnosticsPanel(
                        report = "report",
                        events = List(12) { index ->
                            DiagnosticEvent(
                                atMillis = index.toLong(),
                                severity = DiagnosticSeverity.INFO,
                                category = "offline",
                                message = "event-$index " + "x".repeat(80),
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("event-11", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-export").assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        try {
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), error)
        }
    }

    private fun database(): HubDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.databaseBuilder(context, HubDatabase::class.java, DATABASE)
            .allowMainThreadQueries()
            .build()
    }

    private fun snapshot(id: String, payload: String, sequence: Long, at: Long): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = "shark-incremental",
            sequence = sequence,
            capturedAtMillis = at,
            reason = "cadence",
            pinned = false,
            payload = payload,
            progressJson = null,
            lineageId = "lineage-shark",
            contentSha256 = PayloadDigest.sha256(payload),
        )
    }

    private fun emptyApi(): WorkstationApi = object : WorkstationApi {
        override fun fetchSaves(): List<RemoteSave>? = emptyList()
        override fun putSave(save: RemoteSave) = null
        override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
        override fun download(url: String, destination: File) = false
        override fun fetchHistoryIndex(
            gameId: String,
            afterReceivedAt: Long,
            afterSnapshotId: String,
            limit: Int,
        ) = HistoryPage(emptyList(), complete = true)
    }

    private fun shark(feature: Int, prestige: Long): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", "10")
            .put("shark_level", 1)
            .put("total_fish", JSONObject().put("s", 1).put("e", "4").put("c", JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", 0))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val DATABASE = "hub_vault_scroll_probe"
    }
}
