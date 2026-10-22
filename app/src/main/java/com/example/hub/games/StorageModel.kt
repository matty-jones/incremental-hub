package com.example.hub.games

import java.security.MessageDigest

enum class StorageRole {
    PROGRESS,
    BACKUP,
    PREFERENCE,
}

/**
 * A localStorage name the adapter owns.
 * [required] names must appear in an envelope. A missing value on that entry
 * means the key is absent, which is different from a value of "".
 */
data class StorageEntry(
    val name: String,
    val required: Boolean,
    val role: StorageRole,
)

/**
 * One captured key. [value] null means the key is explicitly absent.
 * An empty string is a stored value.
 */
data class StorageSlot(
    val name: String,
    val value: String?,
)

data class SaveBundle(
    val slots: List<StorageSlot>,
) {
    fun slot(name: String): StorageSlot? = slots.firstOrNull { it.name == name }
}

data class ProgressMarker(
    val name: String,
    val value: Long,
)

/**
 * Ordered progress markers. Null means the two saves cannot be ranked.
 * Marker magnitude is not a license to compare payload length or timestamps.
 */
data class ProgressView(
    val markers: List<ProgressMarker>,
)

sealed class ImportOutcome {
    data object Unsupported : ImportOutcome()
}

/**
 * Result of checking a restored save against the running game.
 * [DEFERRED] means the page is busy, such as an offline warp: keep the
 * restored storage and do not capture or roll back.
 */
enum class RuntimeVerdict {
    ACCEPTED,
    REJECTED,
    DEFERRED,
}

/**
 * Length and SHA-256 of a save string. The text itself is not included.
 */
object PayloadDigest {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun describe(text: String?): String {
        if (text == null) return "absent"
        return "length=${text.length} sha256=${sha256(text)}"
    }

    fun sha256OrAbsent(text: String?): String = if (text == null) "absent" else sha256(text)
}

/**
 * Rank two progress views only when every marker moves the same way.
 * A null view, a different marker list, or mixed directions is [SaveComparison.INCONCLUSIVE].
 */
fun rankProgress(live: ProgressView?, stored: ProgressView?): SaveComparison {
    if (live == null || stored == null) return SaveComparison.INCONCLUSIVE
    if (live.markers.map { it.name } != stored.markers.map { it.name }) {
        return SaveComparison.INCONCLUSIVE
    }
    var direction = 0
    for (index in live.markers.indices) {
        val delta = live.markers[index].value.compareTo(stored.markers[index].value)
        if (delta == 0) continue
        if (direction == 0) {
            direction = delta
        } else if (direction != delta) {
            return SaveComparison.INCONCLUSIVE
        }
    }
    return when {
        direction > 0 -> SaveComparison.LIVE_AHEAD
        direction < 0 -> SaveComparison.LIVE_BEHIND
        else -> SaveComparison.MATCH
    }
}
