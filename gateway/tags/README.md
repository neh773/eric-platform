# gateway/tags

Tag engine, tag/UDT config resources, and tag config export/import.

## Export/import (TAG-005)

[`TagExchange`](src/main/kotlin/org/oip/tags/TagExchange.kt) exports a provider (or a folder
subtree) as one JSON document and imports that document back, per

### Document shape

```json
{
  "version": 1,
  "provider": "default",
  "udts": [ { "name": "<config resource name>", "spec": { ... Udt fields ... } } ],
  "tags":  [ { "name": "<config resource name>", "spec": { ... Tag fields ... } } ]
}
```

`udts`/`tags` are sorted by `name` so two exports of the same state are byte-identical. `name` is
the config resource's *name*, not a file name -- `kind: Udt` is still one file per resource
(`<root>/tags/gateway/Udt/<name>.yaml`, ADR 0005), but `kind: Tag` resources share a fixed 256-shard
set per module/scope (`<root>/tags/gateway/Tag/_shards/sXX.yaml`, ADR 0011); either way, two
different tag paths can only collide on `name` if they were already two different resource names.
`spec` is the same map `TagConfigBinding` loads from the store.

**Memory-tag runtime values are not part of this document.** Only the configured seed `value`
(the one already in the tag's `spec`, if any) is exported; a tag written to at runtime does not
change what a later export produces.

**A folder-subtree export (`folderPath` set) pulls in more than UDTs whose own path is under that
folder.** It also follows every included tag's UDT instance `typeId`, that UDT's `parent` chain, and
any nested member's `typeId`, transitively, so the UDT set is exactly what the exported tags need --
the result can be imported into an empty provider on its own.

### Collision policies

Applied per resource (by `name`), against what's already in the store:

| Policy | Existing resource | New resource |
|---|---|---|
| `OVERWRITE` | replaced entirely by the imported spec | created |
| `MERGE` | RFC 7386 merge patch: only keys present in the import change; others are kept | created |
| `IGNORE` | untouched | created |

### All-or-nothing

Every resource in the document is validated before anything is written: JSON Schema (same schema
`TagConfigBinding` enforces), UDT `typeId`/`parent` references against the union of existing and
imported UDT paths, and `expression`/`readExpression`/`writeExpression` syntax. If any one fails,
`ImportReport.failed` names every bad resource and the store is unchanged and fires no change event.

UDT resources are written before Tag resources in the same `FileConfigStore.putAll` batch, so a UDT
instance imported alongside its definition expands correctly, matching `TagConfigBinding.reload()`.

### Provider

`import`'s optional `provider` parameter, when given, must equal the document's own `provider`
field or the import is rejected outright (nothing written) -- this library never silently remaps a
document exported from one provider onto another. Leave it `null` to accept whatever provider the
document declares.

### REST

Out of scope for this change (TagExchange is a library in `gateway/tags`, called wherever a module
needs it). The gateway already exposes a generic batch endpoint
(`POST /api/v1/config/batch`, see `ConfigApi`) that can apply tag resources directly but has no
collision-policy concept; a dedicated `/api/v1/tags/export` and `/api/v1/tags/import` wiring this
library into `gateway/server` would be a reasonable follow-up issue.
