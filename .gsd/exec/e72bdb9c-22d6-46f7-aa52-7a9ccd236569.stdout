package com.example.hub.games

/**
 * UI-ready presentation of a validated game definition.
 *
 * Carries the fields needed by the launcher card: display name, launch URL,
 * adapter identifier, and color hints from [DisplayDefaults]. The [lastPlayedAtMillis]
 * field is populated from [com.example.hub.database.GameInstance] when the DAO has
 * data; null means no session history yet.
 *
 * @param gameId            Stable identifier matching the game definition.
 * @param displayName       Human-readable game name.
 * @param launchUrl         URL to navigate to on launch.
 * @param adapterId         Identifier for the [com.example.hub.games.GameAdapter] to use.
 * @param displayDefaults   Color and icon hints from the definition.
 * @param lastPlayedAtMillis Epoch millis of the most recent play, or null if none.
 */
data class RegisteredGame(
    val gameId: String,
    val displayName: String,
    val launchUrl: String,
    val adapterId: String,
    val displayDefaults: DisplayDefaults,
    val lastPlayedAtMillis: Long? = null,
)

/**
 * UI color/icon hints extracted from a game definition's `displayDefaults` object.
 *
 * All fields are nullable so the launcher can degrade gracefully when a definition
 * omits a particular hint.
 *
 * @param showIcon      Whether to show an icon on the launcher card.
 * @param iconResId     Resource ID of the icon drawable (e.g. `R.drawable.ic_shark`).
 * @param colorPrimary  Primary accent color (hex string, e.g. `#1565C0`).
 * @param colorSecondary Secondary accent color (hex string).
 * @param badgeText     Short badge text shown on the card (e.g. `"S"` for Shark).
 */
data class DisplayDefaults(
    val showIcon: Boolean = false,
    val iconResId: String? = null,
    val colorPrimary: String? = null,
    val colorSecondary: String? = null,
    val badgeText: String? = null,
)

/**
 * A game definition paired with its validation outcome.
 *
 * Used by [GameRegistry.allDefinitions] to surface both valid and invalid
 * definitions for debugging and error reporting.
 *
 * @param definition The raw parsed JSON (as a [String]) for diagnostic dumps.
 * @param validationResult The result of validating the definition.
 * @param resolved A non-null [RegisteredGame] when validation passed; null otherwise.
 */
data class DefinitionWithStatus(
    val definition: String,
    val validationResult: com.example.hub.games.ValidationResult,
    val resolved: RegisteredGame? = null,
) {
    val isValid get() = validationResult.valid
    val isError get() = !validationResult.valid
}
