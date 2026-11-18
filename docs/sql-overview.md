# SQL usage overview

Doc source: FEATURES DAT-030. Where SQL is used in the platform and where to configure each use.

| Use | What it does | Admin page |
| --- | --- | --- |
| Database connections | JDBC connections to PostgreSQL, SQL Server, SQLite and others, with status and the query browser | [Databases](/admin/databases) |
| Named queries | Parameterised, project-scoped statements called from bindings and scripts | [Databases](/admin/databases) |
| Transaction groups | Timed or triggered inserts and updates between tags and tables | [Config](/admin/config) |
| Tag history | Samples stored in a SQL historian provider | [Historian](/admin/historian) |
| Alarm journal | Alarm events written to a database journal | [Store and Forward](/admin/store-and-forward) |
| Audit | Audit entries stored and queried | [Audit](/admin/audit) |
| Scripting db functions | `system.db.runNamedQuery`, `runPrepQuery`, `runPrepUpdate`, `beginTransaction` and the rest of `system.db` | [Connections](/admin/connections) |

Scripting scopes that may call `system.db` are listed in the [scripting overview](scripting-overview.md).
