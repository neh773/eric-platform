package org.oip.opcua.server

import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.AccessContext
import org.eclipse.milo.opcua.sdk.server.AddressSpace
import org.eclipse.milo.opcua.sdk.server.AddressSpaceFilter
import org.eclipse.milo.opcua.sdk.server.AddressSpaceFragment
import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.Session
import org.eclipse.milo.opcua.sdk.server.SimpleAddressSpaceFilter
import org.eclipse.milo.opcua.sdk.server.items.DataItem
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue
import org.oip.security.AuditContext
import org.oip.security.SecurityZone
import org.oip.tags.BrowseNode
import org.oip.tags.DataType
import org.oip.tags.NodeKind
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import org.oip.tags.TagEngine
import org.oip.tags.TagSample
import java.util.concurrent.ConcurrentHashMap

/**
 * Tag providers as an OPC UA address space. Objects organizes a `Tags` folder; each provider is a
 * folder under that; folders and tags follow the tag path.
 *
 * Whether a node is a variable, and its data type, is cached per node until the tag tree changes.
 * A read or a subscription does not browse the parent folder. Values come from a [TagSample]
 * resolved once, so the read path does not parse the tag path or take the config lock.
 *
 * Reads, writes and monitored items act as the session's user ([callerOf]): a tag whose read permissions deny
 * that user reads as `Bad_UserAccessDenied` with no value, and a write the tag's permissions deny is refused
 * with the same status and audited (TAG-013). A user without the gateway `tags.write` permission may write no
 * tag at all (refused the same way and audited) and sees UserAccessLevel without CurrentWrite (review-2 F7). For such a tag only the attributes a browse needs (NodeId,
 * NodeClass, BrowseName, DisplayName, and the constant Description and write masks) answer; DataType, ValueRank,
 * AccessLevel and every other variable attribute are `Bad_UserAccessDenied` too, so its type does not leak (S-1).
 * The check is [TagSample.mayRead], the same decision as [TagEngine.mayRead] and the REST tag routes.
 *
 */
