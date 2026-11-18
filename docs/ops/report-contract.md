# C-9 report contract (contractVersion 1)

Owner: C-9a (reporting backend, branch `claude/c9a-reporting-backend`). Consumer: C-9b (rendering: tables, charts, PDF).
Kotlin mirror: `gateway/reporting/src/main/kotlin/org/oip/reporting/Contract.kt` (data classes, same field names).

Changes:
- 2026-10-27 (before any backend code): run/status routes need `tags.read` (there is no `reports.run` permission
  in the closed permission vocabulary); namedQuery sources may declare optional `columns` (type/unit per column).
- 2026-10-27: error list made complete (401, 422, which errors are recorded as runs). No shape change.

All JSON. Field order below is the order the backend writes. Timestamps are **epoch milliseconds (UTC), JSON integers**,
everywhere in the result, including `generatedAt`. Unknown fields must be ignored by readers.

## 1. Report definition (config resource)

Stored through the normal config resource API: module `reporting`, kind `Report`, gateway scope.

```
GET/POST        /api/v1/config/reporting/gateway/Report
GET/PUT/PATCH/DELETE /api/v1/config/reporting/gateway/Report/{name}
```

Envelope is the standard `{apiVersion, kind:"Report", metadata:{name, module:"reporting", ...}, spec}`. `spec`:

```jsonc
{
  "title": "Line 1 daily",              // optional, default = metadata.name
  "description": "",                    // optional
  "enabled": true,                      // optional; false = not runnable, not scheduled
  "parameters": [                       // optional, max 32, order is kept
    { "name": "range", "type": "timeRange", "label": "Range", "default": { "last": "24h" }, "required": false },
    { "name": "line",  "type": "string",    "default": "L1" }
  ],
  "dataSources": [                      // required, 1..16, names unique; result datasets keep this order
    { "name": "production", "type": "namedQuery", "query": "line-production",
      "parameters": {                   // named-query parameter -> binding
        "line":  { "param": "line" },                    // report parameter value
        "start": { "param": "range", "field": "from" },  // timeRange parameter: field "from" or "to"
        "end":   { "param": "range", "field": "to" },
        "limit": { "value": 100 }                        // literal
      },
      "columns": { "count": { "type": "number", "unit": "pcs" } } },   // optional per-column type/unit override
    { "name": "temps", "type": "tagHistory", "mode": "raw",               // raw | aggregate
      "tags": [ { "path": "[default]Line1/Temp", "label": "Temp", "unit": "degC" } ],   // 1..32
      "range": { "param": "range" },    // binding to a timeRange parameter, or a literal { "value": {"last":"8h"} }
      "limit": 10000 },                 // raw only, per tag, optional
    { "name": "hourly", "type": "tagHistory", "mode": "aggregate", "aggregate": "Average", "intervalMillis": 3600000,
      "tags": [ { "path": "Line1/Temp" } ], "range": { "param": "range" } },
    { "name": "alarms", "type": "alarmJournal",                         // journal rows of the range
      "range": { "param": "range" },                                  // timeRange parameter or literal, as tagHistory
      "minPriority": "medium", "maxPriority": "critical",             // optional: diagnostic | low | medium | high | critical
      "eventTypes": ["active", "ack"],                                // optional: active clear ack shelve unshelve enabled disabled
      "source": "prov:default:*", "displayPath": "Tank*",             // optional glob filters
      "order": "desc", "limit": 1000 }                                // desc (default) | asc; 1..1000 rows
  ],
  "schedule": {                         // optional
    "enabled": true,
    "cron": "0 6 * * *",                // 5-field cron: minute hour day-of-month month day-of-week
    "timezone": "UTC",                  // IANA zone, default UTC
    "parameters": { "line": "L2" }      // optional overrides of parameter values for scheduled runs
  },
  "layout": { }                         // OPAQUE to the backend: any JSON object, stored and returned verbatim
}
```

Parameter `type`: `string | number | boolean | timestamp | timeRange`.
- `timestamp` value: epoch millis integer, ISO-8601 instant string (`2026-10-27T06:00:00Z`), or `"now"`.
- `timeRange` value: `{ "last": "<n><unit>" }` with unit `s|m|h|d|w` (relative to run time), or
  `{ "from": <timestamp>, "to": <timestamp> }` (each a timestamp value as above). Resolved at run time.
- A parameter without `default` and with `required: true` must be supplied in the run request.
- A parameter with neither a default nor a supplied value resolves to `null` (bound query parameters then get SQL NULL).
- `aggregate`: `Average | SimpleAverage | Sum | Minimum | Maximum | Range | LastValue | Count` (from the history provider).

Validation runs on save (schema + semantic checks: parameter names unique, bindings refer to existing parameters,
`field` only on timeRange parameters, `range` binds a timeRange, cron parses, timezone exists, defaults have the right type).

## 2. Run a report

```
POST /api/v1/reports/{name}/run      body: { "parameters": { "<name>": <value>, ... } }   (body optional)
```

Response 200, `application/json`, the **report result**:

