package com.example.hub.session

import org.json.JSONObject

/**
 * Same-origin page served in place of the game so storage can be read or
 * written before Shark's scripts run. The page does not load the game.
 */
object StorageBootstrap {
    const val MARKER = "__hubStorageBootstrap"

    fun matchesLaunch(requestUrl: String, launchUrl: String): Boolean {
        if (launchUrl.isBlank()) return false
        val request = requestUrl.substringBefore('#')
        val launch = launchUrl.substringBefore('#')
        return request == launch || request.trimEnd('/') == launch.trimEnd('/')
    }

    fun document(phase: DocumentPhase, storageKey: String, payload: String?): String {
        val key = JSONObject.quote(storageKey)
        val action = when (phase) {
            DocumentPhase.PEEK, DocumentPhase.GAME -> "window.$MARKER='peek';"
            DocumentPhase.WRITE -> writeAction(key, payload, "write")
            DocumentPhase.ROLLBACK -> writeAction(key, payload, "rollback")
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><script>$action</script></body></html>"
    }

    fun shouldServe(phase: DocumentPhase, requestUrl: String, launchUrl: String, mainFrame: Boolean): Boolean {
        if (!mainFrame || phase == DocumentPhase.GAME) return false
        return matchesLaunch(requestUrl, launchUrl)
    }

    private fun writeAction(quotedKey: String, payload: String?, marker: String): String {
        val write = if (payload == null) {
            "localStorage.removeItem($quotedKey);"
        } else {
            "localStorage.setItem($quotedKey, ${JSONObject.quote(payload)});"
        }
        return write + "window.$MARKER='$marker';"
    }
}

enum class DocumentPhase {
    PEEK,
    WRITE,
    ROLLBACK,
    GAME,
}

class DocumentGate {
    var phase: DocumentPhase = DocumentPhase.PEEK
    var storageKey: String = ""
    var payload: String? = null
    var launchUrl: String = ""
}
