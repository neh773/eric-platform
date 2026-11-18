import { useEffect, useMemo, useState, type FormEvent } from "react";
import { allows, errorMessage } from "../access";
import { ApiError, type ApiClient, type ResourceDocument, type SessionState } from "../api";
import { indexProblemErrors, mergeFieldErrors, validateAgainstSchema, type JsonSchema } from "../schema";
import { SchemaForm } from "../schemaForm";
import { ACCESS_DENIED, deleteTag, getTag, saveTag, writeValue } from "./tagClient";
import {
  CATEGORIES,
  categorize,
  describeAccess,
  formatTagPath,
  formatValue,
  formCanEdit,
  fromAccessRule,
  fromJsonText,
  parseValueInput,
  pathProblems,
  splitNames,
  toAccessRule,
  toJsonText,
  withKey,
  type AccessRule,
  type CategoryId,
} from "./tagModel";
import { formatTime } from "./TagTree";
import { useLiveValues } from "./useLiveValues";

/** Keys edited in the Move / rename box rather than in a category form. */
const LOCATION_KEYS = new Set(["provider", "path"]);

/**
 * Tag editor (TAG-012): every property of the `Tag` JSON Schema, grouped by category. Saving replaces the
 * resource at the loaded version, so the version goes up by one; if someone else saved first the gateway
 * answers 409 and the editor offers a reload. Move / rename changes `path` (the gateway carries a memory
 * tag's value to the new path). Configuration edits need gateway.write; value writes need tags.write and
 * the tag's own write permissions.
 */
