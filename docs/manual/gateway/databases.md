# Databases

The **Databases** page (Connections group) shows database connections and named queries, uploads JDBC drivers, picks the alarm journal storage, and has a query browser.

![Databases](../img/admin-databases.png)

## Add a PostgreSQL connection

1. Choose **New connection**.
2. Fill in **Connection name** (for example `plant-db`), pick **Database type** `PostgreSQL`, then **Host**, **Port** (5432) and **Database**. The JDBC URL is built for you and shown under the fields. To paste a URL instead, fill in **JDBC URL**; the other fields are read from it.
3. Fill in **Username** and **Password**. The password is stored as a secret. It is never shown again; when you edit the connection the field is empty and says a password is stored. Leave it empty to keep the stored password, or tick **Remove the stored password**.
4. Choose **Create and test**. The connection is saved and tested, and the result is shown on the page.

To change a connection choose **Edit plant-db**, change the fields and choose **Save and test**. **Test plant-db** checks it again; **Delete plant-db** removes it after you confirm.

Database types in the form: PostgreSQL, MySQL, MariaDB, Microsoft SQL Server, SQLite file, and Other (an uploaded JDBC driver with its own URL). If a driver's JAR is not bundled, upload it under **JDBC drivers** (driver id, optional driver class, JAR).

The same connections can be scripted with `POST /api/v1/db/connections` (see [REST](rest.md)).

## Named queries

A named query is a parameterised statement tied to a connection and used from bindings and scripts. Choose **New named query**, or POST to `/api/v1/db/queries`:

```json
{ "name": "tank-by-id", "connection": "plant-db", "queryType": "query",
  "sql": "SELECT * FROM tank WHERE id = :id",
  "parameters": [{ "name": "id", "dataType": "integer" }] }
```

Use **Test** on a query row, fill in the parameters and **Run** to see the result table.

## Query browser

Pick a **Connection**, type a **SQL statement**, set the **Row limit** and choose **Run statement**. Statements that change data are refused on connections that are read only.
