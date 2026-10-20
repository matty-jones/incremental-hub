package com.example.hub.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Loads a game page's browser icon on a background thread.
 * Successful bitmaps are kept for the process. A miss leaves the letter mark in place.
 */
object SiteIconLoader {
    private const val TAG = "SiteIcon"
    private const val HEAD_LIMIT = 128 * 1024
    private const val IMAGE_LIMIT = 256 * 1024
    private const val MAX_CANDIDATES = 4

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, Bitmap>()

    suspend fun load(pageUrl: String): Bitmap? {
        cache[pageUrl]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { fetch(pageUrl) } ?: return null
        cache.putIfAbsent(pageUrl, bitmap)
        return cache[pageUrl]
    }

    private fun fetch(pageUrl: String): Bitmap? {
        val page = pageUrl.toHttpUrlOrNull() ?: return null
        if (page.scheme != "https" || page.host.isEmpty()) return null
        val document = fetchBytes(pageUrl, page.host, HEAD_LIMIT, untilHead = true)
        val base = document?.first ?: pageUrl
        val html = document?.second?.toString(Charsets.UTF_8).orEmpty()
        val candidates = siteIconUrls(html, base).take(MAX_CANDIDATES)
        for (candidate in candidates) {
            val bytes = fetchBytes(candidate, page.host, IMAGE_LIMIT, untilHead = false)?.second ?: continue
            decodeSiteIcon(bytes)?.let { return it }
        }
        Log.d(TAG, "No browser icon for $pageUrl")
        return null
    }

    private fun fetchBytes(url: String, host: String, limit: Int, untilHead: Boolean): Pair<String, ByteArray>? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "IncrementalHub")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val finalUrl = response.request.url
                if (finalUrl.scheme != "https" || !finalUrl.host.equals(host, ignoreCase = true)) return@use null
                val stream = response.body?.byteStream() ?: return@use null
                val bytes = if (untilHead) {
                    readHead(stream, limit)
                } else {
                    readCapped(stream, limit) ?: return@use null
                }
                if (bytes.isEmpty()) null else finalUrl.toString() to bytes
            }
        }.onFailure { error ->
            Log.d(TAG, "Icon fetch failed for $url: ${error.message}")
        }.getOrNull()
    }
}

internal fun decodeSiteIcon(bytes: ByteArray): Bitmap? {
    val payload = imagePayload(bytes) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    return BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
}

private fun sampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    while (width / sample > target * 2 && height / sample > target * 2) {
        sample *= 2
    }
    return sample
}

private fun readHead(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < limit) {
        val count = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (count < 0) break
        out.write(buf, 0, count)
        if (containsHeadEnd(out.toByteArray())) break
    }
    return out.toByteArray()
}

private fun containsHeadEnd(bytes: ByteArray): Boolean {
    if (bytes.size < 7) return false
    for (start in 0..bytes.size - 7) {
        if ((bytes[start].toInt() and 0xFF) != '<'.code) continue
        if ((bytes[start + 1].toInt() and 0xFF) != '/'.code) continue
        if (!sameLetter(bytes[start + 2], 'h')) continue
        if (!sameLetter(bytes[start + 3], 'e')) continue
        if (!sameLetter(bytes[start + 4], 'a')) continue
        if (!sameLetter(bytes[start + 5], 'd')) continue
        if ((bytes[start + 6].toInt() and 0xFF) == '>'.code) return true
    }
    return false
}

private fun sameLetter(byte: Byte, lower: Char): Boolean {
    val found = byte.toInt() and 0xFF
    return found == lower.code || found == lower.uppercaseChar().code
}

private fun readCapped(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() <= limit) {
        val count = input.read(buf)
        if (count < 0) return out.toByteArray()
        if (out.size() + count > limit) return null
        out.write(buf, 0, count)
    }
    return null
}

private const val TARGET_PX = 192
