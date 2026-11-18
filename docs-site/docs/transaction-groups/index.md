---
title: Transaction groups
sidebar_label: Transaction groups
---

# Transaction groups

A transaction group moves values between tags and a database. **Databases** has the form under **Transaction groups**.

## Create a group

1. Click **New transaction group** and give it a short lowercase name.
2. Choose the type: standard, historical, block, or stored procedure.
3. **Connection** is a database connection name, or `<default>` for the connection marked as the default.
4. **Table** is the SQL table. A stored-procedure group names the procedure instead.
5. **Direction** is tag to database, database to tag (a recipe that loads a row into tags), or both ways with either side winning.
6. **Insert a row** adds a row each run. **Update a row** changes an existing one. **Key column matches an item** is the update-or-insert key.
7. **Rate** is how often a timer group runs, at least 100 milliseconds. A **Trigger tag** runs the group when that tag changes, rises, falls, or stays true.
8. Each item is a tag, an expression, or a fixed value, written to a column. A boolean tag can be an hour meter or an event meter.
9. A block group lists one tag path per line. Each tag becomes one row in the block column.
10. Save. **Edit as JSON** keeps settings the form does not list.

The group runs on the gateway. A disabled group stays saved and does not run.
