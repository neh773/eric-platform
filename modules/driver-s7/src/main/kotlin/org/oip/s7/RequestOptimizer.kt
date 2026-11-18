package org.oip.s7

/**
 * Coalesce items that share an area (and data block) into the smallest set of S7 reads that fit
 * in the negotiated PDU.
 *
 * Adjacent or overlapping addresses become one any-type item (a contiguous byte range, or a run
 * of timers or counters). A gap is included only when [S7DeviceConfig.spanGaps] is set and the
 * gap is at most [S7DeviceConfig.maxGap]. A single item is never larger than the ack-data that
 * fits in the PDU, and several items share one PDU when both the request and the response fit.
 * An address that cannot fit in a PDU by itself is rejected.
 *
 * The PDU size is the S7 message negotiated at setup (RFC 1006 length does not count). The
 * configured PDU size is that budget. Doc source: FEATURES DEV-036.
 */
data class ReadSlice(
    val area: S7Area,
    val db: Int,
    val kind: S7Kind,
    val start: Int,
    val length: Int,
    val addresses: List<ParsedAddress>,
) {
    val dataBytes: Int get() = if (kind == S7Kind.MEMORY) length else length * 2
}

data class ReadPdu(val slices: List<ReadSlice>)

data class ReadPlan(val pdus: List<ReadPdu>, val rejected: List<ParsedAddress>)

object RequestOptimizer {
    fun plan(items: List<ParsedAddress>, pduSize: Int, config: S7DeviceConfig): ReadPlan {
        val pdu = pduSize.coerceAtLeast(1)
        val maxData = S7Wire.maxDataBytes(pdu)
        val rejected = ArrayList<ParsedAddress>()
        val slices = ArrayList<ReadSlice>()
        val groups = items.groupBy { Triple(it.area, it.db, it.kind) }
        for ((_, group) in groups) {
            val sorted = group.sortedWith(compareBy({ it.start }, { it.span }, { it.raw }))
            var batch: MutableList<ParsedAddress>? = null
            var start = 0
            var end = 0
            fun flush() {
                val cur = batch ?: return
                slices += ReadSlice(cur.first().area, cur.first().db, cur.first().kind, start, end - start, cur.toList())
                batch = null
            }
            for (item in sorted) {
                val alone = if (item.kind == S7Kind.MEMORY) item.span else 2
                if (alone > maxData || alone <= 0) {
                    flush()
                    rejected += item
                    continue
                }
                val cur = batch
                if (cur == null) {
                    batch = mutableListOf(item)
                    start = item.start
                    end = item.endExclusive
                    continue
                }
                val gap = item.start - end
                val unionEnd = maxOf(end, item.endExclusive)
                val unionBytes = if (item.kind == S7Kind.MEMORY) unionEnd - start else (unionEnd - start) * 2
                val fits = unionBytes <= maxData
                val touch = gap <= 0
                val spanGap = config.spanGaps && gap in 1..config.maxGap.coerceAtLeast(0)
                if (fits && (touch || spanGap)) {
                    cur.add(item)
                    end = unionEnd
                } else {
                    flush()
                    batch = mutableListOf(item)
                    start = item.start
                    end = item.endExclusive
                }
            }
            flush()
        }
        return ReadPlan(pack(slices, pdu, rejected), rejected)
    }

    private fun pack(slices: List<ReadSlice>, pdu: Int, rejected: MutableList<ParsedAddress>): List<ReadPdu> {
        val pdus = ArrayList<ReadPdu>()
        var batch = ArrayList<ReadSlice>()
        fun flush() {
            if (batch.isEmpty()) return
            pdus += ReadPdu(batch.toList())
            batch = ArrayList()
        }
        for (slice in slices) {
            if (!fits(emptyList(), slice, pdu)) {
                flush()
                rejected += slice.addresses
                continue
            }
            if (batch.isEmpty()) {
                batch.add(slice)
                continue
            }
            if (fits(batch, slice, pdu)) batch.add(slice) else {
                flush()
                batch.add(slice)
            }
        }
        flush()
        return pdus
    }

    private fun fits(existing: List<ReadSlice>, extra: ReadSlice, pdu: Int): Boolean {
        val n = existing.size + 1
        if (S7Wire.requestBytes(n) > pdu) return false
        val data = ArrayList<Int>(n)
        for (s in existing) data += s.dataBytes
        data += extra.dataBytes
        return S7Wire.responseBytes(data) <= pdu
    }
}
