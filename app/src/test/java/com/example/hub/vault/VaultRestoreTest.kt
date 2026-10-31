package com.example.hub.vault

import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SaveComparison
import com.example.hub.games.SharkIncrementalAdapter
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VaultRestoreTest {

    private val adapter = SharkIncrementalAdapter()

    @Test
    fun equalMilestonesDoNotMoveThePointerOrOrderCurrencies() = runTest {
        val pointer = shark(feature = 9, fish = "1", played = "100", prestige = 2639)
        val other = shark(feature = 9, fish = "3689787472063838", played = "100", prestige = 2639)
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(other, pointer))
        val staged = assess(other, pointer, confirmed = false, branch = false)
        assertEquals(ProgressionClass.EQUAL, staged.classification)
        assertTrue(staged.ready)
        assertFalse(staged.allowed)
        assertFalse(staged.requiresBranch)
        assertTrue(staged.unorderedNote!!.contains("not ordered"))
        val confirmed = assess(other, pointer, confirmed = true, branch = false)
        assertTrue(confirmed.allowed)
        assertFalse(confirmed.proposeWorkstationPointer)
        assertFalse(confirmed.moveHighWater)
        assertFalse(confirmed.localBranch)
    }

    @Test
    fun higherPlayTimeDoesNotAuthorizeARegression() = runTest {
        val pointer = shark(feature = 9, fish = "1", played = "100", prestige = 2639)
        val older = shark(feature = 9, fish = "99", played = "999999999", prestige = 10)
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(older, pointer))
        val refused = assess(older, pointer, confirmed = true, branch = false)
        assertEquals(ProgressionClass.REGRESSED, refused.classification)
        assertTrue(refused.requiresBranch)
        assertFalse(refused.allowed)
        assertFalse(refused.proposeWorkstationPointer)
        val unconfirmed = assess(older, pointer, confirmed = false, branch = true)
        assertFalse(unconfirmed.allowed)
        val branched = assess(older, pointer, confirmed = true, branch = true)
        assertTrue(branched.allowed)
        assertTrue(branched.localBranch)
        assertFalse(branched.proposeWorkstationPointer)
        assertFalse(branched.moveHighWater)
    }

    @Test
    fun hashAndLineageFailuresWriteNothing() = runTest {
        val payload = shark(feature = 4, fish = "1", played = "10", prestige = 1)
        val mismatch = assessVaultRestore(
            requestedId = "snap",
            requestedLineageId = "legacy",
            recordId = "snap",
            recordLineageId = "legacy",
            recordSha256 = "not-the-digest",
            payload = payload,
            capturedAtMillis = 10L,
            pointerPayload = shark(feature = 9, fish = "1", played = "10", prestige = 2),
            adapter = adapter,
            confirmed = true,
            branchConfirmed = true,
        )
        assertFalse(mismatch.ready)
        assertFalse(mismatch.allowed)
        assertTrue(mismatch.message.contains("hash"))
        val lineage = assessVaultRestore(
            requestedId = "snap",
            requestedLineageId = "other",
            recordId = "snap",
            recordLineageId = "legacy",
            recordSha256 = PayloadDigest.sha256(payload),
            payload = payload,
            capturedAtMillis = 10L,
            pointerPayload = null,
            adapter = adapter,
            confirmed = true,
            branchConfirmed = true,
        )
        assertFalse(lineage.ready)
        assertTrue(lineage.message.contains("lineage"))
    }

    private suspend fun assess(
        payload: String,
        pointer: String,
        confirmed: Boolean,
        branch: Boolean,
    ) = assessVaultRestore(
        requestedId = "snap",
        requestedLineageId = "legacy",
        recordId = "snap",
        recordLineageId = "legacy",
        recordSha256 = PayloadDigest.sha256(payload),
        payload = payload,
        capturedAtMillis = 10L,
        pointerPayload = pointer,
        adapter = adapter,
        confirmed = confirmed,
        branchConfirmed = branch,
    )

    private fun shark(feature: Int, fish: String, played: String, prestige: Long): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", played)
            .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", JSONArray().put(1)))
            .put("total_fish", JSONObject().put("s", 1).put("e", fish).put("c", JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", 2026))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
}
