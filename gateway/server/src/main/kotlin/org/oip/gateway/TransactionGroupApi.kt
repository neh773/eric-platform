package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.db.groups.GroupActivity
import org.oip.db.groups.GroupNotFound
import org.oip.db.groups.GroupRejected
import org.oip.db.groups.GroupTags
import org.oip.db.groups.PooledConnections
import org.oip.db.groups.TagRead
import org.oip.db.groups.TransactionGroups
import org.oip.redundancy.RedundancyRuntime
import org.oip.tags.Level
import org.oip.tags.Quality
import org.oip.tags.TagEngine
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value

/**
 * Transaction groups on the gateway: wiring, and REST under `/api/v1/db/groups`.
 *
 *
 * Routes: list and one group's status (state, last run, duration, rows written, error), plus enable and
 * disable. Creating and editing groups goes through the config API (`db-groups/gateway/TransactionGroup`).
 * Enable and disable are config writes, so each one leaves a single audit row via the config audit hook.
 *
 * Access is decided centrally in [GatewayAuth]: GET and HEAD need `gateway.read`, like database
 * connections; enable and disable need `gateway.write`.
 *
 * Groups borrow pooled connections from [Gateway.databases]. In a redundant pair only the active node runs
 * groups: each tick checks [RedundancyRuntime.isActive] through the [GroupActivity] port, and a redundancy
 * listener stops the schedulers when the node becomes backup and starts them when it becomes active
 * (see [followRedundancy]). An independent gateway is always active.
 *
 * Groups start when the HTTP server starts and stop when it stops, which is what [Gateway.start] and
 * [Gateway.close] already do to [Gateway.app]; that keeps the wiring in Gateway to one line.
 */
class TransactionGroupApi private constructor(val groups: TransactionGroups) {

    fun register(app: Javalin) {
        app.get("/api/v1/db/groups") { ctx -> json(ctx, 200, mapOf("items" to groups.list())) }
        app.get("/api/v1/db/groups/{name}") { ctx -> json(ctx, 200, guard { groups.status(ctx.pathParam("name")) }) }
        app.post("/api/v1/db/groups/{name}/enable") { ctx -> toggle(ctx, true) }
        app.post("/api/v1/db/groups/{name}/disable") { ctx -> toggle(ctx, false) }
    }

    private fun toggle(ctx: Context, enabled: Boolean) {
        val name = ctx.pathParam("name")
        val actor = GatewayAuth.principal(ctx).name
        guard { groups.setEnabled(name, enabled, actor) }
        json(ctx, 200, guard { groups.status(name) })
    }

    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: GroupNotFound) {
        throw Problems.notFound(e.message ?: "not found")
    } catch (e: GroupRejected) {
        throw Problems.unprocessable(e.message ?: "invalid")
    } catch (e: org.oip.config.ConflictException) {
        throw Problems.conflict(e.message ?: "version conflict")
    }

    private fun json(ctx: Context, status: Int, body: Any?) {
        ctx.status(status).contentType("application/json").result(Json.write(body))
    }

    companion object {
        /** The one call [Gateway] makes. Builds the engine, registers routes, and ties it to the server lifecycle. */
        fun install(gw: Gateway, app: Javalin): TransactionGroupApi {
            val activity = GroupActivity { gw.redundancy.isActive() }
            val groups = TransactionGroups(gw.store, TagEngineGroupTags(gw.tags), PooledConnections(gw.databases), gw.clock, activity, org.oip.db.groups.FileMeterStore(gw.dataDir.resolve("group-meters")))
                .also { it.attach() }
            val following = followRedundancy(groups, gw.redundancy)
            app.events { events ->
                events.serverStarted { groups.start() }
                events.serverStopping {
                    following.close()
                    groups.close()
                }
            }
            return TransactionGroupApi(groups).also { it.register(app) }
        }

        /**
         * Start and stop [groups] with this node's redundancy activity. Registered with replay, so the first
         * call is the state at startup. The listener only passes the flag on: [TransactionGroups.setNodeActive]
         * does the start or stop on the group control thread, so no group, tag, or database work runs on
         * `redundancy-listener` (P2-14). The returned handle removes the listener.
         */
        fun followRedundancy(groups: TransactionGroups, redundancy: RedundancyRuntime): AutoCloseable =
            redundancy.addListener(replay = true) { _, current -> groups.setNodeActive(current.active) }
    }
}

/**
 * The tag engine as a [GroupTags]. Each call is one engine read or write, so the engine's lock is held only
 * for that call and never across database work (P2-14).
 */
class TagEngineGroupTags(private val tags: TagEngine) : GroupTags {
    override fun read(path: String): TagRead {
        val qv = tags.read(path)
        return TagRead(qv.value, qv.quality.level == Level.GOOD, qv.quality.diagnostic)
    }

    override fun write(path: String, value: Any?): String? {
        val result = tags.write(path, value)
        return if (result.ok) null else result.quality.toString()
    }

    /** Expression language of expression tags. - */
    override fun evaluate(expression: String, ref: (String) -> TagRead): TagRead {
        val value = Expressions.eval(expression, EvalContext(ref = { body ->
            val read = ref(body)
            Value(read.value, if (read.good) Quality.GOOD else Quality(org.oip.tags.QualityCode.BAD, read.diagnostic))
        }))
        return TagRead(value.value, value.quality.level == Level.GOOD, value.quality.diagnostic)
    }
}
