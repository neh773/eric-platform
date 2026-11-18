---
title: Secrets
sidebar_label: Secrets
---

# Secrets

A secret is a value the gateway stores encrypted. A database password, a token, or a key belongs here. Other settings store the reference, `secret://` followed by the name, and the gateway fills in the value when it connects.

## Save a secret

1. Open **Secrets**.
2. Choose a lowercase name. Digits and dashes are allowed.
3. Add a short description so you can tell the secrets apart.
4. Enter the value. After you save, the value is not shown again. To change it, save the same name with a new value.
5. Click **Save secret**. The table shows the reference, for example `secret://db-password`.

**Edit as JSON** is the same three fields. The list never includes the value.

## Use the reference

Copy the reference and paste it into the setting that asks for a password or a token. Do not paste the value itself into that setting.

Delete a secret only when nothing still uses its reference. A setting that points at a missing secret cannot connect.
