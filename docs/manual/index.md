# ERIC user manual

ERIC (Engineered Runtime Interface & Control) is a gateway that connects to devices, holds tags, history and alarms, and serves operator screens. It has three surfaces, all in a browser:

| Surface | Address | Used for |
| --- | --- | --- |
| Gateway admin | `/admin` | Configure the gateway: tags, databases, historian, users, projects, backups, diagnostics. |
| Control Canvas | `/studio/?project=<name>` | Design a project's views, pages, styles and scripts. |
| Runtime session | `/hmi/<project>/` | What operators use: live values, writing setpoints, alarms, charts. |

All three share one sign-in (the gateway's user accounts and roles).

## Contents

- [What ERIC is and what it can do](overview.md)
- [Mission and business model](mission-and-business-model.md)
- [Support and maintenance](support-and-maintenance.md)
- [Contributing features](contributing-features.md)
- [Future features and wishlist](wishlist.md)
- [Known bugs (0.1.1-beta)](known-bugs.md)
- [Funding and support](funding.md)
- [Release process (proposal)](release-process.md)
- [Feature coverage](feature-coverage.md)
- [Safety and disclaimer](safety.md)
- [Install and uninstall](install.md)
- [Troubleshooting on Windows](troubleshooting-windows.md)
- [Getting started](getting-started.md)
- [Getting started for testers](getting-started-testers.md)
- Gateway admin: [connections and devices](gateway/connections.md), [tags](gateway/tags.md), [historian](gateway/historian.md), [alarming](gateway/alarming.md), [databases](gateway/databases.md), [users and roles](gateway/users.md), [more security](gateway/security-more.md), [network, redundancy and enterprise admin](gateway/network.md), [reports and other services](gateway/services.md), [projects](gateway/projects.md), [scripting](gateway/scripting.md), [REST API](gateway/rest.md), [backups](gateway/backups.md), [diagnostics, logs, metrics dashboards and deployment modes](gateway/diagnostics.md)
- Control Canvas: [open the Control Canvas](studio/open.md), [workspace](studio/workspace.md), [views and components](studio/views.md), [component catalog](studio/components.md), [pipes](studio/pipes.md), [faceplates](studio/faceplates.md), [trends](studio/trends.md), [map](studio/map.md), [bindings](studio/bindings.md), [events and scripts](studio/events-scripts.md), [styles](studio/styles.md), [pages and navigation](studio/pages.md), [find, diagnostics, save and preview](studio/tools.md)
- PLC: [overview](plc/overview.md), [editor](plc/editor.md), [write, check, and deploy](plc/programs.md), [tasks and the core split](plc/tasks.md), [Windows soft-PLC](plc/windows.md), [standard library](plc/stdlib.md), [process library](plc/process-library.md), [tags](plc/tags.md), [structures and gateway UDTs](plc/udts.md), [diagnostics](plc/diagnostics.md), [troubleshooting](plc/troubleshooting.md)
- Runtime: [sessions, navigation and live values](runtime/sessions.md), [writing a value](runtime/writing.md), [alarms and charts](runtime/alarms-charts.md), [touchscreens and security](runtime/touch-security.md)
- Examples: [Brownsville wastewater](examples/brownsville.md)

The screenshots use a demo project, `demo-plant`: five memory tags, an Overview view with labels, a gauge, a button and a power chart, and a Pumps page.

Where a task cannot be done in a page yet, the manual says so and shows the API call instead.
