package com.example.hub

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import com.example.hub.vault.IhubExportResult
import com.example.hub.vault.IhubPublisher
import com.example.hub.vault.VaultArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Disposable Room files and a real WebView. Does not open hub_database.
 */
@RunWith(AndroidJUnit4::class)
class VaultRecoveryDrillTest {

    @Test
    fun webViewRoundTripAndArchiveImportLeaveTheLivePointer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SOURCE)
        context.deleteDatabase(IMPORTED)
        val source = database(SOURCE)
        val payload = "drill-payload-\u0000"
        try {
            runBlocking { source.snapshotDao().insert(
                SnapshotRecord(
                    id = "drill-1",
                    gameId = "shark-incremental",
                    sequence = 1,
                    capturedAtMillis = 1L,
                    reason = "cadence",
                    pinned = false,
                    payload = payload,
                    progressJson = null,
                    lineageId = "legacy",
                    contentSha256 = PayloadDigest.sha256(payload),
                ),
            ) }
            runBlocking { source.gameInstanceDao().upsert(
                GameInstance(
                    gameId = "shark-incremental",
                    profileName = "hub-drill",
                    sessionIdentity = "drill",
                    lastPlayedAtMillis = 1L,
                    lastCaptureAtMillis = 1L,
                    snapshotPayload = "live-pointer",
                    lineageId = "legacy",
                ),
            ) }
            val seen = AtomicReference<String>()
            val latch = CountDownLatch(1)
            ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val web = WebView(activity)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                "(function(){localStorage.setItem('hub-drill','exact-webview'); return localStorage.getItem('hub-drill');})()",
                            ) { raw ->
                                seen.set(raw)
                                latch.countDown()
                                view.destroy()
                            }
                        }
                    }
                    web.loadDataWithBaseURL(
                        "https://example.test/drill/",
                        "<html></html>",
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            }
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            assertTrue(seen.get().orEmpty().contains("exact-webview"))

            val exported = runBlocking {
                VaultArchive(source.snapshotDao(), emptyApi(), publisher(source), "s5b").exportGame("shark-incremental")
            }
            assertTrue(exported is IhubExportResult.Ready)
            val file = (exported as IhubExportResult.Ready).file
            val importedDb = database(IMPORTED)
            try {
                runBlocking { importedDb.gameInstanceDao().upsert(
                    GameInstance(
                        gameId = "shark-incremental",
                        profileName = "hub-drill",
                        sessionIdentity = "drill",
                        lastPlayedAtMillis = 1L,
                        lastCaptureAtMillis = 1L,
                        snapshotPayload = "live-pointer",
                        lineageId = "legacy",
                    ),
                ) }
                val result = runBlocking {
                    VaultArchive(importedDb.snapshotDao(), emptyApi(), publisher(importedDb), "s5b").import(file)
                }
                assertEquals(1, result.imported)
                runBlocking {
                    assertEquals(payload, importedDb.snapshotDao().getById("drill-1")!!.payload)
                    assertEquals("live-pointer", importedDb.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
                    assertEquals("live-pointer", source.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
                }
            } finally {
                importedDb.close()
            }
            file.delete()
        } finally {
            source.close()
            context.deleteDatabase(SOURCE)
            context.deleteDatabase(IMPORTED)
        }
    }

    private fun publisher(db: HubDatabase): IhubPublisher {
        return IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao())
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

    private companion object {
        const val SOURCE = "hub_recovery_drill"
        const val IMPORTED = "hub_recovery_import"
    }
}
