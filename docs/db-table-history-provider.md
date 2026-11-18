# Existing database tables as a history provider

Doc source: FEATURES HIS-008. Test: `DbTableHistoryProviderTest` in `gateway/historian`.

A `HistoryProvider` resource of `type: table` reads history from a table that already exists in PostgreSQL.
It is read only: it creates nothing, writes nothing and manages no partitions. A sample routed to it is refused as
permanent. History queries, the power chart and tag history bindings read it through the same interfaces as any other
provider, by naming the provider.

## Narrow table (one row per sample)

```yaml
type: table
connection: plant-db          # a database connection
sourceTable: public.readings  # table or schema.table
timestampColumn: ts
layout: narrow
tagColumn: tag                # holds the tag path
valueColumn: val
qualityColumn: q              # optional
```

`tagColumn` holds the tag path, either as `[provider]path` or as the bare path. If it holds an id instead, add
`tagIds: { Line/Flow: 7 }` to map tag paths to ids.

## Wide table (one column per tag)

```yaml
type: table
connection: plant-db
sourceTable: public.line_wide
timestampColumn: epoch_ms
timestampUnit: epochMillis
layout: wide
tagColumns: { Line/Speed: speed, Line/State: state }
```

A null cell is no sample.

## Details

- `timestamp` and `timestamptz` columns are read as UTC. A `timestamp` without zone is taken to hold UTC. For a number
  column set `timestampUnit` to `epochMillis` or `epochSeconds`.
- Names are quoted exactly as written, so give the case the table uses. Only letters, digits, `_` and `$` are accepted.
- Quality text is read by quality name. A number from 0 to 255 follows the OPC DA convention (192 and over good, 64
  and over uncertain, below that bad). A larger number is an OPC UA status code. Without a quality column every sample is Good.
- Every read runs in a read-only transaction that is rolled back.
