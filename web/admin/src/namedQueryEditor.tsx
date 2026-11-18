import { useEffect, useState } from "react";
import { errorMessage } from "./access";
import type { ApiClient, DbConnection, NamedQueryInfo } from "./api";
import { QueryTest } from "./databasesPage";
import { CheckField, NumberField, SelectField, TextField } from "./reports/controls";
import { sqlPlaceholders } from "./reports/reportModel";

/**
 * Named query editor (FEATURES DAT-031): the settings of one named query (connection, statement, parameters, roles,
 * result cache, row limit), Save and Delete, and a test run of the saved query with typed parameter values.
 * The test run goes through the normal execute route, so the query's role list applies to the tester as to any caller.
 * doc source: FEATURES DAT-031.
 */

const DATA_TYPES = ["string", "integer", "long", "float", "double", "boolean", "datetime"] as const;
const QUERY_TYPES = ["query", "scalar", "update", "procedure"] as const;
const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

interface ParamDraft {
  name: string;
  dataType: string;
  mode: string;
  queryString: boolean;
}

interface Draft {
  description: string;
  enabled: boolean;
  connection: string;
  queryType: string;
  sql: string;
  procedure: string;
  routine: string;
  returnType: string;
  parameters: ParamDraft[];
  roles: string;
  cacheEnabled: boolean;
  cacheTtlMillis: number;
  maxRows: number;
}

const EMPTY: Draft = {
  description: "",
  enabled: true,
  connection: "<default>",
  queryType: "query",
  sql: "",
  procedure: "",
  routine: "procedure",
  returnType: "",
  parameters: [],
  roles: "",
  cacheEnabled: false,
  cacheTtlMillis: 0,
  maxRows: 10_000,
};

