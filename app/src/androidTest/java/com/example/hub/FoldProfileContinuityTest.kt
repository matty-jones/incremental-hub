package com.example.hub

import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.example.hub.games.SharkIncrementalAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Disposable profile on the fold AVD. The Hub activity must get past
 * ProfileStore, and one Shark WebView must keep its document across postures.
 */
@RunWith(AndroidJUnit4::class)
class FoldProfileContinuityTest {

    @Test
    fun hubLaunchesAndProfileBoundSharkSurvivesPosture() {
        shell("cmd device_state state 2")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
        }
        val profileRef = AtomicReference<androidx.webkit.Profile>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = ProfileStore.getInstance()
            assertNotNull(store.getOrCreateProfile("hub-game-shark-incremental"))
            runCatching { store.deleteProfile(PROFILE) }
            profileRef.set(store.getOrCreateProfile(PROFILE))
        }
        val profile = profileRef.get()
        assertNotNull(profile)
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val finishes = AtomicInteger(0)
            val web = addWeb(scenario, profile.name, finishes)
            val ready = waitUntil(web, 60_000) {
                eval(web, SharkIncrementalAdapter.JS_UI_READY) == "true"
            }
            assertTrue("Shark did not become usable", ready)
            val loads = finishes.get()
            val token = eval(web, "window.__hubFoldToken='fold-token'; window.__hubFoldToken")
            assertEquals("fold-token", token)
            val identity = System.identityHashCode(web)
            shell("cmd device_state state 0")
            Thread.sleep(1_200)
            val folded = shell("wm size")
            assertTrue(folded, folded.contains("1080x2092"))
            assertEquals(identity, System.identityHashCode(web))
            assertEquals("fold-token", eval(web, "window.__hubFoldToken"))
            assertEquals(loads, finishes.get())
            shell("cmd device_state state 2")
            Thread.sleep(1_200)
            val unfolded = shell("wm size")
            assertTrue(unfolded, unfolded.contains("2208x1840"))
            assertEquals(identity, System.identityHashCode(web))
            assertEquals("fold-token", eval(web, "window.__hubFoldToken"))
            assertEquals(loads, finishes.get())
            scenario.onActivity {
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
        Thread.sleep(500)
        deleteDisposable(PROFILE)
    }

    private fun addWeb(
        scenario: ActivityScenario<WebViewProbeActivity>,
        profileName: String,
        finishes: AtomicInteger,
    ): WebView {
        val ref = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            WebViewCompat.setProfile(web, profileName)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    finishes.incrementAndGet()
                }
            }
            activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(
                web,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            web.loadUrl(SHARK)
            ref.set(web)
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun waitUntil(web: WebView, timeoutMs: Long, accept: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (accept()) return true
            Thread.sleep(400)
        }
        return false
    }

    private fun eval(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val ref = AtomicReference("")
        web.post {
            web.evaluateJavascript(script) { value ->
                ref.set(value?.trim()?.removeSurrounding("\"") ?: "")
                latch.countDown()
            }
        }
        assertTrue(latch.await(8, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun deleteDisposable(name: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(name) }
                .onFailure { error ->
                    android.util.Log.i(
                        "FOLD_PROFILE",
                        "profile $name stays until the next process; ${error.message}",
                    )
                }
        }
    }

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    companion object {
        private const val PROFILE = "hub-qual-fold-probe"
        private const val SHARK = "https://mrredshark77.github.io/shark-incremental/"
    }
}
