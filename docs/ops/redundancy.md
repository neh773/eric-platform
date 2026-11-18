# Gateway redundancy

A gateway is independent, or one node of a master/backup pair. The pair uses the mutual-TLS peer network from ADR 0012. The backup dials the master. There is no second port and the messages are not the reference platform's redundancy protocol.

The behavior follows the public redundancy pages:

- -
- -
- -
- -
- -

## `gateway.yaml`

Redundancy settings are node-local. They are not copied to the peer.

```yaml
redundancy:
  mode: independent          # independent, master, or backup
  standbyActivityLevel: warm # cold or warm
  startupAllowanceMillis: 30000
  syncTimeoutSeconds: 60
  recoveryMode: automatic    # automatic or manual
  masterHost: 127.0.0.1
  masterPort: 8060
  pingRateMillis: 1000
  pingTimeoutMillis: 300
  pingMaxMissed: 30
  historyMode: partial       # partial or full
  useActiveUptime: false
  connectionName: redundancy
```

`mode: independent` is the default. The gateway publishes redundancy system tags and does not open a redundancy session.

A backup creates the outgoing connection named `connectionName` and calls the master. Approve that certificate the same way as any other gateway-network connection. The master serves redundancy only while its mode is master.

`startupAllowanceMillis` is how long a node waits before becoming active on its own. `0` makes the master active before it has seen the backup, and manual recovery cannot then leave the backup in control. `pingRateMillis`, `pingTimeoutMillis`, and `pingMaxMissed` are the backup's heartbeat. The backup takes control when that many heartbeats are missed.

`recoveryMode: automatic` returns control to the master when the link is healthy. `manual` leaves the backup active until it fails or someone calls assume. Cold standby does not subscribe to devices and does not write them. Warm standby subscribes and still does not write devices or log history. Active is the node in control.

## Config and runtime

The master is the source of configuration. The backup pulls a full snapshot, then incremental changes, through the public config store API. A resource labeled `oip-local: true` stays on the node that wrote it. A full sync deletes other resources the master does not have.

Runtime sync copies memory and OPC tag values, live alarm state, and a redundancy history buffer. Partial history on the backup keeps samples taken while the backup is active and forwards them when the master is back. Samples taken while the master was itself active are dropped, so a partition does not double-log them. Full mode logs on the node that has the samples. This buffer is not a SQL historian.

`useActiveUptime: true` lets the node that has been active longer keep conflicting runtime values. The default is that the master's values win when the link returns.

## Status

`GET /api/v1/redundancy` requires `gateway.read`. `POST /api/v1/redundancy/failover`, `/assume`, and `/resync` require `gateway.write` and the CSRF header.

System tags, under the system provider:

| Tag | Values |
| --- | --- |
| `Gateway/Redundancy/Role` | Independent, Master, Backup |
| `Gateway/Redundancy/ActivityLevel` | undecided, cold, warm, active |
| `Gateway/Redundancy/IsActive` | true when this node is in control |
| `Gateway/Redundancy/IsMaster` | true when this node is the master and it is in control |
| `Gateway/Redundancy/Connection/IsConnected` | heartbeat is current |
| `Gateway/Redundancy/Connection/PeerId` | peer gateway name, or empty |

Both nodes can be active while the link is down. The next successful heartbeat leaves one active. That is the split-brain guard.

## Activity API

Gateway services read the node from `RedundancyRuntime`. An independent gateway is always active and uses the active gate, so those services behave as they do with redundancy turned off.

```kotlin
fun snapshot(): RedundancySnapshot
fun role(): NodeRole
fun activity(): ActivityLevel
fun gate(): RuntimeGate
fun isActive(): Boolean
fun addListener(replay: Boolean, listener: RedundancyListener): AutoCloseable
fun addListener(listener: RedundancyListener): AutoCloseable  // replay = false
fun removeListener(listener: RedundancyListener)

data class RedundancySnapshot(
    val role: NodeRole,
    val activity: ActivityLevel,
    val gate: RuntimeGate,
    val active: Boolean,
)

fun interface RedundancyListener {
    fun onChange(previous: RedundancySnapshot, current: RedundancySnapshot)
}
```

`snapshot()` is the consistent read. It and the other reads are lock-free (one volatile read), so hot paths such as the alarm journal and script, chart, and event-stream gates call them under their own locks. `role()` is the configured node (independent, master, or backup). `activity()` is undecided, cold, warm, or active. `gate()` is the subscription, device-write, and history flags for that activity. `isActive()` is true for an independent gateway and for the node currently in control.

