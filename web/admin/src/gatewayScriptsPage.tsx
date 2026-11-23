import { useCallback, useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { ApiClient, ResourceDocument } from "./api";
import type { AdminPageProps } from "./pages";

const MODULE = "scripting";
const KIND = "GatewayEvent";
const TYPES = [
  { value: "timer", label: "Timer" },
  { value: "tagChange", label: "Tag change" },
  { value: "scheduled", label: "Scheduled (cron)" },
  { value: "startup", label: "Startup" },
  { value: "shutdown", label: "Shutdown" },
] as const;

export interface ScriptDraft {
  name: string;
  type: string;
  description: string;
  enabled: boolean;
  delayMillis: string;
  delayType: "fixedDelay" | "fixedRate";
  tagPaths: string;
  cron: string;
  source: string;
}

const EMPTY: ScriptDraft = {
  name: "", type: "timer", description: "", enabled: true, delayMillis: "1000", delayType: "fixedDelay", tagPaths: "", cron: "", source: "",
};

export function draftFromResource(resource: ResourceDocument): ScriptDraft {
  const spec = resource.spec as Record<string, unknown>;
  return {
    name: resource.metadata.name,
    type: String(spec.type ?? "timer"),
    description: String(spec.description ?? ""),
    enabled: spec.enabled !== false,
    delayMillis: String(spec.delayMillis ?? 1000),
    delayType: spec.delayType === "fixedRate" ? "fixedRate" : "fixedDelay",
    tagPaths: Array.isArray(spec.tagPaths) ? spec.tagPaths.join("\n") : "",
    cron: String(spec.cron ?? ""),
    source: String(spec.source ?? ""),
  };
}

/** The GatewayEvent spec for a draft, or a message saying what to fix. */
export function specFromDraft(draft: ScriptDraft): Record<string, unknown> | string {
  if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(draft.name.trim())) return "Name: lower-case letters, digits and dashes.";
  const spec: Record<string, unknown> = { type: draft.type, enabled: draft.enabled, description: draft.description, source: draft.source };
  if (draft.type === "timer") {
    const delay = Number(draft.delayMillis);
    if (!Number.isInteger(delay) || delay < 10 || delay > 86_400_000) return "Delay: a whole number of milliseconds from 10 to 86400000.";
    spec.delayMillis = delay;
    spec.delayType = draft.delayType;
  }
  if (draft.type === "tagChange") {
    const paths = draft.tagPaths.split(/\n|,/).map((item) => item.trim()).filter(Boolean);
    if (paths.length === 0) return "Tag change scripts need at least one tag path.";
    spec.tagPaths = paths;
  }
  if (draft.type === "scheduled") {
    if (!draft.cron.trim()) return "Scheduled scripts need a cron schedule, for example 0 */5 * * * *.";
    spec.cron = draft.cron.trim();
  }
  return spec;
}

export function GatewayScriptsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>Scripts the gateway runs on a timer, when tags change, on a schedule, or at startup and shutdown.</p>
      {client ? <ScriptEditor client={client} canWrite={canWrite} /> : null}
    </article>
  );
}

