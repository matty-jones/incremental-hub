package com.example.hub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import com.example.hub.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visual intro for a cold process start. Registry, database, sync, and navigation
 * keep running underneath; the overlay does not gate them.
 *
 * The artwork stays fully visible for [HOLD_MS] after the first frame that draws
 * it, then fades for [FADE_MS]. A tap removes it immediately. That time is only
 * the intro the user sees. Tune [HOLD_MS] if the pause feels too long on device.
 *
 * Armed once per process, and only for a fresh activity (no saved instance), so
 * a restore into a game, a return from a game, rotation, or resume does not replay it.
 * The bitmap is decoded off the main thread. [painterResource] would decode the
 * full splash on the UI thread, so the overlay draws the prefetched bitmap with
 * [ContentScale.Fit] instead.
 */
object ColdStartSplash {
    const val HOLD_MS = 600L
    const val FADE_MS = 180

    private const val TAG = "ColdStartSplash"
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val armed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val preloadStarted = AtomicBoolean(false)
    private val preloadAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    var artwork by mutableStateOf<ImageBitmap?>(null)
        private set

    var decodeFinished by mutableStateOf(false)
        private set

    @Volatile
    var decodeMs: Long = -1L
        private set

    /** @return true only the first time this process asks to show the intro. */
    fun arm(): Boolean = armed.compareAndSet(false, true)

    val isFinished: Boolean get() = finished.get()

    fun finish() {
        finished.set(true)
    }

    fun preload(context: Context) {
        if (!preloadStarted.compareAndSet(false, true)) return
        preloadAtMs.set(SystemClock.elapsedRealtime())
        val app = context.applicationContext
        decodeScope.launch {
            val started = SystemClock.elapsedRealtime()
            val bitmap = runCatching { decode(app) }
                .onFailure { error -> Log.e(TAG, "splash decode failed", error) }
                .getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - started
            val image = bitmap?.asImageBitmap()
            Log.i(
                TAG,
                "decoded on ${Thread.currentThread().name} in ${elapsed}ms " +
                    "${bitmap?.width ?: 0}x${bitmap?.height ?: 0}",
            )
            withContext(Dispatchers.Main.immediate) {
                decodeMs = elapsed
                artwork = image
                decodeFinished = true
            }
        }
    }

    fun log(message: String) {
        val origin = preloadAtMs.get()
        val delta = if (origin == 0L) -1L else SystemClock.elapsedRealtime() - origin
        Log.i(TAG, "$message +${delta}ms")
    }

    internal fun resetForTest() {
        armed.set(false)
        finished.set(false)
        artwork = null
        decodeFinished = false
        decodeMs = -1L
    }

    private fun decode(context: Context): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.ihub_splash_art, options)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ColdStartSplashHost(
    armed: Boolean,
    content: @Composable () -> Unit,
) {
    var dismissed by remember { mutableStateOf(!armed || ColdStartSplash.isFinished) }
    val covering = armed && !dismissed
    LaunchedEffect(Unit) {
        ColdStartSplash.log(
            if (covering) "launcher composed under intro" else "launcher composed without intro",
        )
    }
    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { if (covering) invisibleToUser() },
            propagateMinConstraints = true,
        ) {
            content()
        }
        if (covering) {
            SplashOverlay(
                onDismiss = {
                    ColdStartSplash.finish()
                    dismissed = true
                },
            )
        }
    }
}

@Composable
private fun SplashOverlay(onDismiss: () -> Unit) {
    val artwork = ColdStartSplash.artwork
    val decodeFinished = ColdStartSplash.decodeFinished
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(decodeFinished, artwork) {
        if (!decodeFinished) return@LaunchedEffect
        if (artwork == null) {
            ColdStartSplash.log("art missing; dismissing")
            onDismiss()
            return@LaunchedEffect
        }
        withFrameNanos { }
        val frameAt = SystemClock.elapsedRealtime()
        ColdStartSplash.log("artwork frame; holding ${ColdStartSplash.HOLD_MS}ms")
        delay(ColdStartSplash.HOLD_MS)
        alpha.animateTo(0f, tween(durationMillis = ColdStartSplash.FADE_MS))
        ColdStartSplash.log(
            "dismissed after fade; held ${SystemClock.elapsedRealtime() - frameAt}ms",
        )
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(colorResource(R.color.ihub_brand_black))
            .semantics { contentDescription = "Incremental Hub intro" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Skip intro",
            ) {
                ColdStartSplash.log("dismissed on tap")
                onDismiss()
            }
            .testTag("cold-start-splash"),
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
