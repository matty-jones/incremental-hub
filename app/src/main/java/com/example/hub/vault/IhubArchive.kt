package com.example.hub.vault

import com.example.hub.games.PayloadDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
    data class Ready(val file: File, val snapshotCount: Int) : IhubExportResult() {
        val bytes: ByteArray
            get() {
                val length = file.length()
                if (length > 16L * 1024 * 1024) {
                    error("Refusing to load an archive larger than 16 MiB into memory.")
                }
                return file.readBytes()
            }
    }
    data class Refused(val message: String, val omissions: List<String>) : IhubExportResult()
}

sealed class IhubOpenResult {
    data class Ready(val source: IhubSource) : IhubOpenResult()
    data class Rejected(val message: String) : IhubOpenResult()
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
    private const val MAX_METADATA_BYTES = 1024L * 1024L
    private const val MAX_CENTRAL_DIRECTORY = 8L * 1024L * 1024L

    /** Raw metadata bytes held at once while a zip is checked. One manifest plus one other file. */
    const val MAX_RETAINED_METADATA_BYTES = 2L * 1024 * 1024

    internal var validationPeakMetadataBytes: Long = 0
    internal var exportPeakPayloadBytes: Long = 0
    internal var lastIoThread: String? = null
    private var validationResidentMetadataBytes = 0L
    private var exportResidentPayloadBytes = 0L

