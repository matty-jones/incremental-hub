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

    /** localStorage key that holds this game's save. */
    val storageKey: String

    /**
     * Read the persisted save without calling the game's save routine.
     */
    suspend fun peekStorage(webView: WebView): PeekOutcome

    /**
     * True when the pre-script bootstrap page ran in this document.
     */
    suspend fun confirmBootstrap(webView: WebView): Boolean

    fun summarize(payload: String?): SaveSummary

    /**
     * True when the running game's in-memory progress matches [importedPayload].
     * Must not call the game's save routine.
     */
    suspend fun acceptsRuntime(webView: WebView, importedPayload: String): Boolean

    /**
     * When true, snapshots are version-1 envelopes and restores are journaled.
     * Shark and FE000000 stay on the raw string.
     */
    val usesSaveEnvelope: Boolean get() = false

    /** Keys this adapter captures. The default is the single [storageKey]. */
    val storageEntries: List<StorageEntry>
        get() = listOf(StorageEntry(storageKey, required = true, role = StorageRole.PROGRESS))

    /** Credentials and account tokens. They are never part of an envelope. */
    val deniedStorageKeys: Set<String> get() = emptySet()

    /** JS expression returning extra key names. Default discovers nothing. */
    fun discoveryScript(): String = "[]"

    /** True for a runtime-discovered name such as an NG+3 slot key. */
    fun allowsDiscoveredKey(name: String): Boolean = false

    /**
     * Diagnostic probe for this adapter. Shark's probe must not be the gate
     * for every game. An empty script means there is no page probe.
     */
    val readinessScript: String get() = ""

    /**
     * Save string used only when a qualification run has to inject storage.
     * Null refuses the injection. A shared synthetic blob is not a fixture.
     */
    val qualificationFixture: String? get() = null

    fun envelopePolicy(): EnvelopePolicy = EnvelopePolicy(
        allowedNames = storageEntries.map { it.name }.toSet(),
        requiredNames = storageEntries.filter { it.required }.map { it.name }.toSet(),
        deniedNames = deniedStorageKeys,
        allowDynamic = ::allowsDiscoveredKey,
    )

    /**
     * Every owned key from one evaluation. The default wraps [capture].
     * Envelope adapters override this so the keys come from one script.
     */
    suspend fun captureBundle(webView: WebView, result: QualificationResult): SaveBundle? {
        val payload = capture(webView, result) ?: return null
        return SaveBundle(listOf(StorageSlot(storageKey, payload)))
    }

    suspend fun prepareRestoreBundle(payload: String): SaveBundle {
        val prepared = prepareRestore(payload)
        return if (usesSaveEnvelope) {
            when (val decoded = SaveEnvelope.decode(prepared, envelopePolicy())) {
                is EnvelopeDecode.Ok -> decoded.bundle
                is EnvelopeDecode.Rejected -> SaveBundle(emptyList())
            }
        } else {
            SaveBundle(listOf(StorageSlot(storageKey, prepared)))
        }
    }

    /**
     * Null when this payload has no total order. The default compare then
     * returns [SaveComparison.INCONCLUSIVE] for unequal valid saves.
     */
    fun progressView(payload: String?): ProgressView? = null

    /** The session does not guess a game's import function. */
    suspend fun nativeImport(webView: WebView, exportText: String): ImportOutcome =
        ImportOutcome.Unsupported

    /**
     * Why [capture] or [captureBundle] returned null. Null means an ordinary failure.
     * A deferred offline warp uses this so the session can say the snapshot was kept.
     */
    fun captureDecline(): String? = null

    /**
     * [ACCEPTED] enters play. [REJECTED] rolls the restore back.
     * [DEFERRED] keeps the restored keys and does not capture.
     */
    suspend fun runtimeVerdict(webView: WebView, importedPayload: String): RuntimeVerdict =
        if (acceptsRuntime(webView, importedPayload)) RuntimeVerdict.ACCEPTED else RuntimeVerdict.REJECTED

    fun compare(livePayload: String?, storedPayload: String?): SaveComparison {
        val storedStanding = summarize(storedPayload).standing
        if (storedStanding == SaveStanding.ABSENT) return SaveComparison.NO_SNAPSHOT
        val liveStanding = summarize(livePayload).standing
        if (liveStanding == SaveStanding.ABSENT) return SaveComparison.LIVE_MISSING
        if (liveStanding == SaveStanding.INVALID || storedStanding == SaveStanding.INVALID) {
            return SaveComparison.INCONCLUSIVE
        }
        if (livePayload != null && livePayload == storedPayload) return SaveComparison.MATCH
        return rankProgress(progressView(livePayload), progressView(storedPayload))
    }

    /**
     * Capture-commit order. A missing reference is a first save and may become
     * the pointer. An unequal payload is [ProgressionClass.UNORDERED] unless
     * the adapter has a real order or an explicit contradiction. Unordered
     * saves may replace the pointer only after the previous payload is retained.
     * They do not move the high-water anchor.
     */
    fun classifyProgress(candidate: String?, reference: String?): ProgressionClass {
        if (candidate.isNullOrBlank()) return ProgressionClass.INVALID
        if (reference.isNullOrBlank()) return ProgressionClass.ADVANCED
        if (candidate == reference) return ProgressionClass.EQUAL
        return ProgressionClass.UNORDERED
    }

    /**
     * The first candidate is the profile pointer. Return it when it is a
     * valid fresh or progressed save. Do not scan later candidates.
     */
    fun chooseStored(candidates: List<String>): String? {
        val pointer = candidates.firstOrNull() ?: return null
        val standing = summarize(pointer).standing
        return if (standing == SaveStanding.PROGRESSED || standing == SaveStanding.FRESH) {
            pointer
        } else {
            null
        }
    }
}
