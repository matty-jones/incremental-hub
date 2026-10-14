package com.example.hub.bridge

import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebMessageCompat
import com.example.hub.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android-side host for bridge message validation.
 *
 * Wraps a [android.webkit.WebView]'s bridge listener via [WebViewCompat] and
 * forwards validated messages to a callback. Rejects invalid messages silently
 * (with log) so that stale bridge callbacks from old WebViews / renderers /
 * games / epochs cannot mutate the current session.
 *
 * The host tracks:
 * - [approvedOrigins] per game — only messages from approved origins are accepted.
 * - [navigationEpoch] — incremented on each new session open; messages with a
 *   stale epoch are rejected.
 * - [outstandingRequests] — the set of request IDs currently awaiting a response.
 *
 * The [validator] is a pure [BridgeValidator] that can be swapped for testing.
 */
class BridgeHost(
    private val validator: BridgeValidator = BridgeValidator(),
) {
    companion object {
        private const val TAG = "BridgeHost"

        /**
         * The JavaScript channel name used for bridge communication.
         * Matches the constant expected by the game WebView.
         */
        const val CHANNEL_NAME = "hubBridge"
    }

    // -----------------------------------------------------------------
    // Public state
    // -----------------------------------------------------------------

    private val _activeGameId = MutableStateFlow<String?>(null)
    val activeGameId: StateFlow<String?> = _activeGameId.asStateFlow()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Closed)
    /** Current session state the host is observing. */
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _navigationEpoch = MutableStateFlow<Int>(0)
    /** Current navigation epoch — incremented per session open. */
    val navigationEpoch: StateFlow<Int> = _navigationEpoch.asStateFlow()

    // -----------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------

    /** Origins approved for the current game session. */
    private val _approvedOrigins = MutableStateFlow<Set<String>>(emptySet())
    private val approvedOrigins: Set<String> get() = _approvedOrigins.value

    /** Set of request IDs currently outstanding (awaiting response). */
    private val _outstandingRequests = MutableStateFlow<Set<String>>(emptySet())
    private val outstandingRequests: Set<String> get() = _outstandingRequests.value

    /** Coroutine scope for bridge message dispatch. */
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    /** The registered WebView, set when [attach] is called. */
    var webView: android.webkit.WebView? = null
        private set

    // -----------------------------------------------------------------
    // Callback types
    // -----------------------------------------------------------------

    /**
     * Callback invoked when a validated message is received.
     * The raw [WebMessageCompat] is passed through; the caller deserializes the payload.
     */
    var onMessage: ((WebMessageCompat) -> Unit)? = null

    /**
     * Callback invoked when the host is torn down (e.g. session close).
     */
    var onCleanup: (() -> Unit)? = null

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    /**
     * Attaches this host to [webView] and sets up the [WebViewCompat.addWebMessageListener].
     *
     * @param webView            The WebView to register the listener on.
     * @param approvedOrigins    Set of allowed origins for the current session.
     * @param initialState       The current session state at attachment time.
     */
    fun attach(
        webView: android.webkit.WebView,
        approvedOrigins: Set<String>,
        initialState: SessionState,
    ) {
        this.webView = webView
        _approvedOrigins.value = approvedOrigins
        _sessionState.value = initialState
        Log.d(TAG, "BridgeHost attached for origins=$approvedOrigins state=$initialState")

        WebViewCompat.addWebMessageListener(
            webView,
            CHANNEL_NAME,
            approvedOrigins.toMutableSet(),
            WebViewCompat.WebMessageListener { _, message, origin, _, _ ->
                scope.launch {
                    handleMessage(message, origin.toString())
                }
            },
        )
    }

    /**
     * Updates the session state and tears down listeners if the state becomes
     * terminal (Closed or Closing).
     */
    fun updateState(newState: SessionState) {
        val wasActive = validator.isActiveState(_sessionState.value)
        val isNowActive = validator.isActiveState(newState)
        _sessionState.value = newState
        Log.d(TAG, "BridgeHost state=$newState (active=$isNowActive)")
        if (!isNowActive && wasActive) {
            Log.d(TAG, "BridgeHost: state became terminal — tearing down listeners")
            teardown()
        }
    }

    /**
     * Increments the navigation epoch and marks all outstanding requests as
     * stale (by clearing the set). Call this when the session is closed or
     * a new session is opened.
     */
    fun incrementEpoch() {
        _navigationEpoch.value = _navigationEpoch.value + 1
        _outstandingRequests.value = emptySet()
        Log.d(TAG, "BridgeHost epoch incremented -> ${_navigationEpoch.value}")
    }

    /**
     * Registers a new outstanding request ID.
     */
    fun addOutstandingRequest(requestId: String) {
        _outstandingRequests.value = _outstandingRequests.value + requestId
        Log.d(TAG, "BridgeHost: added outstanding request $requestId")
    }

    /**
     * Removes a request ID after its response is received.
     */
    fun removeOutstandingRequest(requestId: String) {
        _outstandingRequests.value = _outstandingRequests.value - requestId
        Log.d(TAG, "BridgeHost: removed outstanding request $requestId")
    }

    /**
     * Sets the active game ID for logging and diagnostics.
     */
    fun setActiveGameId(gameId: String) {
        _activeGameId.value = gameId
    }

    /**
     * Tears down the WebMessageListener and clears state. Call on session close.
     */
    fun teardown() {
        webView?.let { wv ->
            WebViewCompat.removeWebMessageListener(wv, CHANNEL_NAME)
        }
        webView = null
        _outstandingRequests.value = emptySet()
        onCleanup?.invoke()
        Log.d(TAG, "BridgeHost: teardown complete")
    }

    // -----------------------------------------------------------------
    // Message handling
    // -----------------------------------------------------------------

    private suspend fun handleMessage(message: WebMessageCompat, sourceOrigin: String) {
        val payload = message.data
        val payloadSize = (payload?.length ?: 0).toLong()

        // Extract epoch and message ID from the payload JSON.
        // Format expected: {"action":"...", "epoch":N, "id":"..."}
        val messageEpoch = extractEpoch(payload)
        val messageId = extractMessageId(payload)

        val result = validator.validateMessage(
            sourceOrigin = sourceOrigin,
            approvedOrigins = approvedOrigins,
            currentEpoch = _navigationEpoch.value,
            messageEpoch = messageEpoch,
            outstandingRequests = outstandingRequests,
            messageId = messageId,
            payloadSize = payloadSize,
        )

        when (result) {
            is ValidationResult.Valid -> {
                Log.d(TAG, "BridgeHost: message accepted (epoch=$messageEpoch id=$messageId origin=$sourceOrigin)")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    onMessage?.invoke(message)
                }
                // If the message carries an ID, remove from outstanding.
                messageId?.let { removeOutstandingRequest(it) }
            }
            is ValidationResult.Invalid -> {
                Log.w(TAG, "BridgeHost: rejected message reason=${result.reason} origin=$sourceOrigin epoch=$messageEpoch vs current=${_navigationEpoch.value}")
                // Silent reject per spec — no mutation, no callback.
            }
        }
    }

    /**
     * Extracts the `epoch` field from a JSON payload string.
     * Returns 0 if absent or malformed (epoch 0 is valid for the first session).
     */
    private fun extractEpoch(payload: String?): Int {
        if (payload.isNullOrEmpty()) return 0
        return try {
            val json = org.json.JSONObject(payload)
            json.optInt("epoch", 0)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Extracts the `id` field from a JSON payload string.
     * Returns null if absent.
     */
    private fun extractMessageId(payload: String?): String? {
        if (payload.isNullOrEmpty()) return null
        return try {
            val json = org.json.JSONObject(payload)
            val id = json.optString("id", "")
            if (id.isEmpty()) null else id
        } catch (e: Exception) {
            null
        }
    }
}
