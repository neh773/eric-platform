package org.oip.network

import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.oip.network.v1.RemoteTasksGrpc
import org.oip.network.v1.TaskList
import org.oip.network.v1.TaskListRequest
import org.oip.network.v1.TaskRef
import org.oip.network.v1.TaskState
import org.oip.network.v1.TaskSubmit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal data class TaskView(
    val id: String,
    val name: String,
    val argument: String,
    val direction: String,
    val peer: String,
    val state: String,
    val detail: String,
    val startedMillis: Long,
    val finishedMillis: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "argument" to argument,
        "direction" to direction,
        "peer" to peer,
        "state" to state,
        "detail" to detail,
        "startedMillis" to startedMillis,
        "finishedMillis" to finishedMillis,
    )
}

internal class TaskBoard {
    private val tasks = CopyOnWriteArrayList<TaskView>()

    fun save(view: TaskView): TaskView {
        tasks.removeAll { it.id == view.id && it.direction == view.direction && it.peer == view.peer }
        tasks.add(0, view)
        while (tasks.size > 50) tasks.removeAt(tasks.lastIndex)
        return view
    }

    fun update(id: String, direction: String, next: (TaskView) -> TaskView): TaskView? {
        val current = tasks.firstOrNull { it.id == id && it.direction == direction } ?: return null
        val updated = next(current)
        tasks.remove(current)
        tasks.add(0, updated)
        return updated
    }

    fun get(id: String, direction: String): TaskView? = tasks.firstOrNull { it.id == id && it.direction == direction }

    fun publicList(): List<Map<String, Any?>> = tasks.map { it.toMap() }
}

internal interface TaskServerHost {
    fun dropping(): Boolean
    fun fingerprint(): String
    fun access(fingerprint: String): ServiceAccess
    fun gatewayName(): String
    fun tagCount(provider: String): Int
    fun board(): TaskBoard
}

/**
 * Built-in tasks a peer can run here: gateway-status, tag-count, and hold.
 * hold runs until its argument (milliseconds, at most five seconds) elapses or it is cancelled.
 */
internal class RemoteTaskService(
    private val host: TaskServerHost,
) : RemoteTasksGrpc.RemoteTasksImplBase() {
    private val running = CopyOnWriteArrayList<Hold>()

    override fun submit(request: TaskSubmit, responseObserver: StreamObserver<TaskState>) {
        val access = try {
            requireTasks()
        } catch (error: io.grpc.StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        if (!access.canWrite) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("task write denied").asRuntimeException())
            return
        }
        val name = request.name.trim()
        if (name !in TASKS) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("task name is invalid").asRuntimeException())
            return
        }
        val argument = request.argument.take(200)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val peer = host.fingerprint()
        if (name == "hold") {
            val millis = argument.toLongOrNull()?.coerceIn(1, MAX_HOLD_MILLIS) ?: 1_000L
            val view = TaskView(id, name, argument, "incoming", peer, "running", "", now, 0)
            host.board().save(view)
            val hold = Hold(id)
            running.add(hold)
            Thread({
                try {
                    val deadline = System.nanoTime() + millis * 1_000_000
                    while (System.nanoTime() < deadline && !hold.cancelled.get()) Thread.sleep(20)
                    val state = if (hold.cancelled.get()) "cancelled" else "succeeded"
                    host.board().update(id, "incoming") {
                        it.copy(state = state, detail = if (state == "cancelled") "cancelled" else "held", finishedMillis = System.currentTimeMillis())
                    }
                } catch (_: InterruptedException) {
                    host.board().update(id, "incoming") {
                        it.copy(state = "cancelled", detail = "cancelled", finishedMillis = System.currentTimeMillis())
                    }
                } finally {
                    running.remove(hold)
                }
            }, "gan-task-$id").apply { isDaemon = true }.start()
            responseObserver.onNext(view.toProto())
            responseObserver.onCompleted()
            return
        }
        val detail = when (name) {
            "gateway-status" -> host.gatewayName()
            else -> host.tagCount(argument).toString()
        }
        val view = TaskView(id, name, argument, "incoming", peer, "succeeded", detail, now, System.currentTimeMillis())
        host.board().save(view)
        responseObserver.onNext(view.toProto())
        responseObserver.onCompleted()
    }

    override fun get(request: TaskRef, responseObserver: StreamObserver<TaskState>) {
        try {
            requireTasks()
        } catch (error: io.grpc.StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val view = host.board().get(request.id, "incoming")
        if (view == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("task not found").asRuntimeException())
            return
        }
        responseObserver.onNext(view.toProto())
        responseObserver.onCompleted()
    }

    override fun list(request: TaskListRequest, responseObserver: StreamObserver<TaskList>) {
        try {
            requireTasks()
        } catch (error: io.grpc.StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        val rows = host.board().publicList().mapNotNull { row ->
            if (row["direction"] != "incoming") return@mapNotNull null
            TaskView(
                id = row["id"] as String,
                name = row["name"] as String,
                argument = row["argument"] as String,
                direction = "incoming",
                peer = row["peer"] as String,
                state = row["state"] as String,
                detail = row["detail"] as String,
                startedMillis = (row["startedMillis"] as Number).toLong(),
                finishedMillis = (row["finishedMillis"] as Number).toLong(),
            ).toProto()
        }
        responseObserver.onNext(TaskList.newBuilder().addAllTasks(rows).build())
        responseObserver.onCompleted()
    }

    override fun cancel(request: TaskRef, responseObserver: StreamObserver<TaskState>) {
        val access = try {
            requireTasks()
        } catch (error: io.grpc.StatusRuntimeException) {
            responseObserver.onError(error)
            return
        }
        if (!access.canWrite) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("task write denied").asRuntimeException())
            return
        }
        val hold = running.firstOrNull { it.id == request.id }
        hold?.cancelled?.set(true)
        val view = host.board().update(request.id, "incoming") {
            if (it.state == "running") it.copy(state = "cancelled", detail = "cancelled", finishedMillis = System.currentTimeMillis()) else it
        }
        if (view == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("task not found").asRuntimeException())
            return
        }
        responseObserver.onNext(view.toProto())
        responseObserver.onCompleted()
    }

    private fun requireTasks(): ServiceAccess {
        if (host.dropping()) throw Status.UNAVAILABLE.withDescription("connection dropped").asRuntimeException()
        val access = host.access(host.fingerprint())
        if (!access.allowed) throw Status.PERMISSION_DENIED.withDescription("service denied").asRuntimeException()
        return access
    }

    private class Hold(val id: String) {
        val cancelled = AtomicBoolean(false)
    }

    private companion object {
        val TASKS = setOf("gateway-status", "tag-count", "hold")
        const val MAX_HOLD_MILLIS = 5_000L
    }
}

internal fun TaskState.toView(direction: String, peer: String): TaskView = TaskView(
    id = id,
    name = name,
    argument = argument,
    direction = direction,
    peer = peer,
    state = state,
    detail = detail,
    startedMillis = startedUnixMillis,
    finishedMillis = finishedUnixMillis,
)

private fun TaskView.toProto(): TaskState = TaskState.newBuilder()
    .setId(id)
    .setName(name)
    .setArgument(argument)
    .setState(state)
    .setDetail(detail)
    .setStartedUnixMillis(startedMillis)
    .setFinishedUnixMillis(finishedMillis)
    .build()