```jsonc
{
  "contractVersion": 1,
  "report": { "id": "reporting/gateway/Report/line1-daily", "name": "line1-daily", "title": "Line 1 daily", "version": 3 },
  "parameters": [                       // definition order; resolved values
    { "name": "range", "type": "timeRange", "value": { "from": 1761458400000, "to": 1761544800000 } },
    { "name": "line",  "type": "string",    "value": "L1" }
  ],
  "generatedAt": 1761544800123,
  "datasets": [ /* one per data source, definition order, see below */ ],
  "run": {
    "runId": "6f1c...",                 // UUID
    "trigger": "manual",                // manual | schedule
    "user": "admin",                    // caller name, or "system" for scheduled runs
    "startedAt": 1761544800100,
    "durationMillis": 23,
    "status": "success",                // success | partial (some tags were excluded or a dataset was truncated)
    "warnings": [ "temps: 1 tag excluded (no read permission)" ]
  },
  "layout": { }                         // the definition's layout, verbatim (absent if the definition has none)
}
```

### Dataset

```jsonc
{
  "name": "production",
  "sourceType": "namedQuery",          // namedQuery | sql | tagHistory | alarmJournal
  "columns": [ { "name": "line", "type": "string" }, { "name": "count", "type": "number", "unit": "pcs" } ],
  "rows": [ [ "L1", 120 ], [ "L1", 98 ] ],     // each row: one value per column, column order; null allowed
  "truncated": false,
  "series": [ ],                       // tagHistory only (empty for namedQuery)
  "excludedTags": [ ]                  // tagHistory only: paths removed because the caller can't read them
}
```

Column `type`: `string | number | boolean | timestamp`. Named-query column order is the query's column order. Types come
from the source's `columns` override when given (values are converted: numeric text -> number, ISO instant -> epoch
millis); otherwise inferred from the values: all non-null numbers -> `number`, all booleans -> `boolean`, all ISO-8601
instants (SQL timestamps) -> `timestamp` (converted to epoch millis), anything else (or all null) -> `string`.
A scalar named query gives one column `value`. Update queries are refused (the report run fails before executing).

**tagHistory, mode raw** - long format, rows ordered by tag (definition order) then timestamp ascending. Fixed columns:

| # | name | type |
|---|------|------|
| 0 | `tagPath` | string (provider-qualified, e.g. `[default]Line1/Temp`) |
| 1 | `timestamp` | timestamp |
| 2 | `value` | `number` when every non-null value is numeric, `boolean` when every one is boolean, else `string` (values written as-is) |
| 3 | `quality` | string (e.g. `Good`, `Bad_NotConnected`) |

`value` gets `unit` when all included tags declare the same unit.

**tagHistory, mode aggregate** - long format, rows ordered by tag then window start. Fixed columns:
`tagPath` (string), `start` (timestamp), `end` (timestamp, exclusive), `value` (number, null for an empty window), `rows` (number).

**Nested queries** (REP-035) - a `namedQuery` or `sql` source may carry `"parent": { "source": "orders", "bind": { "orderId": "id" } }`.
`source` is an earlier data source; the query runs once per parent row (the first 100; more set `truncated`) with each `bind`
parameter set to that row's column value (so the parameter is not also listed in `parameters`). Result rows are prefixed with
`parent_<column>` columns holding the parent's values, in `bind` order, ready for a table `groupBy`. A parent with no rows
gives no rows and only the `parent_` columns. A query that returns a column named like a `parent_` column, or different
columns for different parent rows, fails the run with 422.

**alarmJournal** (REP-032) - one row per journal event in the range, newest first unless `order` is `asc`. Fixed columns:
`eventTime` (timestamp), `source` (string), `displayPath` (string), `priority` (string, e.g. `High`), `eventType` (string,
e.g. `active`, `ack`), `actor` (string, null for system rows), `eventId` (string). A caller's run leaves out alarms whose
source tag the caller may not read, like the journal API; a scheduled run is not filtered. More rows than `limit` set
`truncated` and make the run `partial`. The range end is exclusive. No `series`.

**series** (both modes, for charts) - one entry per included tag, definition order:

```jsonc
{ "tagPath": "[default]Line1/Temp", "label": "Temp", "unit": "degC",
  "points": [ [1761458400000, 21.5], [1761458460000, 21.7] ] }   // [timestamp, value]; aggregate uses window start
```

`label` defaults to the tag path; `unit` is omitted when not set.

### Errors (RFC 7807 problem JSON, like the rest of the API)
- 400 bad run parameters (wrong type, missing required, unknown parameter name).
- 401 not authenticated.
- 403 no `tags.read` permission; when **every** tag of a tagHistory source is unreadable by the caller; or a named
  query's role list excludes the caller.
- 404 report not found. 409 report disabled.
- 422 the definition can't run as stored (named query missing, an update query, or too many aggregate windows for the range).
- 502 a named query failed; 503 a data source is unavailable (database or history provider down).
- Every 403/422/502/503 from a data source is recorded in run history as `failure`. 400/404/409 are not runs.

## 3. Run history and status

```
GET /api/v1/reports/{name}/runs?limit=50     -> { "items": [ RunRecord, ... ] }   newest first, max 200 kept per report
GET /api/v1/reports/{name}/status            -> ReportStatus
GET /api/v1/reports/status                   -> { "items": [ ReportStatus, ... ] }  sorted by name
```

