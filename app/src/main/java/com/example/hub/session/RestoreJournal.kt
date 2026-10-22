package com.example.hub.session

import android.content.Context
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.StorageSlot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class JournalPhase {
    PREPARED,
    APPLYING,
    APPLIED,
    VERIFIED,
    POINTER_COMMITTED,
}

enum class RestoreDirection {
    INTENDED,
    PREVIOUS,
}

/**
 * Durable record of a multi-key restore. The WebView writes are not one
 * atomic commit: a process death between keys leaves this file behind.
 */
data class RestoreJournal(
    val profileName: String,
    val gameId: String,
    val phase: JournalPhase,
    val appliedKeys: Int,
    val previousSlots: List<StorageSlot>,
    val intendedSlots: List<StorageSlot>,
    val previousPointer: String?,
    val intendedPointer: String?,
    val direction: RestoreDirection,
) {
    fun targetSlots(): List<StorageSlot> = when (direction) {
        RestoreDirection.INTENDED -> intendedSlots
        RestoreDirection.PREVIOUS -> previousSlots
    }

    fun targetPointer(): String? = when (direction) {
        RestoreDirection.INTENDED -> intendedPointer
        RestoreDirection.PREVIOUS -> previousPointer
    }
}

class RestoreInterrupted(val stage: String) : RuntimeException("restore interrupted after $stage")

interface RestoreStorage {
    fun read(name: String): String?
    fun apply(slot: StorageSlot)
}

interface RestorePointer {
    fun get(): String?
    fun set(value: String?)
}

sealed class RecoveryResult {
    data class Completed(val pointer: String?, val slots: List<StorageSlot>) : RecoveryResult()
    data class RolledBack(val pointer: String?, val slots: List<StorageSlot>) : RecoveryResult()
    data class Blocked(val detail: String) : RecoveryResult()

    val allowsGameLoad: Boolean
        get() = this is Completed || this is RolledBack
}

/**
 * File journal under the app files directory. Writes are flushed and renamed
 * so a crash does not leave a half-written record as the current journal.
 */
class RestoreJournalStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "restore-journals"))

    fun read(profileName: String): RestoreJournal? {
        val file = fileFor(profileName)
        if (!file.isFile) return null
        return decode(file.readText(Charsets.UTF_8))
    }

    /** True when a journal file is present, including one that cannot be parsed. */
    fun unfinished(profileName: String): Boolean = fileFor(profileName).isFile

    fun write(journal: RestoreJournal) {
        directory.mkdirs()
        val file = fileFor(journal.profileName)
        val tmp = File(directory, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encode(journal).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                tmp.delete()
                error("Could not replace restore journal")
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("Could not replace restore journal")
            }
        }
    }

    fun delete(profileName: String) {
        fileFor(profileName).delete()
        File(directory, fileName(profileName) + ".tmp").delete()
    }

    private fun fileFor(profileName: String): File = File(directory, fileName(profileName))

    private fun fileName(profileName: String): String {
        require(profileName.matches(SAFE_NAME)) { "Unsafe profile name" }
        return "$profileName.json"
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

        fun encode(journal: RestoreJournal): String {
            return JSONObject()
                .put("journalVersion", 1)
                .put("profileName", journal.profileName)
                .put("gameId", journal.gameId)
                .put("phase", journal.phase.name)
                .put("appliedKeys", journal.appliedKeys)
                .put("previousSlots", slotsJson(journal.previousSlots))
                .put("intendedSlots", slotsJson(journal.intendedSlots))
                .put("previousPointer", journal.previousPointer ?: JSONObject.NULL)
                .put("intendedPointer", journal.intendedPointer ?: JSONObject.NULL)
                .put("direction", journal.direction.name)
                .toString()
        }

        fun decode(text: String): RestoreJournal {
            val json = JSONObject(text)
            return RestoreJournal(
                profileName = json.getString("profileName"),
                gameId = json.getString("gameId"),
                phase = JournalPhase.valueOf(json.getString("phase")),
                appliedKeys = json.getInt("appliedKeys"),
                previousSlots = slots(json.getJSONArray("previousSlots")),
                intendedSlots = slots(json.getJSONArray("intendedSlots")),
                previousPointer = optionalText(json, "previousPointer"),
                intendedPointer = optionalText(json, "intendedPointer"),
                direction = RestoreDirection.valueOf(json.getString("direction")),
            )
        }

        private fun optionalText(json: JSONObject, key: String): String? {
            if (!json.has(key) || json.isNull(key)) return null
            val raw = json.get(key)
            return if (raw is String) raw else null
        }

        private fun slotsJson(slots: List<StorageSlot>): JSONArray {
            val array = JSONArray()
            for (slot in slots) {
                val entry = JSONObject().put("name", slot.name)
                if (slot.value != null) entry.put("value", slot.value)
                array.put(entry)
            }
            return array
        }

        private fun slots(array: JSONArray): List<StorageSlot> {
            val slots = ArrayList<StorageSlot>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val value = if (entry.has("value")) entry.getString("value") else null
                slots.add(StorageSlot(entry.getString("name"), value))
            }
            return slots
        }
    }
}

