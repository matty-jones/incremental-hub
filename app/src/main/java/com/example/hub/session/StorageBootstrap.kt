package com.example.hub.session

import com.example.hub.games.SaveEnvelope
import org.json.JSONObject

/**
 * Same-origin page served in place of the game so storage can be read or
 * written before Shark's scripts run. The page does not load the game.
 */
object StorageBootstrap {
    const val MARKER = "__hubStorageBootstrap"

    /**
     * True when [marker] is one this page sets after a successful peek, write,
     * or rollback. `error` is not success: a later finish-ignore check uses it
     * only to recognize that the bootstrap document is still showing.
     */
    fun markerConfirms(marker: String?): Boolean =
        marker == "peek" || marker == "write" || marker == "rollback"

    fun matchesLaunch(requestUrl: String, launchUrl: String): Boolean {
        if (launchUrl.isBlank()) return false
        val request = requestUrl.substringBefore('#')
        val launch = launchUrl.substringBefore('#')
        return request == launch || request.trimEnd('/') == launch.trimEnd('/')
    }

    fun document(phase: DocumentPhase, storageKey: String, payload: String?): String {
        val action = when (phase) {
            DocumentPhase.PEEK, DocumentPhase.GAME -> "window.$MARKER='peek';"
            DocumentPhase.WRITE -> writeBody(storageKey, payload, "write")
            DocumentPhase.ROLLBACK -> writeBody(storageKey, payload, "rollback")
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><script>$action</script></body></html>"
    }

    fun shouldServe(phase: DocumentPhase, requestUrl: String, launchUrl: String, mainFrame: Boolean): Boolean {
        if (!mainFrame || phase == DocumentPhase.GAME) return false
        return matchesLaunch(requestUrl, launchUrl)
    }

    private fun writeBody(storageKey: String, payload: String?, marker: String): String {
        if (payload != null && SaveEnvelope.looksLikeEnvelope(payload)) {
            val quoted = JSONObject.quote(payload)
            return "var envelope=JSON.parse($quoted);var entries=envelope.entries;" +
                "for(var i=0;i<entries.length;i++){var entry=entries[i];" +
                "if(!Object.prototype.hasOwnProperty.call(entry,'value'))localStorage.removeItem(entry.name);" +
                "else localStorage.setItem(entry.name, entry.value);}" +
                "window.$MARKER='$marker';"
        }
        return writeAction(JSONObject.quote(storageKey), payload, marker)
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
