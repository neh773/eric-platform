import { useCallback, useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { ApiClient, ResourceDocument } from "./api";
import {
  ALARM_KINDS,
  ALARM_MODULE,
  alarmFromSpec,
  alarmProblem,
  emptyAlarm,
  specFromAlarm,
  type AlarmDraft,
  type AlarmKind,
} from "./alarmModel";
import type { AdminPageProps } from "./pages";
import { PipelineStatusPanel } from "./pipelineStatus";
import { ActiveAlarmsPanel } from "./activeAlarms";

/**
 * Rosters, pipelines, schedules, and contacts as forms. Edit as JSON keeps the other settings.
 * Doc source: FEATURES ALA notification, roster, schedule, and pipeline rows.
 */

interface Stored {
  kind: AlarmKind;
  resource: ResourceDocument;
}

interface Editing {
  name: string;
  version: number | null;
  draft: AlarmDraft;
  jsonText: string;
  mode: "form" | "json";
}

export function AlarmEditorPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>Build who is on call, when they are on call, and the pipeline that notifies them. Pipelines already running are listed first.</p>
      <ActiveAlarmsPanel client={client} canAck={allows(session, "tags.write") || allows(session, "gateway.write")} />
      <PipelineStatusPanel client={client} />
      {client ? <AlarmForms client={client} canWrite={canWrite} /> : null}
    </article>
  );
}

