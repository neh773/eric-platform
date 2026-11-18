# Edge profile

`profile: edge` in `gateway.yaml` (or `OIP_PROFILE=edge`) runs a reduced gateway for a field device. Doc source: FEATURES GAT-004.

What the profile sets:

- The HTTP listener binds `127.0.0.1` unless `http.host` says otherwise.
- Local drivers only by default: the OPC UA client, Modbus, Logix and MQTT drivers are configured on the device itself.
- Local storage and connection caps through `limits` (below).
- Optional upstream sync through `edgeSync` (see [edge-sync.md](edge-sync.md)). Sync is off until `edgeSync.remoteGateway` names a gateway-network connection.

## Limits

```yaml
profile: edge
limits:
  maxDevices: 50      # device and OPC UA connections together; 0 = no cap
  maxStorageMb: 2048  # size of the data directory; 0 = no cap
```

The edge profile supplies the values shown. Other profiles have no caps. A `limits` key in `gateway.yaml` replaces the profile value for that key only.

- Creating a device or connection past `maxDevices` is refused with `409 limit-reached`.
- Creating any configuration resource while the data directory is at or over `maxStorageMb` is refused with `507 storage-limit`.
- Editing and deleting existing resources always works, so an operator can free space.
- `GET /api/v1/diagnostics/environment` reports the caps next to the current device count and storage use.

History growth on the device is bounded by the `edgeSync.history` retention keys (`retentionDays`, `maxPoints`).
