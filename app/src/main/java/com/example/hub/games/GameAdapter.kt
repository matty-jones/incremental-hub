package com.example.hub.games

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred

/**
 * Contract that all game-specific adapters must implement.
 *
 * Adapters translate between the game's native save format and the Hub's
 * generic capture/restore pipeline. Each adapter owns a single localStorage
 * key (or a small set of keys) for a specific game.
 *
 * Typical usage pattern:
 * ```
 * val adapter = SharkIncrementalAdapter()
 * adapter.probe(webView)           // verify adapter applies
 * adapter.awaitReady(webView)      // wait for game to be ready
 * val captured = adapter.capture(webView, deferred)  // snapshot save state
 * val restored = adapter.prepareRestore(captured)    // prepare import payload
 * adapter.verifyLoaded(webView, restored, deferred)  // verify import succeeded
 * ```
 */
interface GameAdapter {

    /** Unique identifier matching the adapterId in the game definition JSON. */
    val adapterId: String

    /** Semantic version of this adapter implementation. */
    val adapterVersion: String

    /**
     * Probe the WebView to determine whether this adapter is applicable.
     *
     * Called early, before the game has necessarily finished loading. A
     * probe should be fast and non-invasive — typically just checking for
     * a presence of a known JS global, CSS selector, or localStorage key.
     *
     * @param webView the target WebView
     * @return true if this adapter believes it can handle saves in this WebView
     */
    suspend fun probe(webView: WebView): Boolean

    /**
     * Wait until the game is ready to be queried for its save state.
     *
     * Blocks until a readiness signal is observed (e.g. `player` object
     * exists for Shark Incremental, or `saveData` exists for FE000000).
     * Returns after a timeout if the signal never arrives.
     *
     * @param webView the target WebView
     * @param timeoutMs maximum time to wait in milliseconds
     * @return true if the game became ready within the timeout
     */
    suspend fun awaitReady(webView: WebView, timeoutMs: Long = 10_000L): Boolean

    /**
     * Capture the current save state from the WebView.
     *
     * Reads the game's localStorage key(s), applies any format-specific
     * decoding, and returns an opaque capture payload. The payload is
     * guaranteed to be a valid, importable save string when passed to
     * [prepareRestore].
     *
     * @param webView the target WebView
     * @param result the [QualificationResult] to record evidence into
     * @return the capture payload (a string suitable for [prepareRestore])
     */
    suspend fun capture(webView: WebView, result: QualificationResult): String?

    /**
     * Validate a capture payload that was previously produced by [capture].
     *
     * Performs format-specific sanity checks (e.g. VERSION field ≥ min,
     * valid JSON, correct marker wrapping). Returns a validation report
     * that can be displayed to the user.
     *
     * @param payload the capture payload from [capture]
     * @return true if the payload passes all validation checks
     */
    suspend fun validate(payload: String): Boolean

    /**
     * Prepare a capture payload for import into a fresh WebView.
     *
     * Re-encodes the payload into the format the game expects on load.
     * For example, Shark's adapter may re-serialize JSON and base64-encode;
     * FE000000's adapter may gzip-compress, base64-encode, and wrap markers.
     *
     * @param payload the capture payload from [capture]
     * @return the import-ready payload string
     */
    suspend fun prepareRestore(payload: String): String

    /**
     * Verify that a restored save was successfully loaded in the WebView.
     *
     * After the game has finished initializing post-import, this method
     * reads the save key from localStorage and compares it against the
     * expected value. A match indicates a successful round-trip.
     *
     * @param webView the target WebView (fresh profile)
     * @param importedPayload the result of [prepareRestore]
     * @return true if the save was verified as loaded correctly
     */
    suspend fun verifyLoaded(webView: WebView, importedPayload: String): Boolean
}
