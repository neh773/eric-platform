---
title: History providers
sidebar_label: History providers
---

# History providers

**Historian** is where history is stored and which tags record into it. The built-in provider **local** is always there. You do not create it.

## Add a provider

1. Under **Providers**, click **New provider**.
2. Choose the type.
   - **Simulator** makes values from the tag path, for a test with no database.
   - **SQL database** stores rows in a connection and a table.
   - **CSV file** reads a file under the gateway's history folder. The first column is the time, and each later column is a tag path.
   - **Existing table** reads a table that is already there. It is never written. Name the time, tag, and value columns.
   - **Splitter** writes to two providers. Queries use the first, and the second when the first cannot answer.
3. For a SQL or simulator provider, set the forward rate, the batch size, and the disk limit. Those decide how queued samples move when the database is slow.
4. Save. **Edit as JSON** keeps settings the form does not list, such as partition sizes.

## Record a tag

Under **Tag history**, click **Record a tag**.

- **Tag path** is the tag, for example `[default]Plant/Speed`.
- **On change** stores a row when the value changes. **Periodic** stores one at the sample rate.
- **Deadband** ignores small changes. Off stores every change that the mode asks for.
- **Provider** is `local` or the name of a provider you added.

The same settings are what a tag's history binding uses. A disabled provider keeps the rows queued until it is enabled again.