function ScriptEditor({ client, canWrite }: { client: ApiClient; canWrite: boolean }) {
  const [items, setItems] = useState<ResourceDocument[]>([]);
  const [draft, setDraft] = useState<ScriptDraft | null>(null);
  const [version, setVersion] = useState<number | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setItems(await client.listResources(MODULE, "gateway", KIND));
    } catch (err) {
      setFailure(errorMessage(err, "Could not list the gateway scripts"));
    }
  }, [client]);

  useEffect(() => { void reload(); }, [reload]);

  function save(event: FormEvent) {
    event.preventDefault();
    if (!draft) return;
    const spec = specFromDraft(draft);
    if (typeof spec === "string") { setFailure(spec); return; }
    setFailure(null);
    void (async () => {
      try {
        const saved = version == null
          ? await client.createResource(MODULE, "gateway", KIND, draft.name.trim(), spec)
          : await client.putResource(MODULE, "gateway", KIND, draft.name, { apiVersion: "oip/v1", kind: KIND, metadata: { name: draft.name, module: MODULE, version }, spec });
        setVersion(saved.metadata.version);
        setNotice(`Saved ${saved.metadata.name}.`);
        await reload();
      } catch (err) {
        setFailure(errorMessage(err, "Could not save the script"));
      }
    })();
  }

  async function remove() {
    if (!draft || version == null) return;
    try {
      await client.deleteResource(MODULE, "gateway", KIND, draft.name, version);
      setNotice(`Deleted ${draft.name}.`);
      setDraft(null);
      setVersion(null);
      await reload();
    } catch (err) {
      setFailure(errorMessage(err, "Could not delete the script"));
    }
  }

  const set = (patch: Partial<ScriptDraft>) => setDraft((current) => (current ? { ...current, ...patch } : current));
  return (
    <section aria-label="Gateway scripts">
      {failure ? <p role="alert">{failure}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      <table aria-label="Gateway scripts list">
        <thead><tr><th>Name</th><th>Runs</th><th>Enabled</th></tr></thead>
        <tbody>
          {items.length === 0 ? <tr><td colSpan={3}>No gateway scripts yet.</td></tr> : null}
          {items.map((item) => {
            const spec = item.spec as Record<string, unknown>;
            return (
              <tr key={item.metadata.name}>
                <td><button type="button" className="link" onClick={() => { setDraft(draftFromResource(item)); setVersion(item.metadata.version); setFailure(null); setNotice(null); }}>{item.metadata.name}</button></td>
                <td>{TYPES.find((t) => t.value === spec.type)?.label ?? String(spec.type)}{spec.type === "timer" ? ` every ${String(spec.delayMillis ?? 1000)} ms` : ""}</td>
                <td>{spec.enabled === false ? "No" : "Yes"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {canWrite ? <button type="button" onClick={() => { setDraft({ ...EMPTY }); setVersion(null); setFailure(null); setNotice(null); }}>New script</button> : null}
      {draft ? (
        <form aria-label={version == null ? "New gateway script" : `Gateway script ${draft.name}`} onSubmit={save} className="stage-grid">
          <label>Script name<input aria-label="Script name" value={draft.name} disabled={version != null} onChange={(event) => set({ name: event.target.value })} autoComplete="off" /></label>
          <label>
            Runs
            <select aria-label="Script type" value={draft.type} onChange={(event) => set({ type: event.target.value })}>
              {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </label>
          {draft.type === "timer" ? (
            <>
              <label>Delay (ms)<input aria-label="Delay (ms)" inputMode="numeric" value={draft.delayMillis} onChange={(event) => set({ delayMillis: event.target.value })} /></label>
              <label>
                Timing
                <select aria-label="Timing" value={draft.delayType} onChange={(event) => set({ delayType: event.target.value as ScriptDraft["delayType"] })}>
                  <option value="fixedDelay">Fixed delay (wait after each run)</option>
                  <option value="fixedRate">Fixed rate</option>
                </select>
              </label>
            </>
          ) : null}
          {draft.type === "tagChange" ? (
            <label>Tag paths (one per line)<textarea aria-label="Tag paths" value={draft.tagPaths} onChange={(event) => set({ tagPaths: event.target.value })} placeholder="[default]Plant/Level" /></label>
          ) : null}
          {draft.type === "scheduled" ? (
            <label>Cron schedule<input aria-label="Cron schedule" value={draft.cron} onChange={(event) => set({ cron: event.target.value })} placeholder="0 */5 * * * *" /></label>
          ) : null}
          <label>Description<input aria-label="Description" value={draft.description} onChange={(event) => set({ description: event.target.value })} /></label>
          <label>Enabled<input type="checkbox" checked={draft.enabled} onChange={(event) => set({ enabled: event.target.checked })} /></label>
          <label className="wide">Script (Python)<textarea aria-label="Script source" rows={10} spellCheck={false} value={draft.source} onChange={(event) => set({ source: event.target.value })} /></label>
          <div className="actions">
            {canWrite ? <button type="submit">Save script</button> : null}
            {canWrite && version != null ? <button type="button" className="danger" onClick={() => void remove()}>Delete {draft.name}</button> : null}
            <button type="button" className="quiet" onClick={() => setDraft(null)}>Close</button>
          </div>
        </form>
      ) : null}
    </section>
  );
}
