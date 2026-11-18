# Feature coverage

This table is the audit of user-facing behaviour in the 0.1.0-beta tree. Status is **documented** when an older manual page already covered it, **added** when a page was written for this audit, and **partial** when the page names it but does not walk every step.

## Front

| Feature | Manual page | Status |
| --- | --- | --- |
| What the product is | [Overview](overview.md) | added |
| License and business model | [Mission](mission-and-business-model.md) | added |
| Channels, LTS, support | [Support](support-and-maintenance.md) | added |
| Ask, fund, or send a PR | [Contributing features](contributing-features.md) | added |
| Beta warning | [Safety](safety.md) | documented |
| Installers and uninstall | [Install](install.md) | documented |

## Admin pages

| Feature | Manual page | Status |
| --- | --- | --- |
| Home, first-run setup | [Getting started](getting-started.md) | documented |
| Overview and health | [Diagnostics](gateway/diagnostics.md) | documented |
| Gateway settings | [Services](gateway/services.md) | added |
| PLC / ERIC resource split | [PLC tasks](plc/tasks.md) | documented |
| Modules | [Services](gateway/services.md) | added |
| Backups and restore | [Backups](gateway/backups.md) | documented |
| Canvas sessions | [Open Control Canvas](studio/open.md) | partial |
| Projects, import, export | [Projects](gateway/projects.md) | documented |
| Logs and levels | [Diagnostics](gateway/diagnostics.md) | documented |
| Diagnostics | [Diagnostics](gateway/diagnostics.md) | documented |
| Metrics dashboards | [Diagnostics](gateway/diagnostics.md) | documented |
| Store and forward | [Services](gateway/services.md) | added |
| Users and roles | [Users](gateway/users.md) | documented |
| API keys | [More security](gateway/security-more.md) | added |
| Identity providers | [More security](gateway/security-more.md) | added |
| User sources | [More security](gateway/security-more.md) | added |
| Security zones | [More security](gateway/security-more.md) | added |
| Security levels | [More security](gateway/security-more.md) | added |
| Security settings | [More security](gateway/security-more.md) | added |
| Secrets | [More security](gateway/security-more.md) | added |
| Audit | [More security](gateway/security-more.md) | added |
| OPC UA client certificates | [More security](gateway/security-more.md) | added |
| Outbound TLS | [More security](gateway/security-more.md) | added |
| OAuth2 clients | [More security](gateway/security-more.md) | added |
| Tags and UDT wizard | [Tags](gateway/tags.md) | documented |
| Historian | [Historian](gateway/historian.md) | documented |
| Alarming and notification pipelines | [Alarming](gateway/alarming.md) | documented |
| Event streams | [Services](gateway/services.md) | added |
| Email profiles | [Services](gateway/services.md) | added |
| Charts (SFC) | [Services](gateway/services.md) | added |
| Reports | [Services](gateway/services.md) | added |
| HTTP endpoints | [Services](gateway/services.md) | added |
| Config resource editor | [Connections](gateway/connections.md) | partial |
| Connections list | [Connections](gateway/connections.md) | documented |
| Databases, named queries, transaction groups | [Databases](gateway/databases.md), [Services](gateway/services.md) | documented |
| Network, HTTPS, gateway network | [Network](gateway/network.md) | added |
| Enterprise admin | [Network](gateway/network.md) | added |
| OPC UA connections | [Connections](gateway/connections.md) | partial |
| Architectures | [Network](gateway/network.md) | added |
| Deployment mode | [Diagnostics](gateway/diagnostics.md), [Services](gateway/services.md) | partial |
| Scripting console and libraries | [Scripting](gateway/scripting.md) | documented |
| REST groups | [REST](gateway/rest.md) | documented |

## Drivers

