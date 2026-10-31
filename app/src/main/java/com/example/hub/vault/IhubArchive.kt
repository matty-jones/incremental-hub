package com.example.hub.vault

import com.example.hub.games.PayloadDigest
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class IhubSnapshotDraft(
    val id: String,
    val gameId: String,
    val lineageId: String,
    val sequence: Long,
    val capturedAtMillis: Long,
    val reason: String,
    val origin: String,
    val pinned: Boolean,
    val userProtected: Boolean,
    val storedSha256: String,
    val payload: String?,
)

data class IhubSnapshotFile(
    val id: String,
    val gameId: String,
    val lineageId: String,
    val sequence: Long,
    val capturedAtMillis: Long,
    val reason: String,
    val origin: String,
    val pinned: Boolean,
    val userProtected: Boolean,
    val contentSha256: String,
    val payload: String,
)

data class IhubContents(
    val formatVersion: Int,
    val archiveId: String,
    val createdAt: String,
    val exporterVersion: String,
    val encrypted: Boolean,
    val label: String,
    val complete: Boolean,
    val omissions: List<String>,
    val games: List<Pair<String, List<String>>>,
    val snapshots: List<IhubSnapshotFile>,
)

sealed class IhubExportResult {
    data class Ready(val bytes: ByteArray, val snapshotCount: Int) : IhubExportResult()
    data class Refused(val message: String, val omissions: List<String>) : IhubExportResult()
}

sealed class IhubReadResult {
    data class Ok(val archive: IhubContents) : IhubReadResult()
    data class Rejected(val message: String) : IhubReadResult()
}

