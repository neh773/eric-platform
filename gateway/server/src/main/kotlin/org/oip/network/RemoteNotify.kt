package org.oip.network

import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.oip.network.v1.NotificationForward
import org.oip.network.v1.NotificationForwardAck
import org.oip.network.v1.RemoteNotificationsGrpc

/** One notification a peer asked this gateway to hand to a local profile. */
data class RemoteNotification(
    val profile: String,
    val eventId: String,
    val source: String,
    val displayPath: String,
    val priority: String,
    val state: String,
    val active: Boolean,
    val sender: String,
)

internal interface NotificationServerHost {
    fun dropping(): Boolean
    fun fingerprint(): String
    fun access(fingerprint: String): ServiceAccess
    fun accept(notice: RemoteNotification): Boolean
}

internal class RemoteNotificationService(
    private val host: NotificationServerHost,
) : RemoteNotificationsGrpc.RemoteNotificationsImplBase() {
    override fun deliver(request: NotificationForward, responseObserver: StreamObserver<NotificationForwardAck>) {
        try {
            if (host.dropping()) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
            val access = host.access(host.fingerprint())
            if (!access.canWrite) throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        } catch (error: io.grpc.StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val profile = request.remoteProfile.trim()
        if (profile.isEmpty() || profile.length > 63) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("profile is invalid").asRuntimeException())
            return
        }
        val notice = RemoteNotification(
            profile = profile,
            eventId = request.eventId.take(200),
            source = request.source.take(1024),
            displayPath = request.displayPath.take(1024),
            priority = request.priority.take(40),
            state = request.state.take(80),
            active = request.active,
            sender = request.sender.take(200),
        )
        val accepted = try {
            host.accept(notice)
        } catch (_: RuntimeException) {
            responseObserver.onError(Status.INTERNAL.withDescription("notification delivery failed").asRuntimeException())
            return
        }
        if (!accepted) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("profile not found").asRuntimeException())
            return
        }
        responseObserver.onNext(NotificationForwardAck.newBuilder().setAccepted(true).setProfile(profile).build())
        responseObserver.onCompleted()
    }
}

internal fun RemoteNotification.toProto(senderName: String): NotificationForward = NotificationForward.newBuilder()
    .setRemoteProfile(profile)
    .setEventId(eventId)
    .setSource(source)
    .setDisplayPath(displayPath)
    .setPriority(priority)
    .setState(state)
    .setActive(active)
    .setSender(sender.ifBlank { senderName })
    .build()
