package com.example.hub.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class LayoutReservationStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences(LayoutReservationStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun smallerValueDoesNotReplaceALargerOne() {
        val store = LayoutReservationStore(context)
        val key = "#osc-a\nDIV|16px|20px"
        store.merge("shark", payload("408x100x1.00", key, 50))
        store.merge("shark", payload("408x100x1.00", key, 40))
        assertEquals(50, value("shark", "408x100x1.00", key))

        store.merge("shark", payload("840x100x1.00", key, 10))
        assertEquals(50, value("shark", "408x100x1.00", key))
        assertEquals(10, value("shark", "840x100x1.00", key))

        store.merge("shark", payload("408x100x1.00", key, 60))
        assertEquals(60, value("shark", "408x100x1.00", key))
        assertEquals(10, value("shark", "840x100x1.00", key))
    }

    @Test
    fun oldestConfigIsDroppedAndTheActiveOneStays() {
        val store = LayoutReservationStore(context)
        repeat(8) { index ->
            store.merge("shark", payload("${1000 + index * 100}x100x1.00", "#a\nSIG", 10 + index))
        }
        store.merge("shark", payload("1800x100x1.00", "#a\nSIG", 90))
        val configs = JSONObject(store.read("shark"))
        assertFalse(configs.has("1000x100x1.00"))
        assertTrue(configs.has("1100x100x1.00"))
        assertTrue(configs.has("1800x100x1.00"))
        assertEquals(8, configs.length())

        store.merge("shark", payload("1000x100x1.00", "#a\nSIG", 12))
        val restored = JSONObject(store.read("shark"))
        assertTrue(restored.has("1000x100x1.00"))
        assertFalse(restored.has("1100x100x1.00"))
        assertTrue(restored.has("1800x100x1.00"))
        assertEquals(12, restored.getJSONObject("1000x100x1.00").getInt("#a\nSIG"))
    }

    @Test
    fun identityCapKeepsExistingValuesGrowing() {
        val store = LayoutReservationStore(context)
        val entries = JSONObject()
        repeat(LayoutReservationStore.MAX_IDENTITIES) { index ->
            entries.put("#id$index\nSIG", 10)
        }
        store.merge(
            "shark",
            JSONObject().put("config", "408x100x1.00").put("entries", entries).toString(),
        )
        val full = JSONObject(store.read("shark")).getJSONObject("408x100x1.00")
        assertEquals(LayoutReservationStore.MAX_IDENTITIES, full.length())

        store.merge("shark", payload("408x100x1.00", "#new\nSIG", 10))
        val capped = JSONObject(store.read("shark")).getJSONObject("408x100x1.00")
        assertFalse(capped.has("#new\nSIG"))
        assertEquals(LayoutReservationStore.MAX_IDENTITIES, capped.length())

        store.merge("shark", payload("408x100x1.00", "#id0\nSIG", 30))
        assertEquals(30, value("shark", "408x100x1.00", "#id0\nSIG"))
    }

    @Test
    fun rejectedWritesLeaveStorageUntouched() {
        val store = LayoutReservationStore(context)
        assertFalse(store.merge("", payload("408x100x1.00", "#a\nSIG", 10)))
        assertEquals("{}", store.read(""))
        assertFalse(store.merge("shark", payload("wide", "#a\nSIG", 10)))
        store.merge("shark", payload("408x100x1.00", "#a\nSIG", 321))
        store.merge("shark", payload("408x100x1.00", "no-signature", 20))
        assertEquals("{}", store.read("shark"))

        store.merge("shark", payload("408x100x1.00", "#a\nSIG", 320))
        assertEquals(320, value("shark", "408x100x1.00", "#a\nSIG"))

        val bridge = LayoutReservationBridge(store)
        bridge.gameId = "shark"
        bridge.remember(payload("408x100x1.00", "#a\nSIG", 10))
        bridge.remember(payload("408x100x1.00", "#a\nSIG", 40))
        assertEquals(320, value("shark", "408x100x1.00", "#a\nSIG"))
        bridge.remember(payload("408x100x1.00", "#b\nSIG", 15))
        assertEquals(15, value("shark", "408x100x1.00", "#b\nSIG"))
    }

    private fun payload(config: String, key: String, px: Int): String =
        JSONObject().put("config", config).put("entries", JSONObject().put(key, px)).toString()

    private fun value(gameId: String, config: String, key: String): Int =
        JSONObject(LayoutReservationStore(context).read(gameId)).getJSONObject(config).getInt(key)
}
