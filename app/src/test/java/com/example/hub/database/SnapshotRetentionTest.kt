package com.example.hub.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SnapshotRetentionTest {

    @Test
    fun tiersKeepBucketCoverageThroughABurst() {
        val today = LocalDate.of(2026, 6, 15)
        val now = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val priorMonday = thisMonday.minusWeeks(1)
        val priorWednesday = priorMonday.plusDays(2)
        val rows = mutableListOf<SnapshotRecord>()
        repeat(3_000) { index ->
            rows += automatic(
                id = "burst-$index",
                at = now - 3_000L + index,
                payload = "burst-$index",
                sequence = index.toLong(),
            )
        }
        rows += automatic("monday", priorMonday.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), "monday", 10_000)
        rows += automatic("wednesday", priorWednesday.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), "wednesday", 10_001)
        rows += automatic("week-in", today.minusWeeks(11).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), "week-in", 10_002)
        rows += automatic("week-out", today.minusWeeks(13).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), "week-out", 10_003)
        rows += automatic("hour-in", now - 48L * 3_600_000L, "hour-in", 10_004)
        rows += automatic("hour-out", now - 48L * 3_600_000L - 1L, "hour-out", 10_005)
        repeat(40) { index ->
            rows += automatic(
                id = "dup-$index",
                at = now - 2L * 3_600_000L + 60_000L + index,
                payload = "same-payload",
                sequence = 20_000L + index,
            )
        }
        rows += permanent("checkpoint", SnapshotReasons.CHECKPOINT, "checkpoint-payload", pinned = true)
        rows += permanent("pre", SnapshotReasons.PRE_RESTORE, "pre-\u0000-payload", pinned = true)
        rows += permanent("diag", SnapshotReasons.DIAGNOSTIC_BACKUP, "diag-payload", pinned = true)
        rows += permanent("remote", SnapshotReasons.WORKSTATION, "remote-payload", pinned = false)
        rows += permanent("quarantine", SnapshotReasons.QUARANTINE, "quarantine-payload", pinned = true)
        rows += permanent("lineage", SnapshotReasons.LINEAGE_BASE, "lineage-payload", pinned = true)
        rows += automatic("anchor", Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(), "anchor-payload", 30_000)
        repeat(20) { index ->
            rows += automatic(
                id = "other-$index",
                at = now - 20L + index,
                payload = "other-$index",
                sequence = 40_000L + index,
                lineage = "other-lineage",
            )
        }
        val keep = SnapshotRetention.keepIds(rows, now, protectedIds = setOf("anchor"))
        assertEquals(keep, SnapshotRetention.keepIds(rows, now, protectedIds = setOf("anchor")))
        val burstKept = (0 until 3_000).count { "burst-$it" in keep }
        assertEquals(SnapshotRetention.RECENT_DISTINCT, burstKept)
        assertTrue("burst-2999" in keep)
        assertFalse("burst-0" in keep)
        assertTrue("monday" in keep)
        assertTrue("wednesday" in keep)
        assertTrue("week-in" in keep)
        assertFalse("week-out" in keep)
        assertTrue("hour-in" in keep)
        assertFalse("hour-out" in keep)
        assertEquals(1, rows.count { it.payload == "same-payload" && it.id in keep })
        assertTrue("checkpoint" in keep)
        assertTrue("pre" in keep)
        assertTrue("diag" in keep)
        assertTrue("remote" in keep)
        assertTrue("quarantine" in keep)
        assertTrue("lineage" in keep)
        assertTrue("anchor" in keep)
        assertEquals(10, (0 until 20).count { "other-$it" in keep })
        assertEquals("pre-\u0000-payload", rows.single { it.id == "pre" }.payload)
    }

    @Test
    fun utcHoursStaySeparateAcrossTheSpringForwardGap() {
        val now = Instant.parse("2026-03-08T18:00:00Z").toEpochMilli()
        val rows = mutableListOf<SnapshotRecord>()
        rows += automatic("early", Instant.parse("2026-03-08T08:30:00Z").toEpochMilli(), "early", 1)
        rows += automatic("same-hour", Instant.parse("2026-03-08T08:45:00Z").toEpochMilli(), "same-hour", 2)
        rows += automatic("next-hour", Instant.parse("2026-03-08T09:30:00Z").toEpochMilli(), "next-hour", 3)
        repeat(12) { index ->
            rows += automatic(
                id = "later-$index",
                at = Instant.parse("2026-03-08T17:00:00Z").toEpochMilli() + index,
                payload = "later-$index",
                sequence = 100L + index,
            )
        }
        val keep = SnapshotRetention.keepIds(rows, now, protectedIds = emptySet())
        assertFalse("early" in keep)
        assertTrue("same-hour" in keep)
        assertTrue("next-hour" in keep)
    }

    @Test
    fun roomPruneKeepsTheAnchorAndSkipsAnOpenIncident() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            val now = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
            val gameId = "shark-incremental"
            db.gameInstanceDao().upsert(
                GameInstance(
                    gameId = gameId,
                    profileName = "hub",
                    sessionIdentity = "session",
                    lastPlayedAtMillis = 1L,
                    lastCaptureAtMillis = 0L,
                    captureIncident = "open",
                ),
            )
            repeat(30) { index ->
                db.snapshotDao().insert(automatic("open-$index", now - 86_400_000L * 40 - index, "open-$index", index.toLong(), gameId))
            }
            SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), gameId, now)
            assertEquals(30, db.snapshotDao().history(gameId).size)
            val cleared = db.gameInstanceDao().get(gameId)!!.copy(captureIncident = null, highWaterSnapshotId = "open-0")
            db.gameInstanceDao().upsert(cleared)
            db.snapshotDao().insert(
                permanent("checkpoint", SnapshotReasons.CHECKPOINT, "checkpoint-\u0000", pinned = true, gameId = gameId)
                    .copy(sequence = 10_000, capturedAtMillis = 1L),
            )
            repeat(900) { index ->
                db.snapshotDao().insert(
                    automatic(
                        id = "row-$index",
                        at = now - 900L + index,
                        payload = "row-$index",
                        sequence = 20_000L + index,
                        gameId = gameId,
                    ),
                )
            }
            SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), gameId, now)
            SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), gameId, now)
            val history = db.snapshotDao().history(gameId)
            assertEquals("checkpoint-\u0000", history.single { it.id == "checkpoint" }.payload)
            assertEquals("open-0", history.single { it.id == "open-0" }.payload)
            assertEquals("row-899", history.single { it.id == "row-899" }.payload)
            assertTrue(history.none { it.id == "row-0" })
            assertTrue(history.none { it.id == "open-1" })
            val second = db.snapshotDao().history(gameId).map { it.id }.toSet()
            assertEquals(second, history.map { it.id }.toSet())
        } finally {
            db.close()
        }
    }

    private fun automatic(
        id: String,
        at: Long,
        payload: String,
        sequence: Long,
        gameId: String = "shark-incremental",
        lineage: String = "legacy",
    ): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = gameId,
            sequence = sequence,
            capturedAtMillis = at,
            reason = "cadence",
            pinned = false,
            payload = payload,
            progressJson = null,
            lineageId = lineage,
            contentSha256 = "",
        )
    }

    private fun permanent(
        id: String,
        reason: String,
        payload: String,
        pinned: Boolean,
        gameId: String = "shark-incremental",
    ): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = gameId,
            sequence = 50_000,
            capturedAtMillis = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(),
            reason = reason,
            pinned = pinned,
            payload = payload,
            progressJson = null,
            lineageId = "legacy",
        )
    }
}