/**
 * Applies a restore one key at a time and can be resumed from the journal
 * after the process that started it is gone.
 *
 * Completing the intended write is preferred. If that state cannot be
 * verified, the previous slots and pointer are restored. The game must not
 * load while [RestoreJournalStore.unfinished] is true.
 */
class RestoreTransaction(
    private val store: RestoreJournalStore,
    private val storage: RestoreStorage,
    private val pointer: RestorePointer,
    private val fault: (String) -> Unit = {},
) {
    fun run(journal: RestoreJournal): RecoveryResult {
        store.write(
            journal.copy(
                phase = JournalPhase.PREPARED,
                appliedKeys = 0,
                direction = RestoreDirection.INTENDED,
            ),
        )
        checkpoint("prepared")
        return applyAndCommit(store.read(journal.profileName)!!)
    }

    fun recover(profileName: String): RecoveryResult {
        val journal = store.read(profileName)
            ?: return RecoveryResult.Blocked("No restore journal")
        if (journal.phase == JournalPhase.POINTER_COMMITTED && verify(journal.targetSlots())) {
            store.delete(profileName)
            return finish(journal)
        }
        return applyAndCommit(journal)
    }

    private fun applyAndCommit(start: RestoreJournal): RecoveryResult {
        val profileName = start.profileName
        store.write(start.copy(phase = JournalPhase.APPLYING, appliedKeys = 0))
        checkpoint("applying")
        val slots = start.targetSlots()
        slots.forEachIndexed { index, slot ->
            storage.apply(slot)
            val current = store.read(profileName)!!
            store.write(current.copy(phase = JournalPhase.APPLYING, appliedKeys = index + 1))
            checkpoint("key:$index")
        }
        store.write(store.read(profileName)!!.copy(phase = JournalPhase.APPLIED))
        checkpoint("applied")
        val applied = store.read(profileName)!!
        if (!verify(applied.targetSlots())) {
            if (applied.direction == RestoreDirection.INTENDED) {
                store.write(
                    applied.copy(
                        direction = RestoreDirection.PREVIOUS,
                        phase = JournalPhase.APPLYING,
                        appliedKeys = 0,
                    ),
                )
                return applyAndCommit(store.read(profileName)!!)
            }
            return RecoveryResult.Blocked("Previous storage could not be verified")
        }
        store.write(applied.copy(phase = JournalPhase.VERIFIED))
        checkpoint("verified")
        val verified = store.read(profileName)!!
        pointer.set(verified.targetPointer())
        store.write(verified.copy(phase = JournalPhase.POINTER_COMMITTED))
        checkpoint("pointer")
        val done = store.read(profileName)!!
        store.delete(profileName)
        return finish(done)
    }

    private fun finish(journal: RestoreJournal): RecoveryResult {
        val pointerValue = journal.targetPointer()
        val slots = journal.targetSlots()
        return when (journal.direction) {
            RestoreDirection.INTENDED -> RecoveryResult.Completed(pointerValue, slots)
            RestoreDirection.PREVIOUS -> RecoveryResult.RolledBack(pointerValue, slots)
        }
    }

    private fun verify(slots: List<StorageSlot>): Boolean {
        return slots.all { slot -> storage.read(slot.name) == slot.value }
    }

    private fun checkpoint(stage: String) {
        fault(stage)
    }
}

/** Encoded envelope for the journal's current target, used before the game document loads. */
fun RestoreJournal.applyPayload(): String = SaveEnvelope.encode(targetSlots())