function AlarmForms({ client, canWrite }: { client: ApiClient; canWrite: boolean }) {
  const [items, setItems] = useState<Stored[]>([]);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    const lists = await Promise.all((Object.keys(ALARM_KINDS) as AlarmKind[]).map(async (kind) => {
      try {
        const resources = await client.listResources(ALARM_MODULE, "gateway", ALARM_KINDS[kind].resourceKind);
        return resources.map((resource) => ({ kind, resource }));
      } catch {
        return [] as Stored[];
      }
    }));
    setItems(lists.flat());
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
      setFailure(errorMessage(err, err instanceof Error ? err.message : "The alarm setting could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  function openNew(kind: AlarmKind) {
    const draft = emptyAlarm(kind);
    setEditing({ name: "", version: null, draft, jsonText: pretty(specFromAlarm(draft)), mode: "form" });
  }

  function openStored(item: Stored) {
    const draft = alarmFromSpec(item.kind, item.resource.spec);
    setEditing({ name: item.resource.metadata.name, version: item.resource.metadata.version, draft, jsonText: pretty(item.resource.spec), mode: "form" });
  }

  function save(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    void run(async () => {
      const spec = editing.mode === "json" ? parseObject(editing.jsonText) : specFromAlarm(editing.draft);
      const draft = editing.mode === "json" ? alarmFromSpec(editing.draft.kind, spec) : editing.draft;
      const problem = alarmProblem(editing.name.trim(), draft, editing.version == null);
      if (problem) throw new Error(problem);
      const kind = ALARM_KINDS[draft.kind];
      const saved = editing.version == null
        ? await client.createResource(ALARM_MODULE, "gateway", kind.resourceKind, editing.name.trim(), spec)
        : await client.putResource(ALARM_MODULE, "gateway", kind.resourceKind, editing.name, {
            apiVersion: "oip/v1",
            kind: kind.resourceKind,
            metadata: { name: editing.name, module: ALARM_MODULE, version: editing.version },
            spec,
          });
      openStored({ kind: draft.kind, resource: saved });
      setNotice(`Saved ${saved.metadata.name}.`);
      await reload();
    });
  }

  return (
    <section aria-label="Alarm setup">
      {failure ? <p role="alert">{failure}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {items.length === 0 ? <p>No rosters, pipelines, schedules, or contacts yet.</p> : (
        <table className="data-table" aria-label="Alarm setup list">
          <thead><tr><th>Name</th><th>Kind</th><th></th></tr></thead>
          <tbody>
            {items.map((item) => (
              <tr key={`${item.kind}:${item.resource.metadata.name}`}>
                <td>{item.resource.metadata.name}</td>
                <td>{ALARM_KINDS[item.kind].label}</td>
                <td>{canWrite ? <button type="button" className="quiet" onClick={() => openStored(item)}>Edit {item.resource.metadata.name}</button> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite && !editing ? (
        <div className="row-actions">
          <button type="button" onClick={() => openNew("roster")}>New roster</button>
          <button type="button" className="quiet" onClick={() => openNew("pipeline")}>New pipeline</button>
          <button type="button" className="quiet" onClick={() => openNew("schedule")}>New schedule</button>
          <button type="button" className="quiet" onClick={() => openNew("contact")}>New contact</button>
        </div>
      ) : null}
      {editing ? (
        <form className="stack-form" aria-label={editing.version == null ? `New ${ALARM_KINDS[editing.draft.kind].label.toLowerCase()}` : `${ALARM_KINDS[editing.draft.kind].label} ${editing.name}`} onSubmit={save}>
          <h2>{editing.version == null ? `New ${ALARM_KINDS[editing.draft.kind].label.toLowerCase()}` : editing.name}</h2>
          <JsonToggle editing={editing} onChange={setEditing} onError={setFailure} />
          {editing.mode === "json" ? (
            <label>
              Alarm JSON
              <textarea className="json-editor" aria-label="Alarm JSON" value={editing.jsonText} onChange={(event) => setEditing({ ...editing, jsonText: event.target.value })} />
            </label>
          ) : (
            <AlarmFields editing={editing} onChange={setEditing} />
          )}
          <div className="row-actions">
            <button type="submit" disabled={busy}>Save</button>
            {editing.version != null ? (
              <button type="button" className="quiet" disabled={busy} onClick={() => {
                const current = editing;
                if (current.version == null) return;
                void run(async () => {
                  await client.deleteResource(ALARM_MODULE, "gateway", ALARM_KINDS[current.draft.kind].resourceKind, current.name, current.version!);
                  setEditing(null);
                  setNotice(`Deleted ${current.name}.`);
                  await reload();
                });
              }}>Delete {editing.name}</button>
            ) : null}
            <button type="button" className="quiet" onClick={() => setEditing(null)}>Close</button>
          </div>
        </form>
      ) : null}
    </section>
  );
}

function AlarmFields({ editing, onChange }: { editing: Editing; onChange: (editing: Editing) => void }) {
  const draft = editing.draft;
  const set = (patch: Partial<AlarmDraft>) => onChange({ ...editing, draft: { ...draft, ...patch } });
  return (
    <div className="stage-grid">
      {editing.version == null ? (
        <label>Name<input aria-label="Alarm name" value={editing.name} onChange={(event) => onChange({ ...editing, name: event.target.value })} autoComplete="off" /></label>
      ) : null}
      {draft.kind === "roster" ? (
        <label>Users<input aria-label="Roster users" value={draft.users} onChange={(event) => set({ users: event.target.value })} placeholder="ada, bo" /></label>
      ) : null}
      {draft.kind === "pipeline" ? (
        <>
          <label>Roster<input aria-label="Pipeline roster" value={draft.roster} onChange={(event) => set({ roster: event.target.value })} /></label>
          <label>Email profile<input aria-label="Email profile" value={draft.profile} onChange={(event) => set({ profile: event.target.value })} placeholder="optional" /></label>
          <label>Message<textarea aria-label="Pipeline message" value={draft.message} onChange={(event) => set({ message: event.target.value })} /></label>
          <label>Delay before the end (seconds)<input aria-label="Delay seconds" inputMode="numeric" value={draft.delaySeconds} onChange={(event) => set({ delaySeconds: event.target.value })} placeholder="blank to notify and finish" /></label>
          <label>Stop when acknowledged<input type="checkbox" checked={draft.dropOnAck} onChange={(event) => set({ dropOnAck: event.target.checked })} /></label>
        </>
      ) : null}
      {draft.kind === "schedule" ? (
        <label>Weekday hours<input aria-label="Weekday hours" value={draft.hours} onChange={(event) => set({ hours: event.target.value })} placeholder="08:00-17:00" /></label>
      ) : null}
      {draft.kind === "contact" ? (
        <>
          <label>Email<input aria-label="Contact email" value={draft.email} onChange={(event) => set({ email: event.target.value })} /></label>
          <label>Phone<input aria-label="Contact phone" value={draft.phone} onChange={(event) => set({ phone: event.target.value })} /></label>
        </>
      ) : null}
    </div>
  );
}

function JsonToggle({ editing, onChange, onError }: { editing: Editing; onChange: (editing: Editing) => void; onError: (message: string | null) => void }) {
  return (
    <div className="row-actions">
      <button
        type="button"
        className="quiet"
        onClick={() => {
          if (editing.mode === "json") {
            try {
              onChange({ ...editing, mode: "form", draft: alarmFromSpec(editing.draft.kind, parseObject(editing.jsonText)) });
              onError(null);
            } catch (err) {
              onError(err instanceof Error ? err.message : "The JSON could not be read.");
            }
            return;
          }
          onChange({ ...editing, mode: "json", jsonText: pretty(specFromAlarm(editing.draft)) });
        }}
      >
        {editing.mode === "json" ? "Edit as form" : "Edit as JSON"}
      </button>
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
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The alarm JSON must be an object.");
  return value as Record<string, unknown>;
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
