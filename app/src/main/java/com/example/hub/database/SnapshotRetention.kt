package com.example.hub.database

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.WeekFields

/**
 * Minimum coverage for accepted automatic snapshots, per game and lineage.
 * Buckets use UTC. Weeks are ISO weeks. Empty buckets are left empty.
 * Pinned rows, recovery reasons, and the active high-water id are not pruned.
 * An open capture incident skips pruning.
 */
object SnapshotRetention {
    const val RECENT_DISTINCT = 10
    const val HOURLY_WINDOW_HOURS = 48L
    const val DAILY_WINDOW_DAYS = 30L
    const val WEEKLY_WINDOW_WEEKS = 12
    private const val DELETE_BATCH = 400

    val PERMANENT_REASONS = setOf(
        SnapshotReasons.MIGRATED_BASELINE,
        SnapshotReasons.DIAGNOSTIC_BACKUP,
        SnapshotReasons.PRE_RESTORE,
        SnapshotReasons.POST_RESTORE,
        SnapshotReasons.WORKSTATION,
        SnapshotReasons.CHECKPOINT,
        SnapshotReasons.QUARANTINE,
        SnapshotReasons.LINEAGE_BASE,
    )

    fun isPermanentReason(reason: String): Boolean = reason in PERMANENT_REASONS

    fun keepIds(
        rows: List<SnapshotRecord>,
        nowMillis: Long,
        protectedIds: Set<String>,
    ): Set<String> {
        val keep = mutableSetOf<String>()
        rows.filter { isProtected(it, protectedIds) }.forEach { keep += it.id }
        val today = utcDate(nowMillis)
        val earliestDay = today.minusDays(DAILY_WINDOW_DAYS - 1)
        val hourlyStart = nowMillis - HOURLY_WINDOW_HOURS * 60L * 60L * 1000L
        val weeks = isoWeeks(today)
        for (lineageRows in rows.groupBy { it.lineageId }.values) {
            val accepted = lineageRows.filter { isAcceptedAutomatic(it) }
            keep += recentDistinct(accepted).map { it.id }
            keep += representatives(accepted.filter { it.capturedAtMillis >= hourlyStart }) { hourKey(it.capturedAtMillis) }
            keep += representatives(accepted.filter { !utcDate(it.capturedAtMillis).isBefore(earliestDay) }) { utcDate(it.capturedAtMillis) }
            keep += representatives(accepted.filter { weekKey(it.capturedAtMillis) in weeks }) { weekKey(it.capturedAtMillis) }
            if (lineageRows.isNotEmpty() && lineageRows.none { it.id in keep }) {
                keep += latest(lineageRows).id
            }
        }
        return keep
    }

    suspend fun prune(
        snapshotDao: SnapshotDao,
        instanceDao: GameInstanceDao,
        gameId: String,
        nowMillis: Long,
    ) {
        val instance = instanceDao.get(gameId) ?: return
        if (!instance.captureIncident.isNullOrBlank()) return
        val rows = snapshotDao.history(gameId)
        val protectedIds = setOfNotNull(instance.highWaterSnapshotId)
        val keep = keepIds(rows, nowMillis, protectedIds)
        val remove = rows
            .filter { it.id !in keep && !isProtected(it, protectedIds) }
            .map { it.id }
        remove.chunked(DELETE_BATCH).forEach { ids ->
            if (ids.isNotEmpty()) snapshotDao.deleteByIds(gameId, ids)
        }
    }

    private fun isProtected(row: SnapshotRecord, protectedIds: Set<String>): Boolean {
        return row.pinned || row.id in protectedIds || row.reason in PERMANENT_REASONS
    }

    private fun isAcceptedAutomatic(row: SnapshotRecord): Boolean {
        return row.reason !in PERMANENT_REASONS
    }

    private fun recentDistinct(accepted: List<SnapshotRecord>): List<SnapshotRecord> {
        val chosen = mutableListOf<SnapshotRecord>()
        for (row in accepted.sortedWith(newestFirst())) {
            if (chosen.any { sameContent(it, row) }) continue
            chosen += row
            if (chosen.size == RECENT_DISTINCT) break
        }
        return chosen
    }

    private fun <K> representatives(accepted: List<SnapshotRecord>, bucket: (SnapshotRecord) -> K): Set<String> {
        if (accepted.isEmpty()) return emptySet()
        return accepted.groupBy(bucket).map { latest(it.value).id }.toSet()
    }

    private fun latest(rows: List<SnapshotRecord>): SnapshotRecord {
        return rows.maxWith(compareBy<SnapshotRecord> { it.capturedAtMillis }.thenBy { it.sequence })
    }

    private fun newestFirst(): Comparator<SnapshotRecord> {
        return compareByDescending<SnapshotRecord> { it.capturedAtMillis }.thenByDescending { it.sequence }
    }

    private fun sameContent(left: SnapshotRecord, right: SnapshotRecord): Boolean {
        if (left.payload == right.payload) return true
        return left.contentSha256.isNotEmpty() && left.contentSha256 == right.contentSha256
    }

    private fun hourKey(millis: Long): Long = Math.floorDiv(millis, 3_600_000L)

    private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

    private fun weekKey(millis: Long): Pair<Int, Int> {
        val date = utcDate(millis)
        val fields = WeekFields.ISO
        return date.get(fields.weekBasedYear()) to date.get(fields.weekOfWeekBasedYear())
    }

    private fun isoWeeks(today: LocalDate): Set<Pair<Int, Int>> {
        val fields = WeekFields.ISO
        return buildSet {
            var date = today
            repeat(WEEKLY_WINDOW_WEEKS) {
                add(date.get(fields.weekBasedYear()) to date.get(fields.weekOfWeekBasedYear()))
                date = date.minusWeeks(1)
            }
        }
    }
}
