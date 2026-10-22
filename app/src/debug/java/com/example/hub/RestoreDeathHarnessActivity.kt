package com.example.hub

import android.app.Activity
import android.os.Bundle
import com.example.hub.games.PayloadDigest
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.StorageSlot
import com.example.hub.session.JournalPhase
import com.example.hub.session.RecoveryResult
import com.example.hub.session.RestoreDirection
import com.example.hub.session.RestoreJournal
import com.example.hub.session.RestoreJournalStore
import com.example.hub.session.RestorePointer
import com.example.hub.session.RestoreStorage
import com.example.hub.session.RestoreTransaction
import org.json.JSONObject
import java.io.File

/**
 * Kills the process after the first envelope key is durable, then on the
 * next start recovers that journal. Not a launcher. Disposable profile only.
 */
class RestoreDeathHarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECOVER
        if (mode == MODE_KILL) {
            if (File(filesDir, FIRED).exists()) {
                finish()
                return
            }
            File(filesDir, FIRED).writeText("armed")
            File(filesDir, MARKER).writeText("APPLYING:1")
            Thread({
                try {
                    killDuringFirstKey()
                    File(filesDir, RESULT).writeText("result=BLOCKED kill-returned\n")
                } catch (thrown: Throwable) {
                    File(filesDir, RESULT).writeText(
                        "result=BLOCKED kill-error=${thrown.javaClass.simpleName}:${thrown.message}\n",
                    )
                }
            }, "restore-death").start()
        } else {
            File(filesDir, MARKER).delete()
            val report = recover()
            File(filesDir, RESULT).writeText(report)
            android.util.Log.i(TAG, report)
            finish()
        }
    }

    private fun killDuringFirstKey() {
        val storage = FileSlotStorage(File(filesDir, SLOTS))
        storage.apply(StorageSlot(PRIMARY, PREVIOUS_PRIMARY))
        storage.apply(StorageSlot(BACKUP, null))
        val pointer = FilePointer(File(filesDir, POINTER))
        pointer.set(PREVIOUS_POINTER)
        val journal = RestoreJournal(
            profileName = PROFILE,
            gameId = GAME,
            phase = JournalPhase.PREPARED,
            appliedKeys = 0,
            previousSlots = listOf(
                StorageSlot(PRIMARY, PREVIOUS_PRIMARY),
                StorageSlot(BACKUP, null),
            ),
            intendedSlots = listOf(
                StorageSlot(PRIMARY, INTENDED_PRIMARY),
                StorageSlot(BACKUP, null),
            ),
            previousPointer = PREVIOUS_POINTER,
            intendedPointer = INTENDED_POINTER,
            direction = RestoreDirection.INTENDED,
        )
        RestoreTransaction(RestoreJournalStore(this), storage, pointer).run(journal)
    }

    private fun recover(): String {
        val store = RestoreJournalStore(this)
        val unfinished = store.unfinished(PROFILE)
        val journal = runCatching { store.read(PROFILE) }.getOrNull()
        val storage = FileSlotStorage(File(filesDir, SLOTS))
        val pointer = FilePointer(File(filesDir, POINTER))
        if (!unfinished || journal == null) {
            return "result=BLOCKED unfinished=$unfinished\n"
        }
        val before = storage.read(PRIMARY)
        val result = RestoreTransaction(store, storage, pointer).recover(PROFILE)
        val afterPrimary = storage.read(PRIMARY)
        val afterBackup = storage.read(BACKUP)
        val allowsLoad = !store.unfinished(PROFILE) && result.allowsGameLoad
        return buildString {
            append("result=")
            append(
                when (result) {
                    is RecoveryResult.Completed -> "PASS"
                    is RecoveryResult.RolledBack -> "ROLLED_BACK"
                    is RecoveryResult.Blocked -> "BLOCKED"
                },
            )
            append('\n')
            append("unfinishedBefore=true\n")
            append("phaseBefore=").append(journal.phase.name).append('\n')
            append("appliedBefore=").append(journal.appliedKeys).append('\n')
            append("partialPrimaryHash=").append(PayloadDigest.sha256OrAbsent(before)).append('\n')
            append("restoredPrimaryHash=").append(PayloadDigest.sha256OrAbsent(afterPrimary)).append('\n')
            append("backup=").append(if (afterBackup == null) "absent" else "present").append('\n')
            append("pointerHash=").append(PayloadDigest.sha256OrAbsent(pointer.get())).append('\n')
            append("journalGone=").append(!store.unfinished(PROFILE)).append('\n')
            append("allowsGameLoad=").append(allowsLoad).append('\n')
            append("gameLoaded=false\n")
            append("expectedPrimaryHash=").append(PayloadDigest.sha256(INTENDED_PRIMARY)).append('\n')
        }
    }

    private class FileSlotStorage(private val file: File) : RestoreStorage {
        private val values = LinkedHashMap<String, String?>()

        init {
            if (file.isFile) {
                val json = JSONObject(file.readText())
                val names = json.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    values[name] = if (json.isNull(name)) null else json.getString(name)
                }
            }
        }

        override fun read(name: String): String? = if (values.containsKey(name)) values[name] else null

        override fun apply(slot: StorageSlot) {
            if (slot.value == null) values.remove(slot.name) else values[slot.name] = slot.value
            val json = JSONObject()
            values.forEach { (name, value) ->
                if (value == null) json.put(name, JSONObject.NULL) else json.put(name, value)
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    private class FilePointer(private val file: File) : RestorePointer {
        override fun get(): String? = if (file.isFile) file.readText() else null

        override fun set(value: String?) {
            if (value == null) {
                file.delete()
            } else {
                file.writeText(value)
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_KILL = "kill"
        const val MODE_RECOVER = "recover"
        private const val TAG = "RESTORE_DEATH"
        private const val MARKER = "restore-kill-after"
        private const val FIRED = "restore-death-fired"
        private const val RESULT = "restore-death-result.txt"
        private const val SLOTS = "restore-death-slots.json"
        private const val POINTER = "restore-death-pointer.txt"
        private const val PROFILE = "hub-qual-trimps-death"
        private const val GAME = "trimps-death"
        private const val PRIMARY = "trimpSave1"
        private const val BACKUP = "trimpBackup1"
        private const val PREVIOUS_PRIMARY = "previous-primary-value"
        private const val INTENDED_PRIMARY = "intended-primary-value"
        private const val PREVIOUS_POINTER = "previous-pointer"
        private val INTENDED_POINTER = SaveEnvelope.encode(
            listOf(
                StorageSlot(PRIMARY, INTENDED_PRIMARY),
                StorageSlot(BACKUP, null),
            ),
        )
    }
}
