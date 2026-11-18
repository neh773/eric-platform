# Gateway HTTPS

The gateway can serve the web UI and `/api/v1` over TLS. Plain HTTP stays available unless force-redirect is turned on. The container image and `scripts/image_smoke.sh` leave HTTPS off, so their health check stays on HTTP.

Ports, the public address, and the redirect follow the public web-server settings page. Certificate choices (self-signed, CA-signed, or uploaded) follow the public secure-communication page.

- -
- -

The edge profile still binds `127.0.0.1`.

## `gateway.yaml`

```yaml
http:
  host: 0.0.0.0
  port: 8090
  https: false                 # true opens a TLS listener and marks the session cookie Secure
  httpsPort: 8043              # 0 asks the kernel for a port
  publicAddress: gw.example    # hostname or IP, no scheme and no port
  publicHttpsPort: 443         # omit to use the bound HTTPS port; 443 is left out of the URL
  forceHttpsRedirect: false    # true answers every plain-HTTP request with 308
  certificate: /etc/oip/tls/fullchain.pem
  privateKey: /etc/oip/tls/privkey.pem
  # pkcs12: /etc/oip/tls/server.p12   # instead of certificate + privateKey
```

`http.https: false` is the default. `http.https: true` with no certificate files generates a self-signed certificate on first start and reuses it after that. The certificate's subject alternative names include `localhost`, `127.0.0.1`, the bind address when it is a real host, and `publicAddress` when that is set. Deleting `https/server.crt` and `https/server.key` mints a new certificate on the next start with HTTPS on.

`forceHttpsRedirect` requires `https: true`. It is off by default. While it is on, every plain-HTTP request, including `GET /api/v1/health`, returns **308** to `https://<public address>:<port><path>`. The public address is used when it is set. Otherwise the redirect uses a hostname-shaped Host header, or the bind address. Port 443 is omitted. HTTPS requests are not redirected. Turn the redirect off, or point health checks at HTTPS, before enabling it in a container. The image health check uses the HTTP port.

A PEM path pair and `pkcs12` cannot both be set. Those values are file paths. Pasting a PEM block into `gateway.yaml` is rejected. The PKCS#12 password is `OIP_HTTPS_PKCS12_PASSWORD`, not a yaml key. It is not logged. Setting `OIP_HTTPS_PKCS12` ignores `certificate` and `privateKey` from the file. Setting `OIP_HTTPS_CERT` or `OIP_HTTPS_KEY` ignores `pkcs12` from the file.

Changing `certificate`, `privateKey`, or `pkcs12` is applied on the next start. A REST upload or a file drop applies immediately when the TLS listener is already up.

## Files

Active material lives under `<dataDir>/https/`:

| File | Mode | Contents |
|---|---|---|
| `server.crt` | readable certificate | PEM chain, leaf first |
| `server.key` | `0600` | unencrypted PKCS#8 PEM private key |
| `server.source` | readable | `generated`, `configured`, `uploaded`, `file`, or `csr` |
| `tls.json` | readable | the chosen TLS protocols and cipher suites (see below) |
| `pending.key` | `0600` | private key of a pending certificate signing request |
| `pending.csr` | readable | the pending PKCS#10 request (PEM) |

The directory is owner-only. The private key is not written to config resources, API responses, audit detail, or logs.

