package org.oip.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.io.path.*

class ConflictException(message: String) : RuntimeException(message)

/**
 * Checks a create or update before anything is written, on the writing thread under the store's writer
 * lock. Throw [ConflictException] to refuse the whole write. Must be cheap and must not write to the store.
 */
fun interface WriteGuard {
    fun check(writes: List<Pair<ResourceId, Resource>>)
}

data class StoredResource(val id: ResourceId, val resource: Resource)

/**
 * File-backed config store (SPEC §4, ADR 0005, ADR 0011).
 * Files other than tags: `<root>/<module>/<scope>/<kind>/<name>.yaml`, one YAML document each.
 * Tag resources (`kind: Tag`) share 256 multi-document shard files per module and scope
 * (`.../Tag/_shards/sXX.yaml`, FNV-1a of the name). That is the git-friendly layout that keeps inode
 * use from growing with the tag count. A batch still stages temp files (in parallel once it is large),
 * runs [beforeCommit], renames them into place, fsyncs each parent directory once when it is not
 * fsyncing every file, and appends the journal with one gathering write. The first documents in a
 * shard are written once, sorted. Later batches append only the new documents to `sXX.seg.yaml`
 * (a pending record makes a torn append recoverable). The sorted shard is folded again on open,
 * on a single-tag put, and on delete. A batch does not re-read the tags already stored.
 *
 * The index keeps the identity, version, and a content stamp. The document body stays on disk.
 * Heads are also bucketed by module/scope/kind so a security-zone listing does not walk every tag.
 * Readers take the index read lock only long enough to copy those bucket references. Writers hold the
 * exclusive writer lock across the batch, but the index write lock is not held during file I/O, so an
 * auth lookup does not wait out a tag import. A hidden journal holds the last good bytes so an invalid
 * external edit can still be served without keeping a parsed [Resource] per tag. Tag reads use that
 * journal directly; the shard file is the durable, human-readable copy.
 * Writes are validated, version-checked (`metadata.version` must equal the stored version; 0 = create)
 * and committed atomically (temp file + atomic rename). [beforeCommit] runs on the temp file just
 * before the rename. A hook failure deletes the temps and leaves every target unchanged.
 * Opening the store folds a leftover one-file-per-tag tree into the shards. Doing that twice is a no-op.
 */
