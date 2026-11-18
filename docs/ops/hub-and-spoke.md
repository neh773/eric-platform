# Hub and spoke

One hub gateway and any number of spoke gateways. Each spoke logs locally and forwards to the hub when the gateway-network link is up. This is the reference topology for edge sync. It is not a separate product edition: edge, standard, and hub are profiles of one build.

- -
- -
- -

```text
  site A (edge) --+
                  +--> hub (profile hub, port 8060)
  site B (edge) --+
```

## Hub

`profile: hub`. The peer network listens on 8060. For each spoke, allow storage:

- service `history`, access `allow`
- service `audit`, access `allow`
- service `alarm-journal`, access `allow`

`read` does not accept forwarded rows. A missing policy denies these services. Approve each spoke certificate on the hub, and approve the hub certificate on the spoke.

The hub keeps the forwarded history, audit copies, and alarm journal copies. Query them with `GET /api/v1/edge-sync/history`, `/audit`, and `/alarms`. Filter with `source` set to the spoke's gateway name.

## Spoke

`profile: edge`. Create an outgoing connection to the hub's host and port, then set `edgeSync.remoteGateway` to that connection name.

```yaml
profile: edge
gatewayNetwork:
  name: site-a
edgeSync:
  remoteGateway: hub
  history:
    enabled: true
    remoteProvider: enterprise
    captureAll: true
  audit:
    enabled: true
    remoteProfile: Gateway
  alarmJournal:
    enabled: true
    remoteJournal: Journal
```

While the link is down, new history samples, audit records, and alarm journal rows stay on the spoke. When the link is up and the schedule is open, the spoke sends one batch per pass until the hub has them. The spoke keeps its local history rows after a successful forward, so the site and the hub both have the samples. Audit rows stay in the spoke's hash chain. Alarm journal rows stay in the spoke's journal.

A second spoke uses the same hub policies and its own outgoing connection and gateway name. The hub stores each spoke under the name from that spoke's certificate handshake.

Local clients, alarm notification at the site, and enterprise administration of spokes are not part of this topology. Notification pipelines and the enterprise administration module are separate work.
