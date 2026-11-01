package com.example.hub

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.GameRegistry
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.session.CaptureRepository
import com.example.hub.sync.PutResult
import com.example.hub.session.SessionController
import com.example.hub.session.SessionState
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import com.example.hub.ui.theme.HubTheme
import com.example.hub.ui.VaultHost
import com.example.hub.vault.IhubExportResult
import com.example.hub.vault.IhubPublisher
import com.example.hub.vault.RoomVaultIndex
import com.example.hub.vault.VaultArchive
import com.example.hub.vault.VaultCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Clean-device import and activation through the launcher vault control.
 * Uses disposable Room files, not hub_database, and does not delete the
 * emulator's Shark WebView profile.
 */
@RunWith(AndroidJUnit4::class)
class VaultImportActivationTest {

    @get:Rule
    val compose = createAndroidComposeRule<VaultProbeActivity>()

    @Test
    fun launcherActivatesImportedSharkSaveAndResumeKeepsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SOURCE)
        context.deleteDatabase(CLEAN)
        val payload = shark(feature = 9, prestige = 1409)
        val adapter = SharkIncrementalAdapter()
        assertTrue(runBlocking { adapter.validate(payload) })
        assertEquals(ProgressionClass.EQUAL, adapter.classifyProgress(payload, payload))
        val source = database(SOURCE)
        val clean = database(CLEAN)
        try {
            runBlocking {
                source.snapshotDao().insert(
                    SnapshotRecord(
                        id = "chosen",
                        gameId = "shark-incremental",
                        sequence = 2,
                        capturedAtMillis = 2L,
                        reason = "cadence",
                        pinned = false,
                        payload = payload,
                        progressJson = null,
                        lineageId = "lineage-shark",
                        contentSha256 = PayloadDigest.sha256(payload),
                    ),
                )
                val exported = VaultArchive(
                    source.snapshotDao(),
                    emptyApi(),
                    IhubPublisher(source, source.snapshotDao(), source.gameInstanceDao()),
                    "s5b",
                ).exportGame("shark-incremental") as IhubExportResult.Ready
                val imported = VaultArchive(
                    clean.snapshotDao(),
                    emptyApi(),
                    IhubPublisher(clean, clean.snapshotDao(), clean.gameInstanceDao()),
                    "s5b",
                ).import(exported.file)
                exported.file.delete()
                assertEquals(1, imported.imported)
            }
            compose.setContent {
                var slotEmpty by remember { mutableStateOf(true) }
                HubTheme {
                    VaultHost(
                        gameId = "shark-incremental",
                        catalog = VaultCatalog(
                            RoomVaultIndex(clean.snapshotDao(), clean.gameInstanceDao()),
                            emptyApi(),
                        ),
                        slotEmpty = slotEmpty,
                        onActivateImported = { entry ->
                            val commit = CaptureRepository(
                                instanceDao = clean.gameInstanceDao(),
                                snapshotDao = clean.snapshotDao(),
                                roomDatabase = clean,
                                outboxDao = clean.syncOutboxDao(),
                            ).restoreImportedPointer(entry.gameId, entry.id, adapter)
                            slotEmpty = clean.gameInstanceDao().get(entry.gameId)?.snapshotPayload.isNullOrBlank()
                            commit.message ?: "The save was not activated."
                        },
                        onBack = {},
                    )
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-entry-chosen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("vault-entry-chosen").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-activate-imported", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("vault-activate-imported", useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                runBlocking {
                    clean.gameInstanceDao().get("shark-incremental")?.snapshotPayload == payload
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("vault-activate-imported", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
        } finally {
            source.close()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.finish()
        }
        clean.close()

        val reopened = database(CLEAN)
        try {
            val saved = runBlocking { reopened.gameInstanceDao().get("shark-incremental")!! }
            assertEquals(payload, saved.snapshotPayload)
            assertEquals("lineage-shark", saved.lineageId)
            assertEquals("chosen", saved.highWaterSnapshotId)
            val stored = runBlocking { reopened.snapshotDao().getById("chosen")!! }
            assertEquals(payload, stored.payload)
            assertEquals("lineage-shark", stored.lineageId)
            assertEquals("chosen", stored.id)
            assertEquals(PayloadDigest.sha256(payload), stored.contentSha256)

            val controllerRef = arrayOfNulls<SessionController>(1)
            val webRef = arrayOfNulls<WebView>(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val owner = ResumedOwner()
                webRef[0] = WebView(context)
                controllerRef[0] = SessionController(
                    context = context,
                    gameRegistry = GameRegistry(context),
                    instanceDao = reopened.gameInstanceDao(),
                    lifecycleOwner = owner,
                    snapshotDao = reopened.snapshotDao(),
                    roomDatabase = reopened,
                    syncOutboxDao = reopened.syncOutboxDao(),
                    peekClient = { _, _ -> PeekOutcome.Value(payload) },
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
                    val after = reopened.gameInstanceDao().get("shark-incremental")!!
                    assertEquals(payload, after.snapshotPayload)
                    assertEquals("lineage-shark", after.lineageId)
                    assertEquals("chosen", after.highWaterSnapshotId)
                    assertEquals(payload, reopened.snapshotDao().getById("chosen")!!.payload)
                }
            } finally {
                runBlocking { controller.forceCloseForTest() }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { web.destroy() }
            }
        } finally {
            reopened.close()
            context.deleteDatabase(SOURCE)
            context.deleteDatabase(CLEAN)
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
        const val SOURCE = "hub_s5b_drill_source"
        const val CLEAN = "hub_s5b_drill_clean"
    }
}
