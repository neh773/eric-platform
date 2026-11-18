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
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue
import org.oip.security.SecurityZone
import org.oip.tags.TagCaller

/**
 * `Devices` folder of the gateway OPC UA server. Each configured device and connection is a folder,
 * and its items are browsable, readable, and writable.
 *
 * A read or a write calls the driver connection behind that device. It does not go through the tag
 * engine. A session whose user lacks `tags.write` is refused with `Bad_UserAccessDenied` and the
 * driver is not called, so the device value stays as it was. The same session still browses and reads.
 * UserAccessLevel omits CurrentWrite for that session, which is also how the stack refuses the write.
 *
 * the reference platform exposes connected devices through its OPC UA server, with Browse, Read, Write, and Call
 * granted per role. Here Browse and Read are any authenticated session (anonymous access is already
 * off) and Write is the session's `tags.write` permission. A monitored item is sampled by the
 * driver's poll or subscription at the revised sampling interval, shared with any tag already
 * polling that address. Comms loss is a bad-quality sample; the next good sample is recovery.
 */
internal class DeviceAddressSpace(
    server: OpcUaServer,
    private val catalog: DeviceCatalog,
) : AddressSpaceFragment, AutoCloseable {
    val namespaceIndex = server.namespaceTable.add(DeviceNodeIds.NAMESPACE_URI)
    private val monitors = DeviceMonitors(namespaceIndex, catalog, ::readItem)
    private val filter: AddressSpaceFilter = SimpleAddressSpaceFilter.create { nodeId ->
        nodeId.namespaceIndex == namespaceIndex
    }

    override fun close() = monitors.close()

    override fun getFilter(): AddressSpaceFilter = filter

    fun nodeId(identifier: String): NodeId = NodeId(namespaceIndex, identifier)

    override fun browse(
        context: AddressSpace.BrowseContext,
        view: ViewDescription,
        nodeIds: MutableList<NodeId>,
    ): MutableList<AddressSpace.ReferenceResult> {
        val results = ArrayList<AddressSpace.ReferenceResult>(nodeIds.size)
        for (nodeId in nodeIds) results += browseOne(nodeId)
        return results
    }

    override fun gather(
        context: AddressSpace.BrowseContext,
        view: ViewDescription,
        nodeId: NodeId,
    ): AddressSpace.ReferenceResult.ReferenceList {
        if (nodeId == NodeIds.ObjectsFolder) {
            return AddressSpace.ReferenceResult.of(listOf(organizes(NodeIds.ObjectsFolder, nodeId(DeviceNodeIds.ROOT))))
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
        AddressSpace.RevisedDataItemParameters(
            monitors.revised(itemToMonitor.nodeId, requestedSamplingInterval),
            requestedQueueSize,
        )

    override fun onModifyDataItem(
        itemToModify: ReadValueId,
        requestedSamplingInterval: Double,
        requestedQueueSize: UInteger,
    ): AddressSpace.RevisedDataItemParameters =
        AddressSpace.RevisedDataItemParameters(
            monitors.revised(itemToModify.nodeId, requestedSamplingInterval),
            requestedQueueSize,
        )

    override fun onDataItemsCreated(dataItems: MutableList<DataItem>) = monitors.created(dataItems)

    override fun onDataItemsModified(dataItems: MutableList<DataItem>) = monitors.modified(dataItems)

    override fun onDataItemsDeleted(dataItems: MutableList<DataItem>) = monitors.deleted(dataItems)

    override fun onMonitoringModeChanged(monitoredItems: MutableList<MonitoredItem>) = monitors.modeChanged(monitoredItems)

    private fun readItem(item: DataItem): DataValue = readOne(item.readValueId, TagCaller.System)

    private fun browseOne(nodeId: NodeId): AddressSpace.ReferenceResult {
        val loc = location(nodeId) ?: return AddressSpace.ReferenceResult.unknown()
        if (!exists(loc)) return AddressSpace.ReferenceResult.unknown()
        val refs = ArrayList<Reference>()
        refs += Reference(nodeId, NodeIds.HasTypeDefinition, typeDefinition(loc).expanded(), true)
        when (loc) {
            DeviceLoc.Root -> catalog.devices().forEach { endpoint ->
                refs += organizes(nodeId, nodeId(DeviceNodeIds.device(endpoint.name)))
            }
            is DeviceLoc.Device -> {
                val endpoint = catalog.device(loc.name) ?: return AddressSpace.ReferenceResult.unknown()
                for (child in endpoint.browse("")) {
                    refs += organizes(nodeId, nodeId(DeviceNodeIds.item(loc.name, child.nodeId)))
                }
            }
            is DeviceLoc.Item -> {
                val endpoint = catalog.device(loc.device) ?: return AddressSpace.ReferenceResult.unknown()
                val described = endpoint.describe(loc.itemId) ?: return AddressSpace.ReferenceResult.unknown()
                if (!described.variable) {
                    for (child in endpoint.browse(loc.itemId)) {
                        refs += organizes(nodeId, nodeId(DeviceNodeIds.item(loc.device, child.nodeId)))
                    }
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
        if (attribute == AttributeId.Value && loc is DeviceLoc.Item) return valueOf(loc)
        val value = attributeValue(loc, attribute, caller) ?: return error(StatusCodes.Bad_AttributeIdInvalid)
        // Attribute values are already OPC UA types (NodeId, QualifiedName, NodeClass). Do not stringify them.
        return DataValue(Variant(value))
    }

    private fun valueOf(item: DeviceLoc.Item): DataValue {
        val endpoint = catalog.device(item.device) ?: return error(StatusCodes.Bad_NodeIdUnknown)
        val described = endpoint.describe(item.itemId) ?: return error(StatusCodes.Bad_NodeIdUnknown)
        if (!described.variable) return error(StatusCodes.Bad_AttributeIdInvalid)
        if (!described.readable) return error(StatusCodes.Bad_NotReadable)
        val reading = try {
            endpoint.read(listOf(item.itemId)).single()
        } catch (_: Exception) {
            return error(StatusCodes.Bad_UnexpectedError)
        }
        val source = reading.sourceTimestamp ?: reading.serverTimestamp
        return DataValue(
            readingVariant(reading.value),
            status(reading.statusCode),
            TagValues.dateTime(source),
            TagValues.dateTime(reading.serverTimestamp),
        )
    }

    private fun writeOne(write: WriteValue, caller: TagCaller): StatusCode {
        val range = write.indexRange
        if (range != null && range.isNotEmpty()) return StatusCode(StatusCodes.Bad_IndexRangeInvalid)
        val loc = location(write.nodeId) ?: return StatusCode(StatusCodes.Bad_NodeIdUnknown)
        val item = loc as? DeviceLoc.Item ?: return StatusCode(StatusCodes.Bad_NotWritable)
        if (!exists(item)) return StatusCode(StatusCodes.Bad_NodeIdUnknown)
        val attribute = AttributeId.from(write.attributeId).orElse(null)
        if (attribute != AttributeId.Value) return StatusCode(StatusCodes.Bad_NotWritable)
        // Checked before the driver is called. A session without tags.write cannot change the device.
        if (!mayWrite(caller)) return StatusCode(StatusCodes.Bad_UserAccessDenied)
        val endpoint = catalog.device(item.device) ?: return StatusCode(StatusCodes.Bad_NodeIdUnknown)
        val described = endpoint.describe(item.itemId) ?: return StatusCode(StatusCodes.Bad_NodeIdUnknown)
        if (!described.variable || !described.writable) return StatusCode(StatusCodes.Bad_NotWritable)
        val value = TagValues.fromOpc(write.value?.value?.value)
        val code = try {
            endpoint.write(item.itemId, value)
        } catch (_: Exception) {
            return StatusCode(StatusCodes.Bad_UnexpectedError)
        }
        return status(code)
    }

    private fun attributeValue(loc: DeviceLoc, attribute: AttributeId, caller: TagCaller): Any? {
        val described = described(loc)
        val variable = described?.variable == true
        return when (attribute) {
            AttributeId.NodeId -> nodeId(identifier(loc))
            AttributeId.NodeClass -> if (variable) NodeClass.Variable else NodeClass.Object
            AttributeId.BrowseName -> QualifiedName(namespaceIndex, browseName(loc, described))
            AttributeId.DisplayName -> LocalizedText.english(browseName(loc, described))
            AttributeId.Description -> LocalizedText.NULL_VALUE
            AttributeId.WriteMask, AttributeId.UserWriteMask -> uint(0)
            AttributeId.EventNotifier -> if (variable) null else ubyte(0)
            AttributeId.DataType -> if (variable) NodeIds.BaseDataType else null
            AttributeId.ValueRank -> if (variable) -1 else null
            AttributeId.AccessLevel -> if (variable) TagValues.accessLevel(described?.writable == true) else null
            // CurrentWrite follows tags.write, so the stack refuses the write before it reaches [writeOne].
            AttributeId.UserAccessLevel ->
                if (variable) TagValues.accessLevel(described?.writable == true && mayWrite(caller)) else null
            // The driver will not poll faster than this. CreateMonitoredItems revises anything quicker.
            AttributeId.MinimumSamplingInterval -> if (variable) 10.0 else null
            AttributeId.Historizing -> if (variable) false else null
            else -> null
        }
    }

    private fun described(loc: DeviceLoc): DeviceItem? = when (loc) {
        DeviceLoc.Root, is DeviceLoc.Device -> null
        is DeviceLoc.Item -> catalog.device(loc.device)?.describe(loc.itemId)
    }

    private fun browseName(loc: DeviceLoc, described: DeviceItem?): String = when (loc) {
        DeviceLoc.Root -> DeviceNodeIds.ROOT
        is DeviceLoc.Device -> catalog.device(loc.name)?.browseName ?: loc.name
        is DeviceLoc.Item -> described?.browseName ?: loc.itemId
    }

    private fun exists(loc: DeviceLoc): Boolean = when (loc) {
        DeviceLoc.Root -> true
        is DeviceLoc.Device -> catalog.device(loc.name) != null
        is DeviceLoc.Item -> catalog.device(loc.device)?.describe(loc.itemId) != null
    }

    private fun mayWrite(caller: TagCaller): Boolean = caller !is TagCaller.User || caller.tagWrite

    private fun typeDefinition(loc: DeviceLoc): NodeId {
        val variable = loc is DeviceLoc.Item && described(loc)?.variable == true
        return if (variable) NodeIds.BaseDataVariableType else NodeIds.FolderType
    }

    private fun identifier(loc: DeviceLoc): String = when (loc) {
        DeviceLoc.Root -> DeviceNodeIds.ROOT
        is DeviceLoc.Device -> DeviceNodeIds.device(loc.name)
        is DeviceLoc.Item -> DeviceNodeIds.item(loc.device, loc.itemId)
    }

    private fun location(nodeId: NodeId): DeviceLoc? {
        if (nodeId.namespaceIndex != namespaceIndex) return null
        val id = nodeId.identifier as? String ?: return null
        return DeviceNodeIds.parse(id)
    }

    /** The user the authenticator attached to the session. No session is the server's own access. */
    private fun callerOf(context: AccessContext): TagCaller = callerOf(context.session.orElse(null))

    private fun callerOf(session: Session?): TagCaller {
        if (session == null) return TagCaller.System
        (session.identity?.userData as? TagCaller.User)?.let { return it }
        val name = session.clientUserId?.takeIf { it.isNotEmpty() } ?: "opcua-session"
        return TagCaller.User(name, emptySet(), SecurityZone.DEFAULT_NAME, session.clientAddress?.hostAddress ?: "", tagWrite = false)
    }

    private fun organizes(source: NodeId, target: NodeId): Reference =
        Reference(source, NodeIds.Organizes, target.expanded(), true)

    /** Keep an OPC UA scalar as a variant; everything else uses the tag value conversion. */
    private fun readingVariant(value: Any?): Variant = when (value) {
        null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is String,
        is UByte, is UShort, is UInteger, is ULong, is DateTime, is NodeId, is StatusCode,
        is QualifiedName, is LocalizedText,
        -> Variant(value)
        else -> TagValues.variant(value)
    }

    private fun status(code: Long): StatusCode = StatusCode(code and 0xffff_ffffL)

    private fun error(code: Long): DataValue = DataValue(status(code))
}
