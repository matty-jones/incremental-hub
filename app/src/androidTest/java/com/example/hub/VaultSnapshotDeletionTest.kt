package com.example.hub

import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.GameRegistry
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.session.SessionController
import com.example.hub.session.SessionState
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import com.example.hub.ui.VaultHost
import com.example.hub.ui.theme.HubTheme
import com.example.hub.vault.RoomVaultIndex
import com.example.hub.vault.SnapshotDeletion
import com.example.hub.vault.VaultCatalog
import com.example.hub.vault.VaultProtection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Deletes one redundant Shark snapshot from the vault, then saves and restarts
 * on a disposable Room file. Does not open hub_database and does not delete
 * the hub-game-shark-incremental WebView profile.
 */
@RunWith(AndroidJUnit4::class)
class VaultSnapshotDeletionTest {
    @get:Rule
    val compose = createAndroidComposeRule<VaultProbeActivity>()

    @Test
    fun vaultDeletionThenSharkSaveSurvivesRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE)
        val original = shark(feature = 9, prestige = 1409)
        val advanced = shark(feature = 10, prestige = 1410)
        val now = System.currentTimeMillis()
        val db = database(DATABASE)
        runBlocking {
                db.gameInstanceDao().upsert(
                    GameInstance(
                        gameId = "shark-incremental",
                        profileName = "hub-game-shark-incremental",
                        sessionIdentity = "session",
                        lastPlayedAtMillis = now,
                        lastCaptureAtMillis = now,
                        snapshotPayload = original,
                        lineageId = "lineage-shark",
                        highWaterSnapshotId = "hw",
                    ),
                )
                db.snapshotDao().insert(snapshot("hw", original, 2, now))
                db.snapshotDao().insert(snapshot("copy", original, 1, now))
                db.snapshotDao().insert(
                    snapshot("checkpoint", "checkpoint-payload", 3, now - 86_400_000L).copy(
                        reason = SnapshotReasons.CHECKPOINT,
                        pinned = true,
                        contentSha256 = PayloadDigest.sha256("checkpoint-payload"),
                    ),
                )
            }
            compose.setContent {
                HubTheme {
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
                        onBack = {},
                    )
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-entry-checkpoint").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("vault-entry-checkpoint").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-protect", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                0,
                compose.onAllNodesWithTag("vault-delete", useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
            compose.onNodeWithText("Timeline").performClick()
            compose.onNodeWithTag("vault-entry-hw").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-detail").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                0,
                compose.onAllNodesWithTag("vault-delete", useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
            compose.onNodeWithText("Timeline").performClick()
            compose.onNodeWithTag("vault-entry-copy").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-delete", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("vault-protect", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("vault-delete", useUnmergedTree = true).performClick()
            compose.onNodeWithText(
                "This deletion removes the local historical snapshot only. The live save and every other snapshot stay.",
                substring = true,
            ).assertIsDisplayed()
            compose.onNodeWithText("Snapshot copy", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Game shark-incremental", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Snapshot reason cadence", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Progress summary", substring = true).assertIsDisplayed()
            compose.onNodeWithText(
                "Workstation copy: unknown. An external backup was not verified.",
            ).assertIsDisplayed()
            compose.onNodeWithTag("vault-delete-confirm").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-entry-copy").fetchSemanticsNodes().isEmpty() &&
                    runBlocking { db.snapshotDao().getById("copy") == null }
            }
            compose.onNodeWithText("Deleted local snapshot copy", substring = true).assertIsDisplayed()
            runBlocking {
                assertEquals(original, db.snapshotDao().getById("hw")!!.payload)
                assertEquals("checkpoint-payload", db.snapshotDao().getById("checkpoint")!!.payload)
                assertEquals(original, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.finish()
        }
        val controllerRef = arrayOfNulls<SessionController>(1)
        val webRef = arrayOfNulls<WebView>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webRef[0] = WebView(context)
            controllerRef[0] = SessionController(
                context = context,
                gameRegistry = GameRegistry(context),
                instanceDao = db.gameInstanceDao(),
                lifecycleOwner = ResumedOwner(),
                snapshotDao = db.snapshotDao(),
                roomDatabase = db,
                syncOutboxDao = db.syncOutboxDao(),
                peekClient = { _, _ -> PeekOutcome.Value(original) },
                captureClient = { _, _ -> advanced },
                readinessCheck = { _, _ -> true },
            )
        }
        val controller = controllerRef[0]!!
        val web = webRef[0]!!
        try {
            runBlocking {
                controller.open("shark-incremental")
                val url = controller.launchUrl.value
                assertTrue(url!!.contains("shark-incremental"))
                val state = driveResume(controller, url, web)
                assertTrue(state is SessionState.Playing)
                controller.captureNowForTest("request")
                val saved = db.gameInstanceDao().get("shark-incremental")!!
                assertEquals(advanced, saved.snapshotPayload)
                assertEquals("lineage-shark", saved.lineageId)
                assertNull(db.snapshotDao().getById("copy"))
                assertEquals(original, db.snapshotDao().getById("hw")!!.payload)
                assertEquals("checkpoint-payload", db.snapshotDao().getById("checkpoint")!!.payload)
                assertTrue(db.snapshotDao().history("shark-incremental").any { it.payload == advanced })
            }
        } finally {
            runBlocking { controller.forceCloseForTest() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { web.destroy() }
            db.close()
        }

        val reopened = database(DATABASE)
        try {
            val saved = runBlocking { reopened.gameInstanceDao().get("shark-incremental")!! }
            assertEquals(advanced, saved.snapshotPayload)
            assertEquals("lineage-shark", saved.lineageId)
            assertNull(runBlocking { reopened.snapshotDao().getById("copy") })
            assertEquals(original, runBlocking { reopened.snapshotDao().getById("hw")!!.payload })
            assertEquals(
                "checkpoint-payload",
                runBlocking { reopened.snapshotDao().getById("checkpoint")!!.payload },
            )
            assertTrue(
                runBlocking {
                    reopened.snapshotDao().history("shark-incremental").any { it.payload == advanced }
                },
            )
        } finally {
            reopened.close()
            context.deleteDatabase(DATABASE)
        }
    }

    private suspend fun driveResume(controller: SessionController, url: String, web: WebView): SessionState {
        controller.webView = web
        repeat(8) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                controller.onMainFrameFinished(url, controller.loadToken.value)
            }
            val state = controller.state.value
            if (state is SessionState.Playing || state is SessionState.RecoveryRequired) return state
            withTimeoutOrNull(1_000) {
                controller.state.first { it is SessionState.Playing || it is SessionState.RecoveryRequired }
            }?.let { return it }
        }
        return controller.state.value
    }

    private fun database(name: String): HubDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.databaseBuilder(context, HubDatabase::class.java, name)
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

    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private companion object {
        const val DATABASE = "hub_s5c_snapshot_delete"
    }
}
