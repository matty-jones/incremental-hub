package com.example.hub.sync

import com.example.hub.BuildConfig
import com.example.hub.games.PayloadDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

interface WorkstationApi {
    fun fetchSaves(): List<RemoteSave>?
    fun putSave(save: RemoteSave): PutResult?
    fun lookupUpdate(): UpdateCheck
    fun checkUpdate(): UpdateInfo? = when (val result = lookupUpdate()) {
        is UpdateCheck.Available -> result.info
        else -> null
    }
    fun download(url: String, destination: File): Boolean
    fun appendSnapshot(upload: SnapshotUpload): SnapshotUploadResult? = null
    fun proposePointer(proposal: PointerProposal): PointerProposalResult? = null
    fun fetchPointer(gameId: String): PointerFetch = PointerFetch.Unreachable
    fun fetchHistory(gameId: String): List<RemoteSnapshot>? = null
}

class WorkstationClient(
    private val baseUrl: String = BuildConfig.BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build(),
) : WorkstationApi {

    override fun fetchSaves(): List<RemoteSave>? {
        val response = get(endpoint("saves")) ?: return null
        response.use { result ->
            if (!result.isSuccessful) return null
            val body = result.body?.string() ?: return null
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toRemoteSave()?.let { add(it) }
                }
            }
        }
    }

    override fun appendSnapshot(upload: SnapshotUpload): SnapshotUploadResult? {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("saves")
            .addPathSegment(upload.gameId)
            .addPathSegment("snapshots")
            .build()
        val response = post(url, upload.toJson()) ?: return null
        response.use { result ->
            val json = result.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val snapshot = json.optJSONObject("snapshot")?.toRemoteSnapshot()
            if (result.code == 200 && snapshot != null && json.isNull("conflict")) {
                return SnapshotUploadResult.Stored(
                    created = json.optBoolean("created"),
                    snapshotId = snapshot.snapshotId,
                    sha256 = snapshot.sha256,
                )
            }
            if (result.code == 409 && snapshot != null && snapshot.sha256 == PayloadDigest.sha256(upload.snapshotPayload)) {
                return SnapshotUploadResult.Stored(
                    created = false,
                    snapshotId = snapshot.snapshotId,
                    sha256 = snapshot.sha256,
                )
            }
            if (result.code == 409) return SnapshotUploadResult.Rejected
            return null
        }
    }

    override fun proposePointer(proposal: PointerProposal): PointerProposalResult? {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("saves")
            .addPathSegment(proposal.gameId)
            .addPathSegment("pointer")
            .build()
        val body = JSONObject()
            .put("expected_version", proposal.expectedVersion)
            .put("snapshot_id", proposal.snapshotId)
            .put("lineage_id", proposal.lineageId)
            .toString()
        val response = post(url, body) ?: return null
        response.use { result ->
            val json = result.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val pointer = json.optJSONObject("pointer")?.toRemotePointer()
            return when (result.code) {
                200 -> pointer?.let { PointerProposalResult.Stored(it) }
                409 -> PointerProposalResult.Conflict(json.optString("conflict"), pointer)
                else -> null
            }
        }
    }

    override fun fetchPointer(gameId: String): PointerFetch {
        val response = get(endpoint("saves/$gameId/pointer")) ?: return PointerFetch.Unreachable
        response.use { result ->
            if (result.code == 404) return PointerFetch.Absent
            if (!result.isSuccessful) return PointerFetch.Unreachable
            val json = result.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return PointerFetch.Unreachable
            return json.toRemotePointer()?.let { PointerFetch.Found(it) } ?: PointerFetch.Unreachable
        }
    }

    override fun fetchHistory(gameId: String): List<RemoteSnapshot>? {
        val response = get(endpoint("saves/$gameId/snapshots")) ?: return null
        response.use { result ->
            if (!result.isSuccessful) return null
            val body = result.body?.string() ?: return null
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toRemoteSnapshot()?.let { add(it) }
                }
            }
        }
    }

    override fun putSave(save: RemoteSave): PutResult? {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("saves")
            .addPathSegment(save.gameId)
            .build()
        val request = Request.Builder()
            .url(url)
            .put(save.toJson().toRequestBody(JSON))
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return null
        response.use { result ->
            val payload = result.body?.string()?.let { runCatching { JSONObject(it).toRemoteSave() }.getOrNull() }
            return when (result.code) {
                200 -> PutResult.Stored
                409 -> payload?.let { PutResult.Stale(it) }
                else -> null
            }
        }
    }

    override fun lookupUpdate(): UpdateCheck {
        val response = get(endpoint("app/update")) ?: return UpdateCheck.Unreachable
        response.use { result ->
            if (!result.isSuccessful) return UpdateCheck.Unreachable
            val json = result.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return UpdateCheck.Unreachable
            return classifyUpdateResponse(
                installedVersionCode = BuildConfig.VERSION_CODE,
                versionCode = json.optInt("version_code", -1),
                versionName = json.optString("version_name"),
                downloadUrl = json.optString("download_url"),
                commitMessage = json.optionalString("commit_message"),
            )
        }
    }

    override fun download(url: String, destination: File): Boolean {
        val request = Request.Builder().url(url).build()
        val downloader = client.newBuilder()
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(2, TimeUnit.MINUTES)
            .build()
        val response = runCatching { downloader.newCall(request).execute() }.getOrNull() ?: return false
        response.use { result ->
            if (!result.isSuccessful) return false
            val bytes = result.body ?: return false
            destination.parentFile?.mkdirs()
            bytes.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return destination.isFile && destination.length() > 0L
        }
    }

    private fun get(url: String): okhttp3.Response? {
        val request = Request.Builder().url(url).build()
        return runCatching { client.newCall(request).execute() }.getOrNull()
    }

    private fun post(url: okhttp3.HttpUrl, json: String): okhttp3.Response? {
        val request = Request.Builder().url(url).post(json.toRequestBody(JSON)).build()
        return runCatching { client.newCall(request).execute() }.getOrNull()
    }

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private fun RemoteSave.toJson(): String = JSONObject()
    .put("snapshot_payload", snapshotPayload)
    .put("captured_at_millis", capturedAtMillis)
    .put("profile_name", profileName)
    .put("session_identity", sessionIdentity)
    .put("last_played_at_millis", lastPlayedAtMillis)
    .put("resume_token", resumeToken ?: JSONObject.NULL)
    .put("recovery_state", recoveryState ?: JSONObject.NULL)
    .toString()

