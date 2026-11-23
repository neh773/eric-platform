import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { allows, errorMessage } from "./access";
import type { ApiClient, ResourceDocument } from "./api";
import {
  emptyHistoryTag,
  emptyProvider,
  historyTagFromSpec,
  historyTagProblem,
  providerFromSpec,
  providerProblem,
  specFromHistoryTag,
  specFromProvider,
  type HistoryTagDraft,
  type ProviderDraft,
  type ProviderType,
} from "./historyModel";
import type { AdminPageProps } from "./pages";

/**
 * History providers and the tags that record into them.
 * Edit as JSON keeps settings the form does not list.
 * Doc source: FEATURES HIS-001, HIS-002, HIS-004 through HIS-009.
 */

const MODULE = "historian";

export function HistoryPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>The built-in provider named local records history on this gateway. Add another provider when history should go to a database, a file, or two places at once. Then choose which tags record, and where.</p>
      {client ? <Providers client={client} canWrite={canWrite} /> : null}
      {client ? <TagHistory client={client} canWrite={canWrite} /> : null}
    </article>
  );
}

function Providers({ client, canWrite }: { client: ApiClient; canWrite: boolean }) {
  return (
    <ResourceEditor
      client={client}
      canWrite={canWrite}
      title="Providers"
      kind="HistoryProvider"
      newLabel="New provider"
      formLabel="provider"
      load={() => client.listResources(MODULE, "gateway", "HistoryProvider")}
      empty={() => emptyProvider()}
      problem={(name, draft, creating) => providerProblem(name, draft as ProviderDraft, creating)}
      toSpec={(draft) => specFromProvider(draft as ProviderDraft)}
      fromSpec={(spec) => providerFromSpec(spec)}
      fields={(draft, onChange, creating, name, onName) => (
        <ProviderFields draft={draft as ProviderDraft} onChange={onChange as (draft: ProviderDraft) => void} creating={creating} name={name} onName={onName} />
      )}
    />
  );
}

function TagHistory({ client, canWrite }: { client: ApiClient; canWrite: boolean }) {
  return (
    <ResourceEditor
      client={client}
      canWrite={canWrite}
      title="Tag history"
      kind="HistorySettings"
      newLabel="Record a tag"
      formLabel="tag history"
      load={() => client.listResources(MODULE, "gateway", "HistorySettings")}
      empty={() => emptyHistoryTag()}
      problem={(name, draft, creating) => historyTagProblem(name, draft as HistoryTagDraft, creating)}
      toSpec={(draft) => specFromHistoryTag(draft as HistoryTagDraft)}
      fromSpec={(spec) => historyTagFromSpec(spec)}
      fields={(draft, onChange, creating, name, onName) => (
        <TagFields draft={draft as HistoryTagDraft} onChange={onChange as (draft: HistoryTagDraft) => void} creating={creating} name={name} onName={onName} />
      )}
    />
  );
}