export function TagEditor({
  client,
  session,
  schema,
  resourceName,
  onChanged,
  onMoved,
  onDeleted,
  onOpenUdt,
}: {
  client: ApiClient;
  session: SessionState | undefined;
  schema: JsonSchema;
  resourceName: string;
  onChanged: () => void;
  onMoved: (fullPath: string) => void;
  onDeleted: () => void;
  /** Open the UDT instance wizard for a UDT type path (C-8). */
  onOpenUdt?: (typeId: string) => void;
}) {
  const canEdit = allows(session, "gateway.write");
  const [doc, setDoc] = useState<ResourceDocument | null>(null);
  const [spec, setSpec] = useState<Record<string, unknown>>({});
  const [category, setCategory] = useState<CategoryId>("basic");
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [jsonErrors, setJsonErrors] = useState<Record<string, string>>({});
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);
  /** Bumped by "Discard changes" so every field remounts from the stored spec, dropping typed drafts. */
  const [discardToken, setDiscardToken] = useState(0);
  const [moveTo, setMoveTo] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    setConflict(false);
    getTag(client, resourceName)
      .then((next) => {
        if (cancelled) return;
        setDoc(next);
        setSpec(next.spec);
        setMoveTo(typeof next.spec.path === "string" ? next.spec.path : "");
        setDrafts({});
        setJsonErrors({});
        setServerErrors({});
        setConfirmDelete(false);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load the tag"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, resourceName, reloadToken]);

  const groups = useMemo(() => categorize(schema), [schema]);
  const provider = typeof doc?.spec.provider === "string" && doc.spec.provider ? doc.spec.provider : "default";
  const storedPath = typeof doc?.spec.path === "string" ? doc.spec.path : "";
  const fullPath = formatTagPath(provider, storedPath);
  const dirty = doc !== null && JSON.stringify(doc.spec) !== JSON.stringify(spec);

  const structural = validateAgainstSchema(schema, spec);
  const errors = mergeFieldErrors(structural, { ...drafts, ...jsonErrors }, serverErrors);
  const typing = Object.keys(drafts).length > 0 || Object.keys(jsonErrors).length > 0;
  const blocked = structural.length > 0 || typing;

  function discard() {
    if (!doc) return;
    setSpec(doc.spec);
    setDrafts({});
    setJsonErrors({});
    setServerErrors({});
    setDiscardToken((n) => n + 1);
  }

  async function persist(next: Record<string, unknown>, done: string): Promise<ResourceDocument | null> {
    if (!doc || pending) return null;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await saveTag(client, { ...doc, spec: next });
      setDoc(saved);
      setSpec(saved.spec);
      setMoveTo(typeof saved.spec.path === "string" ? saved.spec.path : "");
      setServerErrors({});
      setConflict(false);
      setNotice(`${done} Version ${saved.metadata.version}.`);
      onChanged();
      return saved;
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) setConflict(true);
      else if (err instanceof ApiError && err.status === 422) {
        setServerErrors(indexProblemErrors(err.errors.length > 0 ? err.errors : [err.message]));
        setError(err.message);
      } else setError(errorMessage(err, "Save failed"));
      return null;
    } finally {
      setPending(false);
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!canEdit || blocked) return;
    await persist(spec, "Saved.");
  }

  async function move() {
    const problems = pathProblems(moveTo);
    if (problems.length > 0) {
      setError(problems[0] ?? "Invalid path");
      return;
    }
    const target = moveTo.trim();
    if (target === storedPath) return;
    const saved = await persist({ ...(doc?.spec ?? {}), path: target }, "Moved.");
    if (saved) onMoved(formatTagPath(provider, target));
  }

  async function remove() {
    if (!doc || pending) return;
    setPending(true);
    setError(null);
    try {
      await deleteTag(client, doc.metadata.name, doc.metadata.version);
      onDeleted();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) setConflict(true);
      else setError(errorMessage(err, "Delete failed"));
    } finally {
      setPending(false);
      setConfirmDelete(false);
    }
  }

  const change = (next: Record<string, unknown>) => {
    setSpec(next);
    setServerErrors({});
    setNotice(null);
  };

  const currentKeys = (groups[category] ?? []).filter((key) => !LOCATION_KEYS.has(key));
  const formKeys = currentKeys.filter((key) => formCanEdit(schema.properties?.[key]) && !isAccessKey(key));
  const jsonKeys = currentKeys.filter((key) => !formCanEdit(schema.properties?.[key]) && !isAccessKey(key) && key !== "alarms");
  const formSchema: JsonSchema = { type: "object", properties: Object.fromEntries(formKeys.map((key) => [key, schema.properties![key]!])) };
  const meta = CATEGORIES.find((c) => c.id === category)!;

  return (
    <section className="tag-editor" data-testid="tag-editor" aria-label="Tag editor">
      <header className="tag-editor-head">
        <h2>{fullPath || resourceName}</h2>
        <p className="muted" data-testid="tag-version">
          Resource {resourceName}
          {doc ? ` · version ${doc.metadata.version}` : ""}
          {doc?.metadata.modifiedBy ? ` · ${doc.metadata.modifiedBy}` : ""}
        </p>
      </header>
      {doc && doc.spec.type !== "folder" && doc.spec.type !== "udt" ? (
        <LiveValue client={client} session={session} path={fullPath} dataType={String(spec.dataType ?? "")} />
      ) : null}
      {doc && doc.spec.type === "udt" && typeof doc.spec.typeId === "string" && onOpenUdt ? (
        <p>
          Instance of UDT <code>{doc.spec.typeId}</code>.{" "}
          <button type="button" className="quiet" onClick={() => onOpenUdt(String(doc.spec.typeId))}>
            Create more instances of this UDT
          </button>
        </p>
      ) : null}
      {!canEdit ? (
        <p role="status" data-testid="tag-editor-readonly">
          Changing tag configuration requires the gateway.write permission. The properties are shown read-only.
        </p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {conflict ? (
        <div role="alert" data-testid="version-conflict" className="banner banner-warn">
          <p>This tag was changed somewhere else. Reload the current version. Your edit was not saved.</p>
          <button type="button" onClick={() => setReloadToken((token) => token + 1)}>
            Reload
          </button>
        </div>
      ) : null}
      {doc ? (
        <>
          <div role="tablist" aria-label="Property categories" className="tag-tabs">
            {CATEGORIES.map((c) => {
              const count = (groups[c.id] ?? []).filter((key) => !LOCATION_KEYS.has(key)).length;
              if (c.id === "advanced" && count === 0) return null;
              return (
                <button
                  key={c.id}
                  type="button"
                  role="tab"
                  aria-selected={category === c.id}
                  className={category === c.id ? "active" : undefined}
                  onClick={() => setCategory(c.id)}
                >
                  {c.label}
                </button>
              );
            })}
          </div>
          <form onSubmit={(event) => void save(event)} aria-label={`${meta.label} properties`}>
            <fieldset disabled={!canEdit || pending} className="tag-category" role="tabpanel" aria-label={meta.label}>
              {currentKeys.length === 0 && meta.empty ? <p className="muted">{meta.empty}</p> : null}
              {errors[""] ? <p role="alert">{errors[""]}</p> : null}
              {formKeys.length > 0 ? (
                <SchemaForm
                  key={`${category}:${doc.metadata.version}:${reloadToken}:${discardToken}`}
                  schema={formSchema}
                  value={spec}
                  errors={errors}
                  onDrafts={setDrafts}
                  onChange={(next) => change(next && typeof next === "object" ? (next as Record<string, unknown>) : spec)}
                />
              ) : null}
              {category === "alarms" && currentKeys.includes("alarms") ? <AlarmSummary alarms={spec.alarms} /> : null}
              {[...(category === "alarms" && currentKeys.includes("alarms") ? ["alarms"] : []), ...jsonKeys].map((key) => (
                <JsonField
                  key={`${key}:${doc.metadata.version}:${reloadToken}:${discardToken}`}
                  name={key}
                  value={spec[key]}
                  error={errors[key]}
                  onChange={(result) => {
                    setJsonErrors((prev) => {
                      const next = { ...prev };
                      if (result.error) next[key] = result.error;
                      else delete next[key];
                      return next;
                    });
                    if (!result.error) change(withKey(spec, key, result.remove ? undefined : result.value));
                  }}
                />
              ))}
              {currentKeys.filter(isAccessKey).map((key) => (
                <AccessEditor
                  key={`${key}:${doc.metadata.version}:${reloadToken}:${discardToken}`}
                  name={key} raw={spec[key]} onChange={(rule) => change(withKey(spec, key, fromAccessRule(rule)))} />
              ))}
            </fieldset>
            {canEdit ? (
              <div className="schema-actions">
                <button type="submit" disabled={pending || blocked || !dirty}>
                  Save
                </button>
                <button type="button" className="quiet" disabled={pending || (!dirty && !typing)} onClick={discard}>
                  Discard changes
                </button>
              </div>
            ) : null}
          </form>
          {canEdit ? (
            <div className="tag-actions">
              <label>
                Move or rename to
                <input aria-label="New tag path" value={moveTo} onChange={(event) => setMoveTo(event.target.value)} placeholder="Folder/Name" />
              </label>
              <button type="button" disabled={pending || dirty || moveTo.trim() === storedPath} onClick={() => void move()}>
                Move
              </button>
              {dirty ? <small className="muted">Save or discard changes before moving.</small> : null}
              {confirmDelete ? (
                <span className="inline-form">
                  <button type="button" className="danger" disabled={pending} onClick={() => void remove()}>
                    Confirm delete
                  </button>
                  <button type="button" className="quiet" onClick={() => setConfirmDelete(false)}>
                    Cancel
                  </button>
                </span>
              ) : (
                <button type="button" className="danger" disabled={pending} onClick={() => setConfirmDelete(true)}>
                  Delete tag
                </button>
              )}
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

function isAccessKey(key: string): boolean {
  return key === "readPermissions" || key === "writePermissions";
}

export function LiveValue({ client, session, path, dataType }: { client: ApiClient; session: SessionState | undefined; path: string; dataType: string }) {
  const paths = useMemo(() => [path], [path]);
  const live = useLiveValues(client, paths);
  const row = live.values.get(path);
  const canWrite = allows(session, "tags.write");
  const [text, setText] = useState("");
  const [result, setResult] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const denied = row?.quality === ACCESS_DENIED;

  async function write(event: FormEvent) {
    event.preventDefault();
    const parsed = parseValueInput(text, dataType);
    if (parsed.error) {
      setFailed(true);
      setResult(parsed.error);
      return;
    }
    try {
      const outcome = await writeValue(client, path, parsed.value);
      setFailed(!outcome.ok);
      setResult(outcome.ok ? "Written." : `Write refused: ${outcome.quality}${outcome.message ? ` (${outcome.message})` : ""}`);
    } catch (err) {
      setFailed(true);
      setResult(errorMessage(err, "Write failed"));
    }
  }

  return (
    <div className="tag-live" data-testid="tag-live">
      <dl>
        <dt>Value</dt>
        <dd data-testid="tag-live-value">{denied ? <em>Access denied: no read permission for this tag</em> : row ? formatValue(row.value, row.quality) : "…"}</dd>
        <dt>Quality</dt>
        <dd data-testid="tag-live-quality">{row?.quality ?? ""}</dd>
        <dt>Timestamp</dt>
        <dd>{row && !denied ? formatTime(row.timestamp) : ""}</dd>
      </dl>
      {canWrite ? (
        <form className="inline-form" onSubmit={(event) => void write(event)}>
          <label>
            Write value
            <input aria-label="Value to write" value={text} onChange={(event) => setText(event.target.value)} />
          </label>
          <button type="submit">Write</button>
        </form>
      ) : null}
      {result ? (
        <p role={failed ? "alert" : "status"} data-testid="tag-write-result">
          {result}
        </p>
      ) : null}
    </div>
  );
}

function JsonField({
  name,
  value,
  error,
  onChange,
}: {
  name: string;
  value: unknown;
  error?: string;
  onChange: (result: { value?: unknown; remove?: boolean; error?: string }) => void;
}) {
  const [text, setText] = useState(() => toJsonText(value));
  return (
    <label className="schema-field">
      <span>{name} (JSON)</span>
      <textarea
        aria-label={`${name} (JSON)`}
        rows={Math.min(14, Math.max(2, text.split("\n").length))}
        value={text}
        aria-invalid={error ? true : undefined}
        onChange={(event) => {
          setText(event.target.value);
          onChange(fromJsonText(event.target.value));
        }}
      />
      {error ? <small role="alert">{error}</small> : <small className="schema-help">Leave empty to remove the property.</small>}
    </label>
  );
}

function AccessEditor({ name, raw, onChange }: { name: string; raw: unknown; onChange: (rule: AccessRule) => void }) {
  const rule = toAccessRule(raw);
  const label = name === "readPermissions" ? "Read permissions" : "Write permissions";
  const [roles, setRoles] = useState(rule.roles.join(", "));
  const [zones, setZones] = useState(rule.zones.join(", "));
  return (
    <fieldset className="schema-object" aria-label={label}>
      <legend>{label}</legend>
      <label className="schema-check">
        <input
          type="checkbox"
          aria-label={`Restrict ${label.toLowerCase()}`}
          checked={rule.restricted}
          onChange={(event) => onChange({ restricted: event.target.checked, roles: splitNames(roles), zones: splitNames(zones) })}
        />
        <span>Restrict to roles and zones</span>
      </label>
      {rule.restricted ? (
        <>
          <label className="schema-field">
            <span>Roles</span>
            <input
              aria-label={`${label} roles`}
              value={roles}
              placeholder="administrator, operator"
              onChange={(event) => {
                setRoles(event.target.value);
                onChange({ restricted: true, roles: splitNames(event.target.value), zones: splitNames(zones) });
              }}
            />
          </label>
          <label className="schema-field">
            <span>Zones</span>
            <input
              aria-label={`${label} zones`}
              value={zones}
              placeholder="Any zone when empty"
              onChange={(event) => {
                setZones(event.target.value);
                onChange({ restricted: true, roles: splitNames(roles), zones: splitNames(event.target.value) });
              }}
            />
          </label>
        </>
      ) : null}
      <small className="schema-help">Admitted: {describeAccess(rule)}.</small>
    </fieldset>
  );
}

function AlarmSummary({ alarms }: { alarms: unknown }) {
  const list = Array.isArray(alarms) ? alarms : [];
  if (list.length === 0) return <p className="muted">No alarms are configured on this tag.</p>;
  return (
    <table className="data-table" aria-label="Alarms">
      <thead>
        <tr>
          <th>Name</th>
          <th>Mode</th>
          <th>Priority</th>
          <th>Setpoint</th>
          <th>Enabled</th>
        </tr>
      </thead>
      <tbody>
        {list.map((item, index) => {
          const a = item && typeof item === "object" ? (item as Record<string, unknown>) : {};
          return (
            <tr key={index}>
              <td>{String(a.name ?? "")}</td>
              <td>{String(a.mode ?? "")}</td>
              <td>{String(a.priority ?? "")}</td>
              <td>{bound(a.setpointA)}</td>
              <td>{a.enabled === undefined ? "true" : bound(a.enabled)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function bound(value: unknown): string {
  if (value === undefined) return "";
  if (value && typeof value === "object") {
    const b = value as Record<string, unknown>;
    if (typeof b.tag === "string") return `tag ${b.tag}`;
    if (typeof b.expression === "string") return `expr ${b.expression}`;
    if (typeof b.parameter === "string") return `param ${b.parameter}`;
    return JSON.stringify(value);
  }
  return String(value);
}
