# Scripting overview and where it runs

Doc source: FEATURES SCR-037. Scripts are Python run by the gateway's embedded scripting host. The `system.*` functions a script sees depend on the scope it runs in.

## Scopes

| Scope | Runs where | Configured in |
| --- | --- | --- |
| gateway | On the gateway, with no signed-in user (the acting user is `system`). Gateway event scripts, message handlers, timers, transaction group and alarm pipeline scripts. | [Config](/admin/config) (`GatewayEvent` and `ScriptModule` resources), Designer Tools > Script libraries |
| session | On behalf of a signed-in HMI session: view event scripts, script transforms, and the project script hooks. | Designer: view inspector, event editor, script hooks panel |
| designer | In the Designer script console, as the signed-in Designer user. Same functions as gateway. | Designer: Console panel |

Script libraries (`ScriptModule`) are project resources, shared by every scope of that project.

## system namespaces by scope

Namespaces built into every script: tag, date, dataset, util, db, security, alarm, file, net, http, config, historian, device, opc, opcua, user, sfc, eam, math, project, secrets, roster, eventstream, groups, report, bacnet, dnp.

Namespaces with functions available in the gateway scope: tag, date, dataset, util, db, security, math, project, file, http, alarm, config, historian, device, opc, opcua, user, net, secrets, roster, eventstream, groups, report, bacnet, dnp, sfc.

Namespaces with functions available in the designer scope: tag, date, dataset, util, db, security, math, project, file, http, alarm, config, historian, device, opc, opcua, user, net, secrets, roster, eventstream, groups, report, bacnet, dnp, sfc.

Namespaces with functions available in the session scope: tag, date, dataset, util, db, security, math, project, screen.

`system.util.scopeHints(scope)` returns the exact function list for a scope.

## Gateway-only library functions

Functions in these namespaces refuse to run for a signed-in user's session: opcua, user, project, net, secrets, roster, eventstream, groups, report, bacnet, dnp, config, historian, device, opc, file, http, sfc.

`system.sfc.getRunningCharts` is the one sfc function a session may call.

`system.bacnet` (SCR-007) reads and writes any property of a configured BACnet/IP remote device (`readRaw`, `writeRaw`, `writeWithPriority`) and sends it the gateway clock (`synchronizeTime`, `synchronizeTimeUtc`). The object type is its number or its name, such as `analog-output`. A write returns `{quality, good, diagnostic}`; a null value relinquishes a commanded value at the priority given.

`system.dnp` (SCR-003) sends commands to a configured DNP3 outstation: `selectOperateBinary` and `directOperateBinary` take the point index, the trip/close code (`NUL`, `CLOSE`, `TRIP`), the operation (`PULSE_ON`, `PULSE_OFF`, `LATCH_ON`, `LATCH_OFF`), and optionally the count and on and off times in milliseconds; `selectOperateAnalog` and `directOperateAnalog` take the index, the value and optionally the group 41 variation. Each returns `{ok, status, statusName, diagnostic}` with the status the outstation echoed.