The gateway backup (a zip inside the passphrase encryption) includes `https/` (and `opcua/`). Certificates, `server.source`, `tls.json`, and `pending.csr` are stored as they are. `server.key`, `pending.key`, and any file with a PEM `PRIVATE KEY` marker are sealed with AES-256-GCM under a key derived from the gateway secrets key. **Restoring the sealed entries needs the original secrets key**: the archive's own copy of it or the live gateway's key must match, or the restore is refused before anything changes. `incoming/` and `rejected/` are not archived; a restore recreates them empty and reloads the listener. An archive that has `server.crt` but no sealed `server.key` (made without a secrets key) leaves `https/` as it is. See [Certificate material and sealed keys](../ops.md#certificate-material-and-sealed-keys).

Drop a replacement into `<dataDir>/https/incoming/` and wait for two unchanged reads (about a second):

- `certificate.pem` and `private-key.pem`, or
- `server.p12`, with the password in `password` (one trailing newline is ignored) or in `OIP_HTTPS_PKCS12_PASSWORD`

Write the password file before the PKCS#12 file. A bad drop is moved to `https/rejected/<id>/` with `error.txt`. The live certificate is left as it was. The error file does not contain the key.

## REST and admin UI

`GET /api/v1/https` requires `gateway.read`. It returns the listener, ports, public address, redirect flag, protocols (`TLSv1.2` and `TLSv1.3`), and certificate subject, fingerprint, names, and validity. It does not return the private key.

`PUT /api/v1/https/certificate` requires `gateway.write` and, for a browser session, `X-CSRF-Token`.

```json
{ "format": "pem", "certificatePem": "-----BEGIN CERTIFICATE-----\n...", "privateKeyPem": "-----BEGIN PRIVATE KEY-----\n..." }
```

```json
{ "format": "pkcs12", "pkcs12Base64": "<base64>", "password": "<secret>" }
```

The PEM key must be unencrypted PKCS#8 or PKCS#1. An encrypted key is rejected; use PKCS#12 for a password-protected key. The leaf must match the key, and each certificate must be signed by the next one. A mismatch is **422** and the running certificate does not change. A valid upload is installed on the TLS listener without a process restart. The response is the same document as `GET /api/v1/https`.

Every mutating `/api/v1/https/...` route requires `gateway.write` (and `X-CSRF-Token` for a browser session) and writes an audit record. A denied request is audited as `FAILURE` with the caller's name and address. Reads require `gateway.read`. Request bodies are capped at 256 KiB.

### Certificate signing request (CSR)

The private key for a CA-signed certificate is made on the gateway and never leaves it.

1. `POST /api/v1/https/csr` creates a key and a PKCS#10 request:

   ```json
   { "commonName": "gw.example.com", "organization": "Plant", "organizationalUnit": "OT", "locality": "Town",
     "state": "State", "country": "US", "sans": ["gw.example.com", "IP:10.1.2.3"], "keyType": "RSA", "keySize": 3072 }
   ```

   `commonName` is required; the other subject fields are optional, at most 64 printable characters. `country` must be exactly two letters (`us` is stored as `US`); anything else is **422**. `sans` are DNS names or `IP:` addresses; with none, the common name is used. `keyType` is `RSA` (2048, 3072, or 4096 bits; default 2048) or `EC` (P-256). The key goes to `https/pending.key` (`0600`). A new request replaces a pending one. The response holds the subject, names, a SHA-256 of the public key, and `csrPem`, never the key.
2. `GET /api/v1/https/csr` returns the pending request (**404** when there is none). `?download=true` returns it as `application/pkcs10` (`gateway.csr`). Send that file to the CA.
3. `POST /api/v1/https/csr/install` with `{ "certificatePem": "<leaf, intermediates, optional root, any order>" }` installs the CA reply. The gateway finds the leaf that matches the pending key and orders the chain. A reply for another key, or a certificate that is not part of the chain, is **422** and the live certificate does not change. A valid reply is applied to the running listener, and `pending.key` and `pending.csr` are deleted.
4. `DELETE /api/v1/https/csr` discards a pending request.

Only PEM replies are accepted, not DER or PKCS#7. No route exports a private key.

### TLS protocols and cipher suites

`PUT /api/v1/https/tls` sets the protocols and cipher suites the listener offers:

```json
{ "protocols": ["TLSv1.2", "TLSv1.3"], "cipherSuites": ["TLS_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"] }
```

- `protocols` is one or both of `TLSv1.2` and `TLSv1.3`. TLS 1.1 and older are refused with **422** and are always disabled on the listener.
- `cipherSuites: null` (or omitted) means the JDK's enabled suites minus the weak list. A list must contain only suites the JDK offers, at least one usable with each chosen protocol, and none from the weak list.
- The weak list is fixed: NULL, anonymous, export, RC4, DES/3DES, MD5, SHA-1 MAC, static RSA/DH/ECDH key exchange, and every CBC suite. Those suites are refused with **422** and are also dropped from the default.

The choice is saved to `https/tls.json` and applied to the running listener without a restart; open connections keep their session. `GET /api/v1/https` reports `configuredProtocols`, `configuredCipherSuites`, the listener's actual `protocols` and `cipherSuites`, and `availableCipherSuites`.

### Web server settings

`PUT /api/v1/https/settings` takes any of `httpPort`, `httpsEnabled`, `httpsPort`, `publicAddress`, `publicHttpsPort`, and `forceHttpsRedirect`. The new `gateway.yaml` is checked with the same rules as at start and written atomically; comments in the file are not kept. The change applies at the next start, so the response's `savedSettings` has `restartRequired: true` and `overriddenByEnvironment` lists fields that an `OIP_HTTP_*` variable overrides.

The admin Network page shows the status document, uploads PEM or PKCS#12, edits the web server settings and TLS policy, and runs the CSR flow (create, download, discard, install by paste or file). Users without `gateway.write` see it read-only. It never renders a private key.

## Environment

Empty values are ignored, so a compose file can pass them through only when they are set.

| Variable | Overrides |
|---|---|
| `OIP_HTTP_HTTPS` | `http.https` (`true` or `false`) |
| `OIP_HTTP_HTTPS_PORT` | `http.httpsPort` |
| `OIP_HTTP_PUBLIC_ADDRESS` | `http.publicAddress` |
| `OIP_HTTP_PUBLIC_HTTPS_PORT` | `http.publicHttpsPort` |
| `OIP_HTTP_FORCE_REDIRECT` | `http.forceHttpsRedirect` |
| `OIP_HTTPS_CERT` | `http.certificate` |
| `OIP_HTTPS_KEY` | `http.privateKey` |
| `OIP_HTTPS_PKCS12` | `http.pkcs12` |
| `OIP_HTTPS_PKCS12_PASSWORD` | password for a configured or dropped PKCS#12 file |

`http.https: true` still marks the session cookie `Secure`, including on the plain HTTP port. See [http-auth.md](http-auth.md).

## Container

`compose.yaml` publishes plain HTTP only. Set `OIP_HTTP_HTTPS=true` and publish the HTTPS port yourself, for example `8043:8043`, when you want TLS on the image. Leave `OIP_HTTP_FORCE_REDIRECT` unset so `/api/v1/health` on the HTTP port stays 200. Do not put `OIP_HTTPS_PKCS12_PASSWORD` in an image or a committed file.
