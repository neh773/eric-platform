import { useCallback, useEffect, useState, type FormEvent } from "react";
import { errorMessage } from "./access";
import type { ApiClient, ResourceDocument } from "./api";
import {
  draftFromSpec,
  emptyGroup,
  emptyItem,
  groupProblem,
  specFromDraft,
  type GroupDraft,
  type GroupItemDraft,
} from "./groupModel";

/**
 * Transaction groups: timer or trigger, four group types, items, and update direction.
 * Edit as JSON keeps the settings this form does not list.
 * Doc source: FEATURES DAT-002, DAT-004, DAT-006..DAT-013, DAT-039.
 */

const MODULE = "db-groups";
const KIND = "TransactionGroup";

interface Editing {
  name: string;
  version: number | null;
  draft: GroupDraft;
  jsonText: string;
  mode: "form" | "json";
}

export function TransactionGroups({ client, canWrite }: { client: ApiClient; canWrite: boolean }) {
  const [groups, setGroups] = useState<ResourceDocument[]>([]);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setGroups(await client.listResources(MODULE, "gateway", KIND));
    } catch {
      setGroups([]);
    }
  }, [client]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setFailure(null);
    setNotice(null);
    try {
      await work();
    } catch (err) {
      setFailure(errorMessage(err, err instanceof Error ? err.message : "The group could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  function openNew() {
    const draft = emptyGroup();
    setEditing({ name: "", version: null, draft, jsonText: pretty(specFromDraft(draft)), mode: "form" });
  }

  function openStored(resource: ResourceDocument) {
    const draft = draftFromSpec(resource.spec);
    setEditing({ name: resource.metadata.name, version: resource.metadata.version, draft, jsonText: pretty(resource.spec), mode: "form" });
  }

  function save(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    void run(async () => {
      const spec = editing.mode === "json" ? parseObject(editing.jsonText) : specFromDraft(editing.draft);
      const draft = editing.mode === "json" ? draftFromSpec(spec) : editing.draft;
      const problem = groupProblem(editing.name.trim(), draft, editing.version == null);
      if (problem) throw new Error(problem);
      const saved = editing.version == null
        ? await client.createResource(MODULE, "gateway", KIND, editing.name.trim(), spec)
        : await client.putResource(MODULE, "gateway", KIND, editing.name, {
            apiVersion: "oip/v1",
            kind: KIND,
            metadata: { name: editing.name, module: MODULE, version: editing.version },
            spec,
          });
      openStored(saved);
      setNotice(`Saved ${saved.metadata.name}.`);
      await reload();
    });
  }

  return (
    <section aria-label="Transaction groups">
      <h2>Transaction groups</h2>
      <p>
        A group moves values between tags and a database table on a timer, or when a trigger tag changes.
        Standard and historical groups write rows. A block group writes one column from a list of tags.
        A stored-procedure group calls a procedure instead of a table. Update or insert uses a key column.
        Database to tag is how a recipe loads a row into tags.
      </p>
      {failure ? <p role="alert">{failure}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {groups.length === 0 ? <p>No transaction groups yet.</p> : (
        <table className="data-table" aria-label="Transaction group list">
          <thead><tr><th>Name</th><th>Type</th><th>Table</th><th></th></tr></thead>
          <tbody>
            {groups.map((group) => (
              <tr key={group.metadata.name}>
                <td>{group.metadata.name}</td>
                <td>{typeof group.spec.groupType === "string" ? group.spec.groupType : ""}</td>
                <td>{typeof group.spec.table === "string" ? group.spec.table : ""}</td>
                <td>{canWrite ? <button type="button" className="quiet" onClick={() => openStored(group)}>Edit {group.metadata.name}</button> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite && !editing ? <p><button type="button" onClick={openNew}>New transaction group</button></p> : null}
      {editing ? (
        <form className="stack-form" aria-label={editing.version == null ? "New transaction group" : `Transaction group ${editing.name}`} onSubmit={save}>
          <h3>{editing.version == null ? "New transaction group" : editing.name}</h3>
          <div className="row-actions">
            <button
              type="button"
              className="quiet"
              onClick={() => {
                if (editing.mode === "json") {
                  try {
                    setEditing({ ...editing, mode: "form", draft: draftFromSpec(parseObject(editing.jsonText)) });
                    setFailure(null);
                  } catch (err) {
                    setFailure(err instanceof Error ? err.message : "The JSON could not be read.");
                  }
                  return;
                }
                setEditing({ ...editing, mode: "json", jsonText: pretty(specFromDraft(editing.draft)) });
              }}
            >
              {editing.mode === "json" ? "Edit as form" : "Edit as JSON"}
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(null)}>Close</button>
          </div>
          {editing.mode === "json" ? (
            <label>
              Group JSON
              <textarea className="json-editor" aria-label="Group JSON" value={editing.jsonText} onChange={(event) => setEditing({ ...editing, jsonText: event.target.value })} />
            </label>
          ) : (
            <GroupFields
              name={editing.name}
              creating={editing.version == null}
              draft={editing.draft}
              onName={(name) => setEditing({ ...editing, name })}
              onChange={(draft) => setEditing({ ...editing, draft })}
            />
          )}
          <div className="row-actions">
            <button type="submit" disabled={busy}>Save group</button>
            {editing.version != null ? (
              <button type="button" className="quiet" disabled={busy} onClick={() => {
                if (!editing || editing.version == null) return;
                const { name, version } = editing;
                void run(async () => {
                  await client.deleteResource(MODULE, "gateway", KIND, name, version);
                  setEditing(null);
                  setNotice(`Deleted ${name}.`);
                  await reload();
                });
              }}>Delete {editing.name}</button>
            ) : null}
          </div>
        </form>
      ) : null}
    </section>
  );
}

function GroupFields({
  name, creating, draft, onName, onChange,
}: {
  name: string;
  creating: boolean;
  draft: GroupDraft;
  onName: (name: string) => void;
  onChange: (draft: GroupDraft) => void;
}) {
  const set = <K extends keyof GroupDraft>(key: K, value: GroupDraft[K]) => onChange({ ...draft, [key]: value });
  return (
    <div className="stage-grid">
      {creating ? <label>Group name<input aria-label="Group name" value={name} onChange={(event) => onName(event.target.value)} autoComplete="off" /></label> : null}
      <label>
        Enabled
        <input type="checkbox" checked={draft.enabled} onChange={(event) => set("enabled", event.target.checked)} />
      </label>
      <label>
        Group type
        <select aria-label="Group type" value={draft.groupType} onChange={(event) => set("groupType", event.target.value as GroupDraft["groupType"])}>
          <option value="standard">Standard</option>
          <option value="historical">Historical</option>
          <option value="block">Block</option>
          <option value="storedProcedure">Stored procedure</option>
        </select>
      </label>
      <label>Connection<input aria-label="Connection" value={draft.connection} onChange={(event) => set("connection", event.target.value)} /></label>
      {draft.groupType === "storedProcedure" ? (
        <label>Procedure<input aria-label="Procedure" value={draft.procedure} onChange={(event) => set("procedure", event.target.value)} /></label>
      ) : (
        <label>Table<input aria-label="Table" value={draft.table} onChange={(event) => set("table", event.target.value)} placeholder="samples" /></label>
      )}
      <label>
        Direction
        <select aria-label="Direction" value={draft.updateMode} onChange={(event) => set("updateMode", event.target.value as GroupDraft["updateMode"])}>
          <option value="tagToDb">Tag to database</option>
          <option value="dbToTag">Database to tag (recipe)</option>
          <option value="bidirectionalTagWins">Both ways, tag wins</option>
          <option value="bidirectionalDbWins">Both ways, database wins</option>
        </select>
      </label>
      <label>
        Table action
        <select aria-label="Table action" value={draft.tableAction} onChange={(event) => set("tableAction", event.target.value as GroupDraft["tableAction"])}>
          <option value="insert">Insert a row</option>
          <option value="update">Update a row</option>
        </select>
      </label>
      {draft.tableAction === "update" ? (
        <>
          <label>
            Which row
            <select aria-label="Which row" value={draft.rowMode} onChange={(event) => set("rowMode", event.target.value as GroupDraft["rowMode"])}>
              <option value="last">Last</option>
              <option value="first">First</option>
              <option value="key">Key column matches an item</option>
            </select>
          </label>
          {draft.rowMode === "key" ? (
            <>
              <label>Key column<input aria-label="Key column" value={draft.keyColumn} onChange={(event) => set("keyColumn", event.target.value)} /></label>
              <label>Key item<input aria-label="Key item" value={draft.keyItem} onChange={(event) => set("keyItem", event.target.value)} /></label>
            </>
          ) : null}
        </>
      ) : null}
      <label>Rate (ms)<input aria-label="Rate" inputMode="numeric" value={draft.rateMillis} onChange={(event) => set("rateMillis", event.target.value)} /></label>
      <label>Trigger tag<input aria-label="Trigger tag" value={draft.triggerTag} onChange={(event) => set("triggerTag", event.target.value)} placeholder="leave blank for a timer" /></label>
      {draft.triggerTag.trim() ? (
        <label>
          Trigger
          <select aria-label="Trigger" value={draft.triggerMode} onChange={(event) => set("triggerMode", event.target.value as GroupDraft["triggerMode"])}>
            <option value="">Any change</option>
            <option value="rising">Rising edge</option>
            <option value="falling">Falling edge</option>
            <option value="while">While true</option>
            <option value="change">On change</option>
          </select>
        </label>
      ) : null}
      {draft.groupType === "block" ? (
        <>
          <label>Block column<input aria-label="Block column" value={draft.blockColumn} onChange={(event) => set("blockColumn", event.target.value)} /></label>
          <label>Block tags<textarea aria-label="Block tags" value={draft.blockTags} onChange={(event) => set("blockTags", event.target.value)} placeholder={"one tag path per line"} /></label>
        </>
      ) : (
        <>
          {draft.items.map((item, index) => (
            <ItemFields key={index} item={item} index={index} onChange={(next) => {
              const items = draft.items.slice();
              items[index] = next;
              set("items", items);
            }} onRemove={() => set("items", draft.items.filter((_, at) => at !== index))} />
          ))}
          <p><button type="button" className="quiet" onClick={() => set("items", [...draft.items, { ...emptyItem(), name: `item_${draft.items.length + 1}` }])}>Add item</button></p>
        </>
      )}
    </div>
  );
}

function ItemFields({ item, index, onChange, onRemove }: { item: GroupItemDraft; index: number; onChange: (item: GroupItemDraft) => void; onRemove: () => void }) {
  const set = <K extends keyof GroupItemDraft>(key: K, value: GroupItemDraft[K]) => onChange({ ...item, [key]: value });
  return (
    <fieldset aria-label={`Item ${index + 1}`}>
      <legend>Item {index + 1}</legend>
      <label>Item name<input aria-label="Item name" value={item.name} onChange={(event) => set("name", event.target.value)} /></label>
      <label>
        Source
        <select aria-label="Item source" value={item.source} onChange={(event) => set("source", event.target.value as GroupItemDraft["source"])}>
          <option value="tag">Tag</option>
          <option value="expression">Expression</option>
          <option value="static">Fixed value</option>
        </select>
      </label>
      {item.source === "tag" ? <label>Tag path<input aria-label="Tag path" value={item.tag} onChange={(event) => set("tag", event.target.value)} /></label> : null}
      {item.source === "expression" ? <label>Expression<input aria-label="Expression" value={item.expression} onChange={(event) => set("expression", event.target.value)} /></label> : null}
      {item.source === "static" ? <label>Value<input aria-label="Item value" value={item.value} onChange={(event) => set("value", event.target.value)} /></label> : null}
      <label>Column<input aria-label="Column" value={item.column} onChange={(event) => set("column", event.target.value)} /></label>
      {item.source === "tag" ? (
        <label>
          Meter
          <select aria-label="Meter" value={item.meter} onChange={(event) => set("meter", event.target.value as GroupItemDraft["meter"])}>
            <option value="">None</option>
            <option value="hour">Hour meter</option>
            <option value="event">Event meter</option>
          </select>
        </label>
      ) : null}
      <p><button type="button" className="quiet" onClick={onRemove}>Remove item {index + 1}</button></p>
    </fieldset>
  );
}

function parseObject(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("The JSON could not be read.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The group JSON must be an object.");
  return value as Record<string, unknown>;
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