function str(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

export function draftFromDetail(detail: Record<string, unknown>): Draft {
  const parameters = Array.isArray(detail.parameters) ? detail.parameters : [];
  return {
    description: str(detail.description),
    enabled: detail.enabled !== false,
    connection: str(detail.connection, "<default>"),
    queryType: str(detail.queryType, "query"),
    sql: str(detail.sql),
    procedure: str(detail.procedure),
    routine: str(detail.routine, "procedure"),
    returnType: str(detail.returnType),
    parameters: parameters
      .filter((p): p is Record<string, unknown> => !!p && typeof p === "object")
      .map((p) => ({ name: str(p.name), dataType: str(p.dataType, "string"), mode: str(p.mode, "in"), queryString: p.queryString === true })),
    roles: Array.isArray(detail.roles) ? detail.roles.filter((r): r is string => typeof r === "string").join(", ") : "",
    cacheEnabled: detail.cacheEnabled === true,
    cacheTtlMillis: typeof detail.cacheTtlMillis === "number" ? detail.cacheTtlMillis : 0,
    maxRows: typeof detail.maxRows === "number" ? detail.maxRows : 10_000,
  };
}

/** The fields the gateway's query resource takes. A procedure has no SQL text; a statement has no routine. */
export function fieldsFromDraft(draft: Draft): Record<string, unknown> {
  const fields: Record<string, unknown> = {
    description: draft.description,
    enabled: draft.enabled,
    connection: draft.connection,
    queryType: draft.queryType,
    parameters: draft.parameters.map((p) => {
      const out: Record<string, unknown> = { name: p.name, dataType: p.dataType };
      if (draft.queryType === "procedure" && p.mode !== "in") out.mode = p.mode;
      if (p.queryString) out.queryString = true;
      return out;
    }),
    roles: draft.roles.split(",").map((r) => r.trim()).filter(Boolean),
    cacheEnabled: draft.cacheEnabled,
    cacheTtlMillis: draft.cacheEnabled ? draft.cacheTtlMillis : 0,
    maxRows: draft.maxRows,
  };
  if (draft.queryType === "procedure") {
    fields.procedure = draft.procedure;
    fields.routine = draft.routine;
    if (draft.returnType) fields.returnType = draft.returnType;
  } else {
    fields.sql = draft.sql;
  }
  return fields;
}

export function NamedQueryEditor({ client, name, connections, onSaved, onClose }: {
  client: ApiClient;
  /** The query to edit, or null for a new one. */
  name: string | null;
  connections: DbConnection[];
  /** Called after a save or delete so the list reloads; [name] is the saved query. */
  onSaved: (name: string) => void;
  onClose: () => void;
}) {
  /** A new query becomes an existing one once it is saved, and the editor goes on editing it. */
  const [createdName, setCreatedName] = useState<string | null>(null);
  const current = name ?? createdName;
  const creating = current === null;
  const [queryName, setQueryName] = useState(name ?? "");
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [baseline, setBaseline] = useState(JSON.stringify(EMPTY));
  const [version, setVersion] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(creating);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [testing, setTesting] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (name === null) return;
    let cancelled = false;
    client.namedQueryDetail(name).then(
      (detail) => {
        if (cancelled) return;
        const next = draftFromDetail(detail);
        setDraft(next);
        setBaseline(JSON.stringify(next));
        setVersion(typeof detail.version === "number" ? detail.version : 0);
        setLoaded(true);
      },
      (reason: unknown) => {
        if (!cancelled) setError(errorMessage(reason, "The query could not be loaded"));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [client, name]);

  const dirty = JSON.stringify(draft) !== baseline;
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    setDraft((current) => ({ ...current, [key]: value }));
    setMessage("");
  };
  const setParam = (index: number, patch: Partial<ParamDraft>) => set("parameters", draft.parameters.map((p, i) => (i === index ? { ...p, ...patch } : p)));
  const connectionOptions = [...new Set([...connections.map((c) => c.name), "<default>", "<parameter>", draft.connection])];

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const target = creating ? queryName : current!;
      const saved = await client.saveNamedQuery(target, fieldsFromDraft(draft), creating ? null : version);
      const next = draftFromDetail(saved);
      setDraft(next);
      setBaseline(JSON.stringify(next));
      setVersion(typeof saved.version === "number" ? saved.version : 0);
      setCreatedName(target);
      setMessage(`Saved ${target}.`);
      onSaved(target);
    } catch (reason) {
      setError(errorMessage(reason, "The query could not be saved"));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (creating || version === null) return;
    setBusy(true);
    setError(null);
    try {
      await client.deleteNamedQuery(current!, version);
      onSaved(current!);
      onClose();
    } catch (reason) {
      setError(errorMessage(reason, "The query could not be deleted"));
      setBusy(false);
    }
  }

  const info: NamedQueryInfo = {
    name: current ?? queryName,
    description: draft.description,
    enabled: draft.enabled,
    connection: draft.connection,
    queryType: draft.queryType,
    sql: draft.sql,
    procedure: draft.procedure,
    parameters: draft.parameters.map((p) => ({ name: p.name, dataType: p.dataType, mode: p.mode === "out" || p.mode === "inout" ? p.mode : "in" })),
    roles: [],
  };
  const canSave = loaded && !busy && (creating ? NAME.test(queryName) : dirty);
  return (
    <>
      <form className="stack-form" aria-label="Named query editor" onSubmit={(event) => event.preventDefault()}>
        <h2>{creating ? "New named query" : `Edit ${current}`}</h2>
        {creating ? <TextField label="Query name" value={queryName} placeholder="line-production" onChange={setQueryName} /> : null}
        <TextField label="Query description" value={draft.description} onChange={(v) => set("description", v)} />
        <CheckField label="Query enabled" checked={draft.enabled} onChange={(v) => set("enabled", v)} />
        <SelectField label="Query connection" value={draft.connection} options={connectionOptions} onChange={(v) => set("connection", v)} />
        <SelectField label="Query type" value={draft.queryType} options={QUERY_TYPES} onChange={(v) => set("queryType", v)} />
        {draft.queryType === "procedure" ? (
          <>
            <TextField label="Procedure name" value={draft.procedure} onChange={(v) => set("procedure", v)} />
            <SelectField label="Routine kind" value={draft.routine} options={["procedure", "function"]} onChange={(v) => set("routine", v)} />
            <SelectField label="Return type" value={draft.returnType} options={DATA_TYPES} blank="None" onChange={(v) => set("returnType", v)} />
          </>
        ) : (
          <label>
            Query SQL
            <textarea aria-label="Query SQL" rows={5} value={draft.sql} onChange={(event) => set("sql", event.target.value)} />
          </label>
        )}
        <fieldset className="report-panel">
          <legend>Parameters</legend>
          {draft.parameters.length === 0 ? <p className="hint">Use :name in the SQL to bind a parameter, or mark a parameter as a query string to put its text in the SQL.</p> : null}
          {draft.parameters.map((p, index) => {
            const n = index + 1;
            return (
              <div className="report-row" role="group" aria-label={`Query parameter ${n}`} key={index}>
                <TextField label={`Query parameter ${n} name`} value={p.name} onChange={(v) => setParam(index, { name: v })} />
                <SelectField label={`Query parameter ${n} type`} value={p.dataType} options={DATA_TYPES} onChange={(v) => setParam(index, { dataType: v })} />
                {draft.queryType === "procedure" ? (
                  <SelectField label={`Query parameter ${n} mode`} value={p.mode} options={["in", "out", "inout"]} onChange={(v) => setParam(index, { mode: v })} />
                ) : null}
                <CheckField label={`Query parameter ${n} is a query string`} checked={p.queryString} onChange={(v) => setParam(index, { queryString: v })} />
                <button type="button" onClick={() => set("parameters", draft.parameters.filter((_, i) => i !== index))}>Remove query parameter {n}</button>
              </div>
            );
          })}
          <div className="inline-form">
            <button type="button" onClick={() => set("parameters", [...draft.parameters, { name: "", dataType: "string", mode: "in", queryString: false }])}>Add query parameter</button>
            {draft.queryType !== "procedure" ? (
              <button
                type="button"
                onClick={() => {
                  const have = draft.parameters.map((p) => p.name);
                  const added = sqlPlaceholders(draft.sql).filter((n) => !have.includes(n)).map((n) => ({ name: n, dataType: "string", mode: "in", queryString: false }));
                  set("parameters", [...draft.parameters, ...added]);
                }}
              >
                Detect parameters from SQL
              </button>
            ) : null}
          </div>
        </fieldset>
        <fieldset className="report-panel">
          <legend>Settings</legend>
          <TextField label="Query roles" value={draft.roles} placeholder="Roles that may run it, comma separated; empty allows everyone" onChange={(v) => set("roles", v)} />
          <CheckField label="Cache results" checked={draft.cacheEnabled} onChange={(v) => set("cacheEnabled", v)} />
          {draft.cacheEnabled ? <NumberField label="Cache time to live (ms)" value={draft.cacheTtlMillis} min={0} max={86_400_000} onChange={(v) => set("cacheTtlMillis", v ?? 0)} /> : null}
          <NumberField label="Maximum rows" value={draft.maxRows} min={1} max={100_000} onChange={(v) => set("maxRows", v ?? 10_000)} />
        </fieldset>
        {error ? <p role="alert">{error}</p> : null}
        <p aria-live="polite">{dirty && !creating ? "Unsaved changes" : message}</p>
        <div className="inline-form">
          <button type="button" disabled={!canSave} onClick={() => void save()}>Save query</button>
          {!creating ? <button type="button" disabled={busy || dirty || !draft.enabled} onClick={() => setTesting(true)}>Test run</button> : null}
          {!creating ? <button type="button" disabled={busy} onClick={() => void remove()}>Delete query</button> : null}
          <button type="button" onClick={onClose}>Close editor</button>
        </div>
        {!creating && dirty ? <p className="hint">Save the changes to test them; a test run uses the saved query.</p> : null}
      </form>
      {testing && !creating && !dirty ? <QueryTest key={`${current}-${version}`} client={client} query={info} onClose={() => setTesting(false)} /> : null}
    </>
  );
}
