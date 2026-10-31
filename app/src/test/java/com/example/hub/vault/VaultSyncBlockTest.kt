package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.PointerFetch
import com.example.hub.sync.PointerProposal
import com.example.hub.sync.PointerProposalResult
import com.example.hub.sync.PutResult
import com.example.hub.sync.RemotePointer
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.SaveSync
import com.example.hub.sync.SnapshotUpload
import com.example.hub.sync.SnapshotUploadResult
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VaultSyncBlockTest {

    @Test
    fun aBlockedRestoreDoesNotProposeOrPullAPointer() = runTest {
        val db = open()
        try {
            val proposals = mutableListOf<String>()
            val api = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave> = listOf(
                    RemoteSave(
                        gameId = "shark-incremental",
                        snapshotPayload = "remote-bytes",
                        capturedAtMillis = 9L,
                        profileName = "hub",
                        sessionIdentity = "remote",
                        lastPlayedAtMillis = 9L,
                        resumeToken = null,
                        recoveryState = null,
                    ),
                )
                override fun putSave(save: RemoteSave): PutResult = error("unused")
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
                override fun download(url: String, destination: File): Boolean = false
                override fun fetchPointer(gameId: String): PointerFetch = PointerFetch.Found(
                    RemotePointer(
                        gameId = gameId,
                        lineageId = "legacy",
                        snapshotId = "remote",
                        version = 4,
                        snapshotPayload = "remote-bytes",
                        capturedAtMillis = 9L,
                        sha256 = PayloadDigest.sha256("remote-bytes"),
                        profileName = "hub",
                        sessionIdentity = "remote",
                        lastPlayedAtMillis = 9L,
                        resumeToken = null,
                        recoveryState = null,
                    ),
                )
                override fun appendSnapshot(upload: SnapshotUpload): SnapshotUploadResult {
                    return SnapshotUploadResult.Stored(
                        created = true,
                        snapshotId = upload.snapshotId,
                        sha256 = PayloadDigest.sha256(upload.snapshotPayload),
                    )
                }
                override fun proposePointer(proposal: PointerProposal): PointerProposalResult {
                    proposals.add(proposal.snapshotId)
                    error("pointer proposal is blocked")
                }
            }
            val sync = SaveSync(db.gameInstanceDao(), api, db.snapshotDao(), db.syncOutboxDao())
            sync.pointerChangesBlocked = { true }
            val payload = "local-bytes"
            db.gameInstanceDao().upsert(
                GameInstance(
                    gameId = "shark-incremental",
                    profileName = "hub",
                    sessionIdentity = "session",
                    lastPlayedAtMillis = 20L,
                    lastCaptureAtMillis = 20L,
                    snapshotPayload = payload,
                    lineageId = "lineage-1",
                ),
            )
            db.syncOutboxDao().insert(
                SyncOutboxEntry(
                    idempotencyKey = "append:local",
                    gameId = "shark-incremental",
                    lineageId = "lineage-1",
                    snapshotId = "local",
                    payload = payload,
                    contentSha256 = PayloadDigest.sha256(payload),
                    capturedAtMillis = 20L,
                    reason = "cadence",
                    integrity = SyncOutboxEntry.INTEGRITY_ACCEPTED,
                    profileName = "hub",
                    sessionIdentity = "session",
                    lastPlayedAtMillis = 20L,
                    resumeToken = null,
                    proposePointer = true,
                    enqueuedAtMillis = 20L,
                ),
            )
            sync.push(
                db.gameInstanceDao().get("shark-incremental")!!,
            )
            assertTrue(proposals.isEmpty())
            assertEquals(payload, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(db.syncOutboxDao().pending().isNotEmpty())

            db.gameInstanceDao().delete("shark-incremental")
            sync.merge()
            assertNull(db.gameInstanceDao().get("shark-incremental"))
            assertTrue(proposals.isEmpty())
        } finally {
            db.close()
        }
    }

    private fun open(): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }
}
