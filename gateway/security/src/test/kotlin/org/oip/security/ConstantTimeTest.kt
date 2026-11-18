package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConstantTimeTest {
    @Test fun equalAndUnequal() {
        assertTrue(ConstantTime.equal(ByteArray(0), ByteArray(0)))
        assertTrue(ConstantTime.equal("abc".toByteArray(), "abc".toByteArray()))
        assertTrue(ConstantTime.equal("abc", "abc"))
        assertFalse(ConstantTime.equal("abc", "abd"))
        assertFalse(ConstantTime.equal("abc", "ab"))
        assertFalse(ConstantTime.equal(ByteArray(0), byteArrayOf(0)))
        val hash = ByteArray(32) { it.toByte() }
        val flippedFirst = hash.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val flippedLast = hash.copyOf().also { it[31] = (it[31].toInt() xor 0x01).toByte() }
        assertFalse(ConstantTime.equal(hash, flippedFirst))
        assertFalse(ConstantTime.equal(hash, flippedLast))
        assertTrue(ConstantTime.equal(hash, hash.copyOf()))
    }

    @Test fun comparisonVisitsEveryIndexEvenWhenTheFirstByteDiffers() {
        val a = ByteArray(32) { 1 }
        val early = a.copyOf().also { it[0] = 2 }
        val late = a.copyOf().also { it[31] = 2 }
        val seenEarly = mutableListOf<Int>()
        val seenLate = mutableListOf<Int>()
        assertFalse(ConstantTime.equalVisiting(a, early) { seenEarly += it })
        assertFalse(ConstantTime.equalVisiting(a, late) { seenLate += it })
        assertEquals((0 until 32).toList(), seenEarly)
        assertEquals((0 until 32).toList(), seenLate)
        val seenLen = mutableListOf<Int>()
        assertFalse(ConstantTime.equalVisiting(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)) { seenLen += it })
        assertEquals(listOf(0, 1, 2), seenLen)
    }
}
