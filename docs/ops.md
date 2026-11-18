# Gateway operations

## Backups

**Warning:** A gateway backup contains the secrets key and the secret store. Every backup the gateway creates is encrypted under a passphrase you choose (see [Passphrase encryption](#passphrase-encryption)), so the file on its own does not reveal them. Anyone who holds the file **and** its passphrase can decrypt the stored secrets. The gateway does not keep the passphrase: a lost passphrase means the backup cannot be restored.

Inside that encryption the archive is a zip (`oip-gateway-backup` v1). It holds the config tree, a runtime database dump, and the installed-module manifest. When the files exist it also holds:

- `secrets.key` — the gateway secrets key. On disk this is `<dataDir>/secrets/gateway.key` (directory mode `0700`, file mode `0600`). The zip entry name stays `secrets.key`.
- `db/secrets.sql` (the encrypted secret store)
- `db/audit.sql` (the audit log)
- `db/audit.anchor` (the audit head)
- `certs/https/...` and `certs/opcua/...` (public certificate material: the web certificate chain, `server.source`, `tls.json`, a pending `pending.csr`, the OPC UA certificate, and the OPC UA trust store's certificates)
- `sealed/https/...` and `sealed/opcua/...` (private material: the web `server.key`, a pending CSR's `pending.key`, the OPC UA `server.key`, `server.pfx`, and `store.pass`, and any other file that is not plainly a certificate or carries a PEM `PRIVATE KEY` marker)

Restore writes that key back to `secrets/gateway.key`. A new data directory loads it, so the secret store and the audit chain stay readable. Wipe deletes `secrets/gateway.key`.

`OIP_SECRETS_KEY` is the key itself (base64 or 64 hex characters). It overrides the file, and there is then no key file to put in the archive. `OIP_SECRETS_KEY_FILE` is a path and overrides `secrets/gateway.key`. When neither variable is set, the gateway uses `secrets/gateway.key` and creates it on first start if it is missing. A stray `<dataDir>/secrets.key` is moved to `secrets/gateway.key` at startup when that file is absent. An existing `secrets/gateway.key` is left as it is.

```
oipctl backup --output gw.oipbk --passphrase-file /path/to/pass
oipctl restore --file gw.oipbk --passphrase-file /path/to/pass
```

`POST /api/v1/backups` with `{"passphrase": "..."}` returns the same encrypted file and stores it as `<dataDir>/backups/<timestamp>.oipbk`. `bundleModules=true` (or `oipctl backup --bundle-modules`) also stores module packages. Creating, downloading, or restoring a backup over HTTP requires `backups.admin` (administrator only). Listing backup names and sizes requires `gateway.read`. Backup create, restore, and log-level changes each write one audit record. `oipctl` sends `Authorization: Bearer` from `--token` or `OIP_TOKEN`. See [http-auth.md](ops/http-auth.md).

### Certificate material and sealed keys

Each `sealed/` entry is encrypted with AES-256-GCM under a key derived (HKDF-SHA256) from the gateway secrets key, with the entry path as associated data. No private key is stored in the clear. The manifest field `certificateKeyCheck` is a one-way check value for that key.

**A sealed entry can only be restored with the secrets key it was sealed under.** Restore tries the secrets key carried in the archive, then the live gateway's key. If neither matches `certificateKeyCheck`, the restore is refused before anything changes. Keep the original secrets key: a gateway rebuilt with a new key cannot open the sealed entries of an archive that does not carry the original. A backup made while no secrets key is available holds only the `certs/` entries. Restoring such an archive does not touch `https/` or `opcua/`: a directory whose certificate (`server.crt`, `pending.csr`, or the OPC UA `server.pfx`) arrives without its private half is left as it is on disk, so the live key stays and the next start still serves HTTPS. Other directories and the rest of the archive are restored as usual. With `OIP_SECRETS_KEY` the archive carries no key, so the gateway you restore onto must run with the same `OIP_SECRETS_KEY`.

Once the secrets key is in the archive, the sealing only stops someone from reading a single entry without the rest of the zip. Whoever has the decrypted zip also has the key. The backup passphrase (below) is what protects the sealed keys in a stored or downloaded backup.

`https/incoming/` and `https/rejected/` are not archived. A restore whose archive has `https/` or `opcua/` entries replaces that whole directory (owner-only directories, `0600` key files), recreates `https/incoming/` and `https/rejected/` empty, and reloads the HTTPS listener with the restored certificate and TLS policy. The OPC UA server picks up its restored certificate at the next start. The backup service's wipe (used before a clean restore in tests) removes both directories. Symbolic links inside `https/` and `opcua/` are not followed when the archive is built.

### Passphrase encryption

Every backup created through `POST /api/v1/backups`, the admin Backups page, or `oipctl backup` is encrypted by the gateway before it is written to `<dataDir>/backups/` or returned. There is no unencrypted option. The file is AES-256-GCM over the whole zip. The key is derived with Argon2id (19 MiB, 2 iterations, 1 lane) from the passphrase and a random salt. The passphrase is not written into the file and is not kept by the gateway. A new backup needs a passphrase of at least 12 characters. Neither the stored file nor the download holds `secrets.key`, the secret store, or any zip entry name in clear.

The file format is the one `oipctl backup --encrypt` wrote before this change (header `OIPENC1`), so those files restore the same way. `--encrypt` is still accepted and has no further effect.

`oipctl` prompts for the passphrase. `--passphrase-file` reads it instead. A single trailing newline in that file is not part of the passphrase. The passphrase is never taken from the command line. `oipctl restore` checks the passphrase locally, then sends the encrypted file and the passphrase, so the decrypted zip never crosses the network.

```
oipctl backup --output gw.oipbk --passphrase-file /path/to/pass
oipctl restore --file gw.oipbk --passphrase-file /path/to/pass
```

Over HTTP (all routes need `backups.admin` and, with a session cookie, the CSRF header):

- `POST /api/v1/backups[?bundleModules=true]`, body `{"passphrase": "..."}`: creates, stores, and returns the encrypted file (`application/octet-stream`, name in `X-Backup-Name`).
- `GET /api/v1/backups/{name}`: the stored encrypted file, byte for byte.
- `POST /api/v1/backups/{name}/restore`, body `{"passphrase": "..."}`.
- `POST /api/v1/backups/restore`, `application/json` body `{"passphrase": "...", "archive": "<base64 of the file>"}`.

A wrong passphrase, or an encrypted file sent without one, is refused with 422 and the gateway is left unchanged: the file is decrypted and checked before anything on disk changes.

Encryption does not remove the secrets key (`secrets.key` in the archive, `secrets/gateway.key` on disk), the secret store, or the sealed certificate keys from the zip inside. It stops someone who has only the file from reading them. Someone who has the file and the passphrase can still decrypt the stored secrets and open the sealed keys. Store the passphrase apart from the file.

#### Plaintext backups from an older version

Earlier versions stored REST and admin UI backups as plaintext `<timestamp>.zip` files under `<dataDir>/backups/`, with `secrets.key` in clear. The gateway still lists them (`"encrypted": false`; the admin page shows "Not encrypted") and can restore them by name, but it never serves them for download (`GET` returns 409). To replace one: restore it if you need its contents, create a new encrypted backup, then delete the old `.zip` from `<dataDir>/backups/` and from any copies. An unencrypted zip can still be uploaded to `POST /api/v1/backups/restore` as `application/zip`, or with `oipctl restore` without a passphrase.

## Lost administrator password

`OIP_ADMIN_PASSWORD` seeds `admin` only when the user source is empty. It does not replace an existing password.

Stop the gateway first. `reset-admin` refuses to run while `gateway.lock` is held. The new password is prompted, or read from a file. It is not a command-line argument, so it does not appear in the process list. A single trailing newline in the file is not part of the password. The password has to satisfy the internal password policy (default: at least 8 characters, and 3 of lowercase, uppercase, digit, and special). It must not contain the username.

```
oipctl stop --data-dir /var/lib/oip
oipctl reset-admin --data-dir /var/lib/oip --password-file /path/to/pass
oipctl start --data-dir /var/lib/oip
```

`--user NAME` resets that user instead of `admin`. A missing user is created with the administrator role. An existing user's roles are left as they are.

The password is not written into the audit detail. The config resource stores the hash.

In Docker, `docker stop` the container (the data volume releases `gateway.lock` when the process exits), run `reset-admin` against that volume from a machine with `oipctl`, then start the container again. `oipctl stop` signals the pid in `gateway.pid` on the same machine; it is not how you stop a container.

the reference platform's `gwcmd -p` instead arms a temporary user source on the next restart. This gateway has one internal user source, so the recovery path sets that password directly. See DIFFERENCES.md entry 31.

## Start, stop, and restart

`oipctl start` launches the gateway JVM against `--data-dir` (or `OIP_DATA_DIR`, or `./data`). It prints the client URL when the listener is open. `--port` overrides `gateway.yaml`, and `--port 0` asks the kernel for a port. `--foreground` runs in this process.

`oipctl stop` signals the process recorded in `<dataDir>/gateway.pid`. `oipctl restart` stops it when it is running, then starts it again. A port from the previous `gateway.http` file is reused when `--port` is omitted.

While the gateway is stopped, edit `gateway.yaml` to change `http.port`, `profile`, `http.https`, or `opcua`, then start. That file is the offline settings change. Unknown keys are rejected at startup. HTTPS certificates, the public address, and the HTTP-to-HTTPS redirect are in [ops/https.md](ops/https.md). The private key under `https/server.key` is mode `0600`. The backup holds it only as a sealed entry inside the encrypted file (see [Certificate material and sealed keys](#certificate-material-and-sealed-keys)).

`start` and `stop` do not use `/api/v1` and do not take a token. The other `oipctl` commands still call the running gateway.

## Log files

The gateway writes plain-text rolling log files to `<dataDir>/logs/`, independent of the web log viewer: `gateway.log` is the live file, and `gateway.log.1`, `gateway.log.2`, ... are older generations. One line per record: `timestamp LEVEL logger message key=value ...`. The structured `gateway.jsonl` stays beside them. Tune the files in `gateway.yaml`:

```yaml
logging:
  files: true       # default; false turns the plain-text files off
  maxFileKb: 5120   # roll the live file at this size (default 5 MiB)
  maxFiles: 5       # files kept in total, live one included (default 5)
```

Disk use is bounded by `maxFiles * maxFileKb`. The same per-logger levels as the web log viewer decide which records are written. Changes apply at the next start. Doc source: FEATURES GAT-025.

## Deployment modes

Per-environment overrides, such as a different database URL in dev, test and prod, are in [ops/deployment-modes.md](ops/deployment-modes.md).

## Kubernetes

A Helm chart is in `packaging/helm/oip-gateway/`; see its README.

## MQTT Sparkplug B edge node

`EdgeNode` resources in the MQTT driver publish gateway tags as a Sparkplug B 3.0 edge node: births, report-by-exception data, NDEATH as the will, tag writes from NCMD and DCMD, rebirth, and primary-host STATE. Config, message rules, and limits are in [ops/sparkplug-edge.md](ops/sparkplug-edge.md).
