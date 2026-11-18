---
title: Deployment
sidebar_label: Deployment
---

# Deployment

**Deployment** lists the settings a container and a Helm install read. The page does not change the running gateway. Copy the names into the environment of the container, or copy the short Helm block into the chart values.

## What the names do

- **OIP_PROFILE** is `edge`, `standard`, or `hub`.
- **OIP_DATA_DIR** is the directory that holds config, history, and the key file. Give a container a volume for this directory so a restart keeps it.
- **OIP_HTTP_PORT** and the HTTPS names are the listener. The same names are the chart's `httpPort` and the gateway file.
- **OIP_ADMIN_PASSWORD** creates the administrator only while the gateway has no users. A Helm install points `admin.existingSecret` at a secret the cluster already has. The chart does not invent that password.
- **OIP_SECRETS_KEY_FILE** is the path of the secrets key. Do not put the key itself on this page.

## A proxy in front of the gateway

Terminate TLS on the proxy and forward the websocket upgrade that sessions use. On **Network**, add the proxy to trusted proxies so the gateway records the client address the proxy sends.

Backups and restores stay on **Backups**. The commands that stop the process, reset a password while it is stopped, or rotate secret keys stay on the command line, because they run when this page is not being served.