`addListener` runs callbacks on a thread named `redundancy-listener`, in registration order. It does not run them on the heartbeat thread, and it does not call them while holding the redundancy lock or the tag-engine lock. Changes arrive in the order they happened: each call's `previous` equals the `current` of the call before it, even when the heartbeat loop and an HTTP failover request change activity at the same moment. One listener's failure is logged and does not skip the others or later changes. A listener may call back into the runtime, including `snapshot`, `requestFailover`, and `addListener`. A listener registered during a callback does not see that callback.

To follow the active node from startup, subscribe with replay:

```kotlin
redundancy.addListener(replay = true) { previous, current ->
    if (previous == current) {
        // First call: the state when you subscribed.
    } else {
        // A change.
    }
    if (current.active) startWork() else stopWork()
}
```

The first call carries the current state with `previous == current`, on `redundancy-listener`, ahead of any later change. A real change never has `previous == current`. Do not read `isActive()` and then subscribe without replay: a change between the two is lost. An independent gateway never changes, so with replay it gets exactly one call, with `active` true. `snapshot()` can run slightly ahead of listener delivery, because the state changes before listeners hear about it.

After `close()`, `requestFailover` and `assumeControl` do nothing and no callbacks run.

`RuntimeGate.forActivity` maps undecided and cold to the cold gate (no subscriptions, no device writes, no history), warm to subscriptions without device writes or history, and active to all three.

## Which services follow the gate

Warm standby runs the subscriptions a failover needs and does not take actions that would double up with the active node. Cold standby does not subscribe. These are the services on this gateway:

| Service | When it acts | Why |
| --- | --- | --- |
| Device subscriptions | warm and active | Cold does not subscribe. Warm does, so failover already has values. `TagDriverBridge` reads `gate.subscriptions`. |
| Device writes | active | A standby must not write a device. `gate.deviceWrites` is false for warm and cold. |
| MQTT subscriptions | warm and active | Same subscription rule as other device sources. |
| MQTT host state and commands | active | Follows `gate.deviceWrites`. A standby does not publish primary-host state, rebirth, or command writes. |
| Alarm evaluation | both nodes | Live state has to be present so a failover does not start empty. Runtime sync copies it. |
| Alarm journal append | active | The journal is a log. A standby still updates live state and does not append. |
| Alarm notification pipelines | active | Mail and pipeline steps would otherwise run on both nodes. Notices that arrive while standby are not queued. |
| Gateway startup and shutdown scripts | each process | They belong to the process, not to which node is in control. |
| Gateway timer scripts | active | A timer would otherwise run twice. |
| Gateway scheduled scripts | active | A schedule must not fire on a standby. |
| Gateway tag-change scripts | active | Both nodes see the same tag changes. Events queued while standby are discarded, not replayed. |
| Gateway message handlers | the node that was called | A request is answered where it arrived. |
| Explicit script runs | the caller | Web endpoints and `run` are not a schedule. |
| SFC charts | active | Charts advance only on the active node. Charts marked for redundancy sync are parked when the node leaves active and activated when it returns. A backup that starts as standby also parks synced charts it restored, so its first takeover runs their on-failover action. A master that is still undecided at startup only gates its charts, because becoming active is its normal start. A chart created on a standby that has not run yet stays gated in Starting and starts when the node takes over. |
| Event-stream handlers | active | Tag and HTTP handlers would otherwise post, query, or call out from both nodes. An HTTP post to a standby is rejected with 409. Queued events are dropped rather than replayed. |
| Gateway-network hello, heartbeat, and the redundancy session | both nodes | The pair cannot sync or fail over if that channel stops on a standby. |
| Gateway-network remote tag read, subscribe, and browse | active | Peers should use the node in control. This node still dials its own remote providers while warm. |
| Diagnostics sampler and clock drift | both nodes | Local health. The failover check reads them on the node that took over. |
| OPC UA server | both nodes | Each process keeps its server. It does not publish a service level. |
| In-memory redundancy history buffer | existing partial and full rules | Partial mode on a backup keeps samples taken while that node is active. This is not a SQL historian. |
| SQL transaction groups, tag-history collector, report schedules | not built yet | They must run only on the active node. Call `isActive()` per action, or `addListener(replay = true)` to start and stop with the node. History logging should read `gate().history`, which is false for warm and cold. |
