package com.example.hub.vault

import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRetention
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class VaultResidence {
    LOCAL,
    REMOTE_ONLY,
    BOTH,
}

enum class VaultAvailability {
    PRESENT,
    WORKSTATION_UNAVAILABLE,
}

enum class WorkstationCopy {
    KNOWN,
    UNKNOWN,
    UNAVAILABLE,
}

fun workstationCopyStatement(copy: WorkstationCopy): String = when (copy) {
    WorkstationCopy.KNOWN ->
        "Workstation copy: known. An external backup was not verified."
    WorkstationCopy.UNKNOWN ->
        "Workstation copy: unknown. An external backup was not verified."
    WorkstationCopy.UNAVAILABLE ->
        "Workstation copy: unavailable. An external backup was not verified."
}

/**
 * One timeline row. The save payload is not part of this value.
 * Phone rows sort by capture sequence. Workstation-only rows sort by the
 * workstation receive time. A device clock change cannot reorder phone rows.
 */
data class VaultEntry(
    val id: String,
    val gameId: String,
    val lineageId: String,
    val sequence: Long,
    val capturedAtMillis: Long,
    val receivedAtMillis: Long,
    val reason: String,
    val origin: String,
    val pinned: Boolean,
    val userProtected: Boolean,
    val sha256: String,
    val payloadBytes: Int,
    val integrity: String,
    val residence: VaultResidence,
    val availability: VaultAvailability,
    val active: Boolean,
    val highWater: Boolean,
    val storedDetail: String?,
) {
    val labels: List<String>
        get() = vaultLabels(this)

    val onPhone: Boolean
        get() = residence != VaultResidence.REMOTE_ONLY
}

data class VaultDayGroup(
    val title: String,
    val entries: List<VaultEntry>,
)

data class VaultBrowse(
    val gameId: String,
    val entries: List<VaultEntry>,
    val groups: List<VaultDayGroup>,
    val localCount: Int,
    val localOffset: Int,
    val pageSize: Int,
    val remoteFetched: Int,
    val remoteComplete: Boolean,
    val workstationReachable: Boolean,
) {
    val hasOlderLocal: Boolean
        get() = localOffset + pageSize < localCount

    val hasNewerLocal: Boolean
        get() = localOffset > 0

    val hasMoreRemote: Boolean
        get() = !remoteComplete
}

/**
 * A clean device may adopt one local snapshot. An occupied slot has no
 * activation action; the repository also refuses that write.
 */
fun canActivateImported(slotEmpty: Boolean, entry: VaultEntry): Boolean {
    return slotEmpty &&
        entry.availability == VaultAvailability.PRESENT &&
        entry.residence != VaultResidence.REMOTE_ONLY &&
        !entry.active
}

fun vaultLabels(entry: VaultEntry): List<String> {
    val labels = mutableListOf<String>()
    when (entry.residence) {
        VaultResidence.LOCAL -> labels += "Local"
        VaultResidence.REMOTE_ONLY -> labels += "Remote only"
        VaultResidence.BOTH -> labels += "Local"
    }
    if (entry.availability == VaultAvailability.WORKSTATION_UNAVAILABLE &&
        entry.residence == VaultResidence.REMOTE_ONLY
    ) {
        labels += "Unavailable"
    }
    if (entry.active) labels += "Active"
    if (entry.highWater) labels += "High water"
    when {
        entry.reason == SnapshotReasons.CHECKPOINT -> labels += "Manual"
        entry.reason == SnapshotReasons.QUARANTINE -> labels += "Quarantined"
        entry.reason == "cadence" || entry.reason == "request" || entry.reason == "close" ||
            entry.reason == "background" || entry.reason == "periodic" -> labels += "Automatic"
        !entry.pinned && !entry.userProtected && !SnapshotRetention.isPermanentReason(entry.reason) &&
            entry.reason != SnapshotReasons.RETAINED_POINTER -> labels += "Automatic"
    }
    if (entry.pinned || entry.userProtected || entry.highWater ||
        SnapshotRetention.isPermanentReason(entry.reason)
    ) {
        labels += "Protected"
    }
    if (!entry.active) labels += "Historical"
    return labels.distinct()
}

/**
 * Phone history is ordered by sequence, then id. Workstation-only history
 * follows in receive order. The two lists are not interleaved by wall-clock
 * time, so a changed phone clock cannot move a later sequence behind an
 * earlier one, and equal timestamps stay in sequence order.
 */
fun orderedTimeline(entries: List<VaultEntry>): List<VaultEntry> {
    val phone = entries
        .filter { it.onPhone }
        .sortedWith(compareByDescending<VaultEntry> { it.sequence }.thenBy { it.id })
    val remote = entries
        .filter { !it.onPhone }
        .sortedWith(compareByDescending<VaultEntry> { it.receivedAtMillis }.thenBy { it.id })
    return phone + remote
}

fun groupTimeline(entries: List<VaultEntry>): List<VaultDayGroup> {
    val ordered = orderedTimeline(entries)
    if (ordered.isEmpty()) return emptyList()
    val groups = mutableListOf<VaultDayGroup>()
    val phone = ordered.filter { it.onPhone }
    val remote = ordered.filter { !it.onPhone }
    groups += dayGroups(phone, workstation = false)
    groups += dayGroups(remote, workstation = true)
    return groups
}

private fun dayGroups(entries: List<VaultEntry>, workstation: Boolean): List<VaultDayGroup> {
    if (entries.isEmpty()) return emptyList()
    val groups = mutableListOf<VaultDayGroup>()
    var currentDay = utcDay(entries.first().capturedAtMillis)
    var bucket = mutableListOf<VaultEntry>()
    for (entry in entries) {
        val day = utcDay(entry.capturedAtMillis)
        if (day != currentDay && bucket.isNotEmpty()) {
            groups += VaultDayGroup(dayTitle(currentDay, workstation), bucket)
            bucket = mutableListOf()
            currentDay = day
        }
        bucket += entry
    }
    if (bucket.isNotEmpty()) {
        groups += VaultDayGroup(dayTitle(currentDay, workstation), bucket)
    }
    return groups
}

private fun utcDay(millis: Long): String {
    return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toString()
}

private fun dayTitle(day: String, workstation: Boolean): String {
    return if (workstation) "Workstation only · $day UTC" else "$day UTC"
}

private val displayTime: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

fun formatCaptureTime(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return displayTime.format(zoned)
}

fun shaPrefix(sha: String): String {
    if (sha.isBlank()) return "hash not recorded"
    return sha.take(12)
}