internal class TagAddressSpace(
    private val server: OpcUaServer,
    private val engine: TagEngine,
) : AddressSpaceFragment, AutoCloseable {
    val namespaceIndex = server.namespaceTable.add(TagNodeIds.NAMESPACE_URI)
    private val filter: AddressSpaceFilter = SimpleAddressSpaceFilter.create { nodeId ->
        nodeId.namespaceIndex == namespaceIndex
    }
    private val monitors = TagMonitors(engine, ::readItem, ::itemCaller) { tag, caller -> sampleOf(tag).mayRead(caller) }
    internal val deliveryStripes: Int get() = monitors.stripes
    private val samples = ConcurrentHashMap<String, SampleEntry>()
    private val nodeMeta = ConcurrentHashMap<String, NodeMeta>()

    override fun getFilter(): AddressSpaceFilter = filter

    override fun close() = monitors.close()

    fun nodeId(identifier: String): NodeId = NodeId(namespaceIndex, identifier)

    override fun browse(
        context: AddressSpace.BrowseContext,
        view: ViewDescription,
        nodeIds: MutableList<NodeId>,
    ): MutableList<AddressSpace.ReferenceResult> {
        val caller = callerOf(context)
        val results = ArrayList<AddressSpace.ReferenceResult>(nodeIds.size)
        for (nodeId in nodeIds) results += browseOne(nodeId, caller)
        return results
    }

    override fun gather(
        context: AddressSpace.BrowseContext,
        view: ViewDescription,
        nodeId: NodeId,
    ): AddressSpace.ReferenceResult.ReferenceList {
        if (nodeId == NodeIds.ObjectsFolder) {
            return AddressSpace.ReferenceResult.of(listOf(organizes(NodeIds.ObjectsFolder, nodeId(TagNodeIds.ROOT))))
        }
        return AddressSpace.ReferenceResult.of(emptyList())
    }

    override fun read(
        context: AddressSpace.ReadContext,
        maxAge: Double,
        timestamps: TimestampsToReturn,
        readValueIds: MutableList<ReadValueId>,
    ): MutableList<DataValue> {
        val caller = callerOf(context)
        val results = ArrayList<DataValue>(readValueIds.size)
        for (id in readValueIds) results += readOne(id, caller)
        return results
    }

    override fun write(
        context: AddressSpace.WriteContext,
        writeValues: MutableList<WriteValue>,
    ): MutableList<StatusCode> {
        val caller = callerOf(context)
        val results = ArrayList<StatusCode>(writeValues.size)
        for (write in writeValues) results += writeOne(write, caller)
        return results
    }

    override fun onCreateDataItem(
        itemToMonitor: ReadValueId,
        requestedSamplingInterval: Double,
        requestedQueueSize: UInteger,
    ): AddressSpace.RevisedDataItemParameters =
        AddressSpace.RevisedDataItemParameters(requestedSamplingInterval, requestedQueueSize)

    override fun onModifyDataItem(
        itemToModify: ReadValueId,
        requestedSamplingInterval: Double,
        requestedQueueSize: UInteger,
    ): AddressSpace.RevisedDataItemParameters =
        AddressSpace.RevisedDataItemParameters(requestedSamplingInterval, requestedQueueSize)

    override fun onDataItemsCreated(dataItems: MutableList<DataItem>) = monitors.created(dataItems)

    override fun onDataItemsModified(dataItems: MutableList<DataItem>) = monitors.modified(dataItems)

    override fun onDataItemsDeleted(dataItems: MutableList<DataItem>) = monitors.deleted(dataItems)

    override fun onMonitoringModeChanged(monitoredItems: MutableList<MonitoredItem>) = monitors.modeChanged(monitoredItems)

    /**
     * Children come from [TagEngine.browse] as [caller], the same call the REST browse makes. A tag the caller may
     * not read is still listed (names and kinds are structure); its attributes are guarded in [readOne].
     */
    private fun browseOne(nodeId: NodeId, caller: TagCaller): AddressSpace.ReferenceResult {
        val loc = location(nodeId) ?: return AddressSpace.ReferenceResult.unknown()
        if (!exists(loc)) return AddressSpace.ReferenceResult.unknown()
        val refs = ArrayList<Reference>()
        refs += Reference(nodeId, NodeIds.HasTypeDefinition, typeDefinition(loc).expanded(), true)
        when (loc) {
            Loc.Root -> engine.providers().forEach { provider ->
                refs += organizes(nodeId, nodeId(TagNodeIds.provider(provider.name)))
            }
            is Loc.Provider -> children(loc.name, "", caller).forEach { child ->
                refs += organizes(nodeId, childId(loc.name, child))
            }
            is Loc.Tag -> if (!isVariable(loc)) {
                children(loc.provider, loc.path, caller).forEach { child ->
                    refs += organizes(nodeId, childId(loc.provider, child))
                }
            }
        }
        return AddressSpace.ReferenceResult.of(refs)
    }

    private fun readOne(id: ReadValueId, caller: TagCaller): DataValue {
        val range = id.indexRange
        if (range != null && range.isNotEmpty()) return error(StatusCodes.Bad_IndexRangeNoData)
        val loc = location(id.nodeId) ?: return error(StatusCodes.Bad_NodeIdUnknown)
        if (!exists(loc)) return error(StatusCodes.Bad_NodeIdUnknown)
        val attribute = AttributeId.from(id.attributeId).orElse(null) ?: return error(StatusCodes.Bad_AttributeIdInvalid)
        if (attribute !in OPEN_ATTRIBUTES && caller is TagCaller.User && loc is Loc.Tag && isVariable(loc) &&
            !sampleOf(loc).mayRead(caller)
        ) {
            return error(StatusCodes.Bad_UserAccessDenied)
        }
        val value = attributeValue(loc, attribute, caller) ?: return error(StatusCodes.Bad_AttributeIdInvalid)
        return if (attribute == AttributeId.Value && value is QualifiedHolder) value.data else DataValue(Variant(value))
    }

    private fun writeOne(write: WriteValue, caller: TagCaller): StatusCode {
        val loc = location(write.nodeId) ?: return StatusCode(StatusCodes.Bad_NodeIdUnknown)
        if (!exists(loc)) return StatusCode(StatusCodes.Bad_NodeIdUnknown)
        val attribute = AttributeId.from(write.attributeId).orElse(null)
        if (attribute != AttributeId.Value) return StatusCode(StatusCodes.Bad_NotWritable)
        val tag = loc as? Loc.Tag ?: return StatusCode(StatusCodes.Bad_NotWritable)
        if (!isVariable(tag)) return StatusCode(StatusCodes.Bad_NotWritable)
        val provider = engine.provider(tag.provider)
        if (provider == null || provider.readOnly) return StatusCode(StatusCodes.Bad_UserAccessDenied)
        val value = TagValues.fromOpc(write.value?.value?.value)
        val result = if (caller is TagCaller.User) {
            AuditContext.call(caller.actor, caller.host, AUDIT_CONTEXT) { engine.write(tag.address(), value, caller) }
        } else {
            engine.write(tag.address(), value, caller)
        }
        return TagValues.status(result.quality)
    }

    /**
     * Writes the SDK refused before [write] saw them (UserAccessLevel without CurrentWrite, review-2 F7). For a
     * session user without `tags.write` each such write goes to [TagEngine.write] as that user, which refuses it
     * without touching the tag and records the refusal in the audit log, as [writeOne] would have.
     */
    internal fun auditRefused(session: Session, writes: List<WriteValue>, results: List<StatusCode>) {
        val caller = callerOf(session) as? TagCaller.User ?: return
        if (caller.tagWrite) return
        for ((i, write) in writes.withIndex()) {
            if (results[i].value != StatusCodes.Bad_UserAccessDenied) continue
            if (AttributeId.from(write.attributeId).orElse(null) != AttributeId.Value) continue
            val tag = location(write.nodeId) as? Loc.Tag ?: continue
            if (!exists(tag) || !isVariable(tag)) continue
            val value = TagValues.fromOpc(write.value?.value?.value)
            AuditContext.call(caller.actor, caller.host, AUDIT_CONTEXT) { engine.write(tag.address(), value, caller) }
        }
    }

    private fun readItem(item: DataItem): DataValue = readOne(item.readValueId, itemCaller(item))

    /** A monitored item always belongs to a client session; one without a session gets no roles (fail closed). */
    private fun itemCaller(item: DataItem): TagCaller =
        item.session?.let { callerOf(it) } ?: TagCaller.User("opcua-session", emptySet(), SecurityZone.DEFAULT_NAME, tagWrite = false)

    /** The user a request runs as. No session is the server's own internal access. */
    private fun callerOf(context: AccessContext): TagCaller = callerOf(context.session.orElse(null))

    /**
     * The caller [OpcUaUserAuthenticator] attached to the session's identity. A session without one (it cannot
     * activate without a username token today) holds no roles, is in the default zone and may not write (no
     * `tags.write`), so it reads only tags that do not restrict readers.
     */
    internal fun callerOf(session: Session?): TagCaller {
        if (session == null) return TagCaller.System
        val identity = session.identity
        (identity?.userData as? TagCaller.User)?.let { return it }
        val name = session.clientUserId?.takeIf { it.isNotEmpty() } ?: "opcua-session"
        return TagCaller.User(name, emptySet(), SecurityZone.DEFAULT_NAME, session.clientAddress?.hostAddress ?: "", tagWrite = false)
    }

    private fun attributeValue(loc: Loc, attribute: AttributeId, caller: TagCaller): Any? {
        val variable = loc is Loc.Tag && isVariable(loc)
        return when (attribute) {
            AttributeId.NodeId -> nodeId(identifier(loc))
            AttributeId.NodeClass -> if (variable) NodeClass.Variable else NodeClass.Object
            AttributeId.BrowseName -> QualifiedName(namespaceIndex, displayName(loc))
            AttributeId.DisplayName -> LocalizedText.english(displayName(loc))
            AttributeId.Description -> LocalizedText.NULL_VALUE
            AttributeId.WriteMask, AttributeId.UserWriteMask -> uint(0)
            AttributeId.EventNotifier -> if (variable) null else ubyte(0)
            AttributeId.Value -> if (variable) QualifiedHolder(currentValue(loc as Loc.Tag, caller)) else null
            AttributeId.DataType -> if (variable) dataTypeId(loc as Loc.Tag) else null
            AttributeId.ValueRank -> if (variable) -1 else null
            AttributeId.ArrayDimensions -> if (variable) null else null
            AttributeId.AccessLevel -> if (variable) TagValues.accessLevel(writable(loc as Loc.Tag)) else null
            // A session whose user lacks tags.write sees no CurrentWrite (review-2 F7); the write itself is refused
            // by TagEngine.write with Bad_UserAccessDenied.
            AttributeId.UserAccessLevel ->
                if (variable) TagValues.accessLevel(writable(loc as Loc.Tag) && mayWriteTags(caller)) else null
            AttributeId.MinimumSamplingInterval -> if (variable) 0.0 else null
            AttributeId.Historizing -> if (variable) false else null
            else -> null
        }
    }

    private fun currentValue(tag: Loc.Tag, caller: TagCaller): DataValue {
        val qv = sampleOf(tag).read(caller)
        if (qv.quality.code == QualityCode.BAD_NOT_FOUND) return error(StatusCodes.Bad_NodeIdUnknown)
        return TagValues.dataValue(qv)
    }

    private fun mayWriteTags(caller: TagCaller): Boolean = caller !is TagCaller.User || caller.tagWrite

    private fun writable(tag: Loc.Tag): Boolean {
        val provider = engine.provider(tag.provider) ?: return false
        return !provider.readOnly && isVariable(tag)
    }

    private fun isVariable(tag: Loc.Tag): Boolean = metaOf(tag)?.kind == NodeKind.TAG

    private fun dataTypeId(tag: Loc.Tag): NodeId {
        val type = metaOf(tag)?.dataType ?: TagValues.inferredType(sampleOf(tag).read().value)
        return when (type) {
            DataType.INT32 -> NodeIds.Int32
            DataType.INT64 -> NodeIds.Int64
            DataType.FLOAT -> NodeIds.Float
            DataType.DOUBLE -> NodeIds.Double
            DataType.BOOLEAN -> NodeIds.Boolean
            DataType.STRING, DataType.DOCUMENT, DataType.DATASET -> NodeIds.String
            DataType.DATETIME -> NodeIds.DateTime
            null -> NodeIds.BaseDataType
        }
    }

    private fun exists(loc: Loc): Boolean = when (loc) {
        Loc.Root -> true
        is Loc.Provider -> engine.provider(loc.name) != null
        is Loc.Tag -> metaOf(loc) != null
    }

    private fun children(provider: String, path: String, caller: TagCaller): List<BrowseNode> {
        val text = if (path.isEmpty()) "[$provider]" else "[$provider]$path"
        val out = ArrayList<BrowseNode>()
        var offset = 0
        while (true) {
            val page = engine.browse(text, offset, BROWSE_PAGE, caller)
            if (page.isEmpty()) break
            out.addAll(page)
            if (page.size < BROWSE_PAGE) break
            offset += page.size
        }
        return out
    }

    /**
     * The tag's resolved sample, reopened when [TagEngine.structureEpoch] moves (as [metaOf] does), so a tag that
     * was deleted and re-created, or whose configuration was replaced, is not read through its old node.
     */
    private fun sampleOf(tag: Loc.Tag): TagSample {
        val epoch = engine.structureEpoch()
        val key = tag.address()
        val cached = samples[key]
        if (cached != null && cached.epoch == epoch) return cached.sample
        val opened = SampleEntry(epoch, engine.openSample(key))
        samples[key] = opened
        return opened.sample
    }

    /**
     * One node, not the parent's whole child list. Invalidated when [TagEngine.structureEpoch] moves.
     */
    private fun metaOf(tag: Loc.Tag): NodeMeta? {
        val epoch = engine.structureEpoch()
        val key = tag.address()
        val cached = nodeMeta[key]
        if (cached != null && cached.epoch == epoch) return cached
        val child = engine.childMeta(tag.provider, tag.parentPath, tag.name)
        val built = if (child != null) {
            NodeMeta(epoch, child.kind, child.dataType)
        } else {
            val qv = sampleOf(tag).read()
            val code = qv.quality.code
            if (code == QualityCode.BAD_NOT_FOUND || code == QualityCode.ERROR_INVALID_PATH) {
                nodeMeta.remove(key)
                samples.remove(key)
                return null
            }
            NodeMeta(epoch, NodeKind.TAG, TagValues.inferredType(qv.value))
        }
        nodeMeta[key] = built
        return built
    }

    private fun childId(provider: String, child: BrowseNode): NodeId {
        val segments = child.path.segments
        val path = segments.joinToString("/")
        return nodeId(TagNodeIds.tag(provider, path))
    }

    private fun displayName(loc: Loc): String = when (loc) {
        Loc.Root -> TagNodeIds.ROOT
        is Loc.Provider -> engine.provider(loc.name)?.name ?: loc.name
        is Loc.Tag -> loc.name
    }

    private fun identifier(loc: Loc): String = when (loc) {
        Loc.Root -> TagNodeIds.ROOT
        is Loc.Provider -> TagNodeIds.provider(loc.name)
        is Loc.Tag -> TagNodeIds.tag(loc.provider, loc.path)
    }

    private fun typeDefinition(loc: Loc): NodeId =
        if (loc is Loc.Tag && isVariable(loc)) NodeIds.BaseDataVariableType else NodeIds.FolderType

    private fun location(nodeId: NodeId): Loc? {
        if (nodeId.namespaceIndex != namespaceIndex) return null
        val id = nodeId.identifier as? String ?: return null
        return TagNodeIds.parse(id)
    }

    private fun organizes(source: NodeId, target: NodeId): Reference =
        Reference(source, NodeIds.Organizes, target.expanded(), true)

    private fun error(code: Long): DataValue = DataValue(StatusCode(code))

    private class QualifiedHolder(val data: DataValue)

    private class SampleEntry(val epoch: Int, val sample: TagSample)

    private class NodeMeta(val epoch: Int, val kind: NodeKind, val dataType: DataType?)

    private companion object {
        const val BROWSE_PAGE = 1024
        /**
         * Attributes every session may read on a tag it may not read: what a browse shows, and constants. The
         * Value has its own denial ([currentValue]); everything else is refused for such a tag.
         */
        val OPEN_ATTRIBUTES: Set<AttributeId> = java.util.EnumSet.of(
            AttributeId.NodeId, AttributeId.NodeClass, AttributeId.BrowseName, AttributeId.DisplayName,
            AttributeId.Description, AttributeId.WriteMask, AttributeId.UserWriteMask, AttributeId.Value,
        )
        /** Audit `context` of writes made through the OPC UA server. */
        const val AUDIT_CONTEXT = "gateway-opcua"
    }
}