# More security pages

[Users and roles](users.md) covers accounts. These pages are the rest of the Security group. All of them need an administrator unless the line says otherwise.

## API keys

**API Keys** creates a bearer token for scripts and `oipctl`. The secret is shown once, on create and on rotate. Later GETs return only the id and the roles. `POST /api/v1/security/api-keys` with `{"name":"plant-script","roles":["operator"]}`.

## Identity providers

**Identity Providers** adds an OIDC or SAML login button on the sign-in page.

`PUT /api/v1/security/identity-providers/{name}` stores the provider. Importing discovery metadata is `POST .../import`. The client secret is stored in the secret store, not in the provider resource. A refused login returns to the sign-in page with a message. Map a claim to a username and email or the user is not created.

## User sources

**User Sources** is how accounts are checked: the internal store, LDAP, a database, or a hybrid. **Check user** calls the source and reports whether the account exists. The password is not printed.

## Security zones

**Security Zones** match a client by IP or host name. The built-in zone is Default. A zone is a config resource. Create one on the page, or `PUT` a `SecurityZone` resource under `org.oip.security`. A viewer cannot read the zone list (403).

## Security levels

**Security Levels** are named levels plus rules that grant them at login. A component in Control Canvas can require a level. The page can try a user and show which levels they would get. A bad rule that names a missing level is rejected with 422.

## Security settings

**Security Settings** edits the password policy (length, character classes, username check) and audit retention. Gateway security (inactivity, lockout) is on the same page.

## Secrets

**Secrets** stores a value and shows only the reference, for example `secret:plant-db`. Email profiles, database connections and OAuth clients store passwords this way. `PUT /api/v1/security/secrets/{name}` with `{"value":"..."}`. A later GET does not return the value.

## Audit

**Audit** lists login, config and alarm actions. Filter by actor, action and time. The profile's retention drops old rows. The log is a hash chain in `db/audit.sqlite` with the head in `db/audit.anchor`.

## OPC UA certificates

**OPC UA Certificates** lists trusted client certificates and the quarantine. Import a PEM, trust a quarantined thumbprint, or remove one.

- `GET /api/v1/opcua/certificates`
- `POST /api/v1/opcua/certificates/trusted`
- `POST /api/v1/opcua/certificates/quarantined/{thumbprint}/trust`
- `DELETE /api/v1/opcua/certificates/trusted/{thumbprint}`

## Outbound TLS

**Outbound TLS Certificates** is the trust store for connections the gateway makes (database TLS, MQTT TLS). Import, list and remove server certificates the same way as the OPC UA page. The routes are under `/api/v1/security/outbound-tls`.

## OAuth2 clients

**OAuth2 Clients** registers a client the gateway uses to fetch a token (SMTP XOAUTH2, and the same client for other HTTPS calls).

`PUT /api/v1/security/oauth2-clients/{name}` with `clientId`, `clientSecret`, `tokenUrl`, `authorizationUrl`, `scope` and `grantType`. The secret and refresh token are stored as secrets. **Verify token** is `POST .../verify-token`. The authorization-code flow is `POST .../authorize` and the callback is `GET .../authorize/callback`.
