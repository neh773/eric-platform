---
title: Service policies
sidebar_label: Service policies
---

# Service policies

A service policy says what a remote gateway may do on this one. It sits on **Network**, under the peer network status. It is separate from a security zone: a zone matches an address, and a service policy matches a peer.

## Add a policy

1. Open **Network** and find **Service policies**.
2. **Peer** is a certificate fingerprint, the other gateway's name, or `*` for every approved peer. A fingerprint match wins over a name, and a name wins over `*`.
3. Choose the service: tags, alarms, history, audit, alarm journal, tasks, or notifications.
4. Choose access.
   - **Allow** — the peer may query and change that service.
   - **Read** — the peer may query only.
   - **Deny** — the peer may not use the service.
5. **Roles**, if you set them, are the roles a tag read is limited to. Leave them blank for no extra role limit.
6. Click **Save policy**. Saving the same peer and service replaces the old rule.

Until you add a rule, remote tag reads stay allowed and the other services stay closed.

**Delete** removes that peer and service. The change is stored with the peer network settings, not as a project resource.
