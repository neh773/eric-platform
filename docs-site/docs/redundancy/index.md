---
title: Redundancy pair
sidebar_label: Redundancy pair
---

# Redundancy pair

**Network** holds the pair settings for this gateway. A pair is two nodes. One is the master. The other is the backup. An independent node runs alone.

## Set the pair

1. Open **Network**.
2. Under **Redundancy pair**, choose **Independent**, **Master**, or **Backup**.
3. For a backup, set the master host and port. The host is a name or an address with letters, digits, dots, and dashes.
4. Set how often this node pings the other, and how many missed pings count as a lost link.
5. Click **Save pair**. The address is used immediately, and the same values are written to this node's gateway file so they survive a restart.

**Edit as JSON** shows the rest of the pair: standby level, startup wait, sync timeout, recovery, history mode, and the connection name. Saving JSON replaces those values. Unknown keys are refused.

## When the master is lost

- **Request failover** asks the backup to become active.
- **Take control** makes this node active now.
- **Force sync** asks the peer to send its configuration again.

Each node keeps its own pair settings. Saving here does not copy the file onto the other node.