function ResourceEditor({
  client, canWrite, title, kind, newLabel, formLabel, load, empty, problem, toSpec, fromSpec, fields,
}: {
  client: ApiClient;
  canWrite: boolean;
  title: string;
  kind: string;
  newLabel: string;
  formLabel: string;
  load: () => Promise<ResourceDocument[]>;
  empty: () => ProviderDraft | HistoryTagDraft;
  problem: (name: string, draft: ProviderDraft | HistoryTagDraft, creating: boolean) => string | null;
  toSpec: (draft: ProviderDraft | HistoryTagDraft) => Record<string, unknown>;
  fromSpec: (spec: Record<string, unknown>) => ProviderDraft | HistoryTagDraft;
  fields: (draft: ProviderDraft | HistoryTagDraft, onChange: (draft: ProviderDraft | HistoryTagDraft) => void, creating: boolean, name: string, onName: (name: string) => void) => ReactNode;
}) {
  const [items, setItems] = useState<ResourceDocument[]>([]);
  const [editing, setEditing] = useState<{ name: string; version: number | null; draft: ProviderDraft | HistoryTagDraft; jsonText: string; mode: "form" | "json" } | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const reload = useCallback(async () => {
    try {
      setItems(await load());
    } catch {
      setItems([]);
    }
  }, [load]);
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
      setFailure(errorMessage(err, err instanceof Error ? err.message : "Could not save."));
    } finally {
      setBusy(false);
    }
  }

  function save(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    void run(async () => {
      const spec = editing.mode === "json" ? parseObject(editing.jsonText) : toSpec(editing.draft);
      const draft = editing.mode === "json" ? fromSpec(spec) : editing.draft;
      const issue = problem(editing.name.trim(), draft, editing.version == null);
      if (issue) throw new Error(issue);
      const saved = editing.version == null
        ? await client.createResource(MODULE, "gateway", kind, editing.name.trim(), spec)
        : await client.putResource(MODULE, "gateway", kind, editing.name, {
            apiVersion: "oip/v1",
            kind,
            metadata: { name: editing.name, module: MODULE, version: editing.version },
            spec,
          });
      setEditing({ name: saved.metadata.name, version: saved.metadata.version, draft: fromSpec(saved.spec), jsonText: pretty(saved.spec), mode: "form" });
      setNotice(`Saved ${saved.metadata.name}.`);
      await reload();
    });
  }

  return (
    <section aria-label={title}>
      <h2>{title}</h2>
      {failure ? <p role="alert">{failure}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {items.length === 0 ? <p>None yet.</p> : (
        <table className="data-table">
          <thead><tr><th>Name</th><th></th></tr></thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.metadata.name}>
                <td>{item.metadata.name}</td>
                <td>{canWrite ? <button type="button" className="quiet" onClick={() => setEditing({ name: item.metadata.name, version: item.metadata.version, draft: fromSpec(item.spec), jsonText: pretty(item.spec), mode: "form" })}>Edit {item.metadata.name}</button> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite && !editing ? <p><button type="button" onClick={() => { const draft = empty(); setEditing({ name: "", version: null, draft, jsonText: pretty(toSpec(draft)), mode: "form" }); }}>{newLabel}</button></p> : null}
      {editing ? (
        <form className="stack-form" aria-label={editing.version == null ? `New ${formLabel}` : `${title} ${editing.name}`} onSubmit={save}>
          <h3>{editing.version == null ? newLabel : editing.name}</h3>
          <div className="row-actions">
            <button type="button" className="quiet" onClick={() => {
              if (editing.mode === "json") {
                try {
                  setEditing({ ...editing, mode: "form", draft: fromSpec(parseObject(editing.jsonText)) });
                  setFailure(null);
                } catch (err) {
                  setFailure(err instanceof Error ? err.message : "The JSON could not be read.");
                }
                return;
              }
              setEditing({ ...editing, mode: "json", jsonText: pretty(toSpec(editing.draft)) });
            }}>{editing.mode === "json" ? "Edit as form" : "Edit as JSON"}</button>
          </div>
          {editing.mode === "json" ? (
            <label>JSON<textarea className="json-editor" aria-label="History JSON" value={editing.jsonText} onChange={(event) => setEditing({ ...editing, jsonText: event.target.value })} /></label>
          ) : fields(editing.draft, (draft) => setEditing({ ...editing, draft }), editing.version == null, editing.name, (name) => setEditing({ ...editing, name }))}
          <div className="row-actions">
            <button type="submit" disabled={busy}>Save</button>
            {editing.version != null ? <button type="button" className="quiet" disabled={busy} onClick={() => {
              const current = editing;
              if (current.version == null) return;
              void run(async () => {
                await client.deleteResource(MODULE, "gateway", kind, current.name, current.version!);
                setEditing(null);
                setNotice(`Deleted ${current.name}.`);
                await reload();
              });
            }}>Delete {editing.name}</button> : null}
            <button type="button" className="quiet" onClick={() => setEditing(null)}>Close</button>
          </div>
        </form>
      ) : null}
    </section>
  );
}

function ProviderFields({ draft, onChange, creating, name, onName }: { draft: ProviderDraft; onChange: (draft: ProviderDraft) => void; creating: boolean; name: string; onName: (name: string) => void }) {
  const set = (patch: Partial<ProviderDraft>) => onChange({ ...draft, ...patch });
  return (
    <div className="stage-grid">
      {creating ? <label>Name<input aria-label="Provider name" value={name} onChange={(event) => onName(event.target.value)} autoComplete="off" /></label> : null}
      <label>
        Type
        <select aria-label="Provider type" value={draft.type} onChange={(event) => onChange(emptyProvider(event.target.value as ProviderType))}>
          <option value="sql">SQL database</option>
          <option value="csv">CSV file</option>
          <option value="table">Existing table, read only</option>
          <option value="splitter">Splitter</option>
          <option value="simulator">Simulator (test data)</option>
        </select>
      </label>
      <label>Enabled<input type="checkbox" checked={draft.enabled} onChange={(event) => set({ enabled: event.target.checked })} /></label>
      {draft.type === "sql" || draft.type === "table" ? <label>Connection<input aria-label="Connection" value={draft.connection} onChange={(event) => set({ connection: event.target.value })} /></label> : null}
      {draft.type === "sql" ? <label>Table<input aria-label="Table" value={draft.table} onChange={(event) => set({ table: event.target.value })} /></label> : null}
      {draft.type === "sql" || draft.type === "simulator" ? (
        <>
          <label>Forward rate (ms)<input aria-label="Forward rate" inputMode="numeric" value={draft.forwardRateMillis} onChange={(event) => set({ forwardRateMillis: event.target.value })} /></label>
          <label>Batch size<input aria-label="Batch size" inputMode="numeric" value={draft.batchSize} onChange={(event) => set({ batchSize: event.target.value })} /></label>
          <label>Disk limit<input aria-label="Disk limit" inputMode="numeric" value={draft.maxPending} onChange={(event) => set({ maxPending: event.target.value })} /></label>
        </>
      ) : null}
      {draft.type === "csv" ? <label>File<input aria-label="File" value={draft.file} onChange={(event) => set({ file: event.target.value })} /></label> : null}
      {draft.type === "splitter" ? (
        <>
          <label>First provider<input aria-label="First provider" value={draft.first} onChange={(event) => set({ first: event.target.value })} /></label>
          <label>Second provider<input aria-label="Second provider" value={draft.second} onChange={(event) => set({ second: event.target.value })} /></label>
        </>
      ) : null}
      {draft.type === "table" ? (
        <>
          <label>Source table<input aria-label="Source table" value={draft.sourceTable} onChange={(event) => set({ sourceTable: event.target.value })} /></label>
          <label>Time column<input aria-label="Time column" value={draft.timestampColumn} onChange={(event) => set({ timestampColumn: event.target.value })} /></label>
          <label>Tag column<input aria-label="Tag column" value={draft.tagColumn} onChange={(event) => set({ tagColumn: event.target.value })} /></label>
          <label>Value column<input aria-label="Value column" value={draft.valueColumn} onChange={(event) => set({ valueColumn: event.target.value })} /></label>
        </>
      ) : null}
    </div>
  );
}

function TagFields({ draft, onChange, creating, name, onName }: { draft: HistoryTagDraft; onChange: (draft: HistoryTagDraft) => void; creating: boolean; name: string; onName: (name: string) => void }) {
  const set = (patch: Partial<HistoryTagDraft>) => onChange({ ...draft, ...patch });
  return (
    <div className="stage-grid">
      {creating ? <label>Name<input aria-label="History name" value={name} onChange={(event) => onName(event.target.value)} autoComplete="off" /></label> : null}
      <label>Tag path<input aria-label="Tag path" value={draft.tagPath} onChange={(event) => set({ tagPath: event.target.value })} placeholder="[default]Plant/Speed" /></label>
      <label>
        Sample mode
        <select aria-label="Sample mode" value={draft.sampleMode} onChange={(event) => set({ sampleMode: event.target.value as HistoryTagDraft["sampleMode"] })}>
          <option value="onChange">On change</option>
          <option value="periodic">Periodic</option>
        </select>
      </label>
      <label>Sample rate (ms)<input aria-label="Sample rate" inputMode="numeric" value={draft.sampleRateMillis} onChange={(event) => set({ sampleRateMillis: event.target.value })} /></label>
      <label>
        Deadband
        <select aria-label="Deadband" value={draft.deadbandMode} onChange={(event) => set({ deadbandMode: event.target.value as HistoryTagDraft["deadbandMode"] })}>
          <option value="off">Off</option>
          <option value="absolute">Absolute</option>
          <option value="percent">Percent</option>
        </select>
      </label>
      {draft.deadbandMode !== "off" ? <label>Deadband value<input aria-label="Deadband value" inputMode="decimal" value={draft.deadbandValue} onChange={(event) => set({ deadbandValue: event.target.value })} /></label> : null}
      <label>Max time between samples (ms)<input aria-label="Max time between samples" inputMode="numeric" value={draft.maxTimeBetweenSamplesMillis} onChange={(event) => set({ maxTimeBetweenSamplesMillis: event.target.value })} /></label>
      <label>Provider<input aria-label="Storage provider" value={draft.storageProvider} onChange={(event) => set({ storageProvider: event.target.value })} placeholder="local" /></label>
    </div>
  );
}

function parseObject(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("The JSON could not be read.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The JSON must be an object.");
  return value as Record<string, unknown>;
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