```jsonc
// RunRecord
{ "runId": "6f1c...", "report": "line1-daily", "trigger": "schedule", "user": "system",
  "startedAt": 1761544800100, "durationMillis": 23, "status": "success",   // success | partial | failure
  "error": null, "datasets": 3, "rows": 412 }
// ReportStatus
{ "report": "line1-daily", "scheduled": true, "cron": "0 6 * * *", "timezone": "UTC",
  "nextFireAt": 1761631200000, "lastRunAt": 1761544800100, "lastStatus": "success",
  "lastError": null, "consecutiveFailures": 0 }
```

Run history stores only the record above, not the result data. Rendering/delivery of scheduled results is out of
scope for C-9a: the scheduler hands each scheduled `ReportResult` to a `ReportResultSink` hook. The gateway's sink
(C-9b `PdfArchiveSink`) renders it to PDF under `reports/<name>/` in the data directory, newest 50 per report.

## 4. Layout (owned by C-9b, layoutVersion 1)

Written by C-9b (branch `claude/c9b-reporting-render`, PR #67). The backend keeps treating `spec.layout` as opaque
(store it, copy it into the result verbatim); nothing below needs backend validation. Renderer: `gateway/report-render`
`render(definition, result) -> PDF bytes`; browser preview: `@oip/components` `ReportPreview`. Canonical doc comment:
`gateway/report-render/src/main/kotlin/org/oip/report/render/ReportLayout.kt`.

```jsonc
{
  "layoutVersion": 1,                                     // optional; other values rejected by the renderer
  "page": { "size": "A4", "orientation": "portrait" },    // A4 | Letter, portrait | landscape
  "timezone": "UTC",                                      // IANA zone for all timestamps shown
  "header": { "title": "...", "subtitle": "...", "showParameters": true, "showGeneratedAt": true },
  "components": [                                         // top to bottom; empty/missing = one table per dataset
    { "type": "table", "dataset": "production", "title": "Hourly", "maxRows": 500,
      "columns": [ "line",
        { "field": "count", "label": "Good", "unit": "pcs", "align": "right", "width": 1.0,
          "format": { "type": "number", "decimals": 0, "grouping": true, "nullText": "-" } },
        { "field": "hour", "format": { "type": "timestamp", "pattern": "yyyy-MM-dd HH:mm" } } ] },
    { "type": "timeSeries", "dataset": "temps", "title": "Temps", "height": 220,
      "series": ["[default]Line1/Temp"],                  // tagHistory: filter by tagPath or label (default all)
      "x": "ts", "y": ["flow"],                           // namedQuery datasets: timestamp column + number columns
      "yAxis": { "min": 0, "max": 250, "label": "degC", "decimals": 0 }, "timePattern": "HH:mm" },
    { "type": "bar", "dataset": "byLine", "title": "By line", "height": 200,
      "category": "line", "values": ["good", "scrap"], "categoryFormat": { }, "yAxis": { } },
    { "type": "pie", "dataset": "byLine", "title": "Share", "category": "line", "value": "good",
      "showPercent": true },                              // positive values only; beyond 12 slices the smallest merge into "Other"
    { "type": "xy", "dataset": "curve", "x": "speed", "y": ["temp"], "connect": false,
      "xAxis": { "label": "rpm" }, "yAxis": { } },        // numeric x against numeric y columns; markers, optional joined lines
    { "type": "image", "src": "@Logo@", "width": 120, "align": "left", "alt": "Plant logo" }
                                                          // data:image/png|jpeg;base64 URI, or a data key holding one
  ]
}
```

Table extras: `"groupBy": "line"` draws a heading row per distinct value (groups in order of first appearance,
`"groupSummary": true` adds a subtotal per group, `"summary": true` a grand total); `"autoSize": true` sizes columns
from the longest text (4 to 40 characters per column) instead of the `width` weights.
Chart settings take data keys: `dataset`, `category`, `value`, `values`, `x`, `y`, `series`, `title`, axis `label`, and axis
`min`/`max` (`"max": "@Limit@"`, a number after filling, else automatic with a note).

Rendering reads from the result: `report.title/id/version`, `parameters` (timeRange `{from,to}` shown as times),
`generatedAt` (also the PDF creation date, so keep it stable per run), `run.runId/trigger/user/status/warnings`,
`datasets[].columns/rows/truncated/series`. `excludedTags` is not shown (the run warning covers it).
C-9b requests: none; the contract as written is sufficient for rendering.

PDF routes (C-9b):

```
POST /api/v1/reports/{name}/pdf               body as /run -> application/pdf   (tags.read; 422 if the layout can't render, before running)
GET  /api/v1/reports/{name}/files             -> { "items": [ { "report", "file", "generatedAt", "size" } ] }   newest first (gateway.read)
GET  /api/v1/reports/{name}/files/{file}      -> application/pdf                                                (gateway.read)
```

Archived files come from scheduled runs, which execute as `system` and are not filtered for the reader; hence `gateway.read`.