    suspend fun <T> runOffMain(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lastIoThread = Thread.currentThread().name
        block()
    }

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
        val missing = drafts.filter { it.payload == null }.map { it.id }
        if (missing.isNotEmpty()) {
            return IhubExportResult.Refused(
                "This is not a complete archive. Missing: ${missing.joinToString(", ")}",
                missing,
            )
        }
        val writer = beginExport(archiveId, createdAt, exporterVersion)
        return try {
            for (draft in drafts) {
                val failure = writer.add(draft)
                if (failure != null) return failure
            }
            writer.finish(complete, omissions)
        } catch (cancelled: CancellationException) {
            writer.abort()
            throw cancelled
        } catch (e: IhubFormatException) {
            writer.refuse(e.message ?: "The archive could not be written.", omissions)
        } catch (e: Exception) {
            writer.refuse("The archive could not be written.", omissions)
        }
    }

    internal fun beginExport(
        archiveId: String,
        createdAt: String,
        exporterVersion: String,
    ): ExportWriter {
        exportPeakPayloadBytes = 0
        exportResidentPayloadBytes = 0
        val file = File.createTempFile("ihub-export", ".zip")
        file.deleteOnExit()
        return ExportWriter(
            file,
            ZipOutputStream(file.outputStream()),
            archiveId,
            createdAt,
            exporterVersion,
        )
    }

    internal class ExportWriter(
        private val file: File,
        private val zip: ZipOutputStream,
        private val archiveId: String,
        private val createdAt: String,
        private val exporterVersion: String,
    ) {
        private val members = JSONArray()
        private val written = mutableListOf<IhubSnapshotMeta>()
        private val writtenPayloads = mutableSetOf<String>()
        private val shasByGame = mutableMapOf<String, MutableSet<String>>()
        private var expanded = 0L
        private var closed = false

        fun contains(gameId: String, sha: String): Boolean = sha in shasByGame[gameId].orEmpty()

        fun add(draft: IhubSnapshotDraft): IhubExportResult? {
            val payload = draft.payload
                ?: return refuse(
                    "This is not a complete archive. Missing: ${draft.id}",
                    listOf(draft.id),
                )
            if (!safeSegment(draft.gameId)) {
                return refuse("Game id ${draft.gameId} cannot be stored in an archive path.", listOf(draft.gameId))
            }
            if (!safeSegment(draft.lineageId)) {
                return refuse("A lineage id cannot be stored in an archive path.", listOf(draft.lineageId))
            }
            if (!safeSegment(draft.id)) {
                return refuse("Snapshot id ${draft.id} cannot be stored in an archive path.", listOf(draft.id))
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            holdExportPayload(bytes.size)
            try {
                if (bytes.size > MAX_PAYLOAD_BYTES) {
                    return refuse("Snapshot ${draft.id} is larger than the payload limit.", listOf(draft.id))
                }
                val sha = sha256(bytes)
                if (draft.storedSha256.isNotEmpty() && draft.storedSha256 != sha) {
                    return refuse("Snapshot ${draft.id} does not match its stored hash.", listOf(draft.id))
                }
                expanded += bytes.size
                if (expanded > MAX_EXPANDED_BYTES) {
                    return refuse("The archive expands past the limit.", emptyList())
                }
                if (writtenPayloads.add(sha)) put("payloads/$sha", bytes, true)
                val capturedAt = Instant.ofEpochMilli(draft.capturedAtMillis).toString()
                val snapshotJson = JSONObject()
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
                put("snapshots/${draft.id}.json", snapshotJson, true)
                written += IhubSnapshotMeta(
                    id = draft.id,
                    gameId = draft.gameId,
                    lineageId = draft.lineageId,
                    sequence = draft.sequence,
                    capturedAtMillis = draft.capturedAtMillis,
                    reason = draft.reason,
                    origin = draft.origin,
                    pinned = draft.pinned,
                    userProtected = draft.userProtected,
                    contentSha256 = sha,
                    payloadPath = "payloads/$sha",
                )
                shasByGame.getOrPut(draft.gameId) { mutableSetOf() }.add(sha)
                return null
            } finally {
                dropExportPayload(bytes.size)
            }
        }

        fun finish(complete: Boolean, omissions: List<String>): IhubExportResult {
            if (complete && omissions.isNotEmpty()) {
                return refuse(
                    "This is not a complete archive. Missing: ${omissions.joinToString(", ")}",
                    omissions,
                )
            }
            val byGame = written.groupBy { it.gameId }
            val games = JSONArray()
            for (gameId in byGame.keys.sorted()) {
                val lineages = byGame.getValue(gameId).map { it.lineageId }.distinct().sorted()
                put(
                    "games/$gameId/instance.json",
                    JSONObject()
                        .put("gameId", gameId)
                        .put("lineageIds", JSONArray(lineages))
                        .toString()
                        .toByteArray(Charsets.UTF_8),
                    true,
                )
                games.put(
                    JSONObject()
                        .put("gameId", gameId)
                        .put("lineageIds", JSONArray(lineages))
                        .put("snapshotIds", JSONArray(byGame.getValue(gameId).map { it.id }.sorted())),
                )
            }
            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("archiveId", archiveId)
                .put("createdAt", createdAt)
                .put("exporterVersion", exporterVersion)
                .put("encrypted", false)
                .put("label", LABEL)
                .put("complete", complete && omissions.isEmpty())
                .put("omissions", JSONArray(omissions))
                .put("games", games)
                .put("snapshotIds", JSONArray(written.map { it.id }.sorted()))
                .put("members", members)
            put("manifest.json", manifest.toString().toByteArray(Charsets.UTF_8), false)
            closeZip()
            if (file.length() > MAX_COMPRESSED_BYTES) {
                file.delete()
                return IhubExportResult.Refused("The archive is larger than the compressed limit.", emptyList())
            }
            val verified = open(file)
            if (verified !is IhubOpenResult.Ready) {
                file.delete()
                return IhubExportResult.Refused(verified.message(), emptyList())
            }
            val byId = verified.source.entries.associateBy { it.id }
            for (meta in written) {
                val exported = byId[meta.id]
                if (exported == null || exported.contentSha256 != meta.contentSha256) {
                    file.delete()
                    return IhubExportResult.Refused(
                        "The archive did not round-trip snapshot ${meta.id}.",
                        listOf(meta.id),
                    )
                }
            }
            return IhubExportResult.Ready(file, written.size)
        }

        fun refuse(message: String, omissions: List<String>): IhubExportResult.Refused {
            closeZip()
            file.delete()
            return IhubExportResult.Refused(message, omissions)
        }

        fun abort() {
            closeZip()
            file.delete()
        }

        private fun put(path: String, body: ByteArray, record: Boolean) {
            val entry = ZipEntry(path)
            entry.time = 0L
            zip.putNextEntry(entry)
            zip.write(body)
            zip.closeEntry()
            if (record) {
                members.put(
                    JSONObject()
                        .put("path", path)
                        .put("bytes", body.size)
                        .put("sha256", sha256(body)),
                )
            }
        }

        private fun closeZip() {
            if (!closed) {
                runCatching { zip.close() }
                closed = true
            }
        }
    }


    fun copyLimited(input: InputStream, destination: File, maxBytes: Long): Boolean {
        destination.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return true
                if (total + count > maxBytes) return false
                output.write(buffer, 0, count)
                total += count
            }
        }
    }

    fun read(bytes: ByteArray): IhubReadResult {
        if (bytes.size.toLong() > MAX_COMPRESSED_BYTES) {
            return IhubReadResult.Rejected("The archive is larger than the compressed limit.")
        }
        val file = File.createTempFile("ihub-read", ".zip")
        return try {
            file.outputStream().use { it.write(bytes) }
            read(file)
        } finally {
            file.delete()
        }
    }

    fun read(file: File): IhubReadResult {
        return when (val opened = open(file)) {
            is IhubOpenResult.Rejected -> IhubReadResult.Rejected(opened.message)
            is IhubOpenResult.Ready -> IhubReadResult.Ok(opened.source.toContents())
        }
    }

    fun open(file: File): IhubOpenResult {
        if (!file.isFile) return IhubOpenResult.Rejected("The archive could not be read.")
        if (file.length() > MAX_COMPRESSED_BYTES) {
            return IhubOpenResult.Rejected("The archive is larger than the compressed limit.")
        }
        val names = try {
            centralDirectory(file)
        } catch (e: IhubFormatException) {
            return IhubOpenResult.Rejected(e.message ?: "The archive could not be read.")
        }
        validationPeakMetadataBytes = 0
        validationResidentMetadataBytes = 0
        val memberFacts = linkedMapOf<String, Pair<Int, String>>()
        val parsedSnapshots = mutableListOf<IhubSnapshotMeta>()
        val seenSnapshotIds = mutableMapOf<String, String>()
        var snapshotFailure: String? = null
        var manifestBytes: ByteArray? = null
        var expanded = 0L
        try {
            ZipFile(file).use { zip ->
                val seen = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!seen.add(name)) {
                        return IhubOpenResult.Rejected("The archive repeats $name.")
                    }
                    if (!safePath(name)) {
                        return IhubOpenResult.Rejected("The archive contains an unsafe path.")
                    }
                    val payloadEntry = name.startsWith("payloads/")
                    val snapshotEntry = name.startsWith("snapshots/") && name.endsWith(".json")
                    val cap = if (payloadEntry) MAX_PAYLOAD_BYTES.toLong() else MAX_METADATA_BYTES
                    val declared = entry.size
                    if (declared > cap || declared > MAX_EXPANDED_BYTES) {
                        return IhubOpenResult.Rejected(
                            if (payloadEntry) {
                                "A save payload is larger than the limit."
                            } else {
                                "The archive expands past the limit."
                            },
                        )
                    }
                    if (expanded > MAX_EXPANDED_BYTES) {
                        return IhubOpenResult.Rejected("The archive expands past the limit.")
                    }
                    val body = readBounded(zip.getInputStream(entry), cap)
                    expanded += body.size
                    if (expanded > MAX_EXPANDED_BYTES) {
                        return IhubOpenResult.Rejected("The archive expands past the limit.")
                    }
                    if (payloadEntry) {
                        val hash = sha256(body)
                        val text = body.toString(Charsets.UTF_8)
                        if (PayloadDigest.sha256(text) != hash) {
                            return IhubOpenResult.Rejected("Snapshot payload bytes are not stable text.")
                        }
                        memberFacts[name] = body.size to hash
                    } else if (body.size > MAX_METADATA_BYTES) {
                        return IhubOpenResult.Rejected("The archive expands past the limit.")
                    } else if (name == "manifest.json") {
                        retainMetadata(body.size)
                        manifestBytes = body
                    } else if (snapshotEntry) {
                        retainMetadata(body.size)
                        if (snapshotFailure == null) {
                            try {
                                parsedSnapshots.add(parseSnapshotMeta(name, body, seenSnapshotIds))
                            } catch (e: IhubFormatException) {
                                snapshotFailure = e.message ?: "$name is not valid JSON."
                            }
                        }
                        memberFacts[name] = body.size to sha256(body)
                        releaseMetadata(body.size)
                    } else {
                        retainMetadata(body.size)
                        memberFacts[name] = body.size to sha256(body)
                        releaseMetadata(body.size)
                    }
                }
                if (seen != names.toSet()) {
                    return IhubOpenResult.Rejected("The archive members do not match its directory.")
                }
            }
        } catch (e: IhubFormatException) {
            return IhubOpenResult.Rejected(e.message ?: "The archive could not be read.")
        } catch (e: Exception) {
            return IhubOpenResult.Rejected("The archive could not be read.")
        }
        return interpret(file, manifestBytes, memberFacts, parsedSnapshots, snapshotFailure)
    }

    private fun interpret(
        file: File,
        manifestBytes: ByteArray?,
        memberFacts: Map<String, Pair<Int, String>>,
        parsedSnapshots: List<IhubSnapshotMeta>,
        snapshotFailure: String?,
    ): IhubOpenResult {
        if (manifestBytes == null) return IhubOpenResult.Rejected("The archive has no manifest.")
        val manifest = try {
            JSONObject(manifestBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return IhubOpenResult.Rejected("The manifest is not valid JSON.")
        }
        val version = manifest.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) {
            return IhubOpenResult.Rejected("Archive format $version is not supported.")
        }
        if (manifest.optBoolean("encrypted", true)) {
            return IhubOpenResult.Rejected("Encrypted archives are not supported.")
        }
        val members = manifest.optJSONArray("members")
            ?: return IhubOpenResult.Rejected("The manifest has no member list.")
        val listed = mutableMapOf<String, Pair<Int, String>>()
        for (index in 0 until members.length()) {
            val member = members.optJSONObject(index)
                ?: return IhubOpenResult.Rejected("A manifest member is not an object.")
            val path = member.optString("path")
            if (path.isEmpty() || listed.containsKey(path)) {
                return IhubOpenResult.Rejected("The manifest repeats a member.")
            }
            listed[path] = member.optInt("bytes", -1) to member.optString("sha256")
        }
        if (listed.keys != memberFacts.keys) {
            return IhubOpenResult.Rejected("The manifest members do not match the archive.")
        }
        for ((path, expectedMeta) in listed) {
            val fact = memberFacts[path] ?: return IhubOpenResult.Rejected("Missing $path.")
            if (fact.first != expectedMeta.first || fact.second != expectedMeta.second) {
                return IhubOpenResult.Rejected("The hash of $path does not match the manifest.")
            }
        }
        if (snapshotFailure != null) return IhubOpenResult.Rejected(snapshotFailure)
        for (meta in parsedSnapshots) {
            val fact = memberFacts[meta.payloadPath]
                ?: return IhubOpenResult.Rejected("Snapshot ${meta.id} is missing its payload.")
            if (fact.second != meta.contentSha256) {
                return IhubOpenResult.Rejected("Snapshot ${meta.id} does not match its payload hash.")
            }
        }
        val entries = parsedSnapshots
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
        return IhubOpenResult.Ready(
            IhubSource(
                file = file,
                formatVersion = version,
                archiveId = manifest.optString("archiveId"),
                createdAt = manifest.optString("createdAt"),
                exporterVersion = manifest.optString("exporterVersion"),
                encrypted = false,
                label = manifest.optString("label"),
                complete = manifest.optBoolean("complete"),
                omissions = omissionList,
                games = games,
                entries = entries,
            ),
        )
    }

    private fun parseSnapshotMeta(
        path: String,
        body: ByteArray,
        seen: MutableMap<String, String>,
    ): IhubSnapshotMeta {
        val json = try {
            JSONObject(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw IhubFormatException("$path is not valid JSON.")
        }
        val id = json.optString("snapshotId")
        val gameId = json.optString("gameId")
        val lineageId = json.optString("lineageId")
        val payloadPath = json.optString("payloadPath")
        val sha = json.optString("contentSha256")
        if (!safeSegment(id) || !safeSegment(gameId) || !safeSegment(lineageId) || !safeSegment(sha)) {
            throw IhubFormatException("A snapshot id, game, lineage, or hash is not usable.")
        }
        if (payloadPath != "payloads/$sha") {
            throw IhubFormatException("Snapshot $id points at the wrong payload.")
        }
        val previous = seen.put(id, sha)
        if (previous != null) {
            throw IhubFormatException(
                if (previous == sha) {
                    "Snapshot $id is stored twice."
                } else {
                    "Snapshot $id is stored twice with different payloads."
                },
            )
        }
        val sequence = json.optLong("captureSequence", -1)
        val capturedAtMillis = json.optLong("capturedAtMillis", Long.MIN_VALUE)
        val reason = json.optString("reason")
        if (sequence < 0 || capturedAtMillis == Long.MIN_VALUE || reason.isEmpty()) {
            throw IhubFormatException("A snapshot is missing its sequence, time, or reason.")
        }
        return IhubSnapshotMeta(
            id = id,
            gameId = gameId,
            lineageId = lineageId,
            sequence = sequence,
            capturedAtMillis = capturedAtMillis,
            reason = reason,
            origin = json.optString("origin"),
            pinned = json.optBoolean("pinned"),
            userProtected = json.optBoolean("userProtected"),
            contentSha256 = sha,
            payloadPath = payloadPath,
        )
    }

    private fun retainMetadata(bytes: Int) {
        validationResidentMetadataBytes += bytes
        if (validationResidentMetadataBytes > validationPeakMetadataBytes) {
            validationPeakMetadataBytes = validationResidentMetadataBytes
        }
        if (validationResidentMetadataBytes > MAX_RETAINED_METADATA_BYTES) {
            throw IhubFormatException("The archive metadata is larger than the memory limit.")
        }
    }

    private fun releaseMetadata(bytes: Int) {
        validationResidentMetadataBytes -= bytes
        if (validationResidentMetadataBytes < 0) validationResidentMetadataBytes = 0
    }

    internal fun holdExportPayload(bytes: Int) {
        exportResidentPayloadBytes += bytes
        if (exportResidentPayloadBytes > exportPeakPayloadBytes) {
            exportPeakPayloadBytes = exportResidentPayloadBytes
        }
    }

    internal fun dropExportPayload(bytes: Int) {
        exportResidentPayloadBytes -= bytes
        if (exportResidentPayloadBytes < 0) exportResidentPayloadBytes = 0
    }

    private fun centralDirectory(file: File): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length > MAX_COMPRESSED_BYTES) {
                throw IhubFormatException("The archive is larger than the compressed limit.")
            }
            if (length < 22) throw IhubFormatException("The archive is truncated.")
            val window = minOf(length, 22L + 65535L).toInt()
            val tail = ByteArray(window)
            raf.seek(length - window)
            raf.readFully(tail)
            val eocd = findEocd(tail)
            if (eocd < 0) throw IhubFormatException("The archive is truncated.")
            val entryCount = u16(tail, eocd + 10)
            val cdSize = u32(tail, eocd + 12)
            val cdOffset = u32(tail, eocd + 16)
            if (entryCount > MAX_ENTRIES || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                throw IhubFormatException("The archive is larger than the entry limit.")
            }
            if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > length || cdSize > MAX_CENTRAL_DIRECTORY) {
                throw IhubFormatException("The archive is truncated.")
            }
            val directory = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(directory)
            return parseCentral(directory, entryCount)
        }
    }

    private fun parseCentral(bytes: ByteArray, entries: Int): List<String> {
        val names = mutableListOf<String>()
        var expanded = 0L
        var cursor = 0
        val end = bytes.size
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

    private fun readBounded(input: InputStream, cap: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > cap) throw IhubFormatException("The archive expands past the limit.")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun IhubOpenResult.message(): String = when (this) {
        is IhubOpenResult.Ready -> "verified"
        is IhubOpenResult.Rejected -> message
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

    internal fun readSnapshot(meta: IhubSnapshotMeta, file: File): IhubSnapshotFile {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(meta.payloadPath)
                ?: throw IhubFormatException("Snapshot ${meta.id} is missing its payload.")
            if (entry.size > MAX_PAYLOAD_BYTES) {
                throw IhubFormatException("A save payload is larger than the limit.")
            }
            val bytes = readBounded(zip.getInputStream(entry), MAX_PAYLOAD_BYTES.toLong())
            if (sha256(bytes) != meta.contentSha256) {
                throw IhubFormatException("Snapshot ${meta.id} does not match its payload hash.")
            }
            val payload = bytes.toString(Charsets.UTF_8)
            if (PayloadDigest.sha256(payload) != meta.contentSha256) {
                throw IhubFormatException("Snapshot ${meta.id} payload bytes are not stable text.")
            }
            return IhubSnapshotFile(
                id = meta.id,
                gameId = meta.gameId,
                lineageId = meta.lineageId,
                sequence = meta.sequence,
                capturedAtMillis = meta.capturedAtMillis,
                reason = meta.reason,
                origin = meta.origin,
                pinned = meta.pinned,
                userProtected = meta.userProtected,
                contentSha256 = meta.contentSha256,
                payload = payload,
            )
        }
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

data class IhubSnapshotMeta(
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
    val payloadPath: String,
)

class IhubSource(
    val file: File,
    val formatVersion: Int,
    val archiveId: String,
    val createdAt: String,
    val exporterVersion: String,
    val encrypted: Boolean,
    val label: String,
    val complete: Boolean,
    val omissions: List<String>,
    val games: List<Pair<String, List<String>>>,
    val entries: List<IhubSnapshotMeta>,
) {
    var interruptAt: Int = -1

    fun snapshot(index: Int): IhubSnapshotFile {
        if (interruptAt >= 0 && index == interruptAt) {
            throw IllegalStateException("Import interrupted before publication.")
        }
        return IhubArchive.readSnapshot(entries[index], file)
    }

    fun snapshotById(id: String): IhubSnapshotFile? {
        val meta = entries.firstOrNull { it.id == id } ?: return null
        return IhubArchive.readSnapshot(meta, file)
    }

    fun toContents(): IhubContents {
        return IhubContents(
            formatVersion = formatVersion,
            archiveId = archiveId,
            createdAt = createdAt,
            exporterVersion = exporterVersion,
            encrypted = encrypted,
            label = label,
            complete = complete,
            omissions = omissions,
            games = games,
            snapshots = entries.indices.map { snapshot(it) },
        )
    }
}
