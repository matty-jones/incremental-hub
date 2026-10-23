package com.example.hub.session

/**
 * Result of the menu Save action. [SAVED] is published only after the capture
 * has been validated and the active snapshot has been replaced.
 */
data class ManualSaveNotice(
    val id: Long,
    val outcome: ManualSaveOutcome,
    val message: String,
)

enum class ManualSaveOutcome {
    SAVED,
    FAILED,
    DEFERRED,
    BUSY,
}

const val MANUAL_SAVE_SUCCESS = "Game saved"

fun manualSaveMessage(outcome: ManualSaveOutcome, detail: String? = null): String {
    val text = detail?.trim().orEmpty()
    return when (outcome) {
        ManualSaveOutcome.SAVED -> MANUAL_SAVE_SUCCESS
        ManualSaveOutcome.DEFERRED ->
            text.ifEmpty { "Save was deferred. Last good snapshot was kept." }
        ManualSaveOutcome.BUSY ->
            text.ifEmpty { "Save already in progress." }
        ManualSaveOutcome.FAILED ->
            text.ifEmpty { "Save failed. Last good snapshot was kept." }
    }
}
