# Edge sync

An edge gateway keeps history, audit records, and alarm journal rows locally and forwards them to a hub over the mutual-TLS peer network. The messages are `EdgeSync` RPCs on `oip.gateway.network.v1`. There is no second transport and the messages are not another product's sync protocol.

The behavior follows the public sync and architecture pages:

- -
- -
- -
- -
- -

A reference with one hub and two spokes is [hub-and-spoke.md](hub-and-spoke.md).

## `gateway.yaml`

Sync settings are node-local. They are not a config-store resource.

```yaml
edgeSync:
  remoteGateway: hub          # outgoing connection name
  history:
    enabled: false
    remoteProvider: enterprise
    maxBatchSize: 1000
    syncFrequency: 10
    syncFrequencyUnits: seconds
    scheduleEnabled: false
    schedulePattern: ""
    tagPaths: []
    captureAll: false
    retentionDays: 35
    maxPoints: 10000000
  audit:
    enabled: false
    remoteProfile: Gateway
    syncFrequency: 10
    syncFrequencyUnits: seconds
    scheduleEnabled: false
    schedulePattern: ""
    retentionDays: 7
  alarmJournal:
    enabled: false
    remoteJournal: Journal
    syncFrequency: 10
    syncFrequencyUnits: seconds
    scheduleEnabled: false
    schedulePattern: ""
    retentionDays: 7
```

Each stream is off until `enabled` is true. `remoteGateway` is the outgoing connection created through the peer network API. Approve that connection the same way as any other peer.

`syncFrequencyUnits` is one of milliseconds, seconds, minutes, hours, days, weeks, months, or years. A month is 30 days and a year is 365 days. When a schedule is enabled, forwarding runs only inside the pattern. The end minute is outside the window. `9:00-15:00, 6pm-1am, 06.30-07.30` is the shape from the public page. Windows are evaluated in UTC.

History capture is on change. `tagPaths` lists the tags to buffer. `captureAll: true` buffers every tag except the system provider and folders. The listener copies the sample onto a queue. SQLite and the network run on the edge-sync thread, not under the tag engine lock.

The history buffer keeps a forwarded row until it is older than `retentionDays` (default 35) or the file is past `maxPoints` (default 10 million, and not configurable above that). Past the cap, forwarded rows are dropped first, then the oldest unsent rows. Audit and alarm forwarding skip records older than their retention (default 7 days). Those local stores are not deleted by sync: the audit log is a hash chain, and the alarm journal remains the local record.

One pass sends at most `maxBatchSize` history points (default 1000). Audit and alarm journal passes send at most 200 records. The public audit and alarm tables do not name a batch size. After a link returns, later passes continue until the buffer is caught up.

## Hub storage

The hub accepts a push only when a service policy grants `allow` for that peer and service. `allow` is query and storage. `read` is query only and rejects the push. With no policy, history, audit, and alarm-journal are denied, which matches the default for every service other than tags.

| Service | What it stores |
| --- | --- |
| `history` | Samples for the spoke's remote history provider name |
| `audit` | Copies of audit rows for the remote profile name |
| `alarm-journal` | Copies of journal rows for the remote journal name |

The stored gateway name is the name from the authenticated handshake, not a name in the payload. Audit copies are not appended to the hub's hash-chained audit log. Alarm copies are not written into the hub's local alarm journal. Both are visible from the edge-sync API.

`GET /api/v1/edge-sync` requires `gateway.read`. `POST /api/v1/edge-sync/sync` requires `gateway.write` and the CSRF header. It runs one pass and still honors the schedule and the link.

The buffer file is `<dataDir>/db/edge-sync.sqlite`. A gateway backup does not include it.
