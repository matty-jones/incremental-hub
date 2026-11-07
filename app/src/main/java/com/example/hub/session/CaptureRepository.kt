package com.example.hub.session

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SnapshotRetention
import com.example.hub.database.SyncOutboxDao
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.database.enqueueAcceptedSnapshot
import com.example.hub.database.originForReason
import com.example.hub.games.GameAdapter
import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import com.example.hub.games.combineProgression
import java.util.UUID

enum class CaptureDisposition {
    STORED,
    UNCHANGED,
    QUARANTINED,
    REJECTED,
}

data class CaptureCommit(
    val disposition: CaptureDisposition,
    val classification: ProgressionClass,
    val instance: GameInstance?,
    val message: String?,
    val sync: Boolean,
)

/**
 * One commit path for manual Save, cadence, background, close, and switching.
 * The pointer and the protected high-water snapshot are read again inside the
 * Room transaction. A rejected capture does not move either one.
 */
class CaptureRepository(
    private val instanceDao: GameInstanceDao,
    private val snapshotDao: SnapshotDao?,
    private val roomDatabase: RoomDatabase?,
    private val outboxDao: SyncOutboxDao? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val fault: (suspend (String) -> Unit)? = null,
) {
    suspend fun commitIfSafe(
        gameId: String,
        candidate: String,
        reason: String,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
        manual: Boolean,
    ): CaptureCommit {
        val apply = suspend { commitLocked(gameId, candidate, reason, adapter, sessionId, stillCurrent, manual) }
        val database = roomDatabase
        val committed = if (database != null) database.withTransaction { apply() } else apply()
        if (committed.disposition == CaptureDisposition.STORED) {
            pruneAfterCommit(gameId)
        }
        return committed
    }

    /**
     * Stores [candidate] as the active save without consulting progression order.
     * The previous pointer is pinned and no snapshot row is deleted. Ordinary
     * captures must keep using [commitIfSafe].
     */
    suspend fun forceStore(
        gameId: String,
        candidate: String,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
    ): CaptureCommit {
        val apply = suspend { forceStoreLocked(gameId, candidate, adapter, sessionId, stillCurrent) }
        val database = roomDatabase
        return if (database != null) database.withTransaction { apply() } else apply()
    }

    suspend fun openLineage(
        gameId: String,
        confirmed: Boolean,
        activate: Boolean,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
    ): CaptureCommit {
        if (!confirmed) {
            return CaptureCommit(
                CaptureDisposition.REJECTED,
                ProgressionClass.INVALID,
                instanceDao.get(gameId),
                "Start over needs confirmation. The active save was kept.",
                sync = false,
            )
        }
        val apply = suspend { lineageLocked(gameId, activate, adapter, sessionId, stillCurrent) }
        val database = roomDatabase
        return if (database != null) database.withTransaction { apply() } else apply()
    }

    /**
     * Points a blank local slot at an imported snapshot. The snapshot row is
     * not rewritten. A non-blank live save is left where it is.
     */
    suspend fun restoreImportedPointer(
        gameId: String,
        snapshotId: String,
        adapter: GameAdapter? = null,
    ): CaptureCommit {
        val apply = suspend { restoreImportedLocked(gameId, snapshotId, adapter) }
        val database = roomDatabase
        return if (database != null) database.withTransaction { apply() } else apply()
    }

    /**
     * Activates [snapshotId] on a new lineage. The selected row is not rewritten.
     * The previous pointer is copied onto its old lineage, and the previous
     * high-water row is pinned. Nothing is added to the sync outbox.
     */
    suspend fun branchToSnapshot(
        gameId: String,
        snapshotId: String,
        lineageId: String,
        confirmed: Boolean,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
        activatedPayload: String? = null,
    ): CaptureCommit {
        if (!confirmed) {
            return rejected(
                ProgressionClass.INVALID,
                "A new lineage needs confirmation. The active save was kept.",
            )
        }
        val apply = suspend {
            branchLocked(
                gameId,
                snapshotId,
                lineageId,
                adapter,
                sessionId,
                stillCurrent,
                activatedPayload,
            )
        }
        val database = roomDatabase
        return if (database != null) database.withTransaction { apply() } else apply()
    }

    private suspend fun commitLocked(
        gameId: String,
        candidate: String,
        reason: String,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
        manual: Boolean,
    ): CaptureCommit {
        val instance = instanceDao.get(gameId)
            ?: return rejected(ProgressionClass.INVALID, "unready. Missing game row. Last good snapshot was kept.")
        if (!stillCurrent() || (sessionId != null && instance.sessionIdentity != sessionId)) {
            return rejected(ProgressionClass.INVALID, "unready. Stale capture was ignored. Last good snapshot was kept.")
        }
        val pointer = instance.snapshotPayload?.takeIf { it.isNotBlank() }
        val highWater = highWaterPayload(instance)
        val verdict = classify(adapter, candidate, pointer, highWater)
        val now = clock()
        return when (verdict) {
            ProgressionClass.INVALID -> {
                val detail = adapter.summarize(candidate).detail
                val message = "invalid. The capture could not be classified ($detail). Last good snapshot was kept."
                recordFailure(instance, now, message)
                rejected(ProgressionClass.INVALID, message)
            }
            ProgressionClass.REGRESSED, ProgressionClass.INCOMPARABLE ->
                quarantine(instance, candidate, pointer, highWater, verdict, adapter, now)
            ProgressionClass.EQUAL -> acceptEqual(instance, candidate, reason, adapter, manual, now)
            ProgressionClass.UNORDERED ->
                acceptContinued(instance, candidate, reason, adapter, manual, now, ProgressionClass.UNORDERED)
            ProgressionClass.ADVANCED, ProgressionClass.RESET_APPROVED ->
                acceptAdvance(instance, candidate, reason, adapter, manual, now, verdict)
        }
    }

    private suspend fun acceptEqual(
        instance: GameInstance,
        candidate: String,
        reason: String,
        adapter: GameAdapter,
        manual: Boolean,
        now: Long,
    ): CaptureCommit {
        val sameBytes = candidate == instance.snapshotPayload
        if (sameBytes && !manual) {
            fault?.invoke("before-write")
            val seen = instance.copy(lastSeenAtMillis = now, lastCaptureAttemptAtMillis = now)
            instanceDao.upsert(seen)
            return CaptureCommit(CaptureDisposition.UNCHANGED, ProgressionClass.EQUAL, seen, null, sync = false)
        }
        return acceptContinued(instance, candidate, reason, adapter, manual, now, ProgressionClass.EQUAL)
    }

    private suspend fun acceptContinued(
        instance: GameInstance,
        candidate: String,
        reason: String,
        adapter: GameAdapter,
        manual: Boolean,
        now: Long,
        verdict: ProgressionClass,
    ): CaptureCommit {
        fault?.invoke("before-write")
        val sameBytes = candidate == instance.snapshotPayload
        if (!sameBytes) retainOutgoingPointer(instance, adapter, now)
        val sha = PayloadDigest.sha256(candidate)
        val history = snapshotDao?.history(instance.gameId).orEmpty()
        val inserted = if (manual) {
            ensureSnapshot(
                history = history,
                instance = instance,
                payload = candidate,
                reason = SnapshotReasons.CHECKPOINT,
                pinned = true,
                adapter = adapter,
                now = now,
                sha = sha,
            )
        } else if (!alreadyStored(history, sha, candidate) && !recentAutomatic(history, instance.lineageId, now)) {
            ensureSnapshot(
                history = history,
                instance = instance,
                payload = candidate,
                reason = reason,
                pinned = false,
                adapter = adapter,
                now = now,
                sha = sha,
            )
        } else {
            false
        }
        val seen = instance.copy(lastSeenAtMillis = now, lastCaptureAttemptAtMillis = now)
        val updated = if (sameBytes) {
            seen
        } else {
            seen.copy(
                lastCaptureSuccessAtMillis = now,
                lastCaptureAtMillis = now,
                lastCaptureError = null,
                snapshotPayload = candidate,
            )
        }
        fault?.invoke("after-pointer")
        instanceDao.upsert(updated)
        if (!sameBytes) {
            enqueueCommitted(
                instance = updated,
                payload = candidate,
                sha = sha,
                now = now,
                reason = if (manual) SnapshotReasons.CHECKPOINT else reason,
                verdict = verdict,
            )
        }
        val disposition = if (sameBytes && !inserted) CaptureDisposition.UNCHANGED else CaptureDisposition.STORED
        return CaptureCommit(disposition, verdict, updated, null, sync = !sameBytes)
    }

    private suspend fun acceptAdvance(
        instance: GameInstance,
        candidate: String,
        reason: String,
        adapter: GameAdapter,
        manual: Boolean,
        now: Long,
        verdict: ProgressionClass,
    ): CaptureCommit {
        fault?.invoke("before-write")
        retainOutgoingPointer(instance, adapter, now)
        val sha = PayloadDigest.sha256(candidate)
        val history = snapshotDao?.history(instance.gameId).orEmpty()
        val snapshotReason = if (manual) SnapshotReasons.CHECKPOINT else reason
        val existing = matching(history, sha, candidate)
        val snapshotId = existing?.id ?: insertSnapshot(
            instance = instance,
            payload = candidate,
            reason = snapshotReason,
            pinned = manual,
            adapter = adapter,
            now = now,
            sha = sha,
        )
        val previousAnchor = instance.highWaterSnapshotId
        val updated = instance.copy(
            lastCaptureAttemptAtMillis = now,
            lastCaptureSuccessAtMillis = now,
            lastCaptureAtMillis = now,
            lastCaptureError = null,
            lastSeenAtMillis = now,
            snapshotPayload = candidate,
            highWaterSnapshotId = snapshotId ?: instance.highWaterSnapshotId,
            captureIncident = null,
            captureIncidentSha = null,
            captureIncidentCount = 0,
        )
        fault?.invoke("after-pointer")
        instanceDao.upsert(updated)
        if (snapshotId != null) {
            snapshotDao?.pinById(snapshotId)
            if (previousAnchor != null && previousAnchor != snapshotId) {
                releaseSupersededAnchor(instance, previousAnchor, adapter, now)
            }
        }
        if (snapshotId != null) {
            enqueueCommitted(
                instance = updated,
                payload = candidate,
                sha = sha,
                now = now,
                reason = snapshotReason,
                verdict = verdict,
                snapshotId = snapshotId,
            )
        }
        return CaptureCommit(CaptureDisposition.STORED, verdict, updated, null, sync = true)
    }

    private suspend fun quarantine(
        instance: GameInstance,
        candidate: String,
        pointer: String?,
        highWater: String?,
        verdict: ProgressionClass,
        adapter: GameAdapter,
        now: Long,
    ): CaptureCommit {
        val sha = PayloadDigest.sha256(candidate)
        val reference = highWater ?: pointer
        val message = incidentMessage(verdict, candidate, reference, sha, adapter)
        if (instance.captureIncident != null) {
            val counted = instance.copy(
                captureIncidentCount = instance.captureIncidentCount + 1,
                lastCaptureAttemptAtMillis = now,
                lastCaptureFailureAtMillis = now,
                lastCaptureError = message,
            )
            instanceDao.upsert(counted)
            return CaptureCommit(CaptureDisposition.QUARANTINED, verdict, counted, message, sync = false)
        }
        fault?.invoke("before-write")
        insertSnapshot(
            instance = instance,
            payload = candidate,
            reason = SnapshotReasons.QUARANTINE,
            pinned = true,
            adapter = adapter,
            now = now,
            sha = sha,
        )
        fault?.invoke("after-quarantine")
        val updated = instance.copy(
            captureIncident = message,
            captureIncidentSha = sha,
            captureIncidentCount = 1,
            lastCaptureAttemptAtMillis = now,
            lastCaptureFailureAtMillis = now,
            lastCaptureError = message,
        )
        instanceDao.upsert(updated)
        return CaptureCommit(CaptureDisposition.QUARANTINED, verdict, updated, message, sync = false)
    }

    private suspend fun forceStoreLocked(
        gameId: String,
        candidate: String,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
    ): CaptureCommit {
        val instance = instanceDao.get(gameId)
            ?: return rejected(ProgressionClass.INVALID, "unready. Missing game row. The active save was kept.")
        if (!stillCurrent() || (sessionId != null && instance.sessionIdentity != sessionId)) {
            return rejected(ProgressionClass.INVALID, "unready. Stale force-save was ignored. The active save was kept.")
        }
        if (candidate.isBlank()) {
            return rejected(ProgressionClass.INVALID, "invalid. No save was readable. The active save was kept.")
        }
        val valid = try {
            adapter.validate(candidate)
        } catch (_: Exception) {
            false
        }
        if (!valid) {
            return rejected(ProgressionClass.INVALID, "invalid. The current save failed validation. The active save was kept.")
        }
        val now = clock()
        val previous = instance.snapshotPayload?.takeIf { it.isNotBlank() }
        if (previous != null && previous != candidate) {
            pinExistingPayload(instance, previous, adapter, now)
        }
        fault?.invoke("before-write")
        val sha = PayloadDigest.sha256(candidate)
        val history = snapshotDao?.history(instance.gameId).orEmpty()
        val existing = matching(history, sha, candidate)
        val snapshotId = if (existing != null) {
            snapshotDao?.pinById(existing.id)
            existing.id
        } else {
            insertSnapshot(
                instance = instance,
                payload = candidate,
                reason = SnapshotReasons.CHECKPOINT,
                pinned = true,
                adapter = adapter,
                now = now,
                sha = sha,
            )
        }
        val updated = instance.copy(
            lastCaptureAttemptAtMillis = now,
            lastCaptureSuccessAtMillis = now,
            lastCaptureAtMillis = now,
            lastCaptureError = null,
            lastSeenAtMillis = now,
            snapshotPayload = candidate,
            highWaterSnapshotId = snapshotId ?: instance.highWaterSnapshotId,
            captureIncident = null,
            captureIncidentSha = null,
            captureIncidentCount = 0,
            recoveryState = null,
        )
        fault?.invoke("after-pointer")
        instanceDao.upsert(updated)
        if (snapshotId != null && previous != candidate) {
            enqueueCommitted(
                instance = updated,
                payload = candidate,
                sha = sha,
                now = now,
                reason = SnapshotReasons.CHECKPOINT,
                verdict = ProgressionClass.RESET_APPROVED,
                snapshotId = snapshotId,
            )
        }
        val message = "The current save is now active. Previous progress remains in history."
        return CaptureCommit(
            CaptureDisposition.STORED,
            ProgressionClass.RESET_APPROVED,
            updated,
            message,
            sync = previous != candidate && snapshotId != null,
        )
    }

    private suspend fun pinExistingPayload(
        instance: GameInstance,
        payload: String,
        adapter: GameAdapter,
        now: Long,
    ) {
        val dao = snapshotDao ?: return
        val sha = PayloadDigest.sha256(payload)
        val matches = dao.history(instance.gameId).filter { samePayload(it, sha, payload) }
        if (matches.isEmpty()) {
            insertSnapshot(
                instance = instance,
                payload = payload,
                reason = SnapshotReasons.CHECKPOINT,
                pinned = true,
                adapter = adapter,
                now = now,
                sha = sha,
            )
        } else {
            matches.forEach { dao.pinById(it.id) }
        }
    }

    private suspend fun lineageLocked(
        gameId: String,
        activate: Boolean,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
    ): CaptureCommit {
        val instance = instanceDao.get(gameId)
            ?: return rejected(ProgressionClass.INVALID, "unready. Missing game row. The active save was kept.")
        if (!stillCurrent() || (sessionId != null && instance.sessionIdentity != sessionId)) {
            return rejected(ProgressionClass.INVALID, "unready. Stale start-over was ignored. The active save was kept.")
        }
        val candidate = snapshotDao?.latestByReason(gameId, SnapshotReasons.QUARANTINE)?.payload
        if (candidate.isNullOrBlank()) {
            return rejected(ProgressionClass.INVALID, "invalid. No quarantined save is available. The active save was kept.")
        }
        val now = clock()
        val previous = instance.snapshotPayload
        val previousAnchor = instance.highWaterSnapshotId
        val anchor = if (previousAnchor != null) snapshotDao?.getById(previousAnchor) else null
        if (anchor != null && anchor.payload.isNotBlank() && anchor.payload != previous && !SnapshotRetention.isPermanentReason(anchor.reason)) {
            retainPayload(instance, anchor.payload, adapter, now)
        }
        if (!previous.isNullOrBlank()) {
            insertSnapshot(
                instance = instance,
                payload = previous,
                reason = SnapshotReasons.LINEAGE_BASE,
                pinned = true,
                adapter = adapter,
                now = now,
                sha = PayloadDigest.sha256(previous),
            )
        }
        if (!activate) {
            val parked = instance.copy(lineageId = UUID.randomUUID().toString())
            insertSnapshot(
                instance = parked,
                payload = candidate,
                reason = SnapshotReasons.LINEAGE_BASE,
                pinned = true,
                adapter = adapter,
                now = now,
                sha = PayloadDigest.sha256(candidate),
            )
            val kept = instance.copy(lastCaptureAttemptAtMillis = now)
            instanceDao.upsert(kept)
            val message = "The quarantined save was kept on a separate lineage. The active save was kept."
            return CaptureCommit(CaptureDisposition.STORED, ProgressionClass.RESET_APPROVED, kept, message, sync = false)
        }
        val lineage = UUID.randomUUID().toString()
        val switched = instance.copy(lineageId = lineage)
        val sha = PayloadDigest.sha256(candidate)
        val snapshotId = insertSnapshot(
            instance = switched,
            payload = candidate,
            reason = SnapshotReasons.LINEAGE_BASE,
            pinned = true,
            adapter = adapter,
            now = now,
            sha = sha,
        )
        val updated = switched.copy(
            snapshotPayload = candidate,
            highWaterSnapshotId = snapshotId ?: switched.highWaterSnapshotId,
            captureIncident = null,
            captureIncidentSha = null,
            captureIncidentCount = 0,
            lastCaptureError = null,
            lastCaptureAttemptAtMillis = now,
            lastCaptureSuccessAtMillis = now,
            lastCaptureAtMillis = now,
            lastSeenAtMillis = now,
        )
        instanceDao.upsert(updated)
        if (snapshotId != null) snapshotDao?.pinById(snapshotId)
        if (previousAnchor != null && previousAnchor != updated.highWaterSnapshotId) {
            releaseSupersededAnchor(instance, previousAnchor, adapter, now)
        }
        val message = lineageMessage(adapter, previous, candidate)
        return CaptureCommit(CaptureDisposition.STORED, ProgressionClass.RESET_APPROVED, updated, message, sync = false)
    }

    private suspend fun retainOutgoingPointer(instance: GameInstance, adapter: GameAdapter, now: Long) {
        val payload = instance.snapshotPayload?.takeIf { it.isNotBlank() } ?: return
        retainPayload(instance, payload, adapter, now)
    }

    private suspend fun retainPayload(
        instance: GameInstance,
        payload: String,
        adapter: GameAdapter,
        now: Long,
    ) {
        if (payload.isBlank() || snapshotDao == null) return
        val sha = PayloadDigest.sha256(payload)
        val stored = snapshotDao.history(instance.gameId).any { row ->
            row.reason != SnapshotReasons.QUARANTINE && samePayload(row, sha, payload)
        }
        if (stored) return
        insertSnapshot(
            instance = instance,
            payload = payload,
            reason = SnapshotReasons.RETAINED_POINTER,
            pinned = false,
            adapter = adapter,
            now = now,
            sha = sha,
        )
    }

    private suspend fun releaseSupersededAnchor(
        owner: GameInstance,
        oldId: String,
        adapter: GameAdapter,
        now: Long,
    ) {
        val old = snapshotDao?.getById(oldId) ?: return
        if (SnapshotRetention.isPermanentReason(old.reason)) return
        if (old.payload.isNotBlank()) retainPayload(owner, old.payload, adapter, now)
        snapshotDao?.releaseAutomaticPin(oldId)
    }

    private suspend fun recordFailure(instance: GameInstance, now: Long, error: String) {
        instanceDao.upsert(
            instance.copy(
                lastCaptureAttemptAtMillis = now,
                lastCaptureFailureAtMillis = now,
                lastCaptureError = error,
            ),
        )
    }

    private suspend fun highWaterPayload(instance: GameInstance): String? {
        val id = instance.highWaterSnapshotId ?: return null
        return snapshotDao?.getById(id)?.payload?.takeIf { it.isNotBlank() }
    }

    private fun classify(
        adapter: GameAdapter,
        candidate: String,
        pointer: String?,
        highWater: String?,
    ): ProgressionClass {
        val againstPointer = safeClassify(adapter, candidate, pointer)
        if (highWater.isNullOrBlank() || highWater == pointer) return againstPointer
        return combineProgression(againstPointer, safeClassify(adapter, candidate, highWater))
    }

    private fun safeClassify(adapter: GameAdapter, candidate: String, reference: String?): ProgressionClass {
        return try {
            adapter.classifyProgress(candidate, reference)
        } catch (_: Exception) {
            ProgressionClass.INCOMPARABLE
        }
    }

    private suspend fun ensureSnapshot(
        history: List<SnapshotRecord>,
        instance: GameInstance,
        payload: String,
        reason: String,
        pinned: Boolean,
        adapter: GameAdapter,
        now: Long,
        sha: String,
    ): Boolean {
        if (matching(history, sha, payload)?.let { it.reason == reason && it.pinned == pinned } == true) return false
        if (pinned && history.any { it.reason == reason && it.pinned && (it.contentSha256 == sha || it.payload == payload) }) {
            return false
        }
        insertSnapshot(instance, payload, reason, pinned, adapter, now, sha)
        return true
    }

    private suspend fun insertSnapshot(
        instance: GameInstance,
        payload: String,
        reason: String,
        pinned: Boolean,
        adapter: GameAdapter,
        now: Long,
        sha: String,
    ): String? {
        val dao = snapshotDao ?: return null
        val id = UUID.randomUUID().toString()
        dao.insert(
            SnapshotRecord(
                id = id,
                gameId = instance.gameId,
                sequence = dao.maxSequence(instance.gameId) + 1,
                capturedAtMillis = now,
                reason = reason,
                pinned = pinned,
                payload = payload,
                progressJson = progressJson(adapter, payload),
                origin = originForReason(reason),
                lineageId = instance.lineageId,
                contentSha256 = sha,
            ),
        )
        return id
    }

    private fun matching(history: List<SnapshotRecord>, sha: String, payload: String): SnapshotRecord? {
        return history.firstOrNull { samePayload(it, sha, payload) }
    }

    private fun samePayload(row: SnapshotRecord, sha: String, payload: String): Boolean {
        return row.contentSha256 == sha || (row.contentSha256.isEmpty() && row.payload == payload)
    }

    private fun alreadyStored(history: List<SnapshotRecord>, sha: String, payload: String): Boolean {
        return matching(history, sha, payload) != null
    }

    private fun recentAutomatic(history: List<SnapshotRecord>, lineage: String, now: Long): Boolean {
        val since = now - SAMPLE_WINDOW_MS
        return history.any { row ->
            row.lineageId == lineage &&
                !row.pinned &&
                row.capturedAtMillis >= since &&
                row.reason != SnapshotReasons.QUARANTINE &&
                row.reason != SnapshotReasons.LINEAGE_BASE
        }
    }

    private fun progressJson(adapter: GameAdapter, payload: String): String {
        val summary = adapter.summarize(payload)
        return org.json.JSONObject()
            .put("standing", summary.standing.name)
            .put("detail", summary.detail)
            .put("bytes", summary.byteLength)
            .put("version", summary.version ?: org.json.JSONObject.NULL)
            .toString()
    }

    private fun incidentMessage(
        verdict: ProgressionClass,
        candidate: String,
        reference: String?,
        candidateSha: String,
        adapter: GameAdapter,
    ): String {
        val kind = if (verdict == ProgressionClass.REGRESSED) "regression" else "incomparable"
        val referenceSha = if (reference.isNullOrBlank()) "absent" else PayloadDigest.sha256(reference)
        val preserved = if (verdict == ProgressionClass.INCOMPARABLE) {
            " The raw payload was preserved and the last verified pointer was kept. " +
                "Do not continue on an unsynchronised save."
        } else {
            ""
        }
        return "Capture incident $verdict ($kind). Active $referenceSha ${adapter.summarize(reference).detail}. " +
            "Quarantined $candidateSha ${adapter.summarize(candidate).detail}. The active save was kept.$preserved"
    }

    private fun lineageMessage(adapter: GameAdapter, previous: String?, candidate: String): String {
        val previousSha = if (previous.isNullOrBlank()) "absent" else PayloadDigest.sha256(previous)
        val candidateSha = PayloadDigest.sha256(candidate)
        return "New lineage is active. Previous $previousSha ${adapter.summarize(previous).detail}. " +
            "Active $candidateSha ${adapter.summarize(candidate).detail}. The previous save remains in history."
    }

    private suspend fun restoreImportedLocked(
        gameId: String,
        snapshotId: String,
        adapter: GameAdapter?,
    ): CaptureCommit {
        val dao = snapshotDao
            ?: return rejected(ProgressionClass.INVALID, "Snapshot storage is unavailable. The active save was kept.")
        val row = dao.getById(snapshotId)
            ?: return rejected(ProgressionClass.INVALID, "That snapshot was not found. The active save was kept.")
        if (row.gameId != gameId) {
            return rejected(ProgressionClass.INVALID, "The snapshot belongs to a different game. The active save was kept.")
        }
        val digest = PayloadDigest.sha256(row.payload)
        if (row.contentSha256.isNotEmpty() && row.contentSha256 != digest) {
            return rejected(ProgressionClass.INVALID, "The stored hash does not match the payload. The active save was kept.")
        }
        if (adapter != null) {
            val valid = try {
                adapter.validate(row.payload)
            } catch (_: Exception) {
                false
            }
            if (!valid) {
                return rejected(ProgressionClass.INVALID, "The snapshot failed validation. The active save was kept.")
            }
        }
        val existing = instanceDao.get(gameId)
        if (!existing?.snapshotPayload.isNullOrBlank()) {
            return rejected(ProgressionClass.REGRESSED, "A live save is already stored. The active save was kept.")
        }
        val now = clock()
        val base = existing ?: GameInstance(
            gameId = gameId,
            profileName = "hub-game-$gameId",
            sessionIdentity = "imported",
            lastPlayedAtMillis = now,
            lastCaptureAtMillis = now,
        )
        val updated = base.copy(
            snapshotPayload = row.payload,
            lineageId = row.lineageId,
            highWaterSnapshotId = row.id,
            lastCaptureAtMillis = now,
            lastCaptureSuccessAtMillis = now,
            lastCaptureAttemptAtMillis = now,
            lastCaptureError = null,
            captureIncident = null,
        )
        instanceDao.upsert(updated)
        return CaptureCommit(
            CaptureDisposition.STORED,
            ProgressionClass.EQUAL,
            updated,
            "Restored the imported snapshot. The snapshot bytes were not rewritten.",
            sync = false,
        )
    }

    private suspend fun branchLocked(
        gameId: String,
        snapshotId: String,
        lineageId: String,
        adapter: GameAdapter,
        sessionId: String?,
        stillCurrent: () -> Boolean,
        activatedPayload: String?,
    ): CaptureCommit {
        val instance = instanceDao.get(gameId)
            ?: return rejected(ProgressionClass.INVALID, "The game row is missing. The active save was kept.")
        if (!stillCurrent() || (sessionId != null && instance.sessionIdentity != sessionId)) {
            return rejected(ProgressionClass.INVALID, "The restore session changed. The active save was kept.")
        }
        val dao = snapshotDao
            ?: return rejected(ProgressionClass.INVALID, "Snapshot storage is unavailable. The active save was kept.")
        val row = dao.getById(snapshotId)
            ?: return rejected(ProgressionClass.INVALID, "That snapshot was not found. The active save was kept.")
        if (row.gameId != gameId || row.lineageId != lineageId) {
            return rejected(
                ProgressionClass.INVALID,
                "The snapshot id and lineage do not match. The active save was kept.",
            )
        }
        val digest = PayloadDigest.sha256(row.payload)
        if (row.contentSha256.isNotEmpty() && row.contentSha256 != digest) {
            return rejected(
                ProgressionClass.INVALID,
                "The stored hash does not match the payload. The active save was kept.",
            )
        }
        val valid = try {
            adapter.validate(row.payload)
        } catch (_: Exception) {
            false
        }
        if (!valid) {
            return rejected(
                ProgressionClass.INVALID,
                "The snapshot failed validation. The active save was kept.",
            )
        }
        val verdict = safeClassify(adapter, row.payload, instance.snapshotPayload)
        if (verdict == ProgressionClass.INVALID) {
            return rejected(verdict, "The snapshot could not be classified. The active save was kept.")
        }
        val pointerPayload = activatedPointer(adapter, row.payload, activatedPayload)
            ?: return rejected(
                ProgressionClass.INCOMPARABLE,
                "The running game did not hold the selected snapshot. The active save was kept.",
            )
        fault?.invoke("branch-before-write")
        val now = clock()
        val anchor = instance.highWaterSnapshotId
        if (anchor != null) dao.pinById(anchor)
        val previous = instance.snapshotPayload?.takeIf { it.isNotBlank() }
        if (previous != null) {
            insertLineageBase(instance, instance.lineageId, previous, adapter, now)
        }
        val newLineage = UUID.randomUUID().toString()
        val newId = insertLineageBase(instance, newLineage, pointerPayload, adapter, now)
            ?: error("The new lineage was not stored.")
        fault?.invoke("branch-before-pointer")
        val updated = instance.copy(
            lineageId = newLineage,
            snapshotPayload = pointerPayload,
            highWaterSnapshotId = newId,
            lastCaptureAttemptAtMillis = now,
            lastCaptureSuccessAtMillis = now,
            lastCaptureAtMillis = now,
            lastCaptureError = null,
            lastSeenAtMillis = now,
            recoveryState = null,
            captureIncident = null,
            captureIncidentSha = null,
            captureIncidentCount = 0,
        )
        instanceDao.upsert(updated)
        val message = "Restored on a new local lineage. The workstation pointer was not moved. " +
            "The previous high-water snapshot was kept."
        return CaptureCommit(CaptureDisposition.STORED, verdict, updated, message, sync = false)
    }

    private suspend fun activatedPointer(
        adapter: GameAdapter,
        source: String,
        activated: String?,
    ): String? {
        if (activated.isNullOrBlank() || activated == source) return source
        val valid = try {
            adapter.validate(activated)
        } catch (_: Exception) {
            false
        }
        if (!valid) return null
        val relation = safeClassify(adapter, activated, source)
        return if (relation == ProgressionClass.EQUAL || relation == ProgressionClass.ADVANCED) {
            activated
        } else {
            null
        }
    }

    private suspend fun insertLineageBase(
        instance: GameInstance,
        lineageId: String,
        payload: String,
        adapter: GameAdapter,
        now: Long,
    ): String? {
        val dao = snapshotDao ?: return null
        val id = UUID.randomUUID().toString()
        dao.insert(
            SnapshotRecord(
                id = id,
                gameId = instance.gameId,
                sequence = dao.maxSequence(instance.gameId) + 1,
                capturedAtMillis = now,
                reason = SnapshotReasons.LINEAGE_BASE,
                pinned = true,
                payload = payload,
                progressJson = progressJson(adapter, payload),
                origin = originForReason(SnapshotReasons.LINEAGE_BASE),
                lineageId = lineageId,
                contentSha256 = PayloadDigest.sha256(payload),
            ),
        )
        return id
    }

    private fun rejected(classification: ProgressionClass, message: String): CaptureCommit {
        return CaptureCommit(CaptureDisposition.REJECTED, classification, null, message, sync = false)
    }

    private suspend fun enqueueCommitted(
        instance: GameInstance,
        payload: String,
        sha: String,
        now: Long,
        reason: String,
        verdict: ProgressionClass,
        snapshotId: String? = null,
    ) {
        val dao = outboxDao ?: return
        if (reason == SnapshotReasons.QUARANTINE || verdict == ProgressionClass.REGRESSED || verdict == ProgressionClass.INCOMPARABLE) {
            return
        }
        val id = snapshotId ?: snapshotDao?.history(instance.gameId)?.lastOrNull { row ->
            row.reason != SnapshotReasons.QUARANTINE && (row.contentSha256 == sha || row.payload == payload)
        }?.id ?: return
        val ordered = verdict != ProgressionClass.UNORDERED
        enqueueAcceptedSnapshot(
            dao = dao,
            instance = instance,
            snapshotId = id,
            payload = payload,
            sha = sha,
            capturedAtMillis = now,
            reason = reason,
            integrity = if (ordered) SyncOutboxEntry.INTEGRITY_ACCEPTED else SyncOutboxEntry.INTEGRITY_ARCHIVED,
            proposePointer = ordered,
            enqueuedAtMillis = now,
        )
    }

    /**
     * Retention runs after the snapshot, pointer, and outbox commit. A pruning
     * failure leaves that commit in place.
     */
    private suspend fun pruneAfterCommit(gameId: String) {
        val dao = snapshotDao ?: return
        try {
            SnapshotRetention.prune(dao, instanceDao, gameId, clock(), roomDatabase)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Automatic pruning failed after the save committed for $gameId", error)
        }
    }

    private companion object {
        const val SAMPLE_WINDOW_MS = 60L * 60L * 1000L
        const val TAG = "CaptureRepository"
    }
}
