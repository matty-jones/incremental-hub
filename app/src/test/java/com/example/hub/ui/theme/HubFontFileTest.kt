package com.example.hub.ui.theme

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class HubFontFileTest {

    @Test
    fun staticManropeFilesHaveDistinctOs2Weights() {
        val expected = listOf(
            "manrope_regular.ttf" to 400,
            "manrope_medium.ttf" to 500,
            "manrope_semibold.ttf" to 600,
            "manrope_bold.ttf" to 700,
        )
        val weights = expected.map { (name, weight) ->
            val actual = usWeightClass(resourceFile("font/$name").readBytes())
            assertEquals(name, weight, actual)
            actual
        }
        assertEquals(4, weights.distinct().size)
    }

    private fun resourceFile(relative: String): File {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("missing $relative from ${File(".").absolutePath}")
    }

    private fun usWeightClass(bytes: ByteArray): Int {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val tableCount = buffer.short.toInt() and 0xFFFF
        buffer.position(12)
        repeat(tableCount) {
            val tag = ByteArray(4)
            buffer.get(tag)
            buffer.int
            val offset = buffer.int
            buffer.int
            if (String(tag, Charsets.US_ASCII) == "OS/2") {
                val table = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN)
                table.short
                table.short
                return table.short.toInt() and 0xFFFF
            }
        }
        error("OS/2 table missing")
    }
}
