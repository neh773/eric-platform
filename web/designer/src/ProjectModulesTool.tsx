import { useEffect, useState, type FormEvent } from "react";
import { GatewayError, type ConfigResource, type GatewayClient } from "./gatewayClient";

/**
 * Project-scoped script library modules and named queries (PRO-001, DES-011). A session or script of the project
 * imports its own modules and runs its own queries first, then a parent project's, then the gateway's. Both are
 * saved straight to the gateway as config resources of the project (`scripting` / `ScriptModule`, `db` / `NamedQuery`).
 */

const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const NAME_HELP = "Use lowercase letters, digits and hyphens, 1 to 63 characters.";

function message(error: unknown): string {
  if (error instanceof GatewayError) return error.message;
  return error instanceof Error ? error.message : String(error);
}

interface LibrarySpec {
  source?: string;
  module?: string;
}

export function LibrariesTool({ client, project }: { client: GatewayClient; project: string }) {
  const scope = `project/${project}`;
  const [items, setItems] = useState<ConfigResource<LibrarySpec>[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const current = items.find((item) => item.metadata.name === selected) ?? null;

  async function reload(pick?: string) {
    const next = await client.listResources<LibrarySpec>("scripting", scope, "ScriptModule");
    setItems(next);
    const chosen = pick ?? selected;
    const found = next.find((item) => item.metadata.name === chosen) ?? next[0];
    setSelected(found?.metadata.name ?? null);
    setDraft(found?.spec.source ?? "");
  }
  useEffect(() => {
    void reload().catch((err: unknown) => setError(message(err)));
  }, [client, project]);

  async function add(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!NAME.test(trimmed)) return setError(NAME_HELP);
    if (items.some((item) => item.metadata.name === trimmed)) return setError("A library with that name already exists.");
    try {
      await client.putResource(scope, { apiVersion: "oip/v1", kind: "ScriptModule", metadata: { name: trimmed, module: "scripting", version: 0 }, spec: { source: "" } });
      setName("");
      await reload(trimmed);
    } catch (err) {
      setError(message(err));
    }
  }

  async function save() {
    if (!current) return;
    setError(null);
    try {
      await client.putResource(scope, { ...current, spec: { ...current.spec, source: draft } });
      setStatus(`Saved ${current.metadata.name}`);
      await reload(current.metadata.name);
    } catch (err) {
      setError(message(err));
    }
  }

  async function remove() {
    if (!current) return;
    setError(null);
    try {
      await client.deleteResource("scripting", scope, "ScriptModule", current.metadata.name, current.metadata.version ?? 0);
      setStatus(`Removed ${current.metadata.name}`);
      await reload();
    } catch (err) {
      setError(message(err));
    }
  }

  return (
    <section aria-label="Script libraries" data-testid="script-libraries">
      <p>Library modules are Python code this project&apos;s scripts import. A module here replaces a gateway or parent project module of the same name.</p>
      {items.length === 0 ? <p>No script libraries in this project.</p> : null}
      <ul aria-label="Libraries">
        {items.map((item) => (
          <li key={item.metadata.name}>
            <button
              type="button"
              aria-current={item.metadata.name === selected ? "true" : undefined}
              onClick={() => {
                setSelected(item.metadata.name);
                setDraft(item.spec.source ?? "");
              }}
            >
              {item.metadata.name}
            </button>
          </li>
        ))}
      </ul>
      <form aria-label="New script library" onSubmit={(event) => void add(event)}>
        <input aria-label="New library name" placeholder="New library name" value={name} onChange={(event) => setName(event.target.value)} />
        <button type="submit">Add library</button>
      </form>
      {current ? (
        <div role="region" aria-label={`Library ${current.metadata.name}`}>
          <textarea aria-label="Library source" rows={10} spellCheck={false} value={draft} onChange={(event) => setDraft(event.target.value)} />
          <button type="button" onClick={() => void save()}>
            Save library
          </button>
          <button type="button" onClick={() => void remove()}>
            Remove library {current.metadata.name}
          </button>
        </div>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {status ? <p role="status">{status}</p> : null}
    </section>
  );
}

interface QuerySpec {
  connection?: string;
  queryType?: string;
  sql?: string;
  parameters?: unknown[];
}

export function ProjectQueriesTool({ client, project }: { client: GatewayClient; project: string }) {
  const scope = `project/${project}`;
  const [items, setItems] = useState<ConfigResource<QuerySpec>[]>([]);
  const [connections, setConnections] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [connection, setConnection] = useState("");
  const [queryType, setQueryType] = useState("query");
  const [sql, setSql] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const current = items.find((item) => item.metadata.name === selected) ?? null;

  function pick(item: ConfigResource<QuerySpec> | undefined) {
    setSelected(item?.metadata.name ?? null);
    setConnection(item?.spec.connection ?? "");
    setQueryType(item?.spec.queryType ?? "query");
    setSql(item?.spec.sql ?? "");
  }
  async function reload(choose?: string) {
    const next = await client.listResources<QuerySpec>("db", scope, "NamedQuery");
    setItems(next);
    pick(next.find((item) => item.metadata.name === (choose ?? selected)) ?? next[0]);
  }
  useEffect(() => {
    void reload().catch((err: unknown) => setError(message(err)));
    void client.dbConnections().then((list) => setConnections(list.map((item) => item.name))).catch(() => {});
  }, [client, project]);

  async function add(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!NAME.test(trimmed)) return setError(NAME_HELP);
    if (items.some((item) => item.metadata.name === trimmed)) return setError("A query with that name already exists.");
    try {
      await client.putResource(scope, {
        apiVersion: "oip/v1",
        kind: "NamedQuery",
        metadata: { name: trimmed, module: "db", version: 0 },
        spec: { connection: connections[0] ?? "", queryType: "query", sql: "SELECT 1", parameters: [] },
      });
      setName("");
      await reload(trimmed);
    } catch (err) {
      setError(message(err));
    }
  }

  async function save() {
    if (!current) return;
    setError(null);
    try {
      await client.putResource(scope, { ...current, spec: { ...current.spec, connection, queryType, sql } });
      setStatus(`Saved ${current.metadata.name}`);
      await reload(current.metadata.name);
    } catch (err) {
      setError(message(err));
    }
  }

  async function remove() {
    if (!current) return;
    setError(null);
    try {
      await client.deleteResource("db", scope, "NamedQuery", current.metadata.name, current.metadata.version ?? 0);
      setStatus(`Removed ${current.metadata.name}`);
      await reload();
    } catch (err) {
      setError(message(err));
    }
  }

  const [convertName, setConvertName] = useState("");
  const [convertScript, setConvertScript] = useState("");
  const [rewritten, setRewritten] = useState("");

  /** DAT-035: the pasted script call becomes a named query of this project, and the call is rewritten to run it. */
  async function convert(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = convertName.trim();
    if (!NAME.test(trimmed)) return setError(NAME_HELP);
    if (items.some((item) => item.metadata.name === trimmed)) return setError("A query with that name already exists.");
    try {
      const converted = await client.convertToNamedQuery({ name: trimmed, script: convertScript, ...(connections[0] ? { connection: connections[0] } : {}) });
      await client.putResource(scope, {
        apiVersion: "oip/v1",
        kind: "NamedQuery",
        metadata: { name: trimmed, module: "db", version: 0 },
        spec: converted.query as QuerySpec,
      });
      setRewritten(converted.script);
      setStatus(`Created named query ${trimmed}`);
      setConvertName("");
      await reload(trimmed);
    } catch (err) {
      setError(message(err));
    }
  }

  return (
    <section aria-label="Project named queries" data-testid="project-queries">
      <p>A query here runs for this project&apos;s sessions instead of a parent project&apos;s or the gateway&apos;s query of the same name. Database connections stay gateway settings.</p>
      {items.length === 0 ? <p>No named queries in this project.</p> : null}
      <ul aria-label="Project queries">
        {items.map((item) => (
          <li key={item.metadata.name}>
            <button type="button" aria-current={item.metadata.name === selected ? "true" : undefined} onClick={() => pick(item)}>
              {item.metadata.name}
            </button>
          </li>
        ))}
      </ul>
      <form aria-label="New project query" onSubmit={(event) => void add(event)}>
        <input aria-label="New query name" placeholder="New query name" value={name} onChange={(event) => setName(event.target.value)} />
        <button type="submit">Add query</button>
      </form>
      {current ? (
        <div role="region" aria-label={`Query ${current.metadata.name}`}>
          <label>
            Connection
            <select aria-label="Project query connection" value={connection} onChange={(event) => setConnection(event.target.value)}>
              {[...new Set([connection, ...connections].filter(Boolean))].map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </label>
          <label>
            Query type
            <select aria-label="Project query type" value={queryType} onChange={(event) => setQueryType(event.target.value)}>
              {["query", "scalar", "update"].map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </label>
          <textarea aria-label="Project query SQL" rows={6} spellCheck={false} value={sql} onChange={(event) => setSql(event.target.value)} />
          <button type="button" onClick={() => void save()}>
            Save query
          </button>
          <button type="button" onClick={() => void remove()}>
            Remove query {current.metadata.name}
          </button>
        </div>
      ) : null}
      <form aria-label="Convert ad hoc SQL" onSubmit={(event) => void convert(event)}>
        <p>Paste a script that calls system.db.runPrepQuery, runPrepUpdate or runScalarPrepQuery. The SQL becomes a named query of this project and the call is rewritten to run it.</p>
        <input aria-label="Converted query name" placeholder="Name for the new query" value={convertName} onChange={(event) => setConvertName(event.target.value)} />
        <textarea aria-label="Ad hoc script" rows={4} spellCheck={false} value={convertScript} onChange={(event) => setConvertScript(event.target.value)} />
        <button type="submit" disabled={!convertScript.trim()}>
          Convert to named query
        </button>
        {rewritten ? <textarea aria-label="Rewritten script" readOnly rows={4} value={rewritten} /> : null}
      </form>
      {error ? <p role="alert">{error}</p> : null}
      {status ? <p role="status">{status}</p> : null}
    </section>
  );
}
