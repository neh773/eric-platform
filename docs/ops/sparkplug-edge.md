# Sparkplug B edge node

The MQTT driver can publish gateway tags as a Sparkplug B 3.0 edge node. Each `EdgeNode` resource opens its own MQTT session, separate from any `Connection` (the host side of the driver), because the NDEATH will belongs to the session.

Specification: https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf

## Config

`EdgeNode` resources live under `org.oip.driver-mqtt`. Creating, updating, or deleting one starts, restarts, or stops the edge node without a gateway restart. An update is a new MQTT session, so it publishes new births.

```yaml
# PUT /api/v1/config/org.oip.driver-mqtt/gateway/EdgeNode/line3
host: broker.plant.local
port: 1883
groupId: Plant
edgeNodeId: Line3
primaryHostId: scada          # optional
username: secret://mqtt-user  # optional, secret references only
password: secret://mqtt-pass
nodeMetrics:
  - name: Speed
    tag: "[default]Line3/Speed"
  - name: Setpoint
    tag: "[default]Line3/Setpoint"
    writable: true
devices:
  - id: Filler
    metrics:
      - name: Count
        tag: "[default]Line3/Filler/Count"
        dataType: Int64       # optional
        writable: true
```

Other fields: `protocol` (`MQTT_3_1_1` or `MQTT_5`), `clientId` (default `oip-edge-<resource name>`), `keepAliveSeconds`, `tls`, `tlsTrustStore`, `tlsTrustStorePassword`, `insecureTrustAllCertificates` (test only), `publishPeriodMs` (default 20), `reconnectInitialMs`, `reconnectMaxMs`, `connectTimeoutMs`, `enabled`.

`groupId`, `edgeNodeId`, `primaryHostId` and device ids are topic levels, so they cannot contain `/`, `+` or `#`. A metric name may contain `/` as a folder separator. `bdSeq` and names under `Node Control/` are reserved.

The metric datatype is fixed at birth: `dataType` if set, otherwise the tag's data type, otherwise the value's type. A metric whose tag is missing, with no `dataType` and no value, is left out of the births until the tag has a type or a value; the edge node then publishes its births again. Values that do not convert, or integers outside the datatype's width (for example 300 for `Int8`), are sent as null and never wrapped. Document tags are sent as JSON text.

## What is published

| Message | When | Notes |
|---|---|---|
| NDEATH | MQTT will on every connect; also published before a clean disconnect | QoS 1, not retained, carries `bdSeq` |
| NBIRTH | After connect (and after the primary host is online, when set) | `seq` 0, `bdSeq`, `Node Control/Rebirth` = false, every node metric |
| DBIRTH | Right after NBIRTH, one per device | |
| NDATA / DDATA | Every `publishPeriodMs` when a mapped tag changed | Report by exception: only changed values. A tag whose quality is not good is sent with `is_null` |

`seq` runs 0 to 255 and wraps. `bdSeq` goes up by one on each connect, wraps at 255, and restarts at 0 when the gateway restarts.

## Commands

- NCMD `Node Control/Rebirth` = true republishes NBIRTH and every DBIRTH in the same session.
- NCMD (node metric) or DCMD (device metric) for a metric with `writable: true` writes the tag. The new tag value then goes out as NDATA or DDATA.
- A command is ignored when it arrives before NBIRTH, names a metric that is not writable or not in the birth, is null, or does not fit the birth datatype: a number for a number metric (within range, no fraction for an integer), true/false for a Boolean, text for a String. Nothing is converted from text.
- Every command write is logged at INFO with the metric, the tag, and whether the tag accepted it. Ignored commands are logged at INFO with the reason. Writes run on one thread with a queue of 256; when it is full, further writes are discarded and a warning is logged.

Sparkplug commands carry no user identity. Broker ACLs on `spBv1.0/<group>/NCMD/<edge node>` and `spBv1.0/<group>/DCMD/<edge node>/+` are the only access control on these writes, so restrict who may publish there.

## Primary host

With `primaryHostId`, the edge node subscribes to `spBv1.0/STATE/<id>` and holds its births until that host reports online. If the host then reports offline (with the same or a newer timestamp), the edge node publishes NDEATH, disconnects, reconnects, and waits for the host again. STATE messages older than the last one seen are ignored.

## Threads and load

The tag-engine listener only stores the newest value in that metric's slot; publishing runs on the edge node's own thread, never under the tag-engine lock. Changes are coalesced per metric: if a tag changes several times within one `publishPeriodMs`, only its newest value is sent, so nothing is dropped as stale and memory stays bounded by the number of metrics. Between sessions nothing is kept, because the next births read current values.

## Not implemented

Aliases, store-and-forward and historical metrics, templates, DataSets, properties, a persisted `bdSeq`, the other `Node Control` commands, per-device DDEATH, and a broker list are not implemented. See DIFFERENCES.md entries 67 to 72.

## Redundancy

On a redundant pair, edge nodes run only on the active node. A backup (inactive) node does not connect to the broker and publishes nothing. When a node becomes inactive, each edge node publishes NDEATH and disconnects. When a node becomes active, each edge node connects with the next `bdSeq` and publishes NBIRTH and DBIRTH again with current values. Both members can carry the same `EdgeNode` configuration, because only one of them is connected at a time. During a failover, the host sees the old session's NDEATH (or its will, if the old node failed) and then the new node's NBIRTH. Changes that happen between the two are not replayed; the new birth carries the current values.
