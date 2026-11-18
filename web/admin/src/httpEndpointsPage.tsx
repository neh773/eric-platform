import { useEffect, useState, type FormEvent } from "react";
import { ApiListing } from "./apiListing";
import { allows, errorMessage } from "./access";
import { ApiError } from "./api";
import type { AdminPageProps } from "./pages";

const MODULE = "webdev";
const KIND = "WebResource";
const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const FOLDER = /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/;

interface EndpointDraft {
  path: string;
  type: "text" | "python" | "mounted";
  contentType: string;
  body: string;
  source: string;
  folder: string;
  requireAuthentication: boolean;
  requireHttps: boolean;
  enabled: boolean;
}

interface EndpointRow {
  name: string;
  path: string;
  type: string;
  version: number;
}

function emptyDraft(): EndpointDraft {
  return {
    path: "",
    type: "text",
    contentType: "text/plain; charset=utf-8",
    body: "",
    source: "",
    folder: "",
    requireAuthentication: false,
    requireHttps: false,
    enabled: true,
  };
}

export function endpointProblem(name: string, draft: EndpointDraft): string | null {
  if (!NAME.test(name)) return "Use a short lowercase name. Digits and dashes are allowed.";
  const path = draft.path.trim().replace(/^\/+|\/+$/g, "");
  if (!path || path.split("/").some((part) => part === "" || part === "." || part === "..")) return "The path needs a name and cannot contain . or ..";
  if (draft.type === "mounted" && !FOLDER.test(draft.folder.trim())) return "The folder name uses letters, digits, dots, and dashes.";
  if (draft.type === "python" && !draft.source.trim()) return "A Python endpoint needs a doGet or doPost function.";
  return null;
}

export function buildEndpoint(draft: EndpointDraft): Record<string, unknown> {
  const spec: Record<string, unknown> = {
    project: "gateway",
    path: draft.path.trim().replace(/^\/+|\/+$/g, ""),
    type: draft.type,
    contentType: draft.contentType.trim() || "text/plain; charset=utf-8",
    requireAuthentication: draft.requireAuthentication,
    requireHttps: draft.requireHttps,
    enabled: draft.enabled,
  };
  if (draft.type === "text") spec.body = draft.body;
  if (draft.type === "python") spec.source = draft.source;
  if (draft.type === "mounted") spec.folder = draft.folder.trim();
  return spec;
}

/** WEB-001 and WEB-003: a text page, a Python handler, or a mounted folder at /system/webdev. */
export function HttpEndpointsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [rows, setRows] = useState<EndpointRow[]>([]);
  const [name, setName] = useState("");
  const [draft, setDraft] = useState<EndpointDraft>(emptyDraft());
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("{}");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function patch(next: Partial<EndpointDraft>) {
    setDraft((current) => ({ ...current, ...next }));
  }

  async function load() {
    if (!client) return;
    const items = await client.listResources(MODULE, "gateway", KIND);
    setRows(items.map((item) => ({
      name: item.metadata.name,
      path: typeof item.spec.path === "string" ? item.spec.path : "",
      type: typeof item.spec.type === "string" ? item.spec.type : "",
      version: item.metadata.version,
    })));
  }

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    load().catch((err: unknown) => {
      if (!cancelled) setError(errorMessage(err, "Could not load HTTP endpoints"));
    });
    return () => {
      cancelled = true;
    };
  }, [client]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!client) return;
    setError(null);
    setNotice(null);
    const problem = jsonMode ? null : endpointProblem(name.trim(), draft);
    if (problem) {
      setError(problem);
      return;
    }
    try {
      const spec = jsonMode ? (JSON.parse(jsonText) as Record<string, unknown>) : buildEndpoint(draft);
      await client.createResource(MODULE, "gateway", KIND, name.trim(), spec);
      const path = typeof spec.path === "string" ? spec.path : draft.path;
      setNotice(`Saved. Open /system/webdev/gateway/${path}`);
      setName("");
      setDraft(emptyDraft());
      setJsonMode(false);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorMessage(err, "Could not save the endpoint") : err instanceof Error ? err.message : "Could not save the endpoint");
    }
  }

  async function remove(row: EndpointRow) {
    if (!client) return;
    setError(null);
    try {
      await client.deleteResource(MODULE, "gateway", KIND, row.name, row.version);
      setNotice(`Deleted ${row.name}.`);
      await load();
    } catch (err) {
      setError(errorMessage(err, "Could not delete the endpoint"));
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>An endpoint is served at /system/webdev/gateway/ followed by its path. Text returns a page. Python runs doGet or doPost. Mounted serves files from a folder under the gateway webdev directory.</p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {canWrite ? (
        <form aria-label="New endpoint" onSubmit={(event) => void save(event)}>
          <label>
            Name
            <input aria-label="Endpoint name" value={name} onChange={(event) => setName(event.target.value)} />
          </label>
          <label>
            Path
            <input aria-label="Path" value={draft.path} disabled={jsonMode} onChange={(event) => patch({ path: event.target.value })} />
          </label>
          <label>
            Type
            <select aria-label="Type" value={draft.type} disabled={jsonMode} onChange={(event) => patch({ type: event.target.value as EndpointDraft["type"] })}>
              <option value="text">Text</option>
              <option value="python">Python</option>
              <option value="mounted">Mounted folder</option>
            </select>
          </label>
          <label>
            Content type
            <input aria-label="Content type" value={draft.contentType} disabled={jsonMode} onChange={(event) => patch({ contentType: event.target.value })} />
          </label>
          {draft.type === "text" ? (
            <label>
              Body
              <textarea aria-label="Body" value={draft.body} disabled={jsonMode} onChange={(event) => patch({ body: event.target.value })} rows={4} />
            </label>
          ) : null}
          {draft.type === "python" ? (
            <label>
              Python
              <textarea aria-label="Python" value={draft.source} disabled={jsonMode} onChange={(event) => patch({ source: event.target.value })} rows={6} />
            </label>
          ) : null}
          {draft.type === "mounted" ? (
            <label>
              Folder
              <input aria-label="Folder" value={draft.folder} disabled={jsonMode} onChange={(event) => patch({ folder: event.target.value })} />
            </label>
          ) : null}
          <label>
            <input type="checkbox" checked={draft.requireAuthentication} disabled={jsonMode} onChange={(event) => patch({ requireAuthentication: event.target.checked })} />
            Require sign-in
          </label>
          <label>
            <input type="checkbox" checked={draft.requireHttps} disabled={jsonMode} onChange={(event) => patch({ requireHttps: event.target.checked })} />
            Require HTTPS
          </label>
          <label>
            <input type="checkbox" checked={draft.enabled} disabled={jsonMode} onChange={(event) => patch({ enabled: event.target.checked })} />
            Enabled
          </label>
          <label>
            <input
              type="checkbox"
              checked={jsonMode}
              onChange={(event) => {
                const on = event.target.checked;
                if (on) setJsonText(JSON.stringify(buildEndpoint(draft), null, 2));
                setJsonMode(on);
              }}
            />
            Edit as JSON
          </label>
          {jsonMode ? <textarea aria-label="Endpoint JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} rows={10} /> : null}
          <button type="submit">Save endpoint</button>
        </form>
      ) : null}
      <table className="data-table" aria-label="HTTP endpoints">
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Address</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.name}>
              <td>{row.name}</td>
              <td>{row.type}</td>
              <td>{`/system/webdev/gateway/${row.path}`}</td>
              <td>{canWrite ? <button type="button" onClick={() => void remove(row)}>Delete {row.name}</button> : null}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <ApiListing />
    </article>
  );
}