open class FileConfigStore(
    val root: Path,
    val registry: SchemaRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val beforeCommit: (Path) -> Unit = {},
) : AutoCloseable {
    /** Files that could not be loaded at open/rescan time (path -> error). */
    val loadErrors: Map<Path, String> get() = readIndex { errors.toMap() }

    /** Positional reads of the hidden journal since this store was opened. */
    internal val journalReads: Long get() = journal.reads.get()

    /** Bytes of tag shard and segment files published since this store was opened. */
    internal val tagFileBytesWritten: Long get() = tagBytesWritten.get()
    protected val errors = linkedMapOf<Path, String>()

    /** Serializes writers. Not taken by get/list, so a batch's file I/O does not stall auth. */
    private val writer = ReentrantLock()
    /** Guards [heads], [byKind], and [errors]. Read lock is not held across batch file I/O. */
    private val index = ReentrantReadWriteLock()
    private val heads = LinkedHashMap<ResourceId, Head>()
    /** module + scope + kind -> heads in insertion order. Same objects as [heads]. */
    private val byKind = HashMap<IndexKey, ArrayList<Head>>()
    /**
     * Decoded bodies for the small documents that are read on every request (users, API keys, zones).
     * Tag documents stay on disk: caching them would put a [Resource] back on the per-tag heap.
     */
    private val hot = object : LinkedHashMap<ResourceId, Resource>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ResourceId, Resource>?) = size > HOT_LIMIT
    }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ConfigEvent) -> Unit>()
    private val guards = java.util.concurrent.CopyOnWriteArrayList<WriteGuard>()
    private var watcher: java.util.concurrent.ScheduledExecutorService? = null
    private val journal = GoodLog(root.resolve(".good-journal"))
    /** Bytes published to tag shard and segment files. Bulk create stays linear in this. */
    private val tagBytesWritten = AtomicLong()
    private val madeDirs = HashSet<Path>()
    private val tmpNames = AtomicLong()
    /** Tag heads by shard. Same objects as [heads]. */
    private val byShard = HashMap<TagShards.Ref, ArrayList<Head>>()
    /** Size and mtime of a shard file the last time it matched the index, so rescan can skip it. */
    private val shardMarks = HashMap<Path, ShardMark>()

    init {
        registry.register(GATEWAY_MODULE, DEPLOYMENT_MODE_KIND, mapOf("type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to listOf("string", "null"), "pattern" to Names.NAME.pattern))))
    }
    init {
        try {
            root.createDirectories()
            loadFromDisk()
        } catch (t: Throwable) {
            journal.close()
            throw t
        }
    }

    private inline fun <T> readIndex(body: () -> T): T = index.readLock().withLock { body() }
    private inline fun <T> writeIndex(body: () -> T): T = index.writeLock().withLock { body() }
    private inline fun <T> mutate(body: () -> T): T = writer.withLock { body() }

    /** Listen for change events. Listeners run synchronously on the writing thread and must not block. */
    fun subscribe(listener: (ConfigEvent) -> Unit): AutoCloseable { listeners += listener; return AutoCloseable { listeners -= listener } }

    /** Add a check that runs before every [put] and [putAll]; see [WriteGuard]. */
    fun guard(guard: WriteGuard): AutoCloseable { guards += guard; return AutoCloseable { guards -= guard } }

    private fun checkGuards(writes: List<Pair<ResourceId, Resource>>) = guards.forEach { it.check(writes) }

    /** Run the write guards on [writes] without writing, so a caller can refuse a whole import before it starts. */
    fun checkWrites(writes: List<Pair<ResourceId, Resource>>) = checkGuards(writes)

    /** Poll the tree every [intervalMs] for external edits (see [rescan]). */
    fun startWatching(intervalMs: Long = 500) = mutate { writeIndex {
        if (watcher != null) return@writeIndex
        watcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "config-watcher").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ runCatching { rescan() } }, intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    } }

    override fun close() {
        mutate {
            writeIndex {
                watcher?.shutdownNow()
                watcher = null
                journal.close()
            }
        }
    }

    /**
     * Detect external file edits. A changed file is parsed and validated as a whole: if valid it is applied
     * (version = stored + 1, modifiedBy = "file", file rewritten in normalised form) and a FILE event is emitted;
     * if not, an INVALID event is emitted and the index is left unchanged. Deleted files emit DELETED.
     * A byte-level change that parses to the same spec and labels updates the stamp and does not bump the version.
     * A tag shard is one file of many documents. Any document that fails to parse or validate rejects that
     * shard edit and leaves the index unchanged for the tags it already holds. A valid shard is rewritten once.
     * A leftover one-file-per-tag YAML is folded into its shard and removed.
     */
    fun rescan() = mutate { writeIndex {
        recoverPendings()
        val seen = HashSet<ResourceId>()
        val legacy = ArrayList<Path>()
        val dirty = HashSet<TagShards.Ref>()
        val shardPaths = HashMap<TagShards.Ref, Path>()
        val segmentPaths = HashMap<TagShards.Ref, Path>()
        yamlFiles().forEach { p ->
            val relPath = rel(p)
            val ref = TagShards.refOf(relPath)
            if (ref != null) {
                if (TagShards.isSegmentPath(relPath)) segmentPaths[ref] = p else shardPaths[ref] = p
                return@forEach
            }
            val id = runCatching { canon(ResourceId.fromRelativePath(relPath)) }.getOrNull()
            if (id?.kind == "Tag") {
                legacy.add(p)
                noteLegacy(p, seen)
                return@forEach
            }
            rescanLoose(p, seen)
        }
        val tagRefs = HashSet<TagShards.Ref>()
        tagRefs.addAll(shardPaths.keys)
        tagRefs.addAll(segmentPaths.keys)
        for (marked in shardMarks.keys) {
            val ref = TagShards.refOf(rel(marked)) ?: continue
            tagRefs.add(ref)
        }
        for (ref in tagRefs) rescanTag(ref, shardPaths[ref], segmentPaths[ref], seen, dirty)
        heads.keys.filter { it !in seen }.toList().forEach { id ->
            if (id.kind == "Tag") dirty += TagShards.refFor(id)
            val file = file(id)
            errors.remove(file)
            val head = heads.remove(id) ?: return@forEach
            unindex(head)
            val old = runCatching { materialize(head) }.getOrNull() ?: return@forEach
            hotRemove(id)
            emit(ConfigEvent(ChangeType.DELETED, id, file, null, old, ChangeSource.FILE, null))
        }
        val consumed = absorbLegacy(legacy, dirty)
        writeShards(dirty, emptyMap(), emptySet(), sync = true, forceDir = true)
        for (path in consumed) {
            path.deleteIfExists()
            pruneEmptyDirs(path.parent)
        }
    } }

    /** One non-tag YAML file. Caller holds the index write lock. */
    private fun rescanLoose(p: Path, seen: MutableSet<ResourceId>) {
        val relPath = rel(p)
        val id = runCatching { canon(ResourceId.fromRelativePath(relPath)) }.getOrNull() ?: return
        val text = runCatching { p.readText() }.getOrNull() ?: return
        val fileStamp = stamp(text)
        val head = heads[id]
        if (head != null && head.sameStamp(fileStamp)) {
            seen.add(id)
            return
        }
        val parsed = runCatching { parseText(id, text) }
        parsed.onFailure { e ->
            seen.add(id)
            errors[p] = e.message ?: e.toString()
            emit(ConfigEvent(ChangeType.INVALID, id, p, null, head?.let { runCatching { materialize(it) }.getOrNull() }, ChangeSource.FILE, e.message ?: e.toString()))
        }
        parsed.onSuccess { loaded ->
            val r = loaded.resource
            seen.add(id)
            errors.remove(p)
            runCatching { validate(id, r) }.onFailure { e ->
                errors[p] = e.message!!
                emit(ConfigEvent(ChangeType.INVALID, id, p, null, head?.let { runCatching { materialize(it) }.getOrNull() }, ChangeSource.FILE, e.message))
                return
            }
            val current = head?.let { runCatching { materialize(it) }.getOrNull() }
            if (current != null && current.spec == r.spec && current.metadata.labels == r.metadata.labels) {
                head.setStamp(fileStamp)
                return
            }
            val stored = compact(id, r.copy(metadata = r.metadata.copy(
                version = (head?.version ?: 0) + 1,
                modified = clock.instant(),
                modifiedBy = Canon.share("file"),
            )))
            val bytes = write(id, stored, sync = true)
            remember(id, stored, bytes)
            emit(ConfigEvent(ChangeType.of(current, stored), id, p, stored, current, ChangeSource.FILE, null))
        }
    }

    /**
     * Sorted shard plus its append segment, if one exists. A matching stat pair does not re-read.
     * Caller holds the index write lock.
     */
    private fun rescanTag(
        ref: TagShards.Ref,
        shardPath: Path?,
        segmentPath: Path?,
        seen: MutableSet<ResourceId>,
        dirty: MutableSet<TagShards.Ref>,
    ) {
        if (shardPath == null && segmentPath == null) {
            shardMarks.remove(shardFile(ref))
            shardMarks.remove(segmentFile(ref))
            dirty += ref
            return
        }
        if (segmentPath == null) {
            if (shardMarks.remove(segmentFile(ref)) != null && shardPath != null) shardMarks.remove(shardPath)
            rescanShard(shardPath!!, ref, seen, dirty)
            return
        }
        val shardKnown = shardMarks.containsKey(shardFile(ref))
        val shardOk = if (shardPath == null) !shardKnown else markMatches(shardPath)
        if (shardOk && markMatches(segmentPath)) {
            byShard[ref]?.forEach { seen.add(it.id) }
            return
        }
        val shardRead = if (shardPath == null) emptyList<ShardDoc>() to emptyList() else parseShardFile(shardPath, ref)
        val segmentRead = parseShardFile(segmentPath, ref)
        val failures = shardRead.second + segmentRead.second
        val reported = shardPath ?: segmentPath
        if (failures.isNotEmpty()) {
            errors[reported] = failures.joinToString("; ")
            byShard[ref]?.forEach { seen.add(it.id) }
            emit(ConfigEvent(ChangeType.INVALID, null, reported, null, null, ChangeSource.FILE, errors[reported]))
            return
        }
        shardPath?.let { errors.remove(it) }
        errors.remove(segmentPath)
        val merged = LinkedHashMap<ResourceId, ShardDoc>()
        for (doc in shardRead.first) merged[doc.id] = doc
        for (doc in segmentRead.first) {
            val current = merged[doc.id]
            if (current == null || doc.resource.metadata.version > current.resource.metadata.version) merged[doc.id] = doc
        }
        applyShardDocs(ref, reported, merged.values, seen, dirty)
        dirty += ref
    }

    /**
     * One tag shard. A stat that matches [shardMarks] does not re-read the file.
     * Caller holds the index write lock.
     */
    private fun rescanShard(path: Path, ref: TagShards.Ref, seen: MutableSet<ResourceId>, dirty: MutableSet<TagShards.Ref>) {
        val size = Files.size(path)
        val mtime = Files.getLastModifiedTime(path).toMillis()
        val mark = shardMarks[path]
        if (mark != null && mark.size == size && mark.mtimeMillis == mtime) {
            byShard[ref]?.forEach { seen.add(it.id) }
            return
        }
        val (good, failures) = parseShardFile(path, ref)
        if (failures.isNotEmpty()) {
            errors[path] = failures.joinToString("; ")
            byShard[ref]?.forEach { seen.add(it.id) }
            emit(ConfigEvent(ChangeType.INVALID, null, path, null, null, ChangeSource.FILE, errors[path]))
            return
        }
        errors.remove(path)
        if (applyShardDocs(ref, path, good, seen, dirty)) dirty += ref
        else shardMarks[path] = ShardMark(size, mtime)
    }

    private fun parseShardFile(path: Path, ref: TagShards.Ref): Pair<List<ShardDoc>, List<String>> {
        val text = runCatching { path.readText() }.getOrNull()
            ?: return emptyList<ShardDoc>() to listOf("unreadable shard")
        val docs = ResourceCodec.decodeDocuments(text)
        val good = ArrayList<ShardDoc>()
        val failures = ArrayList<String>()
        for (doc in docs) {
            if (doc.error != null || doc.resource == null) {
                failures += doc.error ?: "bad document"
                continue
            }
            val id = try {
                canon(doc.resource.id(ref.scope))
            } catch (e: IllegalArgumentException) {
                failures += e.message ?: e.toString()
                continue
            }
            if (id.module != ref.module || id.kind != "Tag" || id.scope != ref.scope) {
                failures += "document $id does not belong in $ref"
                continue
            }
            val parsed = runCatching { parseText(id, doc.body) }
            if (parsed.isFailure) {
                failures += parsed.exceptionOrNull()?.message ?: "bad document"
                continue
            }
            good += ShardDoc(id, parsed.getOrThrow().resource)
        }
        return good to failures
    }

    /** Apply documents already accepted as a shard's contents. Returns true when the shard file must be rewritten. */
    private fun applyShardDocs(
        ref: TagShards.Ref,
        path: Path,
        good: Collection<ShardDoc>,
        seen: MutableSet<ResourceId>,
        dirty: MutableSet<TagShards.Ref>,
    ): Boolean {
        val present = HashSet<ResourceId>()
        var changed = false
        for ((id, resource) in good) {
            present += id
            seen += id
            if (TagShards.index(id.name) != ref.index) {
                dirty += ref
                dirty += TagShards.refFor(id)
            }
            val head = heads[id]
            val current = head?.let { runCatching { materialize(it) }.getOrNull() }
            if (current != null && current.spec == resource.spec && current.metadata.labels == resource.metadata.labels) continue
            val stored = compact(id, resource.copy(metadata = resource.metadata.copy(
                version = (head?.version ?: 0) + 1,
                modified = clock.instant(),
                modifiedBy = Canon.share("file"),
            )))
            remember(id, stored, ResourceCodec.encodeYamlBytes(stored))
            dirty += TagShards.refFor(id)
            changed = true
            emit(ConfigEvent(ChangeType.of(current, stored), id, path, stored, current, ChangeSource.FILE, null))
        }
        val missing = byShard[ref]?.any { it.id !in present } == true
        return changed || missing || ref in dirty
    }

    private fun noteLegacy(path: Path, seen: MutableSet<ResourceId>) {
        val parsed = runCatching { parseFile(path) }
        parsed.onSuccess { seen.add(it.id) }
        parsed.onFailure { e ->
            errors[path] = e.message ?: e.toString()
            emit(ConfigEvent(ChangeType.INVALID, null, path, null, null, ChangeSource.FILE, e.message))
        }
    }

    /**
     * Apply legacy one-file-per-tag documents that are new or newer than the shard copy.
     * Returns the files that are safe to delete once the shard rewrite has landed.
     * Caller holds the index write lock.
     */
    private fun absorbLegacy(paths: List<Path>, dirty: MutableSet<TagShards.Ref>): List<Path> {
        val consumed = ArrayList<Path>()
        for (path in paths) {
            val parsed = runCatching { parseFile(path) }.getOrNull() ?: continue
            val id = parsed.id
            if (id.kind != "Tag") continue
            val head = heads[id]
            val current = head?.let { runCatching { materialize(it) }.getOrNull() }
            val incoming = parsed.resource
            val same = current != null && current.spec == incoming.spec && current.metadata.labels == incoming.metadata.labels
            if (!same && (current == null || incoming.metadata.version > current.metadata.version)) {
                val stored = compact(id, incoming.copy(metadata = incoming.metadata.copy(
                    version = (head?.version ?: 0) + 1,
                    modified = clock.instant(),
                    modifiedBy = Canon.share("file"),
                )))
                val bytes = if (current == null) path.readBytes() else ResourceCodec.encodeYamlBytes(stored)
                remember(id, stored, bytes)
                emit(ConfigEvent(ChangeType.of(current, stored), id, path, stored, current, ChangeSource.FILE, null))
            }
            dirty += TagShards.refFor(id)
            consumed.add(path)
        }
        return consumed
    }

    private fun emit(e: ConfigEvent) = listeners.forEach { runCatching { it(e) } }

    fun get(id: ResourceId): Resource? = readIndex {
        heads[canon(id)]?.let { runCatching { materialize(it) }.getOrNull() }
    }

    /**
     * A full module/scope/kind key reads that bucket only. Copy the head references under the read lock and
     * decode outside it, so a zone lookup stays O(security docs) and does not sit behind a tag import's disk writes.
     */
    fun list(module: String? = null, scope: Scope? = null, kind: String? = null): List<StoredResource> {
        val matched = readIndex {
            if (module != null && scope != null && kind != null) {
                byKind[IndexKey(module, scope, kind)]?.toList() ?: emptyList()
            } else {
                heads.values.filter {
                    (module == null || it.id.module == module) && (scope == null || it.id.scope == scope) && (kind == null || it.id.kind == kind)
                }
            }
        }
        return matched.map { StoredResource(it.id, materialize(it)) }
    }

    /** Create or update. Returns the stored resource with its new version. */
    fun put(scope: Scope, resource: Resource, actor: String = "system"): Resource = mutate {
        putUnlocked(scope, resource, actor)
    }

    private fun putUnlocked(scope: Scope, resource: Resource, actor: String): Resource {
        val id = canon(resource.id(scope))
        validate(id, resource)
        checkGuards(listOf(id to resource))
        val prepared = writeIndex {
            val current = heads[id]
            val expected = resource.metadata.version
            when {
                current == null && expected != 0L -> throw ConflictException("$id does not exist (expected version $expected)")
                current != null && expected != current.version ->
                    throw ConflictException("$id version conflict: expected $expected, stored ${current.version}")
            }
            val stored = compact(id, resource.copy(metadata = resource.metadata.copy(version = expected + 1, modified = clock.instant(), modifiedBy = Canon.share(actor))))
            current to stored
        }
        val (current, stored) = prepared
        val previous = current?.let { runCatching { materialize(it) }.getOrNull() }
        val bytes = ResourceCodec.encodeYamlBytes(stored)
        if (id.kind == "Tag") writeShards(setOf(TagShards.refFor(id)), mapOf(id to bytes), emptySet(), sync = true, forceDir = false)
        else writeMany(listOf(singleFile(id)), listOf(bytes), true)
        val at = journal.append(bytes)
        writeIndex { remember(id, stored, bytes, at) }
        onChange(ChangeType.of(previous, stored), id, stored, previous, ChangeSource.API)
        return stored
    }

    /**
     * Create or update many resources. Versions are checked for the whole batch before any file is replaced.
     * [syncEach] fsyncs every file (the single-[put] durability). A bulk import sets it false and fsyncs each
     * parent directory once, so 100k tags do not wait on 100k disk flushes. Temp files for the batch are written
     * in parallel; [beforeCommit] runs on each temp before any rename, and a hook failure leaves every target
     * unchanged. The journal is appended with gathering writes and forced once.
     * The index write lock is not held across that I/O, so a get does not wait on the batch. Listeners still
     * run once per resource, after the files and the index are in place. Tag documents already on disk are
     * not read back: a later batch appends to that shard's segment file.
     */
    fun putAll(scope: Scope, resources: List<Resource>, actor: String = "system", syncEach: Boolean = true): PutAllResult = mutate {
        if (resources.isEmpty()) return PutAllResult(emptyList(), 0, 0)
        val now = clock.instant()
        val who = Canon.share(actor)
        val drafted = ArrayList<Triple<ResourceId, Resource, Head?>>(resources.size)
        for (resource in resources) {
            val id = canon(resource.id(scope))
            validate(id, resource)
            val current = readIndex { heads[id] }
            val expected = resource.metadata.version
            when {
                current == null && expected != 0L -> throw ConflictException("$id does not exist (expected version $expected)")
                current != null && expected != current.version ->
                    throw ConflictException("$id version conflict: expected $expected, stored ${current.version}")
            }
            drafted += Triple(id, resource, current)
        }
        if (guards.isNotEmpty()) checkGuards(drafted.map { it.first to it.second })
        val planned = ArrayList<Triple<ResourceId, Resource, Resource?>>(drafted.size)
        for ((id, resource, head) in drafted) {
            val stored = compact(id, resource.copy(metadata = resource.metadata.copy(version = resource.metadata.version + 1, modified = now, modifiedBy = who)))
            planned += Triple(id, stored, head?.let { runCatching { materialize(it) }.getOrNull() })
        }
        val payloads = ArrayList<ByteArray>(planned.size)
        val looseTargets = ArrayList<Path>()
        val loosePayloads = ArrayList<ByteArray>()
        val tagDocs = LinkedHashMap<ResourceId, ByteArray>()
        for ((id, stored, _) in planned) {
            val doc = ResourceCodec.encodeYamlBytes(stored)
            payloads.add(doc)
            if (id.kind == "Tag") tagDocs[id] = doc
            else {
                looseTargets.add(singleFile(id))
                loosePayloads.add(doc)
            }
        }
        val targets = ArrayList<Path>(looseTargets.size + 16)
        val filePayloads = ArrayList<ByteArray>(looseTargets.size + 16)
        targets.addAll(looseTargets)
        filePayloads.addAll(loosePayloads)
        val segments = ArrayList<SegmentAppend>()
        if (tagDocs.isNotEmpty()) {
            recoverPendings()
            for ((ref, entries) in tagDocs.entries.groupBy { TagShards.refFor(it.key) }) {
                val ordered = entries.sortedBy { it.key.name }
                val docs = ArrayList<ByteArray>(ordered.size)
                val overrides = LinkedHashMap<ResourceId, ByteArray>(ordered.size)
                for (entry in ordered) {
                    docs += entry.value
                    overrides[entry.key] = entry.value
                }
                val sortedPath = shardFile(ref)
                val segmentPath = segmentFile(ref)
                val sortedExists = Files.exists(sortedPath)
                val segmentExists = Files.exists(segmentPath)
                val existing = readIndex { byShard[ref]?.size ?: 0 }
                if (!sortedExists && !segmentExists && existing > 0) {
                    // The index still has tags whose files were removed out from under it. One full rewrite
                    // puts those bytes back. The bulk path below never does this.
                    targets.add(sortedPath)
                    filePayloads.add(TagShards.encode(ref.index, documentsInShard(ref, overrides, emptySet())))
                } else if (!sortedExists && !segmentExists) {
                    targets.add(sortedPath)
                    filePayloads.add(TagShards.encode(ref.index, docs))
                } else {
                    val oldSize = if (segmentExists) Files.size(segmentPath) else 0L
                    segments += SegmentAppend(ref, segmentPath, oldSize, TagShards.encodeSegment(ref.index, docs, header = oldSize == 0L))
                }
            }
        }
        val parents = HashSet<Path>()
        if (!syncEach) {
            targets.forEach { it.parent?.let(parents::add) }
            segments.forEach { it.segment.parent?.let(parents::add) }
        }
        writeMany(targets, filePayloads, syncEach, segments)
        if (!syncEach) parents.forEach { syncDir(it) }
        for (i in looseTargets.size until targets.size) markWritten(targets[i], filePayloads[i])
        val positions = journal.appendAll(payloads)
        journal.force()
        writeIndex {
            for (i in planned.indices) {
                val (id, stored, _) = planned[i]
                remember(id, stored, payloads[i], positions[i])
            }
        }
        var created = 0
        val stored = ArrayList<Resource>(planned.size)
        ConfigBatches.run(scope.path, actor) {
            for ((id, resource, previous) in planned) {
                if (previous == null) created++
                onChange(ChangeType.of(previous, resource), id, resource, previous, ChangeSource.API)
                stored += resource
            }
        }
        PutAllResult(stored, created, planned.size - created)
    }

    fun delete(id: ResourceId, expectedVersion: Long? = null) = mutate {
        val key = canon(id)
        val current = readIndex { heads[key] } ?: throw NoSuchElementException("$key not found")
        if (expectedVersion != null && expectedVersion != current.version)
            throw ConflictException("$key version conflict: expected $expectedVersion, stored ${current.version}")
        val previous = materialize(current)
        if (key.kind == "Tag") {
            // The shard loses this document before the index does, so a crash reloads the deletion.
            writeShards(setOf(TagShards.refFor(key)), emptyMap(), setOf(key), sync = true, forceDir = false)
        } else {
            val target = singleFile(key)
            target.deleteIfExists()
            pruneEmptyDirs(target.parent)
        }
        writeIndex {
            heads.remove(key)
            unindex(current)
            hotRemove(key)
            onChange(ChangeType.DELETED, key, null, previous, ChangeSource.API)
        }
    }

    /** Remove every resource of [module] and its directory `<root>/<module>/` (module uninstall). */
    @OptIn(ExperimentalPathApi::class)
    fun deleteModule(module: String) = mutate { writeIndex {
        val ids = heads.keys.filter { it.module == module }
        val dir = root.resolve(module).normalize(); require(dir.parent == root.normalize()) { "bad module id '$module'" }
        if (dir.exists()) dir.deleteRecursively()
        byKind.keys.removeIf { it.module == module }
        byShard.keys.removeIf { it.module == module }
        shardMarks.keys.removeIf { it.startsWith(dir) }
        ids.forEach { id ->
            val old = heads.remove(id)?.let { runCatching { materialize(it) }.getOrNull() }
            hotRemove(id)
            onChange(ChangeType.DELETED, id, null, old, ChangeSource.API)
        }
        errors.keys.removeIf { it.startsWith(dir) }
    } }

    /**
     * Swap the files under the config root, then rebuild the index from whatever is on disk.
     * [block] runs while the store lock is held and must not call back into the store (the lock is re-entrant,
     * but a nested write would be wiped by the rebuild). Non-tag files are not rewritten and versions are not
     * bumped, so a backup restore can put those bytes back. A restored one-file-per-tag tree is migrated into
     * shards, same as startup. If [block] throws, the index is still rebuilt.
     */
    fun replaceTree(block: () -> Unit) = mutate {
        // Windows cannot rename a directory while a file inside it is open. The journal lives under the
        // config root, so a restore has to drop that handle before the tree is swapped and open it again after.
        journal.release()
        try {
            block()
        } finally {
            journal.reopen()
            writeIndex { rebuildFromDisk() }
        }
    }

    /**
     * After the tree was replaced underneath the listeners (a live backup restore), tell them what changed against
     * [before]: CREATED, UPDATED (spec or labels differ), and DELETED events with source FILE, the same events a
     * [rescan] emits for external edits. Nothing is written.
     */
    fun announceReplaced(before: Map<ResourceId, Resource>) {
        val after = list().associate { it.id to it.resource }
        val events = ArrayList<ConfigEvent>()
        for ((id, next) in after) {
            val old = before[id]
            if (old == null) events += ConfigEvent(ChangeType.CREATED, id, file(id), next, null, ChangeSource.FILE, null)
            else if (old.spec != next.spec || old.metadata.labels != next.metadata.labels) {
                events += ConfigEvent(ChangeType.UPDATED, id, file(id), next, old, ChangeSource.FILE, null)
            }
        }
        for ((id, old) in before) {
            if (id !in after) events += ConfigEvent(ChangeType.DELETED, id, file(id), null, old, ChangeSource.FILE, null)
        }
        events.forEach { emit(it) }
    }

    /** Drop the index and rebuild it from disk without rewriting files or bumping versions. */
    fun reloadFromDisk() = mutate { writeIndex { rebuildFromDisk() } }

    private fun loadFromDisk() {
        journal.reset()
        shardMarks.clear()
        if (!root.exists()) return
        recoverPendings()
        val incoming = LinkedHashMap<ResourceId, Incoming>()
        val legacyOk = ArrayList<Path>()
        val dirty = HashSet<TagShards.Ref>()
        val segmentRefs = HashSet<TagShards.Ref>()
        val broken = HashSet<TagShards.Ref>()
        yamlFiles().forEach { path ->
            val relPath = rel(path)
            val ref = TagShards.refOf(relPath)
            if (ref != null) {
                if (TagShards.isSegmentPath(relPath)) segmentRefs += ref
                loadShardFile(path, ref, incoming, dirty, broken)
            } else loadLooseFile(path, incoming, legacyOk)
        }
        for (ref in segmentRefs) if (ref !in broken) dirty += ref
        for ((id, doc) in incoming) adopt(id, doc.resource, doc.bytes, emit = false)
        for (path in legacyOk) {
            val id = runCatching { canon(ResourceId.fromRelativePath(rel(path))) }.getOrNull() ?: continue
            if (id.kind == "Tag" && heads.containsKey(id)) dirty += TagShards.refFor(id)
        }
        if (dirty.isNotEmpty()) {
            val cached = HashMap<ResourceId, ByteArray>(incoming.size)
            for ((id, doc) in incoming) if (id.kind == "Tag") cached[id] = doc.bytes
            writeShards(dirty, emptyMap(), emptySet(), sync = true, forceDir = true, cached = cached)
            for (path in legacyOk) {
                if (errors[path] != null) continue
                path.deleteIfExists()
                pruneEmptyDirs(path.parent)
            }
        }
        remarkShards()
    }

    private fun loadLooseFile(path: Path, incoming: MutableMap<ResourceId, Incoming>, legacyOk: MutableList<Path>) {
        val parsed = runCatching { parseFile(path) }
        val stored = parsed.getOrElse { e ->
            errors[path] = e.message ?: e.toString()
            return
        }
        if (stored.id.kind == "Tag") legacyOk.add(path)
        offer(incoming, Incoming(path, path.readBytes(), stored.resource, fromShard = false))
    }

    private fun loadShardFile(
        path: Path,
        ref: TagShards.Ref,
        incoming: MutableMap<ResourceId, Incoming>,
        dirty: MutableSet<TagShards.Ref>,
        broken: MutableSet<TagShards.Ref>,
    ) {
        val segment = TagShards.isSegmentPath(rel(path))
        val text = runCatching { path.readText() }.getOrElse { e ->
            errors[path] = e.message ?: e.toString()
            broken += ref
            return
        }
        val docs = ResourceCodec.decodeDocuments(text)
        val seen = HashSet<String>()
        val failures = ArrayList<String>()
        var duplicate = false
        val relocate = HashSet<TagShards.Ref>()
        for (doc in docs) {
            if (doc.error != null || doc.resource == null) {
                failures += doc.error ?: "bad document"
                continue
            }
            val id = try {
                canon(doc.resource.id(ref.scope))
            } catch (e: IllegalArgumentException) {
                failures += e.message ?: e.toString()
                continue
            }
            if (id.module != ref.module || id.kind != "Tag" || id.scope != ref.scope) {
                failures += "document $id does not belong in ${rel(path)}"
                continue
            }
            val parsed = runCatching { parseText(id, doc.body) }
            if (parsed.isFailure) {
                failures += parsed.exceptionOrNull()?.message ?: "bad document"
                continue
            }
            if (!seen.add(id.name)) duplicate = true
            val correct = TagShards.refFor(id)
            if (correct != ref) relocate += correct
            val bytes = doc.body.toByteArray(Charsets.UTF_8)
            // A tie keeps the sorted shard. A segment loses the tie and wins only on a higher version.
            offer(incoming, Incoming(path, bytes, parsed.getOrThrow().resource, fromShard = !segment))
        }
        if (failures.isNotEmpty()) {
            errors[path] = failures.joinToString("; ")
            broken += ref
            return
        }
        errors.remove(path)
        if (duplicate || relocate.isNotEmpty()) {
            dirty += ref
            dirty.addAll(relocate)
        }
    }

    /** Higher version wins. A tie keeps the shard copy, which is the canonical file after migration. */
    private fun offer(incoming: MutableMap<ResourceId, Incoming>, candidate: Incoming) {
        val id = canon(candidate.resource.id(candidateScope(candidate)))
        val current = incoming[id]
        if (current == null || preferIncoming(candidate, current)) incoming[id] = candidate
    }

    private fun candidateScope(doc: Incoming): Scope {
        val relPath = rel(doc.path)
        val ref = TagShards.refOf(relPath)
        if (ref != null) return ref.scope
        return runCatching { ResourceId.fromRelativePath(relPath).scope }.getOrDefault(Scope.GATEWAY)
    }

    private fun preferIncoming(candidate: Incoming, current: Incoming): Boolean {
        val newer = candidate.resource.metadata.version
        val older = current.resource.metadata.version
        if (newer != older) return newer > older
        val cMod = candidate.resource.metadata.modified
        val oMod = current.resource.metadata.modified
        if (cMod != null && oMod != null && cMod != oMod) return cMod.isAfter(oMod)
        if (cMod != null && oMod == null) return true
        if (cMod == null && oMod != null) return false
        if (candidate.fromShard != current.fromShard) return candidate.fromShard
        return false
    }

    private fun rebuildFromDisk() {
        heads.clear()
        byKind.clear()
        byShard.clear()
        shardMarks.clear()
        hotClear()
        errors.clear()
        madeDirsClear()
        loadFromDisk()
    }

    /**
     * Re-validate a module's indexed resources (after its schemas were registered); invalid ones are dropped from the
     * index and reported. A kind with no schema yet is left as loaded, the same as at startup: a module registers its
     * kinds one at a time, and its manifest schema is revalidated before the driver registers `Device` (0.1.1 fix: a
     * Modbus device vanished after a restart).
     */
    fun revalidate(module: String): Map<ResourceId, String> = mutate { writeIndex {
        val bad = linkedMapOf<ResourceId, String>()
        heads.filterKeys { it.module == module && (it.scope.isOverlay || registry.isRegistered(it.module, it.kind)) }.forEach { (id, head) ->
            val r = runCatching { materialize(head) }.getOrElse { bad[id] = it.message ?: it.toString(); return@forEach }
            runCatching { validate(id, r) }.onFailure { bad[id] = it.message ?: it.toString() }
        }
        bad.forEach { (id, msg) ->
            val head = heads.remove(id)
            if (head != null) unindex(head)
            hotRemove(id)
            errors[file(id)] = msg
        }
        bad
    } }

    /** Active deployment mode (GAT-033), persisted as resource [GATEWAY_MODULE]/gateway/[DEPLOYMENT_MODE_KIND]/active; null = none. */
    fun activeMode(): String? = get(ACTIVE_MODE_ID)?.spec?.get("mode") as? String

    fun setActiveMode(mode: String?, actor: String = "system") = mutate {
        mode?.let { Scope.mode(it) }
        val version = readIndex { heads[canon(ACTIVE_MODE_ID)]?.version ?: 0L }
        putUnlocked(
            Scope.GATEWAY,
            Resource(DEPLOYMENT_MODE_KIND, Metadata(ACTIVE_MODE_ID.name, GATEWAY_MODULE, version = version), mapOf("mode" to mode)),
            actor,
        )
    }

    /** Deployment modes that hold at least one overlay resource, sorted. Reads the kind index, not every resource. */
    fun overlayModes(): List<String> = readIndex {
        byKind.filter { (key, heads) -> key.scope.isOverlay && heads.isNotEmpty() }.keys.mapNotNull { it.scope.qualifier }.distinct().sorted()
    }

    /**
     * Effective resource: the stored resource with the active mode's overlay (`<module>/mode/<mode>/<kind>/<name>.yaml`)
     * applied to its spec as an RFC 7386 merge patch, then validated. Overlays apply to gateway-scope resources only.
     */
    fun resolve(id: ResourceId, mode: String? = activeMode()): Resource? = readIndex {
        val key = canon(id)
        val baseHead = heads[key] ?: return null
        val base = materialize(baseHead)
        if (mode == null || key.scope != Scope.GATEWAY) return base
        val overlay = heads[canon(key.copy(scope = Scope.mode(mode)))] ?: return base
        @Suppress("UNCHECKED_CAST")
        val merged = base.copy(spec = JsonMergePatch.apply(base.spec, materialize(overlay).spec) as Map<String, Any?>)
        registry.check(merged, key.scope)
        merged
    }

    protected open fun onChange(type: ChangeType, id: ResourceId, resource: Resource?, previous: Resource?, source: ChangeSource) =
        emit(ConfigEvent(type, id, file(id), resource, previous, source, null))

    protected fun validate(id: ResourceId, r: Resource) {
        if (id.scope.isOverlay) { if (!registry.isRegistered(id.module, id.kind)) throw UnknownKindException(id.module, id.kind) }
        else registry.check(r, id.scope)
    }

    /** On-disk file that holds [id]. Tags return their shard file; every other kind returns its own YAML file. */
    fun file(id: ResourceId): Path = if (id.kind == "Tag") shardFile(TagShards.refFor(canon(id))) else singleFile(id)

    private fun singleFile(id: ResourceId): Path = root.resolve(id.relativePath)

    private fun shardFile(ref: TagShards.Ref): Path =
        root.resolve(ref.module).resolve(ref.scope.path).resolve("Tag").resolve(TagShards.DIR).resolve(TagShards.fileName(ref.index))

    private fun segmentFile(ref: TagShards.Ref): Path = shardFile(ref).resolveSibling(TagShards.segmentFileName(ref.index))

    protected fun write(id: ResourceId, r: Resource, sync: Boolean = true): ByteArray {
        val bytes = ResourceCodec.encodeYamlBytes(r)
        if (id.kind == "Tag") writeShards(setOf(TagShards.refFor(id)), mapOf(id to bytes), emptySet(), sync, forceDir = false)
        else writeMany(listOf(singleFile(id)), listOf(bytes), sync)
        return bytes
    }

    /**
     * Replace each shard in [keys] with the documents currently in the index, [overrides] winning over the
     * journal and [skip] left out. Empty shards are deleted. A failure before the renames leaves every
     * target unchanged.
     */
    private fun writeShards(
        keys: Set<TagShards.Ref>,
        overrides: Map<ResourceId, ByteArray>,
        skip: Set<ResourceId>,
        sync: Boolean,
        forceDir: Boolean,
        cached: Map<ResourceId, ByteArray> = emptyMap(),
    ) {
        if (keys.isEmpty()) return
        val targets = ArrayList<Path>()
        val payloads = ArrayList<ByteArray>()
        val remove = ArrayList<Path>()
        for (ref in keys) {
            val docs = documentsInShard(ref, overrides, skip, cached)
            val path = shardFile(ref)
            if (docs.isEmpty()) remove.add(path)
            else {
                targets.add(path)
                payloads.add(TagShards.encode(ref.index, docs))
            }
        }
        if (targets.isNotEmpty()) writeMany(targets, payloads, sync)
        for (path in remove) {
            path.deleteIfExists()
            shardMarks.remove(path)
        }
        // The sorted file now holds every indexed document. Drop the append segment only after that rename.
        for (ref in keys) {
            val seg = segmentFile(ref)
            if (seg.deleteIfExists()) {
                shardMarks.remove(seg)
                errors.remove(seg)
            }
        }
        for (path in remove) pruneEmptyDirs(path.parent)
        for (i in targets.indices) markWritten(targets[i], payloads[i])
        if (!sync || forceDir) {
            (targets.asSequence() + remove.asSequence()).mapNotNull { it.parent }.toSet().forEach { syncDir(it) }
        }
    }

    /** Document bytes for one shard, name order, so a one-tag edit diffs as one document. */
    private fun documentsInShard(
        ref: TagShards.Ref,
        overrides: Map<ResourceId, ByteArray>,
        skip: Set<ResourceId>,
        cached: Map<ResourceId, ByteArray> = emptyMap(),
    ): List<ByteArray> {
        val rows = ArrayList<Pair<String, ByteArray>>()
        val seen = HashSet<ResourceId>()
        val members = readIndex { byShard[ref]?.toList() ?: emptyList() }
        for (head in members) {
            if (head.id in skip) continue
            seen += head.id
            val bytes = overrides[head.id] ?: cached[head.id] ?: journal.read(head.snap().goodAt, head.snap().goodLen)
            rows += head.id.name to bytes
        }
        for ((id, bytes) in overrides) {
            if (id in seen || id in skip || TagShards.refFor(id) != ref) continue
            rows += id.name to bytes
        }
        rows.sortBy { it.first }
        return rows.map { it.second }
    }

    private fun markWritten(path: Path, bytes: ByteArray) {
        val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        val size = runCatching { Files.size(path) }.getOrDefault(bytes.size.toLong())
        shardMarks[path] = ShardMark(size, mtime)
    }

    private fun markMatches(path: Path): Boolean {
        val mark = shardMarks[path] ?: return false
        val size = runCatching { Files.size(path) }.getOrNull() ?: return false
        val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull() ?: return false
        return mark.size == size && mark.mtimeMillis == mtime
    }

    private fun remarkShards() {
        shardMarks.clear()
        if (!root.exists()) return
        yamlFiles().forEach { path ->
            if (TagShards.refOf(rel(path)) != null) {
                shardMarks[path] = ShardMark(Files.size(path), Files.getLastModifiedTime(path).toMillis())
            }
        }
    }

    /**
     * Stage every temp, run [beforeCommit] on each, then rename. A failure before the renames start deletes
     * the temps and does not change a target. Large batches stage and rename on [batchIo] threads.
     * [segments] append only their new documents, after the same hook barrier. A pending record is the
     * commit point for each append, so a crash mid-write is finished on the next open.
     */
    private fun writeMany(
        targets: List<Path>,
        payloads: List<ByteArray>,
        sync: Boolean,
        segments: List<SegmentAppend> = emptyList(),
    ) {
        require(targets.size == payloads.size)
        if (targets.isEmpty() && segments.isEmpty()) return
        for (target in targets) ensureParent(target)
        for (seg in segments) ensureParent(seg.segment)
        val tmps = arrayOfNulls<Path>(targets.size)
        val segTmps = arrayOfNulls<Path>(segments.size)
        try {
            val stageCount = targets.size + segments.size
            val stage = { i: Int ->
                if (i < targets.size) tmps[i] = stageTemp(targets[i], payloads[i], sync)
                else segTmps[i - targets.size] = stageTemp(segments[i - targets.size].segment, segments[i - targets.size].payload, sync)
            }
            if (stageCount >= PARALLEL_AT) parallel(stageCount, stage) else for (i in 0 until stageCount) stage(i)
            for (i in targets.indices) beforeCommit(tmps[i]!!)
            for (i in segments.indices) beforeCommit(segTmps[i]!!)
            val move = { i: Int ->
                Files.move(tmps[i]!!, targets[i], StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                tmps[i] = null
                noteTagBytes(targets[i], payloads[i].size)
            }
            if (targets.size >= PARALLEL_AT) parallel(targets.size, move) else for (i in targets.indices) move(i)
            val append = { i: Int ->
                publishSegment(segments[i], segTmps[i]!!, sync)
                segTmps[i] = null
            }
            if (segments.size >= PARALLEL_AT) parallel(segments.size, append) else for (i in segments.indices) append(i)
            for (seg in segments) markWritten(seg.segment, seg.payload)
        } catch (t: Throwable) {
            for (tmp in tmps) if (tmp != null) runCatching { tmp.deleteIfExists() }
            for (tmp in segTmps) if (tmp != null) runCatching { tmp.deleteIfExists() }
            throw t
        }
    }

    /** Install the pending record, then append [seg] onto its segment file. Hook already ran on [payloadTmp]. */
    private fun publishSegment(seg: SegmentAppend, payloadTmp: Path, sync: Boolean) {
        val pending = seg.segment.resolveSibling(TagShards.pendingFileName(seg.ref.index))
        val pendingTmp = seg.segment.resolveSibling(".${seg.segment.fileName}.${tmpNames.incrementAndGet()}.tmp")
        try {
            writeTemp(pendingTmp, TagShards.encodePending(seg.oldSize, seg.payload), sync)
            Files.move(pendingTmp, pending, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            runCatching { pendingTmp.deleteIfExists() }
            throw t
        }
        runCatching { payloadTmp.deleteIfExists() }
        try {
            applySegmentPending(pending, seg.segment, sync)
        } catch (t: Throwable) {
            runCatching { rollbackSegment(seg.segment, seg.oldSize, pending) }
            throw t
        }
        noteTagBytes(seg.segment, seg.payload.size)
    }

    /**
     * Finish a segment append that was committed (the pending file is durable) but not necessarily
     * fully written. Idempotent: the segment is truncated back to the recorded offset and the payload rewritten.
     */
    private fun applySegmentPending(pending: Path, segment: Path, sync: Boolean) {
        val decoded = TagShards.decodePending(pending.readBytes())
            ?: throw IOException("bad tag segment pending ${pending.fileName}")
        val (oldSize, payload) = decoded
        ensureParent(segment)
        FileChannel.open(segment, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
            if (ch.size() < oldSize) throw IOException("tag segment ${segment.fileName} is shorter than its pending record ($oldSize)")
            ch.truncate(oldSize)
            ch.position(oldSize)
            val buf = ByteBuffer.wrap(payload)
            while (buf.hasRemaining()) {
                val n = ch.write(buf)
                if (n < 0) throw IOException("tag segment append wrote nothing")
            }
            if (sync) ch.force(true)
        }
        if (!pending.deleteIfExists()) throw IOException("could not remove $pending")
    }

    private fun rollbackSegment(segment: Path, oldSize: Long, pending: Path) {
        if (oldSize == 0L) segment.deleteIfExists()
        else if (Files.exists(segment)) {
            FileChannel.open(segment, StandardOpenOption.WRITE).use { ch ->
                if (ch.size() > oldSize) ch.truncate(oldSize)
            }
        }
        pending.deleteIfExists()
    }

    /** Complete crashed segment appends. Pending files are not YAML, so the document walk skips them. */
    private fun recoverPendings() {
        if (!root.exists()) return
        val pendings = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".seg.pending") }.toList()
        }
        for (pending in pendings) {
            val segment = pending.resolveSibling(pending.fileName.toString().removeSuffix(".pending") + ".yaml")
            applySegmentPending(pending, segment, sync = true)
            segment.parent?.let { syncDir(it) }
        }
    }

    private fun noteTagBytes(path: Path, n: Int) {
        val parent = path.parent?.fileName?.toString() ?: return
        val name = path.fileName.toString()
        if (parent == TagShards.DIR && (TagShards.isShardFileName(name) || TagShards.isSegmentFileName(name))) {
            tagBytesWritten.addAndGet(n.toLong())
        }
    }

    private fun stageTemp(target: Path, bytes: ByteArray, sync: Boolean): Path {
        val tmp = target.resolveSibling(".${target.name}.${tmpNames.incrementAndGet()}.tmp")
        try {
            writeTemp(tmp, bytes, sync)
            return tmp
        } catch (t: Throwable) {
            runCatching { tmp.deleteIfExists() }
            throw t
        }
    }

    private fun parallel(n: Int, block: (Int) -> Unit) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val error = AtomicReference<Throwable>(null)
        val workers = threads.coerceAtMost(n)
        val latch = CountDownLatch(workers)
        val chunk = (n + workers - 1) / workers
        for (t in 0 until workers) {
            val from = t * chunk
            val to = minOf(n, from + chunk)
            if (from >= to) {
                latch.countDown()
                continue
            }
            batchIo.execute {
                try {
                    for (i in from until to) {
                        if (error.get() != null) return@execute
                        try {
                            block(i)
                        } catch (e: Throwable) {
                            error.compareAndSet(null, e)
                            return@execute
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted during batch write", e)
        }
        error.get()?.let { throw it }
    }

    private fun writeTemp(tmp: Path, bytes: ByteArray, sync: Boolean) {
        try {
            writeChannel(tmp, bytes, sync)
        } catch (e: java.nio.file.NoSuchFileException) {
            tmp.parent?.let { parent -> synchronized(madeDirs) { madeDirs.remove(parent) } }
            ensureParent(tmp)
            writeChannel(tmp, bytes, sync)
        }
    }

    private fun writeChannel(tmp: Path, bytes: ByteArray, sync: Boolean) {
        ensureParent(tmp)
        FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
            ch.write(ByteBuffer.wrap(bytes))
            if (sync) ch.force(true)
        }
    }

    private fun ensureParent(target: Path) {
        val parent = target.parent ?: return
        synchronized(madeDirs) {
            if (parent in madeDirs) return
            parent.createDirectories()
            madeDirs.add(parent)
        }
    }

    private fun madeDirsClear() {
        synchronized(madeDirs) { madeDirs.clear() }
    }

    private fun syncDir(dir: Path) {
        runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
    }

    /** Parse every resource file on disk (temp/hidden files skipped). */
    protected fun scanDisk(): Map<Path, Result<StoredResource>> {
        if (!root.exists()) return emptyMap()
        val out = linkedMapOf<Path, Result<StoredResource>>()
        yamlFiles().forEach { p ->
            if (TagShards.refOf(rel(p)) != null) return@forEach
            out[p] = runCatching { parseFile(p) }
        }
        return out
    }

    private fun yamlFiles(): List<Path> {
        if (!root.exists()) return emptyList()
        return Files.walk(root).use { s ->
            s.filter { it.isRegularFile() && it.name.endsWith(".yaml") && !it.name.startsWith(".") }.sorted().toList()
        }
    }

    protected fun parseFile(p: Path): StoredResource {
        val id = try { canon(ResourceId.fromRelativePath(root.relativize(p).toString().let { if (it.indexOf('\\') < 0) it else it.replace('\\', '/') })) }
        catch (e: IllegalArgumentException) { throw ResourceFormatException("bad resource path: ${e.message}") }
        return parseText(id, p.readText())
    }

    private fun parseText(id: ResourceId, text: String): StoredResource {
        val r = try { ResourceCodec.decode(text, Format.YAML) } catch (e: IOException) { throw ResourceFormatException("unreadable: ${e.message}") }
        if (r.id(id.scope) != id) throw ResourceFormatException("content (${r.id(id.scope)}) does not match file location ($id)")
        if (registry.isRegistered(id.module, id.kind) || id.scope.isOverlay) validate(id, r)
        return StoredResource(id, r)
    }

    private fun rel(p: Path): String {
        val raw = root.relativize(p).toString()
        return if (raw.indexOf('\\') < 0) raw else raw.replace('\\', '/')
    }

    private fun adopt(id: ResourceId, resource: Resource, bytes: ByteArray, emit: Boolean) {
        val key = canon(id)
        val stored = compact(key, resource)
        remember(key, stored, bytes)
        if (emit) onChange(ChangeType.CREATED, key, stored, null, ChangeSource.FILE)
    }

    private fun remember(id: ResourceId, resource: Resource, bytes: ByteArray, at: Long = -1L) {
        val pos = if (at >= 0) at else journal.append(bytes)
        val mark = stamp(bytes.toString(Charsets.UTF_8))
        val head = heads[id]
        if (head == null) {
            val created = Head(id, resource.metadata.version, mark.a, mark.b, mark.length, pos, bytes.size)
            heads[id] = created
            bucket(id).add(created)
            if (id.kind == "Tag") shardBucket(id).add(created)
        } else {
            head.update(resource.metadata.version, mark, pos, bytes.size)
        }
        rememberHot(id, resource)
    }

    private fun shardBucket(id: ResourceId): ArrayList<Head> =
        byShard.getOrPut(TagShards.refFor(id)) { ArrayList() }

    private fun bucket(id: ResourceId): ArrayList<Head> {
        val key = IndexKey(id.module, id.scope, id.kind)
        return byKind.getOrPut(key) { ArrayList() }
    }

    private fun unindex(head: Head) {
        val key = IndexKey(head.id.module, head.id.scope, head.id.kind)
        val list = byKind[key]
        if (list != null) {
            list.remove(head)
            if (list.isEmpty()) byKind.remove(key)
        }
        if (head.id.kind == "Tag") {
            val shard = TagShards.refFor(head.id)
            val docs = byShard[shard] ?: return
            docs.remove(head)
            if (docs.isEmpty()) byShard.remove(shard)
        }
    }

    private fun materialize(head: Head): Resource {
        synchronized(hot) { hot[head.id] }?.let { return it }
        val snap = head.snap()
        // A shard file is many documents, so its bytes never match one tag's stamp. Read the journal.
        if (head.id.kind != "Tag") {
            val path = singleFile(head.id)
            if (path.exists()) {
                val text = runCatching { path.readText() }.getOrNull()
                if (text != null && snap.same(stamp(text))) {
                    val parsed = runCatching { parseText(head.id, text).resource }.getOrNull()
                    if (parsed != null) {
                        rememberHot(head.id, parsed)
                        return parsed
                    }
                }
            }
        }
        val bytes = journal.read(snap.goodAt, snap.goodLen)
        val parsed = parseText(head.id, bytes.toString(Charsets.UTF_8)).resource
        rememberHot(head.id, parsed)
        return parsed
    }

    private fun rememberHot(id: ResourceId, resource: Resource) = synchronized(hot) {
        if (id.kind == "Tag") hot.remove(id) else hot[id] = resource
    }

    private fun hotRemove(id: ResourceId) = synchronized(hot) { hot.remove(id) }

    private fun hotClear() = synchronized(hot) { hot.clear() }

    private fun canon(id: ResourceId): ResourceId {
        val scope = if (id.scope.type == "gateway" && id.scope.qualifier == null) Scope.GATEWAY else id.scope
        val module = Canon.share(id.module)
        val kind = Canon.share(id.kind)
        val name = Canon.share(id.name)
        if (module === id.module && kind === id.kind && name === id.name && scope === id.scope) return id
        return ResourceId(module, scope, kind, name)
    }

    /** 128-bit FNV-style fingerprint plus length. Not a cryptographic hash. */
    private fun stamp(text: String): ContentStamp {
        var a = -0x340d631b7bdddcdbL
        var b = 0x100000001b3L
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i].code.toLong()
            a = (a xor c) * 0x100000001b3L
            b = (b xor (c + 0x9E3779B97F4A7C15uL.toLong())) * 0xC6A4A7935BD1E995uL.toLong()
            i++
        }
        return ContentStamp(a, b xor (n.toLong() shl 32), n)
    }

    /** Drop the per-key entry objects and share the short strings every tag repeats. */
    private fun compact(id: ResourceId, resource: Resource): Resource {
        val spec = SmallMaps.compact(resource.spec)
        val module = Canon.share(resource.metadata.module)
        val kind = Canon.share(resource.kind)
        val name = Canon.share(resource.metadata.name)
        val actor = resource.metadata.modifiedBy?.let { Canon.share(it) }
        if (spec === resource.spec && module === resource.metadata.module && kind === resource.kind &&
            name === resource.metadata.name && actor == resource.metadata.modifiedBy
        ) return resource
        return resource.copy(kind = kind, metadata = resource.metadata.copy(name = name, module = module, modifiedBy = actor), spec = spec)
    }

    private fun pruneEmptyDirs(start: Path) {
        var d: Path? = start
        while (d != null && d != root && d.startsWith(root) && d.isDirectory() && d.listDirectoryEntries().isEmpty()) {
            synchronized(madeDirs) { madeDirs.remove(d) }
            d.deleteExisting()
            d = d.parent
        }
    }

    private class Incoming(val path: Path, val bytes: ByteArray, val resource: Resource, val fromShard: Boolean)

    private data class ShardMark(val size: Long, val mtimeMillis: Long)

    private data class ShardDoc(val id: ResourceId, val resource: Resource)

    private class SegmentAppend(val ref: TagShards.Ref, val segment: Path, val oldSize: Long, val payload: ByteArray)

    private class Head(
        val id: ResourceId,
        var version: Long,
        var stampA: Long,
        var stampB: Long,
        var stampLen: Int,
        var goodAt: Long,
        var goodLen: Int,
    ) {
        fun sameStamp(stamp: ContentStamp) = synchronized(this) {
            stampA == stamp.a && stampB == stamp.b && stampLen == stamp.length
        }

        fun setStamp(stamp: ContentStamp) = synchronized(this) {
            stampA = stamp.a
            stampB = stamp.b
            stampLen = stamp.length
        }

        fun update(version: Long, mark: ContentStamp, at: Long, size: Int) = synchronized(this) {
            this.version = version
            stampA = mark.a
            stampB = mark.b
            stampLen = mark.length
            goodAt = at
            goodLen = size
        }

        /** Consistent view for a reader that does not hold the index lock across file I/O. */
        fun snap() = synchronized(this) { HeadSnap(stampA, stampB, stampLen, goodAt, goodLen) }
    }

    private data class HeadSnap(val stampA: Long, val stampB: Long, val stampLen: Int, val goodAt: Long, val goodLen: Int) {
        fun same(stamp: ContentStamp) = stampA == stamp.a && stampB == stamp.b && stampLen == stamp.length
    }

    /** Last-good document bytes. Hidden, so backup and the YAML walk skip it. */
    private class GoodLog(private val path: Path) : AutoCloseable {
        private var ch: FileChannel = openChannel()

        private fun openChannel(): FileChannel {
            path.parent?.createDirectories()
            return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        }

        /** Drop the file handle so the config directory can be renamed. */
        fun release() {
            if (ch.isOpen) ch.close()
        }

        /** Open the journal again after the tree is back in place. */
        fun reopen() {
            if (!ch.isOpen) ch = openChannel()
        }

        fun reset() {
            ch.truncate(0)
            ch.position(0)
        }

        fun append(bytes: ByteArray): Long = appendAll(listOf(bytes))[0]

        /** One gathering write per group, instead of a positioned write per document. */
        fun appendAll(parts: List<ByteArray>): LongArray {
            val offsets = LongArray(parts.size)
            if (parts.isEmpty()) return offsets
            var pos = ch.size()
            var index = 0
            while (index < parts.size) {
                val end = minOf(parts.size, index + GATHER)
                val bufs = Array(end - index) { ByteBuffer.wrap(parts[index + it]) }
                offsets[index] = pos
                for (k in 1 until bufs.size) offsets[index + k] = offsets[index + k - 1] + parts[index + k - 1].size
                val total = (index until end).sumOf { parts[it].size.toLong() }
                ch.position(pos)
                var written = 0L
                while (written < total) {
                    val n = ch.write(bufs, 0, bufs.size)
                    if (n <= 0) throw IOException("journal append wrote nothing")
                    written += n
                }
                pos += total
                index = end
            }
            return offsets
        }

        val reads = AtomicLong()

        fun read(pos: Long, len: Int): ByteArray {
            reads.incrementAndGet()
            val buf = ByteBuffer.allocate(len)
            var at = pos
            while (buf.hasRemaining()) {
                val n = ch.read(buf, at)
                if (n < 0) break
                at += n
            }
            buf.flip()
            val out = ByteArray(buf.remaining())
            buf.get(out)
            return out
        }

        fun force() { runCatching { ch.force(false) } }

        override fun close() { runCatching { ch.close() } }

        private companion object {
            /** Below Linux IOV_MAX (1024) so one write is one syscall. */
            const val GATHER = 256
        }
    }

    private data class IndexKey(val module: String, val scope: Scope, val kind: String)

    companion object {
        private const val PARALLEL_AT = 32
        private val batchIo: ExecutorService by lazy {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            Executors.newFixedThreadPool(threads) { r ->
                Thread(r, "config-batch-io").apply { isDaemon = true }
            }
        }
    }
}

private const val HOT_LIMIT = 64

private data class ContentStamp(val a: Long, val b: Long, val length: Int)

data class PutAllResult(val stored: List<Resource>, val created: Int, val updated: Int)

const val GATEWAY_MODULE = "org.oip.gateway"
const val DEPLOYMENT_MODE_KIND = "DeploymentMode"
val ACTIVE_MODE_ID = ResourceId(GATEWAY_MODULE, Scope.GATEWAY, DEPLOYMENT_MODE_KIND, "active")

enum class ChangeSource { API, FILE }
enum class ChangeType { CREATED, UPDATED, DELETED, INVALID;
    companion object { fun of(prev: Resource?, @Suppress("UNUSED_PARAMETER") next: Resource) = if (prev == null) CREATED else UPDATED } }

/** Config change event. [id] may be null for INVALID events on files whose path is not a valid resource path. */
data class ConfigEvent(val type: ChangeType, val id: ResourceId?, val path: Path, val resource: Resource?,
                       val previous: Resource?, val source: ChangeSource, val error: String?)
