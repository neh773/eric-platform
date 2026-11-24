# Historian

The **Historian** page (Services group) lists history providers and the tags that record history. History for UDT members is set in the UDT definition; see [Tags](tags.md#udts).

![Historian](../img/admin-historian.png)

## Add a history provider

The built-in historian (`local`) needs no setup. To store history in PostgreSQL or another database, first add the connection on the [Databases](databases.md) page. Then on the Historian page choose **New provider**, enter a **Name**, keep **Type** at **SQL database**, enter the **Connection** name and a **Table** (default `oip_history`), and choose **Save**.

## Enable history on a tag

1. Under **Tag history**, choose **Record a tag**.
2. Enter a name, the **Tag path** (for example `[default]demo/tank-level`), the **Sample mode** (`On change` or `Periodic` with a sample rate) and the provider.
3. Choose **Save**.

- Deadband: `off`, `absolute` or `percent`, with a value.
- Max time between samples stores a sample at least that often (0 disables).
- Provider: `local` (the built-in historian, default) or the name of a provider above.

Choose **Edit <name>** to change it, or **Delete <name>** to stop collection. Collection resumes after a restart. **Edit as JSON** is available for settings the form does not show. A tag-history entry wins over the history settings of a UDT member with the same path. Chart the data in a view with the power chart's historical mode ([Alarms and charts](../runtime/alarms-charts.md)).
