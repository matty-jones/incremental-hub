package com.example.hub.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SharkIncrementalAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SaveSyncIncidentTest {

    @Test
    fun rejectedCaptureAndNewLineageAreNotPushedWhenTheWorkstationIsUnreachable() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inline = java.util.concurrent.Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            val puts = mutableListOf<String>()
            val api = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave>? = null
                override fun putSave(save: RemoteSave): PutResult {
                    puts.add(save.snapshotPayload)
                    return PutResult.Stored
                }
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable
                override fun download(url: String, destination: File): Boolean = false
            }
            val sync = SaveSync(db.gameInstanceDao(), api, db.snapshotDao())
            sync.protectsLocal = { _, reference, candidate ->
                when (SharkIncrementalAdapter().classifyProgress(candidate, reference)) {
                    ProgressionClass.ADVANCED, ProgressionClass.EQUAL, ProgressionClass.RESET_APPROVED -> true
                    else -> false
                }
            }
            val incident = instance("incident-save", captureIncident = "Capture incident REGRESSED")
            db.gameInstanceDao().upsert(incident)
            sync.push(incident)
            assertTrue(puts.isEmpty())

            val startedOver = instance("fresh-lineage", lineageId = "lineage-2")
            db.gameInstanceDao().upsert(startedOver)
            sync.push(startedOver)
            assertTrue(puts.isEmpty())

            val accepted = instance("good-save")
            db.gameInstanceDao().upsert(accepted)
            sync.push(accepted)
            assertEquals(listOf("good-save"), puts)
        } finally {
            db.close()
        }
    }

    @Test
    fun regressedRemoteDoesNotReplaceTheProtectedPointer() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inline = java.util.concurrent.Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            val api = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave> = listOf(
                    RemoteSave(
                        gameId = "shark-incremental",
                        snapshotPayload = "remote-regressed",
                        capturedAtMillis = 500L,
                        profileName = "hub",
                        sessionIdentity = "remote",
                        lastPlayedAtMillis = 500L,
                        resumeToken = null,
                        recoveryState = null,
                    ),
                )
                override fun putSave(save: RemoteSave): PutResult = PutResult.Stored
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
                override fun download(url: String, destination: File): Boolean = false
            }
            val decisions = mutableListOf<String>()
            val sync = SaveSync(db.gameInstanceDao(), api, db.snapshotDao())
            sync.onDecision = { decisions.add(it) }
            sync.protectsLocal = { _, _, _ -> false }
            db.gameInstanceDao().upsert(instance("local-good"))
            sync.merge()
            assertEquals("local-good", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(decisions.any { it.contains("stored snapshot was kept") })
        } finally {
            db.close()
        }
    }

    private fun instance(
        payload: String,
        captureIncident: String? = null,
        lineageId: String = "legacy",
    ): GameInstance {
        return GameInstance(
            gameId = "shark-incremental",
            profileName = "hub-game-shark-incremental",
            sessionIdentity = "session",
            lastPlayedAtMillis = 20L,
            lastCaptureAtMillis = 20L,
            snapshotPayload = payload,
            captureIncident = captureIncident,
            lineageId = lineageId,
        )
    }
}
