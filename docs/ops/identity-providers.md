# Identity providers and user sources: worked setups

This page walks through one OIDC setup and one SAML setup from start to a working login, then explains how to read
a failed test login. It also covers the directory and database user sources. [http-auth.md](http-auth.md#identity-providers)
has the reference.

Each step below is also run, in the same order, against in-process test providers in
`gateway/server/src/test/kotlin/org/oip/gateway/IdpWorkedExamplesTest.kt` (SEC-026, SEC-028).

Everything here needs `users.admin`. Every call has an equivalent on **Security > Identity Providers** and
**Security > User Sources** in the admin UI.

## OIDC (SEC-026)

Before you start, register a web application ("confidential client") at the provider. Allow it the authorization code
grant. The gateway always sends PKCE (S256), so the provider must accept it. Note the issuer URL, the client ID and the
client secret.

1. **Import the discovery document.**
   Send `POST /api/v1/security/identity-providers/import` with `{"type": "oidc", "issuer": "https://login.example.test"}`.
   The gateway fetches `<issuer>/.well-known/openid-configuration` and returns `fields`, which hold the endpoints, the
   JWKS address and the allowed signing algorithms. If the document's `issuer` differs from the one you entered, the
   import is refused (422). It is also refused if an endpoint is missing, if an endpoint uses plain HTTP outside
   loopback, or if S256 is not offered.
2. **Create the provider.**
   Post `fields` to `/api/v1/security/identity-providers`, together with:
   - `name`;
   - `displayName`, the label on the sign-in button;
   - `clientId` and `clientSecret`;
   - `scopes`, for example `["openid", "profile", "email", "groups"]`. Ask for the scope that releases the group
     claim, if the provider has one;
   - `nameClaim`, `emailClaim` and `rolesClaim`;
   - `roleMappings` and `defaultRoles`.

   The secret goes into the secret store as `idp-<name>`. Responses show only `clientSecretSet`.
3. **Register the redirect URI at the provider.**
   Register `<gateway>/api/v1/auth/providers/<name>/callback`; the response's `callbackPath` gives the path. Register it
   exactly: same scheme, host, port and path. Behind a reverse proxy, set `redirectBaseUrl` to the address users open,
   so the gateway sends that address.
4. **Run a test login.**
   `POST /api/v1/security/identity-providers/<name>/test` returns `testId` and `loginPath`. Open `loginPath` in a
   browser and sign in at the provider. The browser comes back to a result page; `GET .../<name>/test/<testId>`
   returns the same result. A success shows the username, the claims, the mapped roles and the login levels. A test
   login creates no session, and it is audited as `identity provider test`.
5. **Log in for real.**
   The sign-in page has a button for the provider. The session principal is `<name>:<username>`.

## SAML (SEC-028)

1. **Import the provider's metadata.**
   Send `POST /api/v1/security/identity-providers/import` with
   `{"type": "saml", "metadataXml": "<md:EntityDescriptor ...>"}`, or with `metadataUrl` instead. The returned
   `fields` hold `idpEntityId`, `ssoUrl`, `sloUrl` and the signing certificates. Metadata with a DTD, with no IdP
   descriptor, or with no usable certificate is refused (422).
2. **Create the provider.**
   Post `fields` together with `name`, `spEntityId` (the gateway's entity ID, for example `urn:oip:gateway`), the
   attribute mappings (`nameClaim`, `emailClaim`, `rolesClaim`) and the role mappings.
3. **Register the gateway at the provider.**
   Register the entity ID and the assertion consumer service `<gateway>/api/v1/auth/providers/<name>/acs` with the
   HTTP-POST binding. The provider must sign the assertion. Set its audience to `spEntityId`.
4. **Run a test login and log in,** as for OIDC. The provider posts its answer to the ACS address.
   Over HTTPS, the gateway's state cookie is `SameSite=None` so that it survives that cross-site POST.

## Reading a failed test login (SEC-032)

A failed test login has a `diagnosis` with a `code`, a `summary`, a `hint` naming the setting to check, and the
`facts` the check compared. The result page shows the same rows. Facts never include a secret, an authorization code
or a token. Values the provider sent are cut to printable text.

| code | Typical cause | Facts |
| --- | --- | --- |
| `clock-skew` | The token or assertion has expired or is not yet valid by the gateway's clock. Synchronise both clocks (NTP); raise `clockSkewSeconds` only as a last resort. | `providerTime`, `gatewayTime`, `differenceSeconds`, `allowedSkewSeconds` |
| `redirect-mismatch` | The provider refused the redirect URI (for example `redirect_uri_mismatch`), or a SAML Destination or Recipient names another ACS address. | `gatewayRedirectUri` or `gatewayAcsUrl`; for SAML, `expected` and `presented` |
| `metadata` | The signature does not verify against the keys or certificates the provider settings hold, or the issuer differs. Import the discovery document or the metadata again. | `expected` and `presented` for an issuer |
| `audience` | The token is for another client ID, or the assertion is for another `spEntityId`. | `expected`, `presented` |
| `client-credentials` | The token endpoint refused the client ID or the secret. | `httpStatus`, `providerError` |
| `provider-error` | The provider answered with another OAuth error, for example `invalid_scope` or `access_denied`. | `providerError` |
| `browser-state` | The browser came back without the login it started: another window, a blocked cookie, or the login took longer than a few minutes. | |
| `claims` | The answer has no usable username. Check `usernameClaim`. | |

A test that was started but has had no answer for a minute also gets a `redirect-mismatch` diagnosis. Usually this
means the provider showed its own error page instead of sending the browser back.

## User sources (SEC-007, SEC-010, SEC-021, SEC-025)

A user source checks passwords somewhere other than the gateway's own user store. Create one with
`POST /api/v1/security/user-sources`, then make it the login source by setting `systemUserSource` in the gateway
security settings. If `systemUserSource` names no user source, logins use the internal store.

| type | Password checked by | Roles from |
| --- | --- | --- |
| `ldap` | the directory (LDAP or Active Directory) | directory groups, through `roleMappings` |
| `ldap-internal` | the directory | the `users` list kept in the gateway, or `defaultRoles` when `allowUnlistedUsers` is set |
| `database` | the hash stored in an external table, read through a named database connection | a roles table, or role values in the users table |
| `ldap-database` | the directory | the roles table in an external database |

### Directory sources

- **Connection security.** Use `ldaps://` URLs, or `ldap://` URLs with `startTls`. Plain LDAP needs `allowPlaintext`.
  Directory certificates are trusted through the outbound trust store for the `user-source` client. The host name is
  always checked.
- **Failover.** List several URLs; they are tried in order.
- **Service account.** Enter the service account in `bindDn` and its password in `bindPassword`. The password is
  stored in the secret store as `usersource-<name>`, is never shown again, and is never logged.
- **Finding the user.** The gateway uses the service account to search `userBaseDn` with `userFilter`, for example
  `(sAMAccountName={username})`. As an alternative to a service account, `userBindPattern` builds the user's DN directly.
- **Groups.** Groups are read from `groupAttribute` (default `memberOf`) and from `groupFilter` under `groupBaseDn`. `nestedGroups` follows
  group-in-group membership to `nestedGroupDepth`.
- **Mapping roles.** A role mapping matches a group by its full DN or by its first RDN value, for example
  `Plant Operators`.

### Database sources

The gateway reads `usersTable`, with `usernameColumn` and `passwordColumn`, through the named connection in
`connection`, using parameterised queries. Stored hashes may be Argon2id, bcrypt or PBKDF2, as `hashScheme` names.
Plain text and unsalted hashes are refused (DIFFERENCES 850). A username that matches more than one row matches none.

### Checking and failures

- **Checking a user.** `POST /api/v1/security/user-sources/<name>/check`, or **Check user** on the User Sources page,
  shows whether the user exists, the groups read, the DN and the mapped roles.
- **Unreachable source.** When the source cannot be reached, logins return 503 and use no lockout slot
  (DIFFERENCES 852).
- **Ending sessions.** Disabling a source, or changing its role rules, ends its sessions.
