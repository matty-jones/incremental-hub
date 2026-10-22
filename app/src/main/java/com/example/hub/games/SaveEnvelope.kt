package com.example.hub.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * Names an envelope is allowed to contain.
 * [allowDynamic] is the only way a name outside [allowedNames] is accepted.
 * Denied names are rejected even when a dynamic rule would match them.
 */
data class EnvelopePolicy(
    val allowedNames: Set<String>,
    val requiredNames: Set<String>,
    val deniedNames: Set<String>,
    val allowDynamic: (String) -> Boolean,
)

enum class EnvelopeRejection {
    MALFORMED,
    DUPLICATE,
    UNEXPECTED,
    MISSING_REQUIRED,
    DENIED,
}

sealed class EnvelopeDecode {
    data class Ok(val bundle: SaveBundle) : EnvelopeDecode()
    data class Rejected(val reason: EnvelopeRejection, val detail: String) : EnvelopeDecode()
}

/**
 * Version 1 save envelope.
 *
 * A missing `value` field means the key is absent and must be removed.
 * `"value": ""` means the key is present and empty. JSON null is malformed.
 * Bytes of each value are preserved by a JSON string round-trip.
 */
object SaveEnvelope {
    const val VERSION = 1

    fun encode(slots: List<StorageSlot>): String {
        val entries = JSONArray()
        for (slot in slots) {
            val entry = JSONObject()
            entry.put("name", slot.name)
            if (slot.value != null) {
                entry.put("value", slot.value)
            }
            entries.put(entry)
        }
        return JSONObject()
            .put("hubEnvelope", VERSION)
            .put("entries", entries)
            .toString()
    }

    fun looksLikeEnvelope(payload: String?): Boolean {
        if (payload.isNullOrBlank() || !payload.trimStart().startsWith("{")) return false
        return try {
            val json = JSONObject(payload)
            val version = json.opt("hubEnvelope")
            version is Number && version.toLong() == VERSION.toLong() && json.opt("entries") is JSONArray
        } catch (_: Exception) {
            false
        }
    }