object IhubArchive {
    const val FORMAT_VERSION = 1
    const val LABEL =
        "Unencrypted. Contains save payloads. Does not contain credentials, cookies, or WebView profiles."
    const val MAX_COMPRESSED_BYTES = 512L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun export(
        drafts: List<IhubSnapshotDraft>,
        archiveId: String,
        createdAt: String,
        exporterVersion: String,
        complete: Boolean,
        omissions: List<String> = emptyList(),
    ): IhubExportResult {
        if (complete && omissions.isNotEmpty()) {
            return IhubExportResult.Refused(
                "This is not a complete archive. Missing: ${omissions.joinToString(", ")}",
                omissions,
            )
        }
        if (!complete && omissions.isEmpty() && drafts.any { it.payload == null }) {
            val missing = drafts.filter { it.payload == null }.map { it.id }
            return IhubExportResult.Refused(
                "This is not a complete archive. Missing: ${missing.joinToString(", ")}",
                missing,
            )
        }
        val missing = drafts.filter { it.payload == null }.map { it.id }
        if (complete && missing.isNotEmpty()) {
            return IhubExportResult.Refused(
                "This is not a complete archive. Missing: ${missing.joinToString(", ")}",
                missing,
            )
        }
        val files = linkedMapOf<String, ByteArray>()
        val snapshots = drafts.filter { it.payload != null }
        val byGame = snapshots.groupBy { it.gameId }
        for (gameId in byGame.keys.sorted()) {
            if (!safeSegment(gameId)) {
                return IhubExportResult.Refused("Game id $gameId cannot be stored in an archive path.", listOf(gameId))
            }
            val lineages = byGame.getValue(gameId).map { it.lineageId }.distinct().sorted()
            if (lineages.any { !safeSegment(it) }) {
                return IhubExportResult.Refused("A lineage id cannot be stored in an archive path.", lineages)
            }
            files["games/$gameId/instance.json"] = JSONObject()
                .put("gameId", gameId)
                .put("lineageIds", JSONArray(lineages))
                .toString()
                .toByteArray(Charsets.UTF_8)
        }
        val payloadBytes = linkedMapOf<String, ByteArray>()
        for (draft in snapshots.sortedWith(compareBy({ it.gameId }, { it.sequence }, { it.id }))) {
            if (!safeSegment(draft.id)) {
                return IhubExportResult.Refused("Snapshot id ${draft.id} cannot be stored in an archive path.", listOf(draft.id))
            }
            val payload = draft.payload ?: continue
            val bytes = payload.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_PAYLOAD_BYTES) {
                return IhubExportResult.Refused("Snapshot ${draft.id} is larger than the payload limit.", listOf(draft.id))
            }
            val sha = sha256(bytes)
            if (draft.storedSha256.isNotEmpty() && draft.storedSha256 != sha) {
                return IhubExportResult.Refused("Snapshot ${draft.id} does not match its stored hash.", listOf(draft.id))
            }
            payloadBytes.putIfAbsent(sha, bytes)
            val capturedAt = Instant.ofEpochMilli(draft.capturedAtMillis).toString()
            files["snapshots/${draft.id}.json"] = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("snapshotId", draft.id)
                .put("gameId", draft.gameId)
                .put("lineageId", draft.lineageId)
                .put("captureSequence", draft.sequence)
                .put("capturedAt", capturedAt)
                .put("capturedAtMillis", draft.capturedAtMillis)
                .put("reason", draft.reason)
                .put("origin", draft.origin)
                .put("pinned", draft.pinned)
                .put("userProtected", draft.userProtected)
                .put("contentSha256", sha)
                .put("payloadPath", "payloads/$sha")
                .toString()
                .toByteArray(Charsets.UTF_8)
        }
        for ((sha, bytes) in payloadBytes) {
            files["payloads/$sha"] = bytes
        }
        val members = JSONArray()
        for ((path, bytes) in files) {
            members.put(
                JSONObject()
                    .put("path", path)
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes)),
            )
        }
        val games = JSONArray()
        for (gameId in byGame.keys.sorted()) {
            val rows = byGame.getValue(gameId)
            games.put(
                JSONObject()
                    .put("gameId", gameId)
                    .put("lineageIds", JSONArray(rows.map { it.lineageId }.distinct().sorted()))
                    .put("snapshotIds", JSONArray(rows.map { it.id }.sorted())),
            )
        }
        val manifest = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("archiveId", archiveId)
            .put("createdAt", createdAt)
            .put("exporterVersion", exporterVersion)
            .put("encrypted", false)
            .put("label", LABEL)
            .put("complete", complete && omissions.isEmpty() && missing.isEmpty())
            .put("omissions", JSONArray(omissions))
            .put("games", games)
            .put("snapshotIds", JSONArray(snapshots.map { it.id }.sorted()))
            .put("members", members)
        files["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        val zip = zip(files)
        if (zip.size > MAX_COMPRESSED_BYTES) {
            return IhubExportResult.Refused("The archive is larger than the compressed limit.", emptyList())
        }
        val verified = read(zip)
        if (verified !is IhubReadResult.Ok) {
            return IhubExportResult.Refused(
                verified.message(),
                emptyList(),
            )
        }
        val exported = verified.archive.snapshots.associate { it.id to it.payload }
        for (draft in snapshots) {
            if (exported[draft.id] != draft.payload) {
                return IhubExportResult.Refused("The archive did not round-trip snapshot ${draft.id}.", listOf(draft.id))
            }
        }
        return IhubExportResult.Ready(zip, snapshots.size)
    }

    fun read(bytes: ByteArray): IhubReadResult {
        if (bytes.size.toLong() > MAX_COMPRESSED_BYTES) {
            return IhubReadResult.Rejected("The archive is larger than the compressed limit.")
        }
        val listed = try {
            centralDirectory(bytes)
        } catch (e: IhubFormatException) {
            return IhubReadResult.Rejected(e.message ?: "The archive could not be read.")
        }
        val extracted = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (extracted.containsKey(name)) {
                        return IhubReadResult.Rejected("The archive repeats $name.")
                    }
                    val declared = entry.size
                    if (declared > MAX_PAYLOAD_BYTES && name.startsWith("payloads/")) {
                        return IhubReadResult.Rejected("A save payload is larger than the limit.")
                    }
                    if (declared > MAX_EXPANDED_BYTES) {
                        return IhubReadResult.Rejected("The archive expands past the limit.")
                    }
                    val cap = if (name.startsWith("payloads/")) {
                        MAX_PAYLOAD_BYTES.toLong()
                    } else {
                        MAX_EXPANDED_BYTES - expanded
                    }
                    val body = readEntry(zip, cap)
                    expanded += body.size
                    if (expanded > MAX_EXPANDED_BYTES) {
                        return IhubReadResult.Rejected("The archive expands past the limit.")
                    }
                    extracted[name] = body
                    zip.closeEntry()
                }
            }
        } catch (e: IhubFormatException) {
            return IhubReadResult.Rejected(e.message ?: "The archive could not be read.")
        } catch (e: Exception) {
            return IhubReadResult.Rejected("The archive could not be read.")
        }
        if (extracted.keys != listed.toSet()) {
            return IhubReadResult.Rejected("The archive members do not match its directory.")
        }
        return interpret(extracted)
    }

    private fun interpret(files: Map<String, ByteArray>): IhubReadResult {
        val manifestBytes = files["manifest.json"]
            ?: return IhubReadResult.Rejected("The archive has no manifest.")
        val manifest = try {
            JSONObject(manifestBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return IhubReadResult.Rejected("The manifest is not valid JSON.")
        }
        val version = manifest.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) {
            return IhubReadResult.Rejected("Archive format $version is not supported.")
        }
        if (manifest.optBoolean("encrypted", true)) {
            return IhubReadResult.Rejected("Encrypted archives are not supported.")
        }
        val members = manifest.optJSONArray("members")
            ?: return IhubReadResult.Rejected("The manifest has no member list.")
        val listed = mutableMapOf<String, Pair<Int, String>>()
        for (index in 0 until members.length()) {
            val member = members.optJSONObject(index)
                ?: return IhubReadResult.Rejected("A manifest member is not an object.")
            val path = member.optString("path")
            if (path.isEmpty() || listed.containsKey(path)) {
                return IhubReadResult.Rejected("The manifest repeats a member.")
            }
            listed[path] = member.optInt("bytes", -1) to member.optString("sha256")
        }
        val expected = files.keys.filter { it != "manifest.json" }.toSet()
        if (listed.keys != expected) {
            return IhubReadResult.Rejected("The manifest members do not match the archive.")
        }
        for ((path, expectedMeta) in listed) {
            val body = files[path] ?: return IhubReadResult.Rejected("Missing $path.")
            if (body.size != expectedMeta.first || sha256(body) != expectedMeta.second) {
                return IhubReadResult.Rejected("The hash of $path does not match the manifest.")
            }
        }
        val snapshots = mutableListOf<IhubSnapshotFile>()
        val seen = mutableMapOf<String, String>()
        for ((path, body) in files) {
            if (!path.startsWith("snapshots/") || !path.endsWith(".json")) continue
            val json = try {
                JSONObject(body.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                return IhubReadResult.Rejected("$path is not valid JSON.")
            }
            val id = json.optString("snapshotId")
            val gameId = json.optString("gameId")
            val lineageId = json.optString("lineageId")
            val payloadPath = json.optString("payloadPath")
            val sha = json.optString("contentSha256")
            if (!safeSegment(id) || !safeSegment(gameId) || !safeSegment(lineageId) || !safeSegment(sha)) {
                return IhubReadResult.Rejected("A snapshot id, game, lineage, or hash is not usable.")
            }
            if (payloadPath != "payloads/$sha") {
                return IhubReadResult.Rejected("Snapshot $id points at the wrong payload.")
            }
            val payloadBytes = files[payloadPath]
                ?: return IhubReadResult.Rejected("Snapshot $id is missing its payload.")
            if (payloadBytes.size > MAX_PAYLOAD_BYTES || sha256(payloadBytes) != sha) {
                return IhubReadResult.Rejected("Snapshot $id does not match its payload hash.")
            }
            val payload = payloadBytes.toString(Charsets.UTF_8)
            if (PayloadDigest.sha256(payload) != sha) {
                return IhubReadResult.Rejected("Snapshot $id payload bytes are not stable text.")
            }
            val previous = seen.put(id, sha)
            if (previous != null) {
                return IhubReadResult.Rejected(
                    if (previous == sha) {
                        "Snapshot $id is stored twice."
                    } else {
                        "Snapshot $id is stored twice with different payloads."
                    },
                )
            }
            snapshots.add(
                IhubSnapshotFile(
                    id = id,
                    gameId = gameId,
                    lineageId = lineageId,
                    sequence = json.optLong("captureSequence", -1),
                    capturedAtMillis = json.optLong("capturedAtMillis", Long.MIN_VALUE),
                    reason = json.optString("reason"),
                    origin = json.optString("origin"),
                    pinned = json.optBoolean("pinned"),
                    userProtected = json.optBoolean("userProtected"),
                    contentSha256 = sha,
                    payload = payload,
                ),
            )
        }
        if (snapshots.any { it.sequence < 0 || it.capturedAtMillis == Long.MIN_VALUE || it.reason.isEmpty() }) {
            return IhubReadResult.Rejected("A snapshot is missing its sequence, time, or reason.")
        }
        val games = mutableListOf<Pair<String, List<String>>>()
        val gameArray = manifest.optJSONArray("games") ?: JSONArray()
        for (index in 0 until gameArray.length()) {
            val game = gameArray.optJSONObject(index) ?: continue
            val lineages = mutableListOf<String>()
            val lineageArray = game.optJSONArray("lineageIds") ?: JSONArray()
            for (line in 0 until lineageArray.length()) lineages.add(lineageArray.optString(line))
            games.add(game.optString("gameId") to lineages)
        }
        val omissionList = mutableListOf<String>()
        val omissionArray = manifest.optJSONArray("omissions") ?: JSONArray()
        for (index in 0 until omissionArray.length()) omissionList.add(omissionArray.optString(index))
        return IhubReadResult.Ok(
            IhubContents(
                formatVersion = version,
                archiveId = manifest.optString("archiveId"),
                createdAt = manifest.optString("createdAt"),
                exporterVersion = manifest.optString("exporterVersion"),
                encrypted = false,
                label = manifest.optString("label"),
                complete = manifest.optBoolean("complete"),
                omissions = omissionList,
                games = games,
                snapshots = snapshots,
            ),
        )
    }

    private fun centralDirectory(bytes: ByteArray): List<String> {
        val eocd = findEocd(bytes)
        if (eocd < 0) throw IhubFormatException("The archive is truncated.")
        val entries = u16(bytes, eocd + 10)
        val cdSize = u32(bytes, eocd + 12)
        val cdOffset = u32(bytes, eocd + 16)
        if (entries > MAX_ENTRIES || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw IhubFormatException("The archive is larger than the entry limit.")
        }
        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > bytes.size) {
            throw IhubFormatException("The archive is truncated.")
        }
        val names = mutableListOf<String>()
        var expanded = 0L
        var cursor = cdOffset.toInt()
        val end = (cdOffset + cdSize).toInt()
        repeat(entries) {
            if (cursor + 46 > end) throw IhubFormatException("The archive is truncated.")
            if (u32(bytes, cursor) != 0x02014b50L) throw IhubFormatException("The archive directory is not a zip directory.")
            val method = u16(bytes, cursor + 10)
            if (method != 0 && method != 8) throw IhubFormatException("The archive uses an unsupported compression method.")
            val uncompressed = u32(bytes, cursor + 24)
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val commentLength = u16(bytes, cursor + 32)
            val versionMadeBy = u16(bytes, cursor + 4)
            val external = u32(bytes, cursor + 38)
            if (cursor + 46 + nameLength > bytes.size) throw IhubFormatException("The archive is truncated.")
            val name = bytes.copyOfRange(cursor + 46, cursor + 46 + nameLength).toString(Charsets.UTF_8)
            if (!safePath(name)) throw IhubFormatException("The archive contains an unsafe path.")
            if ((versionMadeBy ushr 8) == 3 && ((external ushr 16) and 0xF000L) == 0xA000L) {
                throw IhubFormatException("The archive contains a symbolic link.")
            }
            if (uncompressed == 0xFFFFFFFFL || uncompressed > MAX_EXPANDED_BYTES) {
                throw IhubFormatException("The archive expands past the limit.")
            }
            if (name.startsWith("payloads/") && uncompressed > MAX_PAYLOAD_BYTES) {
                throw IhubFormatException("A save payload is larger than the limit.")
            }
            expanded += uncompressed
            if (expanded > MAX_EXPANDED_BYTES) throw IhubFormatException("The archive expands past the limit.")
            if (names.contains(name)) throw IhubFormatException("The archive repeats $name.")
            names.add(name)
            cursor += 46 + nameLength + extraLength + commentLength
        }
        return names
    }

    private fun findEocd(bytes: ByteArray): Int {
        if (bytes.size < 22) return -1
        val start = maxOf(0, bytes.size - 22 - 65535)
        var index = bytes.size - 22
        while (index >= start) {
            if (u32(bytes, index) == 0x06054b50L) return index
            index -= 1
        }
        return -1
    }

    private fun safePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.contains(":")) return false
        if (path.contains('\\') || path.contains('\u0000') || path.endsWith("/")) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return parts.all { safeSegment(it) || it == "manifest.json" || it.endsWith(".json") && safeSegment(it.removeSuffix(".json")) }
    }

    private fun safeSegment(value: String): Boolean {
        if (value.isEmpty() || value.length > 200) return false
        if (value == "." || value == "..") return false
        return value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }

    private fun readEntry(zip: ZipInputStream, cap: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var readTotal = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            readTotal += count
            if (readTotal > cap) throw IhubFormatException("The archive expands past the limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, body) in files) {
                val entry = ZipEntry(path)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(body)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) throw IhubFormatException("The archive is truncated.")
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) throw IhubFormatException("The archive is truncated.")
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun IhubReadResult.message(): String = when (this) {
        is IhubReadResult.Ok -> "verified"
        is IhubReadResult.Rejected -> message
    }
}

private class IhubFormatException(message: String) : IllegalArgumentException(message)
