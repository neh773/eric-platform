# Deployment modes: per-environment overrides

A deployment mode (`dev`, `test`, `prod`, any lowercase name) lets one configuration set carry per-environment
differences. The stored resource holds the base values. A mode adds an **overlay** with only the keys that differ, and
the active mode's overlay is merged over the base (RFC 7386 merge patch: a named key replaces, `null` removes a key,
objects merge key by key, lists are replaced whole). Doc source: FEATURES GAT-033 and GAT-037.

## Worked example: one database connection, three URLs

The base resource is the production connection `main`. It carries everything: driver, URL, user, pool settings.

`config/db/gateway/DatabaseConnection/main.yaml`

```yaml
apiVersion: oip/v1
kind: DatabaseConnection
metadata: {name: main, module: db}
spec:
  driver: postgresql
  url: jdbc:postgresql://db.prod.example.net:5432/plant
  username: plant_app
  password: secret://plant-db-password
  description: Plant historian database
```

The `dev` and `test` overlays name only what changes, here the URL:

`config/db/mode/dev/DatabaseConnection/main.yaml`

```yaml
apiVersion: oip/v1
kind: DatabaseConnection
metadata: {name: main, module: db}
spec:
  url: jdbc:postgresql://localhost:5432/plant_dev
```

`config/db/mode/test/DatabaseConnection/main.yaml`

```yaml
apiVersion: oip/v1
kind: DatabaseConnection
metadata: {name: main, module: db}
spec:
  url: jdbc:postgresql://db.test.example.net:5432/plant_test
```

Effective connection per active mode:

| Active mode | `url` | everything else |
|---|---|---|
| none (or `prod`, which has no overlay) | `jdbc:postgresql://db.prod.example.net:5432/plant` | base |
| `dev` | `jdbc:postgresql://localhost:5432/plant_dev` | base |
| `test` | `jdbc:postgresql://db.test.example.net:5432/plant_test` | base |

Overlays can also be written through the API, `PUT /api/v1/config/db/mode/dev/DatabaseConnection/main` with the
same document as the body, or through the admin Config page.

## Switching the mode

```sh
curl -X PUT http://localhost:8090/api/v1/deployment-mode \
  -H "Authorization: Bearer $OIP_TOKEN" -H "Content-Type: application/json" -d '{"mode":"dev"}'
```

`GET /api/v1/deployment-mode` returns the active mode and the modes that have overlays. Reading needs `gateway.read`;
switching needs `gateway.write` and writes one audit record. `{"mode": null}` clears the mode. No restart is needed:
the database service re-points a connection as soon as its effective URL or other settings change, so open pools are
replaced and new queries use the new database. A mode with no overlay for a resource leaves that resource as stored.

## Behavior to know

- Overlays apply to **gateway-scope** resources only, not project resources. The merged result is validated against
  the resource's schema; an overlay that makes it invalid faults the connection ("deployment mode override is
  invalid") instead of applying.
- The database service applies the active mode to **connections**. A `GET` of a connection reports the effective values.
  Saving a connection from the admin Databases page writes the values it shows to the stored resource, so edit
  a connection's base values while no mode is active, and edit a mode's values in its overlay resource.
- A secret reference stays a reference. Give each mode its own `password: secret://...` when the environments use
  different credentials; never put a password in an overlay.
- Raw reads (`GET /api/v1/config/...` of the base resource) are unaffected by the mode; the effective value is what
  the running service uses.

## Test

`gateway/db/src/test/kotlin/org/oip/db/DeploymentModeOverrideTest.kt` builds this example with three SQLite files,
switches the mode through `dev`, `test`, none, and back, and checks both the resolved resource and the rows a query
returns from the live connection.
