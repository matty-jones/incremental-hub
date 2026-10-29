package com.example.hub.games

/**
 * Partial order used by capture commits. Timestamps, play time, and currency
 * size are not members of this order.
 */
enum class ProgressionClass {
    EQUAL,
    ADVANCED,
    REGRESSED,
    INCOMPARABLE,
    INVALID,
    RESET_APPROVED,
}

/** The worse of two comparisons wins. A loss against either reference blocks the commit. */
fun combineProgression(left: ProgressionClass, right: ProgressionClass): ProgressionClass {
    val blocking = listOf(
        ProgressionClass.INVALID,
        ProgressionClass.REGRESSED,
        ProgressionClass.INCOMPARABLE,
    )
    for (level in blocking) {
        if (left == level || right == level) return level
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
