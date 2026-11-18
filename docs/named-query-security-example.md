# Worked example: a named query restricted to a role

Doc source: FEATURES DAT-037. Test: `NamedQuerySecurityExampleTest` in `gateway/server`.

A named query can list the roles that may run it. An empty list allows every signed-in caller. A non-empty list
allows only callers who hold at least one of those roles. A caller outside the list gets `403` and no rows, and
the query's scalar fallback is not returned in its place.

## 1. Create a restricted query

`POST /api/v1/db/queries` (needs `gateway.write`):

```json
{
  "name": "line-output",
  "connection": "plant",
  "queryType": "query",
  "sql": "SELECT line, units FROM output WHERE line = :line",
  "parameters": [{ "name": "line", "dataType": "string" }],
  "roles": ["operator", "administrator"]
}
```

The same fields work in the Studio: Tools, Named queries, or the admin Databases page.

## 2. An allowed call returns rows

Signed in as a user with the `operator` role:

```
POST /api/v1/db/queries/line-output/execute
{"parameters": {"line": "A"}}
```

```json
{"type": "query", "columns": ["line", "units"], "rows": [["A", 120]], "cached": false, "truncated": false}
```

## 3. A denied call returns 403

Signed in as a user with only the `viewer` role, the same request returns `403` with a problem body and no rows.
An unauthenticated request returns `401`. Scripts and bindings that call the query run as the session's user, so
they follow the same rule.

## Notes

- Role names are matched exactly and must be unique within the query.
- Roles protect execution. Editing queries needs `gateway.write` regardless of the list.
- A project's own query of the same name carries its own role list.
