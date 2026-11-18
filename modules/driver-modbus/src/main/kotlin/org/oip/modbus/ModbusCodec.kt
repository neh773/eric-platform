package org.oip.modbus

import java.math.BigInteger

/**
 * Word and byte order for multi-register values.
 *
 * Modbus registers are 16-bit and the bytes of a register are big-endian on the wire
 * (Modbus Application Protocol). Devices still disagree about the order of those words and,
 * occasionally, the bytes inside a word:
 * - [ByteOrder.BIG_ENDIAN] / [WordOrder.HIGH_WORD_FIRST] is ABCD (the default).
 * - [WordOrder.LOW_WORD_FIRST] is the reference platform's "Reverse Word Order" (CDAB for a 32-bit value).
 *   -
 * - [ByteOrder.LITTLE_ENDIAN] swaps the two bytes of each register (BADC when words are not swapped).
 *
 * String byte order is a separate device setting ([ModbusDeviceConfig.reverseStringByteOrder]),
 * matching the reference platform's "Reverse String Byte Order".
 */
enum class ByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }

enum class WordOrder { HIGH_WORD_FIRST, LOW_WORD_FIRST }

internal object ModbusCodec {
    fun decode(item: ParsedAddress, blockStart: Int, data: ByteArray, config: ModbusDeviceConfig): Any? {
        val rel = item.protocolOffset - blockStart
        require(rel >= 0)
        return when (item.area) {
            ModbusArea.COIL, ModbusArea.DISCRETE_INPUT -> bitAt(data, rel)
            else -> decodeRegister(item, data, rel * 2, config)
        }
    }

    fun encode(item: ParsedAddress, value: Any?, config: ModbusDeviceConfig): ByteArray {
        if (item.type == ModbusType.BIT || item.type == ModbusType.BOOLEAN) {
            if (value !is Boolean) throw TypeMismatchException("expected boolean, got ${value?.javaClass?.simpleName}")
            return byteArrayOf(if (value) 1 else 0)
        }
        val words = when (item.type) {
            ModbusType.INT16 -> intArrayOf(number(value).toInt() and 0xFFFF)
            ModbusType.UINT16 -> intArrayOf(number(value).toLong().requireUInt(16).toInt())
            ModbusType.INT32 -> split32(number(value).toLong() and 0xFFFFFFFFL)
            ModbusType.UINT32 -> split32(number(value).toLong().requireUInt(32))
            ModbusType.INT64 -> split64(number(value).toLong())
            ModbusType.UINT64 -> split64(uint64(value).toLong()) // bit pattern; range checked in uint64
            ModbusType.FLOAT32 -> split32(number(value).toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
            ModbusType.FLOAT64 -> split64(number(value).toDouble().toRawBits())
            ModbusType.BCD16 -> intArrayOf(encodeBcd(number(value).toLong(), 4))
            ModbusType.BCD32 -> split32(encodeBcd(number(value).toLong(), 8).toLong() and 0xFFFFFFFFL)
            ModbusType.STRING -> return encodeString(value, item.stringLength!!, config)
            ModbusType.BOOLEAN, ModbusType.BIT -> error("handled above")
        }
        return wordsToWire(words, config.byteOrder, config.wordOrder)
    }

    private fun decodeRegister(item: ParsedAddress, data: ByteArray, byteOffset: Int, config: ModbusDeviceConfig): Any? {
        if (item.type == ModbusType.STRING) {
            val slice = data.copyOfRange(byteOffset, byteOffset + item.span * 2)
            return decodeString(slice, config)
        }
        val words = wordsFromWire(data, byteOffset, item.span, config.byteOrder, config.wordOrder, scratch.get())
        return when (item.type) {
            ModbusType.INT16 -> signExtend(words[0].toLong(), 16).toInt()
            ModbusType.UINT16 -> words[0]
            ModbusType.BIT -> ((words[0] ushr item.bit!!) and 1) == 1
            ModbusType.INT32 -> signExtend(assemble(words, item.span), 32).toInt()
            ModbusType.UINT32 -> assemble(words, item.span) and 0xFFFFFFFFL
            ModbusType.INT64 -> assemble(words, item.span)
            ModbusType.UINT64 -> assembleUnsigned(words, item.span)
            ModbusType.FLOAT32 -> Float.fromBits(assemble(words, item.span).toInt())
            ModbusType.FLOAT64 -> Double.fromBits(assemble(words, item.span))
            ModbusType.BCD16 -> decodeBcd(words, item.span)
            ModbusType.BCD32 -> decodeBcd(words, item.span)
            ModbusType.STRING, ModbusType.BOOLEAN -> error("unreachable")
        }
    }

    private val scratch = ThreadLocal.withInitial { IntArray(4) }

    fun wordsFromWire(data: ByteArray, byteOffset: Int, count: Int, byteOrder: ByteOrder, wordOrder: WordOrder): IntArray =
        wordsFromWire(data, byteOffset, count, byteOrder, wordOrder, IntArray(count))

    /**
     * Fill [dest] when it is large enough. The scan path passes a thread-local buffer so a register
     * decode does not allocate. Callers must not keep [dest] after the next decode on this thread.
     */
    private fun wordsFromWire(
        data: ByteArray, byteOffset: Int, count: Int, byteOrder: ByteOrder, wordOrder: WordOrder, dest: IntArray,
    ): IntArray {
        val words = if (dest.size >= count) dest else IntArray(count)
        for (i in 0 until count) {
            val o = byteOffset + i * 2
            val b0 = data[o].toInt() and 0xFF
            val b1 = data[o + 1].toInt() and 0xFF
            words[i] = if (byteOrder == ByteOrder.BIG_ENDIAN) (b0 shl 8) or b1 else (b1 shl 8) or b0
        }
        if (wordOrder == WordOrder.LOW_WORD_FIRST) {
            var i = 0
            var j = count - 1
            while (i < j) {
                val tmp = words[i]
                words[i] = words[j]
                words[j] = tmp
                i++
                j--
            }
        }
        return words
    }

    fun wordsToWire(wordsHighFirst: IntArray, byteOrder: ByteOrder, wordOrder: WordOrder): ByteArray {
        val ordered = if (wordOrder == WordOrder.HIGH_WORD_FIRST) wordsHighFirst else wordsHighFirst.reversedArray()
        val out = ByteArray(ordered.size * 2)
        for (i in ordered.indices) {
            val w = ordered[i] and 0xFFFF
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                out[i * 2] = (w ushr 8).toByte()
                out[i * 2 + 1] = w.toByte()
            } else {
                out[i * 2] = w.toByte()
                out[i * 2 + 1] = (w ushr 8).toByte()
            }
        }
        return out
    }

