package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SnapshotRetention
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.PutResult
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.RemoteSnapshot
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VaultProtectionTest {

    @Test
    fun userProtectionSurvivesRetentionAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "user-protect-restart"
        context.deleteDatabase(name)
        val first = open(context, name)
        val gameId = SHARK_GAME_ID
        first.gameInstanceDao().upsert(instance(gameId, highWater = "anchor"))
        val oldAt = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
        first.snapshotDao().insert(row("kept", gameId, 1, oldAt, "old-payload", userProtected = false))
        first.snapshotDao().insert(row("anchor", gameId, 2, oldAt, "anchor-payload", reason = "cadence"))
        first.snapshotDao().insert(
            row("checkpoint", gameId, 3, oldAt, "check-payload", reason = SnapshotReasons.CHECKPOINT, pinned = true),
        )
        val protection = VaultProtection(first.snapshotDao(), first.gameInstanceDao(), OfflineApi(), first)
        val entry = localEntry("kept", gameId, PayloadDigest.sha256("old-payload"))
        val protected = protection.protect(entry)
        assertTrue(protected.applied)
        assertTrue(first.snapshotDao().getById("kept")!!.userProtected)
        assertFalse(first.snapshotDao().getById("kept")!!.pinned)
        assertEquals("cadence", first.snapshotDao().getById("kept")!!.reason)
        first.close()

        val second = open(context, name)
        assertTrue(second.snapshotDao().getById("kept")!!.userProtected)
        assertEquals("old-payload", second.snapshotDao().getById("kept")!!.payload)
        val now = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
        repeat(15) { index ->
            second.snapshotDao().insert(
                row("new-$index", gameId, 100L + index, now + index, "new-$index"),
            )
        }
        SnapshotRetention.prune(second.snapshotDao(), second.gameInstanceDao(), gameId, now)
        assertEquals("old-payload", second.snapshotDao().getById("kept")!!.payload)
        assertEquals("anchor-payload", second.snapshotDao().getById("anchor")!!.payload)
        assertEquals("check-payload", second.snapshotDao().getById("checkpoint")!!.payload)
        val again = VaultProtection(second.snapshotDao(), second.gameInstanceDao(), OfflineApi(), second)
        val removed = again.unprotect(localEntry("checkpoint", gameId, PayloadDigest.sha256("check-payload"), reason = SnapshotReasons.CHECKPOINT, pinned = true))
        assertTrue(removed.applied)
        assertFalse(second.snapshotDao().getById("checkpoint")!!.userProtected)
        assertTrue(second.snapshotDao().getById("checkpoint")!!.pinned)
        SnapshotRetention.prune(second.snapshotDao(), second.gameInstanceDao(), gameId, now)
        assertEquals("check-payload", second.snapshotDao().getById("checkpoint")!!.payload)
        again.unprotect(localEntry("kept", gameId, PayloadDigest.sha256("old-payload")))
        SnapshotRetention.prune(second.snapshotDao(), second.gameInstanceDao(), gameId, now)
        assertNull(second.snapshotDao().getById("kept"))
        second.close()
    }

    @Test
    fun remoteProtectionDownloadsBeforeTheFlagIsSet() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            db.gameInstanceDao().upsert(
                GameInstance(
                    gameId = SHARK_GAME_ID,
                    profileName = "hub",
                    sessionIdentity = "session",
                    lastPlayedAtMillis = 1L,
                    lastCaptureAtMillis = 1L,
                ),
            )
            val payload = "remote-payload-\u0000"
            val sha = PayloadDigest.sha256(payload)
            val api = MutableApi(
                RemoteSnapshot(
                    snapshotId = "remote-1",
                    gameId = SHARK_GAME_ID,
                    lineageId = "legacy",
                    sha256 = "tampered",
                    snapshotPayload = payload,
                    capturedAtMillis = 40,
                    integrity = "accepted",
                    source = "workstation",
                ),
            )
            val protection = VaultProtection(db.snapshotDao(), db.gameInstanceDao(), api, db)
            val entry = remoteEntry("remote-1", sha)
            val refused = protection.protect(entry)
            assertFalse(refused.applied)
            assertEquals(0, db.snapshotDao().countForGame(SHARK_GAME_ID))
            api.snapshot = api.snapshot?.copy(sha256 = sha)
            val applied = protection.protect(entry)
            assertTrue(applied.message, applied.applied)
            val stored = db.snapshotDao().getById("remote-1")
            assertEquals(payload, stored?.payload)
            assertTrue(stored!!.userProtected)
            assertFalse(stored.pinned)
            assertEquals(SnapshotReasons.WORKSTATION, stored.reason)
            api.snapshot = null
            val removed = protection.unprotect(
                entry.copy(residence = VaultResidence.LOCAL, reason = SnapshotReasons.WORKSTATION),
            )
            assertTrue(removed.applied)
            assertFalse(db.snapshotDao().getById("remote-1")!!.userProtected)
            SnapshotRetention.prune(
                db.snapshotDao(),
                db.gameInstanceDao(),
                SHARK_GAME_ID,
                Instant.parse("2026-06-15T12:00:00Z").toEpochMilli(),
            )
            assertEquals(payload, db.snapshotDao().getById("remote-1")!!.payload)
        } finally {
            db.close()
        }
    }

    @Test
    fun remoteFetchRunsOutsideTheRoomTransaction() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            var insideTransaction = true
            var threadName = ""
            val api = MutableApi(null)
            api.onFetch = {
                insideTransaction = db.inTransaction()
                threadName = Thread.currentThread().name
                throw java.io.IOException("workstation down")
            }
            val protection = VaultProtection(db.snapshotDao(), db.gameInstanceDao(), api, db)
            val outcome = protection.protect(remoteEntry("remote-1", "abc"))
            assertFalse(outcome.applied)
            assertFalse(insideTransaction)
            assertTrue(threadName.contains("DefaultDispatcher-worker"))
            assertEquals(0, db.snapshotDao().countForGame(SHARK_GAME_ID))
        } finally {
            db.close()
        }
    }

    private fun open(context: Context, name: String): HubDatabase {
        return Room.databaseBuilder(context, HubDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
    }

    private fun instance(gameId: String, highWater: String): GameInstance {
        return GameInstance(
            gameId = gameId,
            profileName = "hub",
            sessionIdentity = "session",
            lastPlayedAtMillis = 1L,
            lastCaptureAtMillis = 1L,
            highWaterSnapshotId = highWater,
        )
    }

    private fun row(
        id: String,
        gameId: String,
        sequence: Long,
        at: Long,
        payload: String,
        reason: String = "cadence",
        pinned: Boolean = false,
        userProtected: Boolean = false,
    ): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = gameId,
            sequence = sequence,
            capturedAtMillis = at,
            reason = reason,
            pinned = pinned,
            payload = payload,
            progressJson = null,
            contentSha256 = PayloadDigest.sha256(payload),
            userProtected = userProtected,
        )
    }

    private fun localEntry(
        id: String,
        gameId: String,
        sha: String,
        reason: String = "cadence",
        pinned: Boolean = false,
    ): VaultEntry {
        return VaultEntry(
            id = id,
            gameId = gameId,
            lineageId = "legacy",
            sequence = 1,
            capturedAtMillis = 1,
            receivedAtMillis = 0,
            reason = reason,
            origin = "local-capture",
            pinned = pinned,
            userProtected = false,
            sha256 = sha,
            payloadBytes = 1,
            integrity = "hash recorded",
            residence = VaultResidence.LOCAL,
            availability = VaultAvailability.PRESENT,
            active = false,
            highWater = false,
            storedDetail = null,
        )
    }

    private fun remoteEntry(id: String, sha: String): VaultEntry {
        return localEntry(id, SHARK_GAME_ID, sha).copy(
            residence = VaultResidence.REMOTE_ONLY,
            reason = SnapshotReasons.WORKSTATION,
            origin = "workstation-sync",
        )
    }
}

private class OfflineApi : WorkstationApi {
    override fun fetchSaves(): List<RemoteSave>? = null
    override fun putSave(save: RemoteSave): PutResult? = null
    override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable
    override fun download(url: String, destination: File): Boolean = false
    override fun fetchHistoryIndex(
        gameId: String,
        afterReceivedAt: Long,
        afterSnapshotId: String,
        limit: Int,
    ): HistoryPage? = null
}

private class MutableApi(var snapshot: RemoteSnapshot?) : WorkstationApi {
    var onFetch: (() -> Unit)? = null

    override fun fetchSaves(): List<RemoteSave>? = null
    override fun putSave(save: RemoteSave): PutResult? = null
    override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable
    override fun download(url: String, destination: File): Boolean = false
    override fun fetchSnapshot(gameId: String, snapshotId: String): RemoteSnapshot? {
        onFetch?.invoke()
        return snapshot
    }
}
