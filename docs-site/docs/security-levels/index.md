---
title: Security levels
sidebar_label: Security levels
---

# Security levels

A security level is a name a person holds after they sign in. Views and actions can require a level. **Security Levels** is where you build the tree, the rules that grant levels, and the grants that put one person in a level directly.

## Levels

Everyone holds **Public**. Every signed-in person holds **Authenticated**. A level also includes every level above it, so someone who holds `Plant/Night` also holds `Plant`.

Add a level with a path such as `Plant/Night` or `Authenticated/Roles/operator`. A level named `Authenticated/Roles/` plus a role name is held by everyone with that role. Use up to eight names separated by `/`.

## Rules

A rule runs at each login. It can look at the user source, the user name, roles, the address, the security zone, whether the connection is HTTPS, and the time. When the conditions match, the rule grants the levels you tick.

Leave a rule with no conditions to grant those levels to every login the source matches.

## User grants

A user grant puts one person in levels even when no rule would. Use it for a single account that needs access the rules do not give everyone.

1. Add the levels first.
2. Under **User grants**, type the user name.
3. Leave the source as `internal` for a gateway user, or type another user source name.
4. Tick the levels and click **Add user grant**.
5. Click **Save levels and rules**. The grant applies from that person's next login.

Remove a grant with **Remove grant** and save again. One user can have one grant per source.

## Edit as JSON

**Edit as JSON** shows the levels, rules, and grants as they are stored. Use it when you want to paste a tree or read a rule in one place. **Edit as form** brings the text back into the fields. Saving from the JSON view replaces the stored document with that text.

## If a save is refused

The page lists the gateway's reason. The usual ones are a level path the tree does not contain, a grant with no levels, or two grants for the same user and source.
