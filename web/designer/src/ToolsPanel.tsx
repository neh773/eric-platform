import { useEffect, useState, type FormEvent } from "react";
import type { GatewayClient } from "./gatewayClient";
import { HistoryQueryTool } from "./HistoryQueryTool";
import { LibrariesTool, ProjectQueriesTool } from "./ProjectModulesTool";

/**
 * Designer tools: query browser, project named queries, script libraries, image library, translation, and the muted-palette guidance. The script
 * console is the Console panel (DES-008).
 */
export type StudioTool = "query" | "history" | "queries" | "libraries" | "images" | "translation" | "guidance";

export function ToolsPanel({
  client,
  project,
  onUseProcessTheme,
  tool: toolProp,
  onTool,
}: {
  client: GatewayClient;
  project: string;
  onUseProcessTheme: () => void;
  tool?: StudioTool;
  onTool?: (tool: StudioTool) => void;
}) {
  const [toolState, setToolState] = useState<StudioTool>(toolProp ?? "query");
  const tool = toolProp ?? toolState;
  function setTool(next: StudioTool) {
    setToolState(next);
    onTool?.(next);
  }
  return (
    <div className="oip-tools">
      <div role="tablist" aria-label="Designer tools">
        {(
          [
            ["query", "Query browser"],
            ["history", "History query"],
            ["queries", "Named queries"],
            ["libraries", "Script libraries"],
            ["images", "Images"],
            ["translation", "Translation"],
            ["guidance", "Guidance"],
          ] as const
        ).map(([id, label]) => (
          <button key={id} type="button" role="tab" aria-selected={tool === id} onClick={() => setTool(id)}>
            {label}
          </button>
        ))}
      </div>
      {tool === "query" ? <QueryTool client={client} /> : null}
      {tool === "history" ? <HistoryQueryTool client={client} /> : null}
      {tool === "queries" ? <ProjectQueriesTool client={client} project={project} /> : null}
      {tool === "libraries" ? <LibrariesTool client={client} project={project} /> : null}
      {tool === "images" ? <ImageTool client={client} project={project} /> : null}
      {tool === "translation" ? <TranslationTool client={client} project={project} /> : null}
      {tool === "guidance" ? <GuidanceTool onUseProcessTheme={onUseProcessTheme} /> : null}
    </div>
  );
}

function QueryTool({ client }: { client: GatewayClient }) {
  const [connections, setConnections] = useState<Array<{ name: string; allowBrowserWrites?: boolean }>>([]);
  const [connection, setConnection] = useState("");
  const [sql, setSql] = useState("SELECT 1 AS n");
  const [result, setResult] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    void client.dbConnections().then((items) => {
      setConnections(items);
      setConnection(items[0]?.name ?? "");
    }).catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, [client]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const body = await client.browseDatabase(connection, sql, 50);
      setResult(body.type === "update" ? `${body.rowsAffected ?? 0} row(s) affected` : JSON.stringify(body.rows ?? []));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }
  return (
    <form aria-label="Query browser" onSubmit={(event) => void submit(event)}>
      {connections.length === 0 ? <p>No database connection is configured.</p> : null}
      <label>
        Connection
        <select aria-label="Query connection" value={connection} onChange={(event) => setConnection(event.target.value)}>
          {connections.map((item) => (
            <option key={item.name} value={item.name}>
              {item.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        SQL statement
        <textarea aria-label="SQL statement" value={sql} onChange={(event) => setSql(event.target.value)} rows={4} />
      </label>
      <button type="submit" disabled={!connection}>
        Run statement
      </button>
      {error ? <p role="alert">{error}</p> : null}
      {result ? <pre aria-label="Query browser result">{result}</pre> : null}
    </form>
  );
}

function ImageTool({ client, project }: { client: GatewayClient; project: string }) {
  const [items, setItems] = useState<Array<{ name: string }>>([]);
  const [name, setName] = useState("mark.svg");
  const [error, setError] = useState<string | null>(null);
  async function reload() {
    setItems(await client.listImages(project));
  }
  useEffect(() => {
    void reload().catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, [client, project]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const file = ((event.currentTarget as HTMLFormElement).elements.namedItem("file") as HTMLInputElement).files?.[0];
    if (!file) return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    try {
      await client.saveImage(project, name || file.name, btoa(binary));
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }
  return (
    <form aria-label="Image manager" onSubmit={(event) => void submit(event)}>
      <ul aria-label="Project images">
        {items.map((item) => (
          <li key={item.name}>{item.name}</li>
        ))}
      </ul>
      <label>
        Name
        <input aria-label="Image name" value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <input aria-label="Image file" name="file" type="file" accept=".png,.jpg,.jpeg,.gif,.svg" />
      <button type="submit">Upload image</button>
      {error ? <p role="alert">{error}</p> : null}
      <p>Use the image path /api/v1/hmi/projects/{project}/images/{name || "name"} on an image component.</p>
    </form>
  );
}

function TranslationTool({ client, project }: { client: GatewayClient; project: string }) {
  const [key, setKey] = useState("welcome");
  const [text, setText] = useState("");
  const [saved, setSaved] = useState("");
  const [error, setError] = useState<string | null>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const current = await client.getResource<{ locale?: string; messages?: Record<string, string> }>("designer", `project/${project}`, "Translation", "messages");
      const messages = { ...(current?.spec.messages ?? {}), [key]: text };
      await client.putResource(`project/${project}`, {
        apiVersion: "oip/v1",
        kind: "Translation",
        metadata: { name: "messages", module: "designer", version: current?.metadata.version ?? 0 },
        spec: { locale: current?.spec.locale ?? "en-US", messages },
      });
      setSaved(`${key}=${text}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }
  return (
    <form aria-label="Translation" onSubmit={(event) => void submit(event)}>
      <label>
        Key
        <input aria-label="Message key" value={key} onChange={(event) => setKey(event.target.value)} />
      </label>
      <label>
        Text
        <input aria-label="Message text" value={text} onChange={(event) => setText(event.target.value)} />
      </label>
      <button type="submit">Save message</button>
      {error ? <p role="alert">{error}</p> : null}
      {saved ? <p role="status">{saved}</p> : null}
    </form>
  );
}

function GuidanceTool({ onUseProcessTheme }: { onUseProcessTheme: () => void }) {
  return (
    <section aria-label="High-performance guidance">
      <p>Use a muted palette. Reserve red, orange, yellow, and violet for alarms. Running or open is an ink fill. Stopped or closed is a gray outline.</p>
      <button type="button" onClick={onUseProcessTheme}>
        Use Dark theme
      </button>
    </section>
  );
}
