package org.oip.db.groups

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Hour meter and event meter items (doc source: FEATURES DAT-007).
 *
 * A meter item watches a boolean tag. An hour meter adds up the time the tag was true; an event meter counts
 * how often it went from false to true. The running total is the item's value each run, so it can be written
 * to a column, and it survives a gateway restart through a [MeterStore].
 */
enum class MeterMode(val wire: String) {
    HOUR("hour"), EVENT("event");

    companion object {
        fun of(raw: String): MeterMode = entries.firstOrNull { it.wire == raw } ?: throw GroupRejected("item meter must be hour or event")
    }
}

/** What an hour meter's total is expressed in. Totals are kept in seconds and converted when read. */
enum class MeterUnit(val wire: String, val seconds: Double) {
    SECONDS("seconds", 1.0), MINUTES("minutes", 60.0), HOURS("hours", 3600.0);

    companion object {
        fun of(raw: String): MeterUnit = entries.firstOrNull { it.wire == raw } ?: throw GroupRejected("item meterUnit must be seconds, minutes, or hours")
    }
}

/** One meter's saved state: total (seconds for an hour meter, count for an event meter) and the last tag state seen. */
data class MeterState(val total: Double, val active: Boolean)

/** Where meter totals are kept between runs and restarts. Keys are item names within one group. */
interface MeterStore {
    fun load(group: String): Map<String, MeterState>
    fun save(group: String, states: Map<String, MeterState>)

    companion object {
        fun memory(): MeterStore = object : MeterStore {
            private val saved = ConcurrentHashMap<String, Map<String, MeterState>>()
            override fun load(group: String) = saved[group] ?: emptyMap()
            override fun save(group: String, states: Map<String, MeterState>) { saved[group] = states.toMap() }
        }
    }
}

/** One small file per group under [dir]: `item<TAB>total<TAB>active` per line, replaced atomically. */
class FileMeterStore(private val dir: Path) : MeterStore {
    override fun load(group: String): Map<String, MeterState> {
        val file = file(group)
        if (!Files.isRegularFile(file)) return emptyMap()
        val out = LinkedHashMap<String, MeterState>()
        for (line in Files.readAllLines(file)) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val total = parts[1].toDoubleOrNull() ?: continue
            out[parts[0]] = MeterState(total, parts[2] == "1")
        }
        return out
    }

    override fun save(group: String, states: Map<String, MeterState>) {
        Files.createDirectories(dir)
        val tmp = dir.resolve("${safe(group)}.tmp")
        Files.write(tmp, states.map { (name, s) -> "$name\t${s.total}\t${if (s.active) 1 else 0}" })
        Files.move(tmp, file(group), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun file(group: String): Path = dir.resolve("${safe(group)}.meters")
    private fun safe(group: String) = group.filter { it.isLetterOrDigit() || it == '-' }
}

/** The meters of one running group. Thread-safe: ticks and flushes may come from different threads. */
internal class GroupMeters(
    private val group: String,
    private val items: List<GroupItem>,
    private val store: MeterStore,
) {
    private class Meter(val item: GroupItem, var total: Double, var active: Boolean)

    private val lock = Any()
    private val meters = LinkedHashMap<String, Meter>()
    private var lastMillis: Long? = null
    private var savedAtMillis = Long.MIN_VALUE
    private var dirty = false

    val any: Boolean get() = meters.isNotEmpty()

    init {
        val saved = if (items.any { it.meter != null }) store.load(group) else emptyMap()
        for (item in items) {
            if (item.meter == null) continue
            val state = saved[item.name]
            meters[item.name] = Meter(item, state?.total ?: 0.0, state?.active ?: false)
        }
    }

    /**
     * Fold one sample of each meter's tag in. Time between ticks counts for an hour meter only when the tag was
     * already true at the previous sample; time while the gateway was down is never counted. A bad sample
     * changes nothing.
     */
    fun update(nowMillis: Long, read: (String) -> TagRead) {
        if (meters.isEmpty()) return
        val samples = meters.values.associateWith { read(it.item.tag!!) }
        synchronized(lock) {
            val elapsed = lastMillis?.let { ((nowMillis - it).coerceAtLeast(0)) / 1000.0 } ?: 0.0
            lastMillis = nowMillis
            for ((meter, sample) in samples) {
                if (!sample.good) continue
                val on = truthy(sample)
                when (meter.item.meter!!) {
                    MeterMode.HOUR -> if (meter.active) { meter.total += elapsed; dirty = true }
                    MeterMode.EVENT -> if (on && !meter.active) { meter.total += 1.0; dirty = true }
                }
                if (on != meter.active) { meter.active = on; dirty = true }
            }
            if (dirty && nowMillis - savedAtMillis >= SAVE_EVERY_MILLIS) persist(nowMillis)
        }
    }

    /** The item's value for this run: an hour meter in its unit, an event meter as a whole count. */
    fun value(name: String): Any? = synchronized(lock) {
        val meter = meters[name] ?: return null
        when (meter.item.meter!!) {
            MeterMode.HOUR -> meter.total / meter.item.meterUnit.seconds
            MeterMode.EVENT -> meter.total.toLong()
        }
    }

    fun owns(name: String) = meters.containsKey(name)

    /** Write unsaved changes now (a stop, a config change, or a close). */
    fun flush() = synchronized(lock) { if (dirty) persist(savedAtMillis) }

    private fun persist(nowMillis: Long) {
        store.save(group, meters.mapValues { MeterState(it.value.total, it.value.active) })
        savedAtMillis = nowMillis
        dirty = false
    }

    private companion object {
        const val SAVE_EVERY_MILLIS = 5_000L
    }
}
