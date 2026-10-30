package com.example.hub.games

/**
 * Partial order used by capture commits. Timestamps, play time, and currency
 * size are not members of this order.
 *
 * [UNORDERED] means the adapter has no reliable order for these two payloads.
 * The previous payload stays recoverable and the high-water anchor does not
 * move. [INCOMPARABLE] means the saves contradict, so the capture is quarantined.
 *
 * Shark does not order fields inside one milestone: play time, shark level,
 * total fish, upgrades, purchases, and every other JSON key. Trimps does not
 * order a universe or challenge change that did not increase totalPortals.
 * Synergism does not order worlds or golden quarks. FE000000 has no order.
 */
enum class ProgressionClass {
    EQUAL,
    ADVANCED,
    REGRESSED,
    INCOMPARABLE,
    UNORDERED,
    INVALID,
    RESET_APPROVED,
}

/** The worse of two comparisons wins. A loss against either reference blocks an advance. */
fun combineProgression(left: ProgressionClass, right: ProgressionClass): ProgressionClass {
    val blocking = listOf(
        ProgressionClass.INVALID,
        ProgressionClass.REGRESSED,
        ProgressionClass.INCOMPARABLE,
    )
    for (level in blocking) {
        if (left == level || right == level) return level
    }
    if (left == ProgressionClass.UNORDERED || right == ProgressionClass.UNORDERED) {
        return ProgressionClass.UNORDERED
    }
    if (left == ProgressionClass.RESET_APPROVED || right == ProgressionClass.RESET_APPROVED) {
        return ProgressionClass.RESET_APPROVED
    }
    return if (left == ProgressionClass.ADVANCED || right == ProgressionClass.ADVANCED) {
        ProgressionClass.ADVANCED
    } else {
        ProgressionClass.EQUAL
    }
}
