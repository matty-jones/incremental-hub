package com.example.hub.games

/**
 * Pieroxy LZString 1.4.5, the codec Trimps uses for `compressToBase64`.
 * Bit operations stay in 32-bit ints, matching JavaScript's bitwise casts.
 */
internal object LzString {
    private const val KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    private val reverse: IntArray = IntArray(128) { -1 }.also { table ->
        KEY.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun compressToBase64(input: String?): String {
        if (input == null) return ""
        val compressed = compress(input, 6) { code -> KEY[code].toString() }
        return when (compressed.length % 4) {
            0 -> compressed
            1 -> "$compressed==="
            2 -> "$compressed=="
            else -> "$compressed="
        }
    }

    fun decompressFromBase64(input: String?): String? {
        if (input == null) return ""
        if (input.isEmpty()) return null
        return decompress(input.length, 32) { index ->
            val char = input[index]
            if (char.code >= reverse.size) 0 else reverse[char.code].coerceAtLeast(0)
        }
    }

    private fun compress(uncompressed: String, bitsPerChar: Int, getCharFromInt: (Int) -> String): String {
        val dictionary = HashMap<String, Int>()
        val dictionaryToCreate = HashSet<String>()
        var dictSize = 3
        var numBits = 2
        var enlargeIn = 2
        val data = StringBuilder()
        var dataVal = 0
        var dataPosition = 0
        var w = ""

        fun writeBit(bit: Int) {
            dataVal = (dataVal shl 1) or bit
            if (dataPosition == bitsPerChar - 1) {
                dataPosition = 0
                data.append(getCharFromInt(dataVal))
                dataVal = 0
            } else {
                dataPosition++
            }
        }

        fun writeBits(valueIn: Int, count: Int) {
            var value = valueIn
            repeat(count) {
                writeBit(value and 1)
                value = value shr 1
            }
        }

        for (ch in uncompressed) {
            val c = ch.toString()
            if (!dictionary.containsKey(c)) {
                dictionary[c] = dictSize++
                dictionaryToCreate.add(c)
            }
            val wc = w + c
            if (dictionary.containsKey(wc)) {
                w = wc
                continue
            }
            if (dictionaryToCreate.contains(w)) {
                val code = w[0].code
                if (code < 256) {
                    repeat(numBits) { writeBit(0) }
                    writeBits(code, 8)
                } else {
                    repeat(numBits) { writeBit(1) }
                    writeBits(code, 16)
                }
                enlargeIn--
                if (enlargeIn == 0) {
                    enlargeIn = 1 shl numBits
                    numBits++
                }
                dictionaryToCreate.remove(w)
            } else {
                writeBits(dictionary.getValue(w), numBits)
            }
            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            dictionary[wc] = dictSize++
            w = c
        }

        if (w.isNotEmpty()) {
            if (dictionaryToCreate.contains(w)) {
                val code = w[0].code
                if (code < 256) {
                    repeat(numBits) { writeBit(0) }
                    writeBits(code, 8)
                } else {
                    repeat(numBits) { writeBit(1) }
                    writeBits(code, 16)
                }
                enlargeIn--
                if (enlargeIn == 0) {
                    enlargeIn = 1 shl numBits
                    numBits++
                }
                dictionaryToCreate.remove(w)
            } else {
                writeBits(dictionary.getValue(w), numBits)
            }
            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }

        writeBits(2, numBits)
        while (true) {
            dataVal = dataVal shl 1
            if (dataPosition == bitsPerChar - 1) {
                data.append(getCharFromInt(dataVal))
                break
            }
            dataPosition++
        }
        return data.toString()
    }

    private fun decompress(length: Int, resetValue: Int, getNextValue: (Int) -> Int): String? {
        val dictionary = HashMap<Int, Any>()
        var enlargeIn = 4
        var dictSize = 4
        var numBits = 3
        val result = StringBuilder()
        val data = Bits(getNextValue(0), resetValue, 1)

        fun readBits(countPower: Int): Int {
            var bits = 0
            var maxPower = 1 shl countPower
            var power = 1
            while (power != maxPower) {
                val resb = data.value and data.position
                data.position = data.position shr 1
                if (data.position == 0) {
                    data.position = resetValue
                    data.value = getNextValue(data.index++)
                }
                bits = bits or ((if (resb > 0) 1 else 0) * power)
                power = power shl 1
            }
            return bits
        }

        for (i in 0 until 3) dictionary[i] = i

        var bits = readBits(2)
        val first: String = when (bits) {
            0 -> readBits(8).toChar().toString()
            1 -> readBits(16).toChar().toString()
            else -> return ""
        }
        dictionary[3] = first
        var w = first
        result.append(first)

        while (true) {
            if (data.index > length) return ""
            bits = readBits(numBits)
            val c: Int = when (bits) {
                0 -> {
                    dictionary[dictSize++] = readBits(8).toChar().toString()
                    enlargeIn--
                    dictSize - 1
                }
                1 -> {
                    dictionary[dictSize++] = readBits(16).toChar().toString()
                    enlargeIn--
                    dictSize - 1
                }
                2 -> return result.toString()
                else -> bits
            }
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            val entry: String = when (val known = dictionary[c]) {
                is String -> known
                is Int -> if (known == 0) return null else known.toString()
                null -> {
                    if (c == dictSize) {
                        w + w[0]
                    } else {
                        return null
                    }
                }
                else -> return null
            }
            result.append(entry)
            dictionary[dictSize++] = w + entry[0]
            enlargeIn--
            w = entry
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }
    }

    private class Bits(var value: Int, var position: Int, var index: Int)
}