private fun JSONObject.toRemoteSave(): RemoteSave? {
    val gameId = optionalString("game_id") ?: return null
    val payload = optionalString("snapshot_payload") ?: return null
    if (!has("captured_at_millis")) return null
    return RemoteSave(
        gameId = gameId,
        snapshotPayload = payload,
        capturedAtMillis = optLong("captured_at_millis"),
        profileName = optionalString("profile_name").orEmpty(),
        sessionIdentity = optionalString("session_identity").orEmpty(),
        lastPlayedAtMillis = optLong("last_played_at_millis"),
        resumeToken = optionalString("resume_token"),
        recoveryState = optionalString("recovery_state"),
    )
}

private fun SnapshotUpload.toJson(): String = JSONObject()
    .put("snapshot_id", snapshotId)
    .put("lineage_id", lineageId)
    .put("snapshot_payload", snapshotPayload)
    .put("captured_at_millis", capturedAtMillis)
    .put("idempotency_key", idempotencyKey)
    .put("source", "phone")
    .put("integrity", integrity)
    .put("progression_json", JSONObject.NULL)
    .put("profile_name", profileName)
    .put("session_identity", sessionIdentity)
    .put("last_played_at_millis", lastPlayedAtMillis)
    .put("resume_token", resumeToken ?: JSONObject.NULL)
    .put("recovery_state", JSONObject.NULL)
    .toString()

private fun JSONObject.toRemoteSnapshot(): RemoteSnapshot? {
    val snapshotId = optionalString("snapshot_id") ?: return null
    val gameId = optionalString("game_id") ?: return null
    val payload = optionalString("snapshot_payload") ?: return null
    val sha = optionalString("sha256") ?: return null
    return RemoteSnapshot(
        snapshotId = snapshotId,
        gameId = gameId,
        lineageId = optionalString("lineage_id") ?: "legacy",
        sha256 = sha,
        snapshotPayload = payload,
        capturedAtMillis = optLong("captured_at_millis"),
        integrity = optionalString("integrity") ?: "archived",
        source = optionalString("source") ?: "phone",
    )
}

private fun JSONObject.toRemotePointer(): RemotePointer? {
    val snapshot = toRemoteSnapshot() ?: return null
    if (!has("version")) return null
    return RemotePointer(
        gameId = snapshot.gameId,
        lineageId = snapshot.lineageId,
        snapshotId = snapshot.snapshotId,
        version = optInt("version"),
        snapshotPayload = snapshot.snapshotPayload,
        capturedAtMillis = snapshot.capturedAtMillis,
        sha256 = snapshot.sha256,
        profileName = snapshot.let { optionalString("profile_name").orEmpty() },
        sessionIdentity = optionalString("session_identity").orEmpty(),
        lastPlayedAtMillis = optLong("last_played_at_millis"),
        resumeToken = optionalString("resume_token"),
        recoveryState = optionalString("recovery_state"),
    )
}

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotEmpty() }
}
