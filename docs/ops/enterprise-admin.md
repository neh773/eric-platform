# Enterprise administration

One gateway, the **controller**, manages many others, the **agents**, over the peer network. Agents check in with
their inventory and health, and run the tasks the controller queues for them: backup, restore, module install,
project push, tag push, and upgrade staging. Behavior differences from the reference product are DIFFERENCES 501-508.

Doc sources: FEATURES EAM-001 to EAM-017 and SCR-012.

## Roles

The role is the config resource `org.oip.gateway/gateway/EamSettings/eam`. Change it on the **Enterprise Admin**
admin page or with `PUT /api/v1/eam/settings` (gateway.write):

```json
{ "role": "controller", "autoApprove": false, "keepBackups": 5,
  "thresholds": { "offlineMillis": 60000, "cpuPercent": 90, "memoryPercent": 90, "diskPercent": 95, "failedTasks": 3 } }
```

An agent names the outgoing gateway-network connection to its controller, and optionally a second one for a
redundant controller:

```json
{ "role": "agent", "controller": "controller", "backupController": "controller-backup", "group": "north", "checkInMillis": 5000 }
```

## Adding an agent

1. On the agent, add an outgoing gateway-network connection to the controller (Network page or
   `POST /api/v1/gateway-network/outgoing`) and approve the controller's certificate.
2. Set the agent role naming that connection.
3. On the controller, approve the agent's certificate. The Enterprise Admin page lists certificates that are waiting.
4. The agent checks in and is listed as `pending`. Approve it. Until then it gets no task runs.

### First-boot files

Steps 1 and 2 can be done by a file. Put `eam-agent.json` in the data directory before the first start:

```json
{
  "controllerHost": "10.0.0.10",
  "controllerPort": 8060,
  "controllerFingerprint": "<64 hex characters from the controller's Network page>",
  "group": "north",
  "checkInMillis": 5000
}
```

Optional keys: `connection` (default `controller`), `backupControllerHost`, `backupControllerPort`,
`backupControllerFingerprint` (connection `<connection>-backup`). The fingerprint is trusted before the first
handshake, so the agent side needs no manual approval. The file is applied once, when the gateway has no role yet,
and renamed to `eam-agent.json.applied`. The controller still approves the agent.

## Tasks

`POST /api/v1/eam/tasks` creates a task. Without `schedule` it runs at once on every approved target.

| kind | needs | what the agent does |
|---|---|---|
| `backup` | backups.admin | makes an encrypted backup and uploads it; the controller keeps the newest `keepBackups` |
| `restore` | backups.admin | downloads one of its stored backups (`params.backup`, default newest) and restores it |
| `module-install` | modules.admin, `payload` | installs the uploaded module package, or upgrades it when installed |
| `send-project` | `params.project` | imports the controller's project (`params.policy`: `overwrite` or `skip`; `params.target` renames) |
| `send-tags` | | imports the controller's tag configuration (`params.provider`, `params.folder`, `params.policy`) |
| `upgrade` | modules.admin, `payload`, `params.version` | checks and stages the package under `upgrade/`; with `params.apply` also applies it (see Remote upgrade) |

Upload a package first with `POST /api/v1/eam/payloads` (body: the bytes). Targets are `agents` and `groups`.
`maxAttempts` (default 3), `retryDelayMillis` (5000), and `timeoutMillis` (300000) control retry. A failed run can
be retried with `POST /api/v1/eam/runs/{id}/retry`.

`schedule` is a five-field cron (minute, hour, day of month, month, day of week) or a six-field one with seconds,
evaluated in UTC on the controller. A scheduled task fires once per matching period.

## Groups and maintenance

`PUT /api/v1/eam/agents/{name}` sets `group`, `maintenance`, and `description`. An agent in maintenance gets no runs
(they wait in the queue) and raises no threshold events.

## Recovering an agent

If an agent's gateway is replaced, start the new gateway with the same gateway-network name and the same controller
connection. It checks in and is listed as `replacement-pending`. `POST /api/v1/eam/agents/{name}/recover`
(backups.admin) binds its certificate and restores the agent's newest stored backup to it.

## Inventory, status tags, and alarms

- `GET /api/v1/eam/inventory` (`?format=csv`): one row per agent.
- `[system]Gateway/EAM/Agents/<agent>/...` on the controller: `Status`, `Online`, `Group`, `Version`, `LastCheckIn`,
  `CpuPercent`, `MemoryPercent`, `DiskPercent`, `QueuedTasks`, `FailedTasks`, and `Thresholds/<metric>`.
- `[system]Gateway/EAM/Alarms/<agent>/<metric>` carries an alarm for each threshold (`Offline`, `Cpu`, `Memory`,
  `Disk`, `FailedTasks`), so the alarm table and notification pipelines see agent events.
- On an agent: `[system]Gateway/EAM/Controller/Connected`, `State`, `Connection`. Every gateway: `Gateway/EAM/Role`.

## Scripting

On a controller, gateway scripts have `system.eam.getGroups()`, `system.eam.queryAgentStatus(groupIds, agentIds,
isConnected)`, `system.eam.queryAgentHistory(groupIds, agentIds, startDate, endDate, limit)`, and
`system.eam.runTask(taskName)`.

## Redundant controller

Give agents a `backupController` connection to the other node of a redundancy pair. A standby refuses check-ins,
so agents check in with the active node. Agent approvals and tasks are config resources and follow config sync.

## Service policy

The controller serves the gateway-network service `eam`. A policy with access `deny` for a peer refuses it.

## Remote upgrade

A gateway that is started by `packaging/launcher/oip-gateway.sh` can apply an upgrade. The install root
(`OIP_INSTALL_DIR`) holds `current`, a one-line file naming the active version, and `versions/<version>/` with each
unpacked version; the launcher runs `versions/<current>/bin/oip-gateway`. The package is a zip of one version tree
with `bin/oip-gateway`.

An `upgrade` task with `params.apply: true` (and optionally `params.deadlineMillis`, default 120000) checks the
package, unpacks it, writes `<dataDir>/upgrade/pending.json` (old version, new version, deadline), switches `current`,
reports success to the controller, and then exits with code **75** (restart for upgrade). The service manager must
restart the launcher on 75: `packaging/launcher/oip-gateway.service` sets `SuccessExitStatus=75` and
`RestartForceExitStatus=75`; a container restart policy of `unless-stopped` or `always` and a Windows service wrapper
set to restart on any exit do the same.

On the next start the gateway clears the marker once it is up and records `applied` in `upgrade/result.json`. If it
finds the deadline already passed it points `current` back and exits 75. If the new version dies before that, the
launcher sees the marker, points `current` back, records `rolledBack` with the exit code as the reason, and exits 75, so
the old version starts. The agent inventory carries `installedVersion` and `upgradeResult` (`state`, `version`,
`restoredVersion`, `reason`), which the controller shows with the agent. A gateway without `OIP_INSTALL_DIR` refuses
`apply`; it still stages.
