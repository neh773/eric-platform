---
title: Identity providers
sidebar_label: Identity providers
---

# Identity providers

An identity provider lets people sign in with an account that lives somewhere else. **Identity Providers** registers an OpenID Connect provider or a SAML provider, maps the provider's groups onto gateway roles, and checks a login when something is wrong.

## Add a provider

1. Click **Add identity provider** and choose OpenID Connect or SAML.
2. For OpenID Connect, type the issuer and click **Import discovery document**. For SAML, paste the metadata and click **Import metadata**.
3. Type the client ID. The client secret is stored by the gateway and is never shown again; leave it blank later to keep the stored one.
4. Fill the claims below, then create the provider.

## Claims

| Field | What it reads |
|---|---|
| Username claim | The account name. Blank uses `preferred_username`, then `sub`, for OpenID Connect, and the NameID for SAML. |
| Name claim | The display name. Blank uses `name` from an OpenID Connect token. |
| Email claim | The email address. Blank uses `email` from an OpenID Connect token. |
| Groups claim | The list of groups. Each value is matched by the role mappings. |
| Role mappings | One line per group: `plant-ops = operator, viewer`. |
| Default roles | Roles everyone from this provider receives. |
| Redirect base URL | The address users open, when it is not the address the gateway sees. Set this when a proxy sits in front. |
| Clock skew (seconds) | How far the gateway's clock and the provider's clock may differ. Blank uses 60 seconds. |

**Edit as JSON** shows every stored setting, including ones the form does not list. Saving from that view replaces the provider with the text.

## Setup check

**Test login** opens the provider in another window and does not sign the admin page in. After you finish or the provider sends you back, click **Check setup**.

A success shows the user name, display name, email, and roles the gateway accepted. A failure names the likely cause:

- **Clock skew** — the token or assertion is outside its time window. Compare the clocks, or raise **Clock skew** only if they cannot be brought together.
- **Redirect mismatch** — the provider does not have the gateway's callback address. Register the address shown in the check, or set **Redirect base URL** to the host users open.
- **Metadata** — the signature, certificate, or issuer no longer matches. Import the discovery document or metadata again.
- **Audience** — the gateway entity ID or client ID is not the one the provider issued the login for.
- **Client credentials** — the client ID or secret was refused. Enter the secret again.

The check never shows a client secret, a code, or a token.