    /** CRC-16/Modbus. The low byte is transmitted first. */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    private fun decodeString(raw: ByteArray, config: ModbusDeviceConfig): String {
        val chars = CharArray(raw.size)
        var i = 0
        while (i < raw.size) {
            val a = raw[i].toInt() and 0xFF
            val b = raw[i + 1].toInt() and 0xFF
            if (config.reverseStringByteOrder) {
                chars[i] = b.toChar(); chars[i + 1] = a.toChar()
            } else {
                chars[i] = a.toChar(); chars[i + 1] = b.toChar()
            }
            i += 2
        }
        if (config.readRawStrings) return String(chars)
        val nul = chars.indexOf('\u0000')
        var s = if (nul >= 0) String(chars, 0, nul) else String(chars)
        s = if (config.rightJustifyStrings) s.trimStart() else s.trimEnd()
        return s
    }

    private fun encodeString(value: Any?, length: Int, config: ModbusDeviceConfig): ByteArray {
        val text = when (value) {
            is String -> value
            null -> throw TypeMismatchException("expected string")
            else -> value.toString()
        }
        val padded = if (config.rightJustifyStrings) text.take(length).padStart(length, ' ')
        else text.take(length).padEnd(length, ' ')
        val out = ByteArray(length)
        for (i in 0 until length step 2) {
            val c0 = padded[i].code and 0xFF
            val c1 = padded[i + 1].code and 0xFF
            if (config.reverseStringByteOrder) {
                out[i] = c1.toByte(); out[i + 1] = c0.toByte()
            } else {
                out[i] = c0.toByte(); out[i + 1] = c1.toByte()
            }
        }
        return out
    }

    private fun bitAt(data: ByteArray, index: Int): Boolean {
        val b = data[index / 8].toInt() and 0xFF
        return ((b ushr (index % 8)) and 1) == 1
    }

    private fun assemble(words: IntArray, count: Int): Long {
        var v = 0L
        for (i in 0 until count) v = (v shl 16) or (words[i].toLong() and 0xFFFF)
        return v
    }

    private fun assembleUnsigned(words: IntArray, count: Int): Number {
        var bi = BigInteger.ZERO
        for (i in 0 until count) bi = bi.shiftLeft(16).or(BigInteger.valueOf(words[i].toLong() and 0xFFFF))
        return if (bi.bitLength() < 63) bi.toLong() else bi
    }

    private fun split32(value: Long): IntArray =
        intArrayOf(((value ushr 16) and 0xFFFF).toInt(), (value and 0xFFFF).toInt())

    private fun split64(value: Long): IntArray = intArrayOf(
        ((value ushr 48) and 0xFFFF).toInt(),
        ((value ushr 32) and 0xFFFF).toInt(),
        ((value ushr 16) and 0xFFFF).toInt(),
        (value and 0xFFFF).toInt(),
    )

    private fun signExtend(value: Long, bits: Int): Long {
        val shift = 64 - bits
        return (value shl shift) shr shift
    }

    private fun decodeBcd(words: IntArray, count: Int): Long {
        var v = 0L
        for (i in 0 until count) {
            val w = words[i]
            for (shift in intArrayOf(12, 8, 4, 0)) {
                val nibble = (w ushr shift) and 0xF
                if (nibble > 9) throw TypeMismatchException("invalid BCD nibble $nibble")
                v = v * 10 + nibble
            }
        }
        return v
    }

    private fun encodeBcd(value: Long, digits: Int): Int {
        if (value < 0) throw TypeMismatchException("BCD value must be >= 0")
        var v = value
        var out = 0
        for (i in 0 until digits) {
            val digit = (v % 10).toInt()
            out = out or (digit shl (i * 4))
            v /= 10
        }
        if (v != 0L) throw TypeMismatchException("value does not fit in $digits BCD digits")
        return out
    }

    private fun number(value: Any?): Number = when (value) {
        is Number -> value
        else -> throw TypeMismatchException("expected a number, got ${value?.javaClass?.simpleName}")
    }

    private fun Long.requireUInt(bits: Int): Long {
        if (this < 0 || this >= (1L shl bits)) throw TypeMismatchException("value $this does not fit in $bits unsigned bits")
        return this
    }

    private fun uint64(value: Any?): BigInteger {
        val bi = when (value) {
            is BigInteger -> value
            is ULong -> BigInteger(value.toString())
            is Number -> if (value is Long && value < 0) BigInteger(java.lang.Long.toUnsignedString(value))
            else BigInteger.valueOf(value.toLong())
            else -> throw TypeMismatchException("expected a number")
        }
        if (bi.signum() < 0 || bi.bitLength() > 64) throw TypeMismatchException("value does not fit in uint64")
        return bi
    }
}

internal class TypeMismatchException(message: String) : IllegalArgumentException(message)
