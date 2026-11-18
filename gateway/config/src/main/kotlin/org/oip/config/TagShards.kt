package org.oip.config

import java.io.ByteArrayOutputStream

/**
 * On-disk layout for `kind: Tag` (ADR 0011). Other kinds stay one file per resource (ADR 0005).
 *
 * A tag named `n` is one YAML document in
 * `<module>/<scope>/Tag/_shards/sXX.yaml`, where `XX` is the low 8 bits of FNV-1a 32-bit(`n`)
 * printed as two hex digits. 256 files is the ceiling per module and scope, however many tags
 * that scope holds. `_shards` is not a legal resource name, so a shard cannot be mistaken for a
 * legacy `<name>.yaml` file.
 *
 * A bulk batch appends its new documents to `sXX.seg.yaml` instead of rewriting `sXX.yaml`.
 * The segment is folded back into the sorted shard on the next open, single-tag put, or delete.
 * At most one segment file exists per shard, so the file count stays at 512 per module and scope.
 */
internal object TagShards {
    const val COUNT = 256
    const val DIR = "_shards"

    /** `OIPSEG01` plus a big-endian old size. Not a YAML document, so the config walk skips it. */
    private val PENDING_MAGIC = "OIPSEG01".toByteArray(Charsets.US_ASCII)
    private val FILE = Regex("""s([0-9a-f]{2})(?:\.seg)?\.yaml""")
    private val SHARD_NAME = Regex("""s[0-9a-f]{2}\.yaml""")
    private val SEGMENT_NAME = Regex("""s[0-9a-f]{2}\.seg\.yaml""")
    private val SEPARATOR = "---\n".toByteArray(Charsets.UTF_8)

    /** FNV-1a 32-bit, low 8 bits. `index("a")` is 44. */
    fun index(name: String): Int {
        var hash = 2166136261u
        for (ch in name) {
            hash = hash xor ch.code.toUInt()
            hash *= 16777619u
        }
        return (hash and 255u).toInt()
    }

    fun fileName(index: Int): String = "s%02x.yaml".format(index)

    /** Append-only sibling of [fileName]. Folded back into the sorted shard; not a second hash bucket. */
    fun segmentFileName(index: Int): String = "s%02x.seg.yaml".format(index)

    /** Commit record for an in-flight append. A crash leaves this beside the segment; open finishes the append. */
    fun pendingFileName(index: Int): String = "s%02x.seg.pending".format(index)

    fun isSegmentPath(relative: String): Boolean = SEGMENT_NAME.matches(relative.substringAfterLast('/'))

    fun isShardFileName(name: String): Boolean = SHARD_NAME.matches(name)

    fun isSegmentFileName(name: String): Boolean = SEGMENT_NAME.matches(name)

    data class Ref(val module: String, val scope: Scope, val index: Int) {
        init {
            require(index in 0 until COUNT) { "shard index $index" }
        }
    }

    fun refFor(id: ResourceId): Ref = Ref(id.module, id.scope, index(id.name))

    /** Shard file relative to the config root, or null when [relative] is some other YAML file. */
    fun refOf(relative: String): Ref? {
        val parts = relative.split('/')
        if (parts.size < 5 || parts[parts.lastIndex - 1] != DIR) return null
        if (parts[parts.lastIndex - 2] != "Tag") return null
        val match = FILE.matchEntire(parts.last()) ?: return null
        val scope = try {
            Scope.parse(parts.subList(1, parts.lastIndex - 2).joinToString("/"))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!Names.MODULE.matches(parts[0])) return null
        return Ref(parts[0], scope, match.groupValues[1].toInt(16))
    }

    /** Header plus documents, each document already ending in a newline, separated by `---`. */
    fun encode(index: Int, docs: List<ByteArray>): ByteArray {
        val header = "# oip-tag-shard: fnv1a-256 ${fileName(index).removeSuffix(".yaml")}\n".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(header.size + docs.sumOf { it.size + SEPARATOR.size + 1 })
        out.write(header)
        for (i in docs.indices) {
            if (i > 0) out.write(SEPARATOR)
            val doc = docs[i]
            out.write(doc)
            if (doc.isEmpty() || doc.last() != '\n'.code.toByte()) out.write('\n'.code)
        }
        return out.toByteArray()
    }

    /**
     * Documents to append to a segment. [header] is the first chunk of a new segment file.
     * Later chunks are only the new documents, each introduced by `---`.
     */
    fun encodeSegment(index: Int, docs: List<ByteArray>, header: Boolean): ByteArray {
        if (header) {
            val label = "# oip-tag-segment: fnv1a-256 ${fileName(index).removeSuffix(".yaml")}\n".toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream(label.size + docs.sumOf { it.size + SEPARATOR.size + 1 })
            out.write(label)
            writeDocs(out, docs, leadingSeparator = false)
            return out.toByteArray()
        }
        val out = ByteArrayOutputStream(docs.sumOf { it.size + SEPARATOR.size + 1 })
        writeDocs(out, docs, leadingSeparator = true)
        return out.toByteArray()
    }

    /** Durable record of an append that has passed the commit hook but may not have landed on the segment yet. */
    fun encodePending(oldSize: Long, payload: ByteArray): ByteArray {
        require(oldSize >= 0L) { "segment offset $oldSize" }
        val out = ByteArray(PENDING_MAGIC.size + 8 + payload.size)
        PENDING_MAGIC.copyInto(out)
        var shift = 56
        for (i in 0 until 8) {
            out[PENDING_MAGIC.size + i] = ((oldSize ushr shift) and 0xff).toByte()
            shift -= 8
        }
        payload.copyInto(out, PENDING_MAGIC.size + 8)
        return out
    }

    /** Null when [raw] is not a pending record. */
    fun decodePending(raw: ByteArray): Pair<Long, ByteArray>? {
        val magic = PENDING_MAGIC
        if (raw.size < magic.size + 8) return null
        for (i in magic.indices) if (raw[i] != magic[i]) return null
        var oldSize = 0L
        for (i in 0 until 8) oldSize = (oldSize shl 8) or (raw[magic.size + i].toLong() and 0xff)
        return oldSize to raw.copyOfRange(magic.size + 8, raw.size)
    }

    private fun writeDocs(out: ByteArrayOutputStream, docs: List<ByteArray>, leadingSeparator: Boolean) {
        for (i in docs.indices) {
            if (leadingSeparator || i > 0) out.write(SEPARATOR)
            val doc = docs[i]
            out.write(doc)
            if (doc.isEmpty() || doc.last() != '\n'.code.toByte()) out.write('\n'.code)
        }
    }
}
