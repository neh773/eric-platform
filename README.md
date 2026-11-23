# ERIC

**ERIC v0.1.1 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).** See [docs/manual/safety.md](docs/manual/safety.md).

ERIC (Engineered Runtime Interface & Control) connects to devices, holds tags, history and alarms, and serves operator screens. Control Canvas is the designer.

Gateway backup and restore, including the warning that a backup holds `secrets.key` and the secret store (always encrypted under a passphrase you choose), is in [docs/ops.md](docs/ops.md).

Licensed under the GNU Affero General Public License v3.0 or later — see [LICENSE](LICENSE) — with an additional permission to link Eclipse Public License libraries. See [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). The SPDX expression is `AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception`. Copyright (C) 2026 Nick Hepburn, the sole maintainer ([MAINTAINERS.md](MAINTAINERS.md)). The names ERIC and Control Canvas are trademarks; see [TRADEMARKS.md](TRADEMARKS.md). Third-party notices are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). Contributions need the agreement in [CLA.md](CLA.md), and that agreement applies the same linking permission to contributed code.

Testers: [TESTING.md](TESTING.md) and [docs/manual/getting-started-testers.md](docs/manual/getting-started-testers.md). The manual is also served at `/docs` on a running gateway.

How the project plans to pay for development and support, after the beta, is in [docs/funding-model.md](docs/funding-model.md). The wishlist is [docs/wishlist.md](docs/wishlist.md).

## Gateway HTTP authentication

Browser sessions use an `oip_session` cookie (`HttpOnly`, `SameSite=Strict`). The `Secure` flag is on when `http.https` is set or `http.trustedProxies` is non-empty, and off for plain HTTP, which logs a startup warning. Set `http.secureCookies` to force either behavior. Machine clients use `Authorization: Bearer`. Cookie clients send `X-CSRF-Token` on state-changing requests.

Login is limited per client IP (default 20 attempts per minute, then a doubling backoff, `429`). Account lockout is counted per (username, IP) plus a global cap that one address cannot fill by itself, so a single remote client cannot keep `admin` locked. On first start, `OIP_ADMIN_PASSWORD` seeds an `admin` user when the user source is empty. The password is not logged. See [docs/ops/http-auth.md](docs/ops/http-auth.md).

## Gateway HTTPS

`http.https` opens a TLS listener (default port 8043) and marks the session cookie `Secure`. With no certificate configured, the first HTTPS start writes a self-signed certificate under the data directory and reuses it. An administrator can point `gateway.yaml` at a PEM chain and key or a PKCS#12 file, upload either through `PUT /api/v1/https/certificate`, or drop the files in `https/incoming`. `http.forceHttpsRedirect` answers plain HTTP with 308, including `/api/v1/health`. The redirect is off by default, so the HTTP port keeps working, including in the container image. Private keys are mode `0600` and are not written to logs, config resources, or API responses. See [docs/ops/https.md](docs/ops/https.md).
