package org.oip.historian

/**
 * In-process stand-in for a SQL history provider, with the same contract as [SqlHistoryProvider]: a batch is
 * atomic and (sourceId, seq) is a unique key, so a resent record is skipped. Faults:
 *  - [down]: every call fails transiently (the link is cut);
 *  - [commitThenFail]: the next N writes commit and then fail transiently (acknowledgement lost);
 *  - [reject]: a record matching it makes the batch fail permanently (like a constraint error).
 */
class FakeRemoteProvider(override val name: String) : RemoteHistoryProvider {
    private val keys = HashSet<Pair<String, Long>>()
    private val stored = ArrayList<HistorySample>()
    @Volatile var down = false
    @Volatile var commitThenFail = 0
    @Volatile var reject: (HistorySample) -> Boolean = { false }
    var writeCalls = 0
        private set

    /** Every stored row in commit order. */
    @Synchronized fun rows(): List<HistorySample> = stored.toList()

    @Synchronized
    override fun write(records: List<ForwardRecord>) {
        writeCalls++
        if (down) throw HistoryWriteException("connection refused", permanent = false)
        records.firstOrNull { reject(it.sample) }?.let { throw HistoryWriteException("23514: check constraint violated by ${it.sample.value}", permanent = true) }
        for (r in records) if (keys.add(r.sourceId to r.seq)) stored += r.sample
        if (commitThenFail > 0) {
            commitThenFail--
            throw HistoryWriteException("connection reset after commit", permanent = false)
        }
    }

    @Synchronized
    override fun query(query: HistoryQuery): List<HistorySample> {
        if (down) throw HistoryReadException("connection refused")
        return stored.filter { it.tagPath.equals(query.tagPath, true) && it.timestampMillis in query.fromMillis..query.toMillis }
            .sortedBy { it.timestampMillis }.take(query.limit)
    }

    override fun scan(tagPath: String, fromMillis: Long, toMillis: Long, each: (HistorySample) -> Unit) =
        query(HistoryQuery(tagPath, fromMillis, toMillis, HistoryQuery.MAX_LIMIT)).forEach(each)

    @Synchronized
    override fun latestBefore(tagPath: String, beforeMillis: Long): HistorySample? =
        stored.filter { it.tagPath.equals(tagPath, true) && it.timestampMillis < beforeMillis }.maxByOrNull { it.timestampMillis }
}
