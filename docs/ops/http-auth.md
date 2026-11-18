# Gateway HTTP authentication

Browser clients call `POST /api/v1/auth/login` and then send the `oip_session` cookie. State-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`) also send `X-CSRF-Token` from the login (or `GET /api/v1/auth/session`) response. Machine clients send `Authorization: Bearer` and do not send a CSRF token.

`GET /api/v1/health` and `GET /api/docs` do not require credentials. Logout and `GET /api/v1/auth/session` need a session.

Reads of config, modules, the backup list, projects, logs, and diagnostics require `gateway.read` (operator and administrator). Importing a project, changing a log level, and other config writes require `gateway.write` (administrator). Creating, downloading, or restoring a backup requires `backups.admin` (administrator only): the archive holds `secrets.key`, the secret store, and every user. Each backup is encrypted under a passphrase supplied on create and needed on restore ([ops.md](../ops.md#passphrase-encryption)). Installing, upgrading, or uninstalling a module requires `modules.admin`. Reading the audit log requires `audit.read`. A viewer has none of the gateway read or write permissions, so those routes are forbidden.

Every `/api` route has an explicit rule. A registered route with no rule is refused for every caller (`403`). Any other `/api` path needs credentials (`401` without them) and then returns `404`.

Users, roles, and API keys are managed only through `/api/v1/security/*`. The generic config API (`/api/v1/config`, the batch route, and project import) refuses `org.oip.security` `User`, `Role`, and `ApiKey` writes for every caller. Reading those resources there needs `users.admin`, `roles.admin`, or `apikeys.admin`, and responses never include `passwordHash`, `passwordHistory`, or `keyHash`. Writing the other security settings (zones, gateway security, password policy, audit profile) there needs `users.admin`, `roles.admin`, and `apikeys.admin` as well as `gateway.write`.

A gateway seeded before `backups.admin` existed gives it to every role that already holds every other permission (the seeded administrator), on the next start and after a restore.

## Session cookie

The cookie is host-only, `Path=/`, `HttpOnly`, and `SameSite=Strict`.

`Secure` is **on** when any of these is true:

- `http.https: true` (clients reach the gateway over HTTPS, including a TLS proxy in front of this process)
- `http.trustedProxies` is non-empty
- `http.secureCookies: true`

It is **off** for a plain HTTP listener with no trusted proxy. The process logs a warning at startup. Browsers drop a `Secure` cookie on `http://192.168.x.x` (Safari also drops it on `http://localhost`), so plain HTTP has to omit the flag until TLS is in front. The gateway can terminate TLS itself; see [https.md](https.md). Set `http.secureCookies: false` to force the flag off even behind HTTPS; the gateway warns, because browsers will not store the session.

Idle timeout is the gateway security setting `inactivityTimeoutMinutes`. A value of `0` or less disables it, matching the reference platform's gateway general security setting. Each accepted request slides the idle deadline.

Absolute lifetime is `http.absoluteSessionMinutes` (default `720`, twelve hours). `0` disables it. A disabled absolute lifetime is a **session cookie**: the `Set-Cookie` header has no `Max-Age`, and the browser drops it when the browser closes. Idle timeout still applies. A positive absolute lifetime is sent as `Max-Age`.

## Login throttle

Each client IP has a token bucket of `http.loginRatePerMinute` permits (default 20, `0` disables the bucket). Permits refill continuously. When the bucket is empty the gateway returns `429` `urn:oip:problem:rate-limited` with `retryAfter` and a `Retry-After` header, and it does **not** record an account failure.

The first denial waits one refill interval (`60 / loginRatePerMinute` seconds). Further attempts while the address is still denied double that wait, up to 15 minutes.

Every `POST /api/v1/auth/login` spends a permit, including a malformed username or a body the gateway rejects. That is what stops password spraying across many names.

`X-Forwarded-For` is used only when the TCP peer is in `http.trustedProxies` (IP or IPv4 CIDR). Otherwise the header is ignored.

## Account lockout

Classic the reference platform locks the **account** after 5 failures inside 15 minutes. Doing that here lets one address lock `admin` for everyone, then keep renewing the lock. This gateway splits the counter:

1. **Per (username, client IP).** Five failures inside 15 minutes lock only that pair. The lock starts at 15 minutes and doubles on each later lock, up to 24 hours. Other addresses are unaffected.
2. **Global per username.** Each address contributes at most those five failures toward a global counter. The account locks for every address only after `http.lockoutDistinctIps` (default 5) addresses have each contributed a full set, so the global threshold is `5 * lockoutDistinctIps`. One address cannot fill that counter by retrying, including after its own lock expires.
3. A successful login (or a disabled / password-expired result, which means the password was correct) clears that address's pair row and the global counter. It does not clear other addresses' pair locks.

A locked login returns `429` `urn:oip:problem:account-locked` with the same detail whether or not the password was correct. Attempts during a lock do not hash the password and do not add another failure or audit row. The transition into a lock writes one `Authentication Lockout` audit record.

Rows are deleted when they are no longer needed: an unlocked row after the 15 minute window, and a locked row one full window after the lock itself expires (the extra window is what lets the next failure double). Pair rows are also capped at `http.authFailureRowCap` (default 10000). Active locks are kept ahead of unlocked rows. Global rows whose username has no remaining pair row are deleted unless that global lock is still in force.

## `gateway.yaml`

```yaml
http:
  host: 0.0.0.0
  port: 8090
  https: false
  secureCookies: true          # omit to use the default above
  trustedProxies: []           # ["10.0.0.0/8", "192.168.1.1"]
  loginRatePerMinute: 20
  lockoutDistinctIps: 5
  authFailureRowCap: 10000
  absoluteSessionMinutes: 720  # 0 = session cookie, no Max-Age
```

Unknown `http` keys are rejected. HTTPS listen port, public address, redirect, and certificate paths are in [https.md](https.md).

## First administrator

Set `OIP_ADMIN_PASSWORD` in the process environment before the first start. When the user source is empty, the gateway creates `admin` with the administrator role. It does not log the password, and a later start does not change that user. The value has to satisfy the internal password policy (default: at least 8 characters, and 3 of lowercase, uppercase, digit, and special). A rejected password aborts that first start.

The container image reads the same variable. `compose.yaml` passes it through when it is set. Do not bake a password into an image or a committed file. Generate one at runtime.

`OIP_ADMIN_PASSWORD` does not change a user that already exists. To recover a lost password, stop the gateway and run `oipctl reset-admin`. See [Gateway operations](../ops.md).

```
curl -sS -c cookies -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<ephemeral>"}' \
  http://127.0.0.1:8088/api/v1/auth/login
curl -sS -b cookies http://127.0.0.1:8088/api/v1/diagnostics/memory
```

`GET` diagnostics need the session cookie, or a bearer token that has `gateway.read`. They are not public. Cookie writes also send `X-CSRF-Token`. A bearer token created with the default `secureConnectionRequired` is refused on plain HTTP. This image listens on plain HTTP, so `scripts/image_smoke.sh` uses the session cookie. That script generates the password, confirms diagnostics return 401 without a session, then logs in.

## Identity providers

Users can also sign in through an OIDC or SAML identity provider (SEC-013, SEC-014). An administrator with
`users.admin` adds the provider on **Security > Identity Providers** or through `/api/v1/security/identity-providers`:

1. OIDC: enter the issuer and import its discovery document. SAML: paste the provider's metadata and import it, then
   set the gateway entity ID (`spEntityId`).
2. Register the gateway at the provider with the callback shown in the provider list:
   `<gateway>/api/v1/auth/providers/<name>/callback` (OIDC redirect URI) or `<gateway>/api/v1/auth/providers/<name>/acs`
   (SAML assertion consumer service, HTTP-POST). Behind a proxy, set `redirectBaseUrl` to the public base URL.
3. OIDC: enter the client ID and the client secret. The secret is stored in the secret store as `idp-<name>` and is
   never shown again; leave it blank for a public client.
4. Map roles: `rolesClaim` names the claim (or SAML attribute) that lists groups, each role mapping gives the gateway
   roles for one value, and `defaultRoles` apply to every user of the provider.

The sign-in page shows a button per enabled provider. The session principal is `<provider>:<username>`. Disabling or
deleting a provider ends its sessions. Logout of an OIDC session also visits the provider's end-session endpoint when
it has one. Every provider login, accepted or refused, writes an audit record with `provider=<name>`; a refused login
returns to the sign-in page with `?error=idp`, and the reason is in the audit log (DIFFERENCES 255-261).

Provider access tokens are accepted as `Authorization: Bearer` when an enabled OIDC provider's issuer matches the
token's `iss`; the audience is the provider's `apiAudience`, or its client ID when that is blank.

Worked OIDC and SAML setups, how to read a failed test login, and the directory and database user sources are in
[identity-providers.md](identity-providers.md).

## Checking a user

`POST /api/v1/security/user-sources/internal/check` with `{"username": "...", "password": "..."}` (password optional),
or **Check a user** on the Users page, reports whether the user exists, its roles, and whether the password matches.
It needs `users.admin`, is audited as `user source check`, and never records the password (SEC-027).
