package org.oip.opcua.server

import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.Session
import org.eclipse.milo.opcua.sdk.server.servicesets.AttributeServiceSet
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.structured.HistoryReadRequest
import org.eclipse.milo.opcua.stack.core.types.structured.HistoryReadResponse
import org.eclipse.milo.opcua.stack.core.types.structured.HistoryUpdateRequest
import org.eclipse.milo.opcua.stack.core.types.structured.HistoryUpdateResponse
import org.eclipse.milo.opcua.stack.core.types.structured.ReadRequest
import org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse
import org.eclipse.milo.opcua.stack.core.types.structured.WriteRequest
import org.eclipse.milo.opcua.stack.core.types.structured.WriteResponse
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue
import org.eclipse.milo.opcua.stack.transport.server.ServiceRequestContext
import org.slf4j.LoggerFactory

/**
 * The attribute service with one addition (review-2 F7): the SDK refuses a write itself when the node's
 * UserAccessLevel has no CurrentWrite, before the address space sees it, so such a refusal would not be audited.
 * After each Write this hands every `Bad_UserAccessDenied` result to [refused] with the session, so the tag
 * address space can record it ([TagAddressSpace.auditRefused]). Reads and history pass through unchanged.
 */
internal class RefusedWriteAudit(
    private val server: OpcUaServer,
    private val inner: AttributeServiceSet,
    private val refused: (Session, List<WriteValue>, List<StatusCode>) -> Unit,
) : AttributeServiceSet {
    override fun onRead(context: ServiceRequestContext, request: ReadRequest): ReadResponse = inner.onRead(context, request)

    override fun onHistoryRead(context: ServiceRequestContext, request: HistoryReadRequest): HistoryReadResponse =
        inner.onHistoryRead(context, request)

    override fun onHistoryUpdate(context: ServiceRequestContext, request: HistoryUpdateRequest): HistoryUpdateResponse =
        inner.onHistoryUpdate(context, request)

    override fun onWrite(context: ServiceRequestContext, request: WriteRequest): WriteResponse {
        val response = inner.onWrite(context, request)
        val writes = request.nodesToWrite?.toList() ?: return response
        val results = response.results?.toList() ?: return response
        if (results.size != writes.size || results.none { it.value == StatusCodes.Bad_UserAccessDenied }) return response
        try {
            val session = server.sessionManager.getSession(context, request.requestHeader)
            refused(session, writes, results)
        } catch (e: Exception) {
            log.debug("could not audit refused OPC UA writes", e)
        }
        return response
    }

    private companion object {
        val log = LoggerFactory.getLogger(RefusedWriteAudit::class.java)
    }
}
