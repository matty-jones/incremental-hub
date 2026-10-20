package com.example.hub.ui

import java.net.URI

/**
 * Browser-icon URLs for a game page.
 *
 * Declared `<link rel="icon">` hrefs come first (shortcut icon included), then
 * other non-mask icon links. Relative hrefs resolve against [pageUrl] and stay
 * on that https host. When the page declares nothing usable, the candidates are
 * `favicon.ico` beside the page and, if different, at the origin root.
 */
fun siteIconUrls(html: String, pageUrl: String): List<String> {
    val page = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
    if (!page.isHttps() || page.host.isNullOrBlank()) return emptyList()

    val declared = declaredIcons(html)
        .sortedWith(compareBy<DeclaredIcon>({ it.rank }, { -it.size }, { it.order }))
        .mapNotNull { resolveHttps(page, it.href) }
        .distinct()
    if (declared.isNotEmpty()) return declared

    return listOfNotNull(
        resolveHttps(page, "favicon.ico"),
        resolveHttps(page, "/favicon.ico"),
    ).distinct()
}

/**
 * Bytes [android.graphics.BitmapFactory] can decode.
 * ICO containers contribute their largest embedded PNG. Anything else is rejected.
 */
fun imagePayload(bytes: ByteArray): ByteArray? {
    if (isPng(bytes) || isJpeg(bytes) || isGif(bytes) || isWebp(bytes)) return bytes
    if (isIco(bytes)) return largestPngInIco(bytes)
    return null
}

private data class DeclaredIcon(
    val href: String,
    val rank: Int,
    val size: Int,
    val order: Int,
)

private fun declaredIcons(html: String): List<DeclaredIcon> {
    val found = mutableListOf<DeclaredIcon>()
    for (match in LINK_TAG.findAll(html)) {
        val attrs = attributes(match.groupValues[1])
        val href = attrs["href"]?.trim().orEmpty()
        if (href.isEmpty()) continue
        val type = attrs["type"].orEmpty()
        if (type.contains("svg", ignoreCase = true) || href.contains(".svg", ignoreCase = true)) continue
        val rank = iconRank(attrs["rel"].orEmpty()) ?: continue
        found += DeclaredIcon(
            href = href,
            rank = rank,
            size = iconSize(attrs["sizes"]),
            order = found.size,
        )
    }
    return found
}

private fun iconRank(rel: String): Int? {
    val tokens = rel.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }.toSet()
    if ("icon" in tokens) return 0
    if (tokens.any { it.endsWith("-icon") && "mask" !in it }) return 1
    return null
}

private fun iconSize(sizes: String?): Int {
    if (sizes.isNullOrBlank()) return UNSPECIFIED_SIZE
    return sizes.split(WHITESPACE)
        .mapNotNull { token ->
            val width = token.lowercase().substringBefore('x', "").toIntOrNull() ?: return@mapNotNull null
            if (width <= 0) null else width
        }
        .maxOrNull() ?: UNSPECIFIED_SIZE
}

private fun attributes(source: String): Map<String, String> {
    val attrs = mutableMapOf<String, String>()
    for (match in ATTRIBUTE.findAll(source)) {
        val value = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
        attrs[match.groupValues[1].lowercase()] = value
    }
    return attrs
}

private fun resolveHttps(page: URI, href: String): String? {
    val resolved = runCatching { page.resolve(href.trim()) }.getOrNull() ?: return null
    if (!resolved.isHttps() || resolved.host.isNullOrBlank()) return null
    if (!page.host.equals(resolved.host, ignoreCase = true) || page.port != resolved.port) return null
    val path = resolved.path?.takeIf { it.isNotEmpty() } ?: "/"
    return runCatching {
        URI(resolved.scheme, null, resolved.host, resolved.port, path, resolved.query, null).toString()
    }.getOrNull()
}

private fun URI.isHttps(): Boolean = scheme.equals("https", ignoreCase = true)

private fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

private fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

private fun isGif(bytes: ByteArray): Boolean =
    bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()

private fun isWebp(bytes: ByteArray): Boolean =
    bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

private fun isIco(bytes: ByteArray): Boolean =
    bytes.size >= 6 && u16(bytes, 0) == 0 && u16(bytes, 2) == 1

private fun largestPngInIco(bytes: ByteArray): ByteArray? {
    val count = u16(bytes, 4)
    if (count <= 0 || count > MAX_ICO_ENTRIES) return null
    if (6 + count * ICO_ENTRY > bytes.size) return null
    var best: ByteArray? = null
    var bestArea = -1
    for (index in 0 until count) {
        val entry = 6 + index * ICO_ENTRY
        if (entry + ICO_ENTRY > bytes.size) return best
        val width = bytes[entry].toInt() and 0xFF
        val height = bytes[entry + 1].toInt() and 0xFF
        val size = u32(bytes, entry + 8)
        val offset = u32(bytes, entry + 12)
        if (size < PNG_SIGNATURE.size.toLong() || offset < 0L || offset > bytes.size || size > bytes.size - offset) {
            continue
        }
        val payload = bytes.copyOfRange(offset.toInt(), (offset + size).toInt())
        if (!isPng(payload)) continue
        val area = (if (width == 0) 256 else width) * (if (height == 0) 256 else height)
        if (area >= bestArea) {
            best = payload
            bestArea = area
        }
    }
    return best
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

private val LINK_TAG = Regex("""<link\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val ATTRIBUTE = Regex(
    """([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
    RegexOption.IGNORE_CASE,
)
private val WHITESPACE = Regex("""\s+""")
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)

private const val UNSPECIFIED_SIZE = 32
private const val MAX_ICO_ENTRIES = 16
private const val ICO_ENTRY = 16