    fun decode(payload: String, policy: EnvelopePolicy): EnvelopeDecode {
        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return reject(EnvelopeRejection.MALFORMED, "Envelope is not JSON")
        }
        val rootKeys = json.keys()
        while (rootKeys.hasNext()) {
            val key = rootKeys.next()
            if (key != "hubEnvelope" && key != "entries") {
                return reject(EnvelopeRejection.MALFORMED, "Unexpected envelope field")
            }
        }
        if (!json.has("hubEnvelope") || !json.has("entries")) {
            return reject(EnvelopeRejection.MALFORMED, "Envelope is missing hubEnvelope or entries")
        }
        val version = json.get("hubEnvelope")
        if (version !is Number || version.toLong() != VERSION.toLong()) {
            return reject(EnvelopeRejection.MALFORMED, "Unsupported hubEnvelope version")
        }
        val entries = json.get("entries")
        if (entries !is JSONArray) {
            return reject(EnvelopeRejection.MALFORMED, "Envelope entries are not an array")
        }
        val slots = ArrayList<StorageSlot>(entries.length())
        val seen = HashSet<String>()
        for (index in 0 until entries.length()) {
            val raw = entries.get(index)
            if (raw !is JSONObject) {
                return reject(EnvelopeRejection.MALFORMED, "Envelope entry is not an object")
            }
            val entryKeys = raw.keys()
            while (entryKeys.hasNext()) {
                val key = entryKeys.next()
                if (key != "name" && key != "value") {
                    return reject(EnvelopeRejection.MALFORMED, "Unexpected entry field")
                }
            }
            if (!raw.has("name")) {
                return reject(EnvelopeRejection.MALFORMED, "Envelope entry has no name")
            }
            val nameRaw = raw.get("name")
            if (nameRaw !is String || nameRaw.isEmpty()) {
                return reject(EnvelopeRejection.MALFORMED, "Envelope entry name is not a string")
            }
            if (!seen.add(nameRaw)) {
                return reject(EnvelopeRejection.DUPLICATE, "Duplicate envelope name")
            }
            val value = if (!raw.has("value")) {
                null
            } else {
                val valueRaw = raw.get("value")
                if (valueRaw !is String) {
                    return reject(EnvelopeRejection.MALFORMED, "Envelope value is not a string")
                }
                valueRaw
            }
            if (nameRaw in policy.deniedNames) {
                return reject(EnvelopeRejection.DENIED, "Envelope contains a denied key")
            }
            val allowed = nameRaw in policy.allowedNames || policy.allowDynamic(nameRaw)
            if (!allowed) {
                return reject(EnvelopeRejection.UNEXPECTED, "Envelope contains an unexpected key")
            }
            slots.add(StorageSlot(nameRaw, value))
        }
        val present = slots.map { it.name }.toSet()
        val missing = policy.requiredNames.filter { it !in present }
        if (missing.isNotEmpty()) {
            return reject(EnvelopeRejection.MISSING_REQUIRED, "Envelope is missing a required entry")
        }
        return EnvelopeDecode.Ok(SaveBundle(slots))
    }

    fun sameDocument(left: String, right: String, policy: EnvelopePolicy): Boolean {
        val decodedLeft = decode(left, policy)
        val decodedRight = decode(right, policy)
        if (decodedLeft !is EnvelopeDecode.Ok || decodedRight !is EnvelopeDecode.Ok) return false
        return decodedLeft.bundle.slots == decodedRight.bundle.slots
    }

    /**
     * One snapshot of [stored]. Static names are always listed, including when
     * absent. Discovered names are listed only when present. Denied names and
     * unrelated keys are left out.
     */
    fun collect(
        stored: Map<String, String>,
        staticNames: List<String>,
        discovered: List<String>,
        policy: EnvelopePolicy,
    ): SaveBundle {
        val names = ArrayList<String>()
        for (name in staticNames) {
            if (name in policy.deniedNames) continue
            if (name !in names) names.add(name)
        }
        for (name in discovered) {
            if (name in policy.deniedNames) continue
            if (name !in stored) continue
            val known = name in policy.allowedNames || policy.allowDynamic(name)
            if (!known) continue
            if (name !in names) names.add(name)
        }
        return SaveBundle(names.map { name -> StorageSlot(name, stored[name]) })
    }

    /**
     * Reads every static name, then discovered names, in one JavaScript evaluation.
     * [discoveryExpression] must be a JS expression that returns an array of names.
     */
    fun readScript(
        staticNames: List<String>,
        discoveryExpression: String,
        deniedNames: Set<String>,
    ): String {
        val fixed = JSONArray(staticNames).toString()
        val denied = JSONArray(deniedNames.toList()).toString()
        return "(function(){ try {" +
            " var fixed = $fixed;" +
            " var deniedList = $denied;" +
            " var denied = {};" +
            " for (var d = 0; d < deniedList.length; d++) denied[deniedList[d]] = true;" +
            " var discovered = [];" +
            " try { discovered = ($discoveryExpression) || []; } catch (e) { discovered = []; }" +
            " var names = fixed.slice();" +
            " if (discovered && discovered.length) {" +
            " for (var i = 0; i < discovered.length; i++) {" +
            " var found = discovered[i];" +
            " if (typeof found !== 'string' || denied[found] || names.indexOf(found) >= 0) continue;" +
            " if (localStorage.getItem(found) === null) continue;" +
            " names.push(found);" +
            " }" +
            " }" +
            " var entries = [];" +
            " for (var n = 0; n < names.length; n++) {" +
            " var name = names[n];" +
            " if (denied[name]) continue;" +
            " var value = localStorage.getItem(name);" +
            " if (value === null) entries.push({name: name});" +
            " else entries.push({name: name, value: value});" +
            " }" +
            " return JSON.stringify({hubEnvelope: 1, entries: entries});" +
            " } catch (e) { return ''; } })()"
    }

    private fun reject(reason: EnvelopeRejection, detail: String) = EnvelopeDecode.Rejected(reason, detail)
}