| Feature | Manual page | Status |
| --- | --- | --- |
| Modbus TCP/RTU | [Connections](gateway/connections.md) | documented |
| OPC UA client | [Connections](gateway/connections.md) | partial |
| OPC UA server | [More security](gateway/security-more.md) | partial |
| BACnet/IP | [Connections](gateway/connections.md) | partial |
| DNP3 | [Connections](gateway/connections.md) | partial |
| Logix | [Connections](gateway/connections.md) | partial |
| MQTT and Sparkplug | [Connections](gateway/connections.md) | partial |
| S7 | [Connections](gateway/connections.md) | partial |
| Simulator | [Connections](gateway/connections.md) | partial |
| UDP/TCP | [Connections](gateway/connections.md) | partial |

Device specs are edited on the Config page from each module's schema. There is no per-driver wizard.

## Control Canvas

| Feature | Manual page | Status |
| --- | --- | --- |
| Open, sign-in, project picker | [Open](studio/open.md) | documented |
| Workspace, docks, outline | [Workspace](studio/workspace.md) | documented |
| Palette and every registered type | [Components](studio/components.md) | added |
| Pipes and equipment ports | [Pipes](studio/pipes.md) | documented |
| Faceplates | [Faceplates](studio/faceplates.md) | documented |
| Trends and power chart | [Trends](studio/trends.md) | documented |
| Map and Leaflet map | [Map](studio/map.md) | documented |
| Bindings | [Bindings](studio/bindings.md) | documented |
| Events, scripts, script builder | [Events](studio/events-scripts.md) | documented |
| Styles | [Styles](studio/styles.md) | documented |
| Pages and navigation | [Pages](studio/pages.md) | documented |
| Find, save, preview | [Tools](studio/tools.md) | documented |
| Views | [Views](studio/views.md) | documented |

## Runtime

| Feature | Manual page | Status |
| --- | --- | --- |
| Sessions and navigation | [Sessions](runtime/sessions.md) | documented |
| Writes | [Writing](runtime/writing.md) | documented |
| Alarms and charts | [Alarms and charts](runtime/alarms-charts.md) | documented |
| Touch and component security | [Touch](runtime/touch-security.md) | documented |
| Public HMI | [Projects](gateway/projects.md) | partial |

## PLC

| Feature | Manual page | Status |
| --- | --- | --- |
| Overview | [Overview](plc/overview.md) | documented |
| Editor | [Editor](plc/editor.md) | documented |
| Check and deploy | [Programs](plc/programs.md) | documented |
| Tasks and core split | [Tasks](plc/tasks.md) | documented |
| Windows soft-PLC | [Windows](plc/windows.md) | documented |
| Standard library | [Stdlib](plc/stdlib.md) | documented |
| Process library and PID | [Process library](plc/process-library.md) | documented |
| Tag mapping | [PLC tags](plc/tags.md) | documented |
| Diagnostics and troubleshooting | [Diagnostics](plc/diagnostics.md), [Troubleshooting](plc/troubleshooting.md) | documented |

## Operations

| Feature | Manual page | Status |
| --- | --- | --- |
| oipctl | [Services](gateway/services.md) | added |
| Compose recipes | [Services](gateway/services.md) | added |
| Helm chart | [Services](gateway/services.md) | added |
| Edge, standard, hub profiles | [Services](gateway/services.md) | added |
| Redundancy panel | [Network](gateway/network.md) | added |
| Edge sync | [Network](gateway/network.md) | added |

## Known incomplete or broken behaviour

These are in the code or the changelog. They are not fixed here.

- On Windows, `https/server.crt` can stay locked after the TLS listener stops. A backup restore or wipe that replaces `https/` fails until the gateway restarts.
- Writing 100,000 tags in one batch took about 75 seconds on a 4-core test laptop. The test budget is 20 seconds.
- The MQTT high-point bench simulator refuses to start when the machine has only a loopback IPv4 address. The MQTT driver itself still runs.
- The Windows soft-PLC is not a real-time partition. The admin PLC page says so.
- Public HMI, when enabled on a project, opens a session that can read and write tags on that gateway.
- Example projects, including Brownsville, are not in the default installer. You import them.
- The wishlist still marks a fuller redundancy design as not started. The Network page already has the pair panel and failover, assume and resync.
