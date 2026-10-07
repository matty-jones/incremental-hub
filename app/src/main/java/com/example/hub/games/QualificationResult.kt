package com.example.hub.games

/**
 * Immutable result of a single game qualification stage.
 *
 * @param stage       human-readable name of the stage (e.g. "probe", "capture", "import")
 * @param passed      true if the stage succeeded, false otherwise
 * @param evidence    human-readable evidence lines produced during the stage
 * @param durationMs  elapsed time for this stage in milliseconds, or -1 if unknown
 */
data class StageResult(
    val stage: String,
    val passed: Boolean,
    val evidence: List<String> = emptyList(),
    val durationMs: Long = -1L,
)

/**
 * Immutable aggregate of a full game qualification run.
 *
 * Each [StageResult] records pass/fail, evidence lines, and timing for a
 * single stage of the round-trip proof. The overall [passed] field is true
 * only when every stage passed and [blocker] is null.
 *
 * @param gameId     the game identifier (matches [GameAdapter.adapterId])
 * @param displayName human-readable game name
 * @param stages     ordered list of stage results
 * @param blocker    non-null when a showstopper issue prevents further qualification
 * @param reportPath optional path to a JSON report written to [android.content.Context.filesDir]
 */
data class QualificationResult(
    val gameId: String,
    val displayName: String,
    val stages: List<StageResult>,
    val blocker: String? = null,
    val reportPath: String? = null,
) {
    /** True when every stage passed and no blocker was recorded. */
    val passed get() = blocker == null && stages.all { it.passed }

    /** Convenience: first evidence line of the first failed stage, or null. */
    val firstFailure: String?
        get() = stages.firstOrNull { !it.passed }?.evidence?.firstOrNull()

    /** Convenience: total elapsed time across all stages. */
    val totalDurationMs: Long get() = stages.map { it.durationMs }.filter { it >= 0L }.sum()

    /** Serialize this result to a JSON string for [reportPath]. */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.append("  \"gameId\": \"").append(escapeJson(gameId)).append("\",\n")
        sb.append("  \"displayName\": \"").append(escapeJson(displayName)).append("\",\n")
        sb.append("  \"passed\": ").append(passed).append(",\n")
        val blockerJson = if (blocker != null) "\"${escapeJson(blocker)}\"" else "null"
        sb.append("  \"blocker\": ").append(blockerJson).append(",\n")
        sb.append("  \"totalDurationMs\": ").append(totalDurationMs).append(",\n")
        sb.appendLine("  \"stages\": [")
        stages.forEachIndexed { i, stage ->
            sb.appendLine("    {")
            sb.append("      \"stage\": \"").append(escapeJson(stage.stage)).append("\",\n")
            sb.append("      \"passed\": ").append(stage.passed).append(",\n")
            sb.append("      \"durationMs\": ").append(stage.durationMs).append(",\n")
            sb.appendLine("      \"evidence\": [")
            stage.evidence.forEachIndexed { j, line ->
                sb.append("        \"").append(escapeJson(line)).append("\"")
                if (j < stage.evidence.size - 1) sb.append(",")
                sb.appendLine()
            }
            sb.appendLine("      ]")
            sb.appendLine("    }" + if (i < stages.size - 1) "," else "")
        }
        sb.appendLine("  ]")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
