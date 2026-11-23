# Network, redundancy, edge sync and enterprise admin

These pages are in the Connections group, except Enterprise Admin, which is its own group. The beta warning applies: do not use a pair or an edge sync path for a live plant.

## Network

**Network** edits the web server and TLS.

- HTTP bind address and port. The default listen port in a profile file is 8090. Installers often use 8088. A test gateway uses whatever port it was started with.
- HTTPS: tick **Serve HTTPS**, set the HTTPS port (default 8043), and choose **Save web server settings**. HTTPS
  starts at once with the gateway's own certificate; the page says "HTTPS is on now". Turning it off stops the
  HTTPS listener the same way. A change to the HTTP port, the bind address, the public address or the redirect
  waits for the next gateway start, and the page says so.
- Upload a PEM certificate and key, or a PKCS#12 file, with `PUT /api/v1/https/certificate`.
- Create a certificate signing request with `POST /api/v1/https/csr`, then install the signed chain with `POST /api/v1/https/csr/install`.
- TLS protocol and cipher policy: `PUT /api/v1/https/tls`.

A backup restore or wipe that replaces `https/` stops the TLS listener's use of the files, puts the new files in place, and starts it again, on Windows too.

The same page has the gateway-network listener (default port 8060). Outgoing peers, incoming approval, service policies and remote tag providers are `POST` calls under `/api/v1/gateway-network/`. The page lists them when the gateway has peers. If another program already uses the port, the gateway still starts and the page shows an alert. Pick a free port in `gateway.yaml` (`gatewayNetwork.port`) and restart.

## Redundancy

The redundancy panel on **Network** saves `PUT /api/v1/redundancy`.

Mode is `independent`, `master` or `backup`. A backup needs `masterHost` and `masterPort` (the master's gateway-network port, default 8060). Heartbeat fields are `pingRateMillis`, `pingTimeoutMillis` and `pingMaxMissed`.

Actions, each a POST with an empty JSON body:

- `/api/v1/redundancy/failover`
- `/api/v1/redundancy/assume`
- `/api/v1/redundancy/resync`

The wishlist still lists a fuller redundant-gateway design as not started. The panel and these three actions are what 0.1.0 actually runs.

## Edge sync

There is no separate admin page. `GET /api/v1/edge-sync` returns the spoke or hub status. History, audit and alarm streams are `GET /api/v1/edge-sync/history`, `/audit` and `/alarms`. `POST /api/v1/edge-sync/sync` runs one pass.

A spoke sets `edgeSync.remoteGateway` and enables `history`, `audit` or `alarmJournal` in `gateway.yaml`. A hub uses the hub profile and receives those streams. Samples sit in `db/edge-sync.sqlite` until they are forwarded.

## Architectures

**Architectures** reads `GET /api/v1/architectures`. Each row (single server, enterprise, edge panel, edge IIoT, edge sync, cloud, scale-out, segmentation, independent, warm pair, cold pair) is checked against this gateway's profile, bind address, peers, redundancy and MQTT connections. A match is live state, not a template you apply.

## Enterprise admin

**Enterprise Admin** is for a controller gateway and the agents that check in to it.

1. Set the role to `controller` and choose **Save role**. That is `PUT /api/v1/eam/settings` with `{"role":"controller"}`.
2. An agent gateway starts with `eam-agent.json` in its data directory: `controllerHost`, `controllerPort`, `controllerFingerprint`, `group`, `checkInMillis`.
3. Approve the agent's certificate, then **Approve &lt;name&gt;** (`POST /api/v1/eam/agents/{name}/approve`).
4. Create a task (send tags, a backup, a restore, and the other kinds the form lists) and run it. Runs are `GET /api/v1/eam/runs`. Retry is `POST /api/v1/eam/runs/{id}/retry`.

`GET /api/v1/eam/inventory?format=csv` downloads the agent list. A viewer with no `gateway.read` sees a refusal and no approve buttons.
