package org.oip.modbus

/**
 * Coalesce items that share a unit id and area into the smallest set of reads.
 *
 * Contiguous items (the next item starts at the previous item's end) always merge.
 * A gap of unused coils or registers is included only when [ModbusDeviceConfig.spanGaps] is set
 * and the gap is at most [ModbusDeviceConfig.maxGap]. A merged block is split so it never exceeds
 * the per-request maximum (125 registers / 2000 coils by default, matching the Modbus PDU limit
 * and the reference platform's "Max Holding Registers per Read Request").
 *
 */
data class ReadBlock(
    val unitId: Int,
    val area: ModbusArea,
    val start: Int,
    val quantity: Int,
    val items: List<ParsedAddress>,
)

data class ReadPlan(val blocks: List<ReadBlock>, val rejected: List<ParsedAddress>)

object RequestOptimizer {
    fun plan(items: List<ParsedAddress>, config: ModbusDeviceConfig): ReadPlan {
        val blocks = mutableListOf<ReadBlock>()
        val rejected = mutableListOf<ParsedAddress>()
        for ((key, group) in items.groupBy { it.unitId to it.area }) {
            val (unit, area) = key
            val maxQty = maxQuantity(area, config)
            val allowMerge = allowMultiple(area, config)
            val gapLimit = if (config.spanGaps) config.maxGap.coerceAtLeast(0) else 0
            val sorted = group.sortedWith(compareBy({ it.protocolOffset }, { it.span }, { it.raw }))
            var batch: MutableList<ParsedAddress>? = null
            var start = 0
            var end = 0
            fun flush() {
                val cur = batch ?: return
                blocks += ReadBlock(unit, area, start, end - start, cur.toList())
                batch = null
            }
            for (item in sorted) {
                if (item.span > maxQty) {
                    flush(); rejected += item; continue
                }
                if (!allowMerge) {
                    flush()
                    blocks += ReadBlock(unit, area, item.protocolOffset, item.span, listOf(item))
                    continue
                }
                val iStart = item.protocolOffset
                val iEnd = item.endExclusive
                val cur = batch
                if (cur == null) {
                    batch = mutableListOf(item); start = iStart; end = iEnd
                    continue
                }
                val gap = iStart - end
                val overlaps = iStart < end && iStart >= start
                val fits = (maxOf(end, iEnd) - start) <= maxQty
                val closeEnough = gap in 0..gapLimit || (overlaps && gap < 0)
                if (fits && closeEnough) {
                    cur.add(item)
                    end = maxOf(end, iEnd)
                } else {
                    flush()
                    batch = mutableListOf(item); start = iStart; end = iEnd
                }
            }
            flush()
        }
        return ReadPlan(blocks, rejected)
    }

    fun maxQuantity(area: ModbusArea, config: ModbusDeviceConfig): Int = when (area) {
        ModbusArea.HOLDING_REGISTER -> config.maxHoldingRegistersPerRead.coerceIn(1, 125)
        ModbusArea.INPUT_REGISTER -> config.maxInputRegistersPerRequest.coerceIn(1, 125)
        ModbusArea.COIL -> config.maxCoilsPerRequest.coerceIn(1, 2000)
        ModbusArea.DISCRETE_INPUT -> config.maxDiscreteInputsPerRequest.coerceIn(1, 2000)
    }

    private fun allowMultiple(area: ModbusArea, config: ModbusDeviceConfig): Boolean = when (area) {
        ModbusArea.HOLDING_REGISTER, ModbusArea.INPUT_REGISTER -> config.allowReadMultipleRegisters
        ModbusArea.COIL -> config.allowReadMultipleCoils
        ModbusArea.DISCRETE_INPUT -> config.allowReadMultipleDiscreteInputs
    }
}
