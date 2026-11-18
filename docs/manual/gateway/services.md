# Reports, email, charts, events and other services

These are admin pages that are not covered on the tags, historian, alarm or database pages.

## Reports

**Reports** opens a canvas. Add a text block or a table, bind a dataset to a named query, preview, and save. `GET /api/v1/reports/components` lists component types. Preview is `POST /api/v1/reports/preview`. PDF is `POST /api/v1/reports/preview/pdf` or `POST /api/v1/reports/{name}/pdf`. A saved report can be scheduled; `GET /api/v1/reports/{name}/status` shows the last run. Files from a run are `GET /api/v1/reports/{name}/files`.

## Email profiles

**Email Profiles** stores an SMTP host, port, username and from-address. The password is a secret, or the profile names an OAuth2 client and sends with XOAUTH2. **Send test** is `POST /api/v1/email/profiles/{name}/test`. A 535 from the server comes back as a short error and does not include the password.

## Charts

**Charts** edits sequential function charts: steps, transitions and actions. The monitor view highlights the active step. Charts are resources. The page saves them through the config API. There is no separate REST group beyond config.

## Event streams

**Event Streams** creates a source, a handler and a pipeline (for example a Kafka topic to a script). **Try** runs one event through the pipeline. Create, edit and delete are on the page. A stream that fails to connect shows the error on the row.

## HTTP endpoints

**HTTP Endpoints** publishes a path the gateway answers with a Python script (webdev). `GET` or `POST` under the path you set returns the script's body. The script runs as the gateway, so do not put a secret in the script text. Put it in the secret store and read the reference.

## Modules

**Modules** installs a `.oipmodule` zip, upgrades it, or removes it.

- `GET /api/v1/modules`
- `POST /api/v1/modules` with the zip body
- `POST /api/v1/modules/{id}/install`
- `DELETE /api/v1/modules/{id}`

A bad zip is refused and leaves no files. The built-in drivers can be removed and installed again from this page.

## Store and forward

**Store and Forward** shows, per history provider, pending rows, quarantined rows and the store rate. Export downloads the quarantine. **Archive** writes a JSON file and removes those rows from the buffer only after the file reads back. **Load** puts an archive back. Retry and delete of one quarantine row are `POST /api/v1/diagnostics/history/quarantine/retry` and `.../delete`. A read-only user sees the counts and cannot press those buttons.

## Deployment

**Deployment** shows the active mode: edge, standard or hub. Switching mode is `PUT /api/v1/deployment-mode` with `{"mode":"edge"}`. The edge profile binds loopback and sets device and storage caps. Standard and hub do not. Gateway settings (system name, default HMI project) are **Gateway Settings**, `PUT /api/v1/gateway/settings`.

## Transaction groups

Transaction groups are on **Databases**, under the connections and named queries. A group reads tags on an interval and writes rows, or runs a stored procedure. **New transaction group** sets the connection, the rate and the action. The group runs in the gateway. If no default database connection exists, the log says `transaction group ... failed: no default database connection`.

## Command line

`oipctl` talks to a running gateway or controls the process:

```
oipctl --url http://127.0.0.1:8090 health
oipctl --url http://127.0.0.1:8090 --token "$OIP_TOKEN" config get org.oip.tags gateway Tag speed
oipctl backup --url http://127.0.0.1:8090 --token "$OIP_TOKEN" -o plant.oipbk
oipctl start --data-dir /var/lib/eric --port 8090
oipctl stop --data-dir /var/lib/eric
oipctl restart --data-dir /var/lib/eric
oipctl reset-admin --data-dir /var/lib/eric --password-file ./pw.txt
oipctl rotate-secret-keys --data-dir /var/lib/eric
```

`reset-admin` only works while the process is stopped. `module install` posts a zip. `project export` and `project import` match the project API.

## Containers

Compose recipes are in `packaging/compose`: `persistent.yaml` keeps the data volume, `restore.yaml` restores from `OIP_RESTORE_DIR`. Set `OIP_ADMIN_PASSWORD` in the environment. Do not commit it.

```
OIP_ADMIN_PASSWORD='...' docker compose -f packaging/compose/persistent.yaml up -d
```

The Helm chart `packaging/helm/oip-gateway` runs one gateway. The data directory is a persistent volume. The admin password comes from an existing Secret. Kubernetes 1.25 or newer.
