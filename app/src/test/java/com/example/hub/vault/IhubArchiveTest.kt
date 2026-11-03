package com.example.hub.vault

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IhubArchiveTest {

    @Test
    fun roundTripPreservesPayloadBytesAndMetadata() {
        val payload = "save-\u0000-bytes"
        val exported = IhubArchive.export(
            drafts = listOf(draft("snap-1", payload, userProtected = true, pinned = true)),
            archiveId = "archive-1",
            createdAt = "2026-10-31T17:00:00Z",
            exporterVersion = "0.1.23",
            complete = true,
        )
        assertTrue(exported is IhubExportResult.Ready)
        val ready = exported as IhubExportResult.Ready
        val read = IhubArchive.read(ready.bytes)
        assertTrue(read is IhubReadResult.Ok)
        val archive = (read as IhubReadResult.Ok).archive
        assertFalse(archive.encrypted)
        assertTrue(archive.label.contains("Unencrypted"))
        assertTrue(archive.label.contains("credentials"))
        assertTrue(archive.complete)
        assertEquals(1, archive.snapshots.size)
        val snapshot = archive.snapshots.single()
        assertEquals("snap-1", snapshot.id)
        assertEquals("legacy", snapshot.lineageId)
        assertEquals(payload, snapshot.payload)
        assertEquals(7L, snapshot.sequence)
        assertEquals(true, snapshot.userProtected)
        assertEquals(true, snapshot.pinned)
        assertEquals("cadence", snapshot.reason)
        val instance = String(
            unzip(ready.bytes).getValue("games/shark-incremental/instance.json"),
            Charsets.UTF_8,
        )
        assertFalse(instance.contains("sessionIdentity"))
        assertFalse(instance.contains("cookie"))
        assertFalse(instance.contains("profileName"))
        assertFalse(instance.contains("resumeToken"))
    }

    @Test
    fun completeExportRefusesAMissingPayload() {
        val exported = IhubArchive.export(
            drafts = listOf(draft("snap-1", payload = null)),
            archiveId = "archive-1",
            createdAt = "2026-10-31T17:00:00Z",
            exporterVersion = "0.1.23",
            complete = true,
        )
        assertTrue(exported is IhubExportResult.Refused)
        assertEquals(listOf("snap-1"), (exported as IhubExportResult.Refused).omissions)
    }

    @Test
    fun tamperedMemberHashIsRejected() {
        val payload = "payload".toByteArray()
        val path = "payloads/" + "ab".repeat(32)
        val manifest = manifest(members = JSONArray().put(member(path, payload.size, "deadbeef")))
        val zip = zipOf(
            "manifest.json" to manifest.toString().toByteArray(),
            path to payload,
        )
        val read = IhubArchive.read(zip)
        assertTrue(read is IhubReadResult.Rejected)
        assertTrue((read as IhubReadResult.Rejected).message.contains("hash"))
    }

    @Test
    fun truncatedZipIsRejected() {
        val exported = IhubArchive.export(
            drafts = listOf(draft("snap-1", "payload")),
            archiveId = "archive-1",
            createdAt = "2026-10-31T17:00:00Z",
            exporterVersion = "0.1.23",
            complete = true,
        ) as IhubExportResult.Ready
        val truncated = exported.bytes.copyOf(exported.bytes.size / 2)
        assertTrue(IhubArchive.read(truncated) is IhubReadResult.Rejected)
    }

    @Test
    fun pathTraversalDuplicateNamesAndSymlinksAreRejected() {
        val traversal = zipOf("../escape.json" to "x".toByteArray())
        assertTrue((IhubArchive.read(traversal) as IhubReadResult.Rejected).message.contains("unsafe"))
        val duplicate = duplicateCentral(zipOf("dup.txt" to "a".toByteArray()))
        val duplicateRead = IhubArchive.read(duplicate)
        val duplicateMessage = (duplicateRead as IhubReadResult.Rejected).message
        assertTrue(duplicateMessage, duplicateMessage.contains("repeats"))
        val link = markSymlink(zipOf("link" to "target".toByteArray()))
        val linkRead = IhubArchive.read(link)
        val linkMessage = (linkRead as IhubReadResult.Rejected).message
        assertTrue(linkMessage, linkMessage.contains("symbolic link"))
    }

    @Test
    fun streamingStopsBeforeAnOversizedArchiveIsAllocated() {
        val input = ByteArray(200) { 1 }.inputStream()
        val file = File.createTempFile("ihub-limit", ".bin")
        try {
            val copied = IhubArchive.copyLimited(input, file, 100)
            assertFalse(copied)
            assertTrue(file.length() <= 100L)
        } finally {
            file.delete()
        }
        val sparse = File.createTempFile("ihub-sparse", ".zip")
        try {
            RandomAccessFile(sparse, "rw").use { it.setLength(IhubArchive.MAX_COMPRESSED_BYTES + 1) }
            val read = IhubArchive.read(sparse)
            assertTrue(read is IhubReadResult.Rejected)
            assertTrue((read as IhubReadResult.Rejected).message.contains("compressed"))
        } finally {
            sparse.delete()
        }
    }

    @Test
    fun oversizedDeclaredEntriesAreRejectedBeforeUse() {
        val payloadPath = "payloads/" + "cd".repeat(32)
        val zip = zipOf(payloadPath to "small".toByteArray())
        val hugePayload = declareUncompressedSize(zip, payloadPath, IhubArchive.MAX_PAYLOAD_BYTES + 1L)
        assertTrue((IhubArchive.read(hugePayload) as IhubReadResult.Rejected).message.contains("payload"))
        val hugeArchive = declareUncompressedSize(zip, payloadPath, 2_000_000_000L)
        assertTrue(IhubArchive.read(hugeArchive) is IhubReadResult.Rejected)
        assertTrue(IhubArchive.MAX_COMPRESSED_BYTES == 512L * 1024 * 1024)
        val overCompressed = ByteArray(0)
        assertTrue(overCompressed.size.toLong() <= IhubArchive.MAX_COMPRESSED_BYTES)
        assertTrue(
            (IhubArchive.read(ByteArray(32) { 0 }) as IhubReadResult.Rejected).message.isNotEmpty(),
        )
    }

    @Test
    fun unsupportedFormatAndConflictingIdsAreRejected() {
        val manifest = manifest(version = 3)
        val zip = zipOf("manifest.json" to manifest.toString().toByteArray())
        assertTrue((IhubArchive.read(zip) as IhubReadResult.Rejected).message.contains("not supported"))
        val first = snapshotJson("same", "one")
        val second = snapshotJson("same", "two")
        val firstBytes = first.toString().toByteArray()
        val secondBytes = second.toString().toByteArray()
        val payloadOne = "one".toByteArray()
        val payloadTwo = "two".toByteArray()
        val onePath = first.getString("payloadPath")
        val twoPath = second.getString("payloadPath")
        val members = JSONArray()
            .put(member("snapshots/one.json", firstBytes.size, sha(firstBytes)))
            .put(member("snapshots/two.json", secondBytes.size, sha(secondBytes)))
            .put(member(onePath, payloadOne.size, sha(payloadOne)))
            .put(member(twoPath, payloadTwo.size, sha(payloadTwo)))
        val conflict = zipOf(
            "manifest.json" to manifest(members = members).toString().toByteArray(),
            "snapshots/one.json" to firstBytes,
            "snapshots/two.json" to secondBytes,
            onePath to payloadOne,
            twoPath to payloadTwo,
        )
        val read = IhubArchive.read(conflict)
        assertTrue(read is IhubReadResult.Rejected)
        assertTrue((read as IhubReadResult.Rejected).message.contains("different payloads"))
    }

    @Test
    fun metadataBytesAreReleasedWhileALargeArchiveIsChecked() {
        val manifest = manifest().toString().toByteArray()
        val files = linkedMapOf<String, ByteArray>()
        files["manifest.json"] = manifest
        var metadata = manifest.size
        repeat(30) { index ->
            val body = ByteArray(100_000) { (index + 1).toByte() }
            files["notes/note-$index.txt"] = body
            metadata += body.size
        }
        val read = IhubArchive.read(zipOf(files))
        assertTrue(read is IhubReadResult.Rejected)
        assertTrue(IhubArchive.validationPeakMetadataBytes < 512L * 1024)
        assertTrue(IhubArchive.validationPeakMetadataBytes < metadata / 4)
    }

    @Test
    fun migratedIdsRoundTripWithoutBecomingZipPaths() {
        val sharkId = "migrated:shark-incremental:" + "ab".repeat(32)
        val farmId = "migrated:fe000000:" + "cd".repeat(32)
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val sharkPayload = "shark-bytes"
        val farmPayload = "farm-bytes"
        val uuidPayload = "uuid-bytes"
        val exported = IhubArchive.export(
            drafts = listOf(
                draft(sharkId, sharkPayload, gameId = "shark-incremental"),
                draft(farmId, farmPayload, gameId = "fe000000"),
                draft(uuid, uuidPayload, gameId = "shark-incremental"),
            ),
            archiveId = "archive-migrated",
            createdAt = "2026-11-02T00:00:00Z",
            exporterVersion = "0.1.24",
            complete = true,
        )
        assertTrue(exported is IhubExportResult.Ready)
        val ready = exported as IhubExportResult.Ready
        val files = unzip(ready.bytes)
        assertTrue(files.keys.none { it.contains(':') || it.contains('\\') || it.contains("..") })
        val manifest = JSONObject(String(files.getValue("manifest.json"), Charsets.UTF_8))
        assertEquals(IhubArchive.FORMAT_VERSION, manifest.getInt("formatVersion"))
        val listed = manifest.getJSONArray("snapshotIds")
        assertEquals(setOf(sharkId, farmId, uuid), jsonStrings(listed))
        assertEquals(sharkId, snapshotIdAt(files, sharkId))
        assertEquals(farmId, snapshotIdAt(files, farmId))
        assertEquals(uuid, snapshotIdAt(files, uuid))
        val read = IhubArchive.read(ready.bytes)
        assertTrue(read is IhubReadResult.Ok)
        val snapshots = (read as IhubReadResult.Ok).archive.snapshots.associateBy { it.id }
        assertEquals(sharkPayload, snapshots.getValue(sharkId).payload)
        assertEquals(farmPayload, snapshots.getValue(farmId).payload)
        assertEquals(uuidPayload, snapshots.getValue(uuid).payload)
        assertEquals("fe000000", snapshots.getValue(farmId).gameId)
        assertEquals("legacy", snapshots.getValue(farmId).lineageId)
    }

    @Test
    fun formatOneArchiveStillImportsTheOriginalId() {
        val payload = "legacy-bytes"
        val json = snapshotJson("snap-1", payload)
        val meta = json.toString().toByteArray()
        val body = payload.toByteArray()
        val payloadPath = json.getString("payloadPath")
        val members = JSONArray()
            .put(member("snapshots/snap-1.json", meta.size, sha(meta)))
            .put(member(payloadPath, body.size, sha(body)))
        val zip = zipOf(
            "manifest.json" to manifest(version = 1, members = members).toString().toByteArray(),
            "snapshots/snap-1.json" to meta,
            payloadPath to body,
        )
        val read = IhubArchive.read(zip)
        assertTrue(read is IhubReadResult.Ok)
        val snapshot = (read as IhubReadResult.Ok).archive.snapshots.single()
        assertEquals(1, (read as IhubReadResult.Ok).archive.formatVersion)
        assertEquals("snap-1", snapshot.id)
        assertEquals(payload, snapshot.payload)
    }

    @Test
    fun wrongPathDuplicateIdAndBadHashAreRejected() {
        val payload = "payload"
        val id = "migrated:shark-incremental:" + "ef".repeat(32)
        val json = snapshotJson(id, payload)
        val meta = json.toString().toByteArray()
        val body = payload.toByteArray()
        val payloadPath = json.getString("payloadPath")
        val members = JSONArray()
            .put(member("snapshots/not-the-digest.json", meta.size, sha(meta)))
            .put(member(payloadPath, body.size, sha(body)))
        val misplaced = zipOf(
            "manifest.json" to manifest(version = 2, members = members).toString().toByteArray(),
            "snapshots/not-the-digest.json" to meta,
            payloadPath to body,
        )
        val misplacedRead = IhubArchive.read(misplaced)
        assertTrue((misplacedRead as IhubReadResult.Rejected).message.contains("archive path"))

        val collided = IhubArchive.export(
            drafts = listOf(draft("snap-1", payload), draft("snap-1", "other")),
            archiveId = "archive-1",
            createdAt = "2026-11-02T00:00:00Z",
            exporterVersion = "0.1.24",
            complete = true,
        )
        assertTrue(collided is IhubExportResult.Refused)
        assertTrue((collided as IhubExportResult.Refused).message.contains("collides"))

        val hashed = snapshotJson("snap-1", payload)
        hashed.put("contentSha256", "ab".repeat(32))
        hashed.put("payloadPath", "payloads/" + "ab".repeat(32))
        val hashedMeta = hashed.toString().toByteArray()
        val hashedMembers = JSONArray()
            .put(member("snapshots/snap-1.json", hashedMeta.size, sha(hashedMeta)))
            .put(member("payloads/" + "ab".repeat(32), body.size, sha(body)))
        val badHash = zipOf(
            "manifest.json" to manifest(version = 1, members = hashedMembers).toString().toByteArray(),
            "snapshots/snap-1.json" to hashedMeta,
            "payloads/" + "ab".repeat(32) to body,
        )
        val hashRead = IhubArchive.read(badHash)
        assertTrue((hashRead as IhubReadResult.Rejected).message.contains("hash"))

        val slash = IhubArchive.export(
            drafts = listOf(draft("migrated/shark", payload)),
            archiveId = "archive-1",
            createdAt = "2026-11-02T00:00:00Z",
            exporterVersion = "0.1.24",
            complete = true,
        )
        assertTrue(slash is IhubExportResult.Refused)
        assertTrue((slash as IhubExportResult.Refused).message.contains("usable"))
    }

    @Test
    fun declaredEntryCountAboveTheCapIsRejectedBeforeTheDirectoryIsUsed() {
        val exported = IhubArchive.export(
            drafts = listOf(draft("snap-1", "payload")),
            archiveId = "archive-1",
            createdAt = "2026-10-31T17:00:00Z",
            exporterVersion = "0.1.24",
            complete = true,
        ) as IhubExportResult.Ready
        val zip = exported.bytes
        var eocd = -1
        var index = zip.size - 22
        while (index >= 0) {
            if (u32at(zip, index) == 0x06054b50L) {
                eocd = index
                break
            }
            index -= 1
        }
        assertTrue(eocd >= 0)
        zip[eocd + 10] = (10_001 and 0xff).toByte()
        zip[eocd + 11] = ((10_001 ushr 8) and 0xff).toByte()
        val read = IhubArchive.read(zip)
        assertTrue(read is IhubReadResult.Rejected)
        assertTrue((read as IhubReadResult.Rejected).message.contains("entry"))
    }

    private fun draft(
        id: String,
        payload: String?,
        userProtected: Boolean = false,
        pinned: Boolean = false,
        gameId: String = "shark-incremental",
    ) = IhubSnapshotDraft(
            id = id,
            gameId = gameId,
            lineageId = "legacy",
            sequence = 7,
            capturedAtMillis = 1_700_000_000_000L,
            reason = "cadence",
            origin = "local-capture",
            pinned = pinned,
            userProtected = userProtected,
            storedSha256 = if (payload == null) "" else com.example.hub.games.PayloadDigest.sha256(payload),
            payload = payload,
        )

    private fun snapshotJson(id: String, payload: String): JSONObject {
        val bytes = payload.toByteArray()
        val hash = sha(bytes)
        return JSONObject()
            .put("formatVersion", 1)
            .put("snapshotId", id)
            .put("gameId", "shark-incremental")
            .put("lineageId", "legacy")
            .put("captureSequence", 1)
            .put("capturedAt", "2026-10-31T17:00:00Z")
            .put("capturedAtMillis", 1_700_000_000_000L)
            .put("reason", "cadence")
            .put("origin", "local-capture")
            .put("pinned", false)
            .put("userProtected", false)
            .put("contentSha256", hash)
            .put("payloadPath", "payloads/$hash")
    }

    private fun manifest(version: Int = 1, members: JSONArray = JSONArray()): JSONObject {
        return JSONObject()
            .put("formatVersion", version)
            .put("archiveId", "archive")
            .put("createdAt", "2026-10-31T17:00:00Z")
            .put("exporterVersion", "test")
            .put("encrypted", false)
            .put("label", IhubArchive.LABEL)
            .put("complete", true)
            .put("omissions", JSONArray())
            .put("games", JSONArray())
            .put("snapshotIds", JSONArray())
            .put("members", members)
    }

    private fun member(path: String, bytes: Int, sha: String): JSONObject {
        return JSONObject().put("path", path).put("bytes", bytes).put("sha256", sha)
    }

    private fun snapshotIdAt(files: Map<String, ByteArray>, id: String): String {
        val digest = sha(id.toByteArray(Charsets.UTF_8))
        val json = JSONObject(String(files.getValue("snapshots/$digest.json"), Charsets.UTF_8))
        return json.getString("snapshotId")
    }

    private fun jsonStrings(array: JSONArray): Set<String> {
        return buildSet {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    private fun sha(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray = zipOf(files.toMap())

    private fun zipOf(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, body) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        return files
    }

    private fun markSymlink(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val header = findCentral(copy, "link")
        copy[header + 5] = 3
        copy[header + 40] = 0
        copy[header + 41] = 0xA0.toByte()
        return copy
    }

    private fun declareUncompressedSize(zip: ByteArray, name: String, size: Long): ByteArray {
        val copy = zip.copyOf()
        val header = findCentral(copy, name)
        copy[header + 24] = (size and 0xff).toByte()
        copy[header + 25] = ((size ushr 8) and 0xff).toByte()
        copy[header + 26] = ((size ushr 16) and 0xff).toByte()
        copy[header + 27] = ((size ushr 24) and 0xff).toByte()
        return copy
    }

    private fun duplicateCentral(zip: ByteArray): ByteArray {
        var eocd = zip.size - 22
        while (eocd >= 0) {
            val signature = u32at(zip, eocd)
            if (signature == 0x06054b50L) break
            eocd -= 1
        }
        val cdOffset = u32at(zip, eocd + 16).toInt()
        val cdSize = u32at(zip, eocd + 12)
        val nameLength = u16at(zip, cdOffset + 28)
        val extraLength = u16at(zip, cdOffset + 30)
        val commentLength = u16at(zip, cdOffset + 32)
        val entryLength = 46 + nameLength + extraLength + commentLength
        val inserted = ByteArray(zip.size + entryLength)
        System.arraycopy(zip, 0, inserted, 0, eocd)
        System.arraycopy(zip, cdOffset, inserted, eocd, entryLength)
        System.arraycopy(zip, eocd, inserted, eocd + entryLength, zip.size - eocd)
        val moved = eocd + entryLength
        inserted[moved + 8] = 2
        inserted[moved + 9] = 0
        inserted[moved + 10] = 2
        inserted[moved + 11] = 0
        val newSize = cdSize + entryLength
        inserted[moved + 12] = (newSize and 0xff).toByte()
        inserted[moved + 13] = ((newSize ushr 8) and 0xff).toByte()
        inserted[moved + 14] = ((newSize ushr 16) and 0xff).toByte()
        inserted[moved + 15] = ((newSize ushr 24) and 0xff).toByte()
        return inserted
    }

    private fun u16at(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32at(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun findCentral(bytes: ByteArray, name: String): Int {
        val encoded = name.toByteArray()
        var index = 0
        while (index + 46 + encoded.size <= bytes.size) {
            val signature = (bytes[index].toLong() and 0xff) or
                ((bytes[index + 1].toLong() and 0xff) shl 8) or
                ((bytes[index + 2].toLong() and 0xff) shl 16) or
                ((bytes[index + 3].toLong() and 0xff) shl 24)
            val length = (bytes[index + 28].toInt() and 0xff) or ((bytes[index + 29].toInt() and 0xff) shl 8)
            if (signature == 0x02014b50L && length == encoded.size) {
                val found = bytes.copyOfRange(index + 46, index + 46 + length)
                if (found.contentEquals(encoded)) return index
            }
            index += 1
        }
        error("missing $name")
    }
}
