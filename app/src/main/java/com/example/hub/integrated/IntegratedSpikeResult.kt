package com.example.hub.integrated

/**
 * Immutable result of a single stage in the integrated play-capture-restore spike.
 *
 * @param stage    human-readable name of the stage (e.g. "source-profile", "capture", "destroy")
 * @param passed   true if the stage succeeded, false otherwise
 * @param evidence human-readable evidence lines produced during the stage
 */
data class IntegratedSpikeStage(
    val stage: String,
    val passed: Boolean,
    val evidence: List<String> = emptyList(),
)

/**
 * Immutable aggregate of a full integrated play-capture-restore spike run.
 *
 * Carries the full flow's outcome from core logic to the UI. Unlike
 * [com.example.hub.games.QualificationResult], this type has fields tuned
 * to S04's distinct stages (source profile, capture, destroy, fresh profile,
 * import) rather than the generic stage model.
 *
 * @param sourceProfileName  the profile bound to the source WebView
 * @param capturedSaveLength length of the captured save payload in characters
 * @param destroyedWebView   true if the source WebView was successfully destroyed
 * @param profileDataSurvived true if the source profile's localStorage survived WebView destruction
 * @param freshProfileName   the profile bound to the fresh (restore-target) WebView
 * @param importVerified     true if the captured save was verified after import into the fresh WebView
 * @param blocker            non-null when a showstopper issue prevents the spike from completing
 * @param stages             ordered list of per-stage results
 */
data class IntegratedSpikeResult(
    val sourceProfileName: String,
    val capturedSaveLength: Int,
    val destroyedWebView: Boolean,
    val profileDataSurvived: Boolean,
    val freshProfileName: String,
    val importVerified: Boolean,
    val blocker: String? = null,
    val stages: List<IntegratedSpikeStage> = emptyList(),
) {
    /** True when every tracked stage passed and no blocker was recorded. */
    val passed get() = blocker == null && stages.all { it.passed }

    /** Convenience: first evidence line of the first failed stage, or null. */
    val firstFailure: String?
        get() = stages.firstOrNull { !it.passed }?.evidence?.firstOrNull()

    /** Serialize this result to a JSON string for the JSON report. */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.append("  \"sourceProfileName\": \"").append(escapeJson(sourceProfileName)).append("\",\n")
        sb.append("  \"capturedSaveLength\": ").append(capturedSaveLength).append(",\n")
        sb.append("  \"destroyedWebView\": ").append(destroyedWebView).append(",\n")
        sb.append("  \"profileDataSurvived\": ").append(profileDataSurvived).append(",\n")
        sb.append("  \"freshProfileName\": \"").append(escapeJson(freshProfileName)).append("\",\n")
        sb.append("  \"importVerified\": ").append(importVerified).append(",\n")
        val blockerJson = if (blocker != null) "\"${escapeJson(blocker)}\"" else "null"
        sb.append("  \"blocker\": ").append(blockerJson).append(",\n")
        sb.append("  \"passed\": ").append(passed).append(",\n")
        sb.appendLine("  \"stages\": [")
        stages.forEachIndexed { i, stage ->
            sb.appendLine("    {")
            sb.append("      \"stage\": \"").append(escapeJson(stage.stage)).append("\",\n")
            sb.append("      \"passed\": ").append(stage.passed).append(",\n")
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

/**
 * Mutable accumulator for building [IntegratedSpikeResult] incrementally.
 *
 * [IntegratedSpikeResult] is immutable, so we use this mutable helper to
 * collect stages and blocker information as the spike progresses, then
 * build the final result once the flow is complete.
 */
class IntegratedSpikeAccumulator(
    private val sourceProfileName: String,
    private val freshProfileName: String,
) {
    var capturedSaveLength: Int = 0
    var destroyedWebView: Boolean = false
    var profileDataSurvived: Boolean = false
    var importVerified: Boolean = false
    private val stages = mutableListOf<IntegratedSpikeStage>()
    var blocker: String? = null

    fun addStage(
        stage: String,
        passed: Boolean,
        evidence: List<String> = emptyList(),
    ) {
        stages.add(IntegratedSpikeStage(stage, passed, evidence))
    }

    fun build(): IntegratedSpikeResult = IntegratedSpikeResult(
        sourceProfileName = sourceProfileName,
        capturedSaveLength = capturedSaveLength,
        destroyedWebView = destroyedWebView,
        profileDataSurvived = profileDataSurvived,
        freshProfileName = freshProfileName,
        importVerified = importVerified,
        blocker = blocker,
        stages = stages.toList(),
    )
}
