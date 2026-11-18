# Gateway network

Two gateways share tags over gRPC with mutual TLS. The protocol is `oip.gateway.network.v1` on port 8060. It is not compatible with any other product's peer network. See ADR 0012.

The connection model, certificate approval, ping settings, and service security follow the public pages:

- -
- -
- -
- -
- -

## `gateway.yaml`

```yaml
gatewayNetwork:
  enabled: true
  port: 8060
  name: gateway
  pingRateMillis: 1000
  pingTimeoutMillis: 300
  missedPings: 30
  backoffInitialMillis: 200
  backoffCapMillis: 5000
```

The listener binds the same address as HTTP (`http.host`). An edge profile therefore stays on `127.0.0.1`. `port: 0` asks the kernel for a port. `enabled: false` does not listen and does not generate a certificate.

Outgoing connections, remote tag providers, service policies, and certificate decisions are not yaml keys. They are stored under the data directory and changed through the API or the Network page.

## Files

`<dataDir>/gateway-network/`:

| Path | Contents |
| --- | --- |
| `identity/server.crt` | Local certificate. The file mode is readable. |
| `identity/server.key` | Local private key, mode 0600. Never returned by the API. |
| `trust/incoming/<fingerprint>.json` | Client certificate presented to this gateway, and whether it is pending, approved, or denied. |
| `trust/outgoing/<fingerprint>.json` | Server certificate seen by an outgoing connection. |
| `outgoing.json` | Outgoing connection list. |
| `providers.json` | Remote tag providers. |
| `policies.json` | Service security policies. |

A gateway backup does not include this directory. Deleting it forgets peer approvals and mints a new identity on the next start. Peers must approve the new certificate.

## Approval

An incoming connection stays pending until an administrator approves it. The first outgoing handshake also stores the remote server certificate as pending and does not complete. Approve that certificate, then approve the incoming certificate on the other gateway. Deny keeps the peer out. That is also how a previously approved certificate is revoked.

REST, with `gateway.read` for GET and `gateway.write` plus CSRF for changes:

- `GET /api/v1/gateway-network`
- `POST /api/v1/gateway-network/outgoing`
- `POST /api/v1/gateway-network/outgoing/{name}/approve` and `.../deny`
- `POST /api/v1/gateway-network/incoming/{fingerprint}/approve` and `.../deny`
- `PUT /api/v1/gateway-network/policies` and `DELETE /api/v1/gateway-network/policies?peer=&service=`
- `POST /api/v1/gateway-network/providers`
- `POST /api/v1/gateway-network/certificate/regenerate`

The Network page lists incoming connections and approves or denies them. The private key is not shown.

## Remote tags

A remote tag provider has a local name, the outgoing connection, and the provider name on the other gateway (default `default`). Reads and subscriptions run on the remote gateway. Writes are rejected. The same provider also subscribes to that peer's live alarms.

With no policy, the tag service is allowed and other services are denied. An explicit `deny` blocks the service. A policy may list roles. A tag that has read permissions requires one of those roles. A tag with no read permissions stays public. Unauthorized reads use quality `Bad_AccessDenied`.

`allow` on `history`, `audit`, or `alarm-journal` accepts edge-sync storage from that peer. `read` does not. See [edge-sync.md](edge-sync.md).

## Remote alarms

Service name: `alarms`.

`read` and `allow` permit query and subscribe. `allow` also permits acknowledge, shelve, and unshelve. `deny`, and no matching policy, reject the call. The owning gateway does not return alarm state in that case.

`GET /api/v1/alarms` includes those remote events. The source provider segment is the local provider name (`prov:site:...` for a provider named `site`), and the event id is prefixed with that name. Ack and shelve on those ids run on the peer that owns the alarm. A successful or rejected acknowledgement is appended to that peer's audit log. The actor is the remote gateway name, and the actor host is the peer certificate fingerprint.

A dropped link resubscribes when the connection returns. The first snapshot on the new stream is the current live set.

Live alarm status is not the edge-sync alarm journal. A client-facing history query, as opposed to the edge-sync history push, is still not a service on this protocol.

`POST /api/v1/gateway-network/certificate/regenerate` replaces the local certificate. Every peer must approve it again.
