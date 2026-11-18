package org.oip.gateway

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * Shared process image. Layout matches `plc/eric-plc/src/image.rs`.
 * The gateway writes inputs. The scan engine writes outputs.
 * A seqlock keeps a slot's value and timestamp together.
 */
internal class PlcImage private constructor(
    private val channel: FileChannel,
    private val buf: ByteBuffer,
    val inputs: Int,
    val outputs: Int,
) : AutoCloseable {
    fun writeInputs(slots: List<PlcSlot>) = writeSlots(OFF_INPUT_SEQ, inputOffset(0), slots)
    fun readInputs(out: Array<PlcSlot>) = readSlots(OFF_INPUT_SEQ, inputOffset(0), out)
    fun writeOutputs(slots: List<PlcSlot>) = writeSlots(OFF_OUTPUT_SEQ, outputOffset(0), slots)
    fun readOutputs(out: Array<PlcSlot>) = readSlots(OFF_OUTPUT_SEQ, outputOffset(0), out)

    fun setGatewayHeartbeat(ns: Long) = setLong(OFF_GW_HB, ns)
    fun gatewayHeartbeat(): Long = getLong(OFF_GW_HB)
    fun setPlcHeartbeat(ns: Long) = setLong(OFF_PLC_HB, ns)
    fun plcHeartbeat(): Long = getLong(OFF_PLC_HB)
    fun setState(state: Int) = setInt(OFF_STATE, state)
    fun state(): Int = getInt(OFF_STATE)

    override fun close() {
        channel.close()
    }

    private fun writeSlots(seqOff: Int, base: Int, slots: List<PlcSlot>) {
        val seen = LONGS.getAndAdd(buf, seqOff, 1L) as Long
        check(seen and 1L == 0L) { "seqlock writer overlapped" }
        for ((index, slot) in slots.withIndex()) store(base + index * SLOT_BYTES, slot)
        LONGS.getAndAdd(buf, seqOff, 1L)
    }

    private fun readSlots(seqOff: Int, base: Int, out: Array<PlcSlot>): Boolean {
        repeat(SPINS) {
            val s1 = getLong(seqOff)
            if (s1 and 1L == 0L) {
                for (index in out.indices) out[index] = load(base + index * SLOT_BYTES)
                val s2 = getLong(seqOff)
                if (s1 == s2) return true
            }
        }
        return false
    }

    private fun store(off: Int, slot: PlcSlot) {
        setInt(off, slot.typeCode or (slot.quality shl 8))
        setLong(off + SLOT_VALUE, slot.value)
        setLong(off + SLOT_SOURCE, slot.sourceNs)
    }

    private fun load(off: Int): PlcSlot {
        val head = getInt(off)
        return PlcSlot(
            typeCode = head and 0xff,
            quality = (head ushr 8) and 0xff,
            value = getLong(off + SLOT_VALUE),
            sourceNs = getLong(off + SLOT_SOURCE),
        )
    }

    private fun inputOffset(index: Int) = HEADER_BYTES + index * SLOT_BYTES
    private fun outputOffset(index: Int) = HEADER_BYTES + (inputs + index) * SLOT_BYTES
    private fun getLong(index: Int) = LONGS.getVolatile(buf, index) as Long
    private fun setLong(index: Int, value: Long) { LONGS.setVolatile(buf, index, value) }
    private fun getInt(index: Int) = INTS.getVolatile(buf, index) as Int
    private fun setInt(index: Int, value: Int) { INTS.setVolatile(buf, index, value) }

    companion object {
        const val MAGIC = 0x43505245
        const val VERSION = 1
        const val HEADER_BYTES = 128
        const val SLOT_BYTES = 32
        const val OFF_INPUT_SEQ = 32
        const val OFF_OUTPUT_SEQ = 40
        const val OFF_GW_HB = 48
        const val OFF_PLC_HB = 56
        const val OFF_STATE = 64
        const val SLOT_VALUE = 8
        const val SLOT_SOURCE = 16
        const val OFF_PERIOD = 80
        const val STATE_RUNNING = 1
        const val SPINS = 10_000

        private val LONGS: VarHandle = MethodHandles.byteBufferViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val INTS: VarHandle = MethodHandles.byteBufferViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)

        fun open(path: Path, inputs: Int, outputs: Int, periodNs: Long): PlcImage {
            require(inputs in 0..4096 && outputs in 0..4096) { "image supports at most 4096 points each way" }
            val len = HEADER_BYTES + (inputs + outputs) * SLOT_BYTES
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = channel.lock()
            var opened: PlcImage? = null
            var failure: Exception? = null
            try {
                if (channel.size() < len) {
                    channel.write(ByteBuffer.allocate(len - channel.size().toInt()), channel.size())
                }
                val buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, len.toLong())
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val image = PlcImage(channel, buf, inputs, outputs)
                image.init(periodNs)
                opened = image
            } catch (e: Exception) {
                failure = e
            } finally {
                try {
                    lock.release()
                } catch (_: Exception) {
                    // A failed init closes nothing until the lock is released.
                }
            }
            if (opened != null) return opened
            channel.close()
            throw failure ?: IllegalStateException("process image was not created")
        }
    }

    private fun init(periodNs: Long) {
        val magic = getInt(0)
        if (magic == 0) {
            setInt(4, VERSION)
            setInt(8, HEADER_BYTES)
            setInt(12, SLOT_BYTES)
            setInt(16, inputs)
            setInt(20, outputs)
            setLong(OFF_PERIOD, periodNs)
            setLong(OFF_INPUT_SEQ, 0)
            setLong(OFF_OUTPUT_SEQ, 0)
            for (index in 0 until inputs) setInt(inputOffset(index), 1 shl 8)
            for (index in 0 until outputs) setInt(outputOffset(index), 1 shl 8)
            setInt(0, MAGIC)
            return
        }
        require(magic == MAGIC) { "process image has the wrong magic" }
        require(getInt(4) == VERSION) { "process image version is not $VERSION" }
        require(getInt(8) == HEADER_BYTES && getInt(12) == SLOT_BYTES) { "process image layout does not match" }
        require(getInt(16) == inputs && getInt(20) == outputs) { "process image point counts do not match the mapping" }
    }
}

internal data class PlcSlot(val typeCode: Int, val quality: Int, val value: Long, val sourceNs: Long)
