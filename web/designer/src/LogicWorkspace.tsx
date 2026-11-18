import { lazy, Suspense, useEffect, useRef, useState } from "react";
import type { GatewayClient } from "./gatewayClient";
import { ST_COMPLETION, mappingDocument, rowsFromMapping, type MapRow } from "./logicModel";
import { PlcWindowsBanner } from "./plcBanner";
import "./logic.css";

const StEditor = lazy(() => import("./StEditor"));

interface Diagnostic {
  line: number;
  column: number;
  message: string;
}

interface Watched {
  name: string;
  value: unknown;
  forced: boolean;
  output: boolean;
}

export function LogicWorkspace({ client }: { client: GatewayClient }) {
  const [programs, setPrograms] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [draftName, setDraftName] = useState("");
  const [source, setSource] = useState("");
  const sourceRef = useRef("");
  const [rows, setRows] = useState<MapRow[]>([]);
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("");
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const [status, setStatus] = useState("stopped");
  const [stats, setStats] = useState("");
  const [watched, setWatched] = useState<Watched[]>([]);
  const [forcesActive, setForcesActive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [symbol, setSymbol] = useState(ST_COMPLETION[0] ?? "PROGRAM");
  const [renameTo, setRenameTo] = useState("");
  const [forceText, setForceText] = useState<Record<string, string>>({});
  const [hostOs, setHostOs] = useState<string | null>(null);
  const ticket = useRef(0);

  async function refreshList() {
    const items = await client.logicPrograms();
    setPrograms(items);
    return items;
  }

  useEffect(() => {
    void client.platform().then((body) => setHostOs(body.os)).catch(() => setHostOs(null));
  }, [client]);

  useEffect(() => {
    const mine = ++ticket.current;
    void refreshList().then((items) => {
      if (mine !== ticket.current || !items[0]) return;
      void openProgram(items[0]);
    }).catch((err: unknown) => setError(message(err)));
    void client.logicMapping().then((file) => {
      const next = rowsFromMapping(file);
      setRows(next);
      setJsonText(JSON.stringify(mappingDocument(next), null, 2));
    }).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (status !== "running") return undefined;
    const timer = window.setInterval(() => {
      void client.logicWatch().then((body) => {
        setWatched(body.variables);
        setForcesActive(body.forcesActive);
        setStatus(body.state);
      }).catch(() => undefined);
    }, 250);
    return () => window.clearInterval(timer);
  }, [client, status]);

  async function openProgram(next: string) {
    const mine = ++ticket.current;
    setName(next);
    const program = await client.logicProgram(next);
    if (mine !== ticket.current) return;
    replaceSource(program.source);
    setDiagnostics([]);
  }

  async function createProgram() {
    setError(null);
    const mine = ++ticket.current;
    const created = await client.logicCreate(draftName);
    if (mine !== ticket.current) return;
    setDraftName("");
    await refreshList();
    setName(created.name);
    replaceSource(created.source);
  }

  async function renameProgram() {
    if (!name || !renameTo) return;
    setError(null);
    const mine = ++ticket.current;
    await client.logicRename(name, renameTo);
    if (mine !== ticket.current) return;
    setRenameTo("");
    await refreshList();
    await openProgram(renameTo);
  }

  function replaceSource(next: string) {
    sourceRef.current = next;
    setSource(next);
  }

  async function saveSource() {
    if (!name) return;
    await client.logicSave(name, sourceRef.current);
  }

  async function check() {
    setError(null);
    await saveSource();
    const found = await client.logicCheck(name);
    setDiagnostics(found);
  }

  async function deploy() {
    setError(null);
    await saveSource();
    await saveMapping();
    const result = await client.logicDeploy(name);
    setDiagnostics(result.diagnostics ?? []);
    if (result.state) setStatus(result.state);
    if (result.ok === false) setError("Compile failed");
    else await refreshStatus();
  }

  async function refreshStatus() {
    const body = await client.logicStatus();
    setStatus(body.state);
    setStats(`period ${body.periodMs} ms, jitter p99 ${body.jitterP99Ns} ns, exec ${body.execP99Ns} ns, overruns ${body.overruns}`);
    const watch = await client.logicWatch();
    setWatched(watch.variables);
    setForcesActive(watch.forcesActive);
  }

  async function saveMapping() {
    const file = jsonMode ? JSON.parse(jsonText) as ReturnType<typeof mappingDocument> : mappingDocument(rows);
    const saved = await client.logicSaveMapping(file);
    const next = rowsFromMapping(saved);
    setRows(next);
    setJsonText(JSON.stringify(mappingDocument(next), null, 2));
  }

  function insertSymbol() {
    const current = sourceRef.current;
    replaceSource(`${current}${current.endsWith("\n") || current.length === 0 ? "" : " "}${symbol}`);
  }

  return (
    <div className="oip-logic">
      <PlcWindowsBanner os={hostOs} />
      <section className="oip-logic-tree" aria-label="PLC programs">
        <h2>Programs</h2>
        <ul>
          {programs.map((item) => (
            <li key={item}>
              <button type="button" aria-current={item === name ? "true" : undefined} onClick={() => void openProgram(item)}>{item}</button>
              <button type="button" aria-label={`Delete ${item}`} onClick={() => void client.logicDelete(item).then(refreshList)}>Delete</button>
            </li>
          ))}
        </ul>
        <form onSubmit={(event) => { event.preventDefault(); void createProgram().catch((err: unknown) => setError(message(err))); }}>
          <input aria-label="Program name" value={draftName} onChange={(event) => setDraftName(event.target.value)} />
          <button type="submit">New program</button>
        </form>
        <form onSubmit={(event) => { event.preventDefault(); void renameProgram().catch((err: unknown) => setError(message(err))); }}>
          <input aria-label="Rename to" value={renameTo} onChange={(event) => setRenameTo(event.target.value)} />
          <button type="submit">Rename</button>
        </form>
      </section>
      <section className="oip-logic-editor" aria-label="Structured Text">
        <h2>{name || "No program"}</h2>
        <div className="oip-logic-actions">
          <label>
            Insert
            <select aria-label="Completion" value={symbol} onChange={(event) => setSymbol(event.target.value)}>
              {ST_COMPLETION.map((item) => <option key={item}>{item}</option>)}
            </select>
          </label>
          <button type="button" onClick={insertSymbol}>Insert symbol</button>
          <button type="button" onClick={() => void check().catch((err: unknown) => setError(message(err)))}>Check program</button>
          <button type="button" onClick={() => void deploy().catch((err: unknown) => setError(message(err)))}>Compile and deploy</button>
        </div>
        <Suspense fallback={<p>Loading the editor.</p>}>
          <StEditor source={source} diagnostics={diagnostics} onChange={replaceSource} />
        </Suspense>
        <ul aria-label="Problems">
          {diagnostics.map((item, index) => (
            <li key={`${item.line}-${index}`}>Line {item.line}, column {item.column}: {item.message}</li>
          ))}
        </ul>
        {error ? <p role="alert">{error}</p> : null}
      </section>
      <section className="oip-logic-side" aria-label="Tag mapping and runtime">
        <h2>Tag mapping</h2>
        <button type="button" onClick={() => setJsonMode((on) => !on)}>{jsonMode ? "Edit as form" : "Edit as JSON"}</button>
        {jsonMode ? (
          <textarea aria-label="Mapping JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} />
        ) : (
          <ul>
            {rows.map((row, index) => (
              <li key={`${row.name}-${index}`} className="oip-logic-map">
                <input aria-label={`Variable ${index}`} value={row.name} onChange={(event) => setRows(edit(rows, index, { name: event.target.value }))} />
                <input aria-label={`Address ${index}`} value={row.address} onChange={(event) => setRows(edit(rows, index, { address: event.target.value }))} />
                <input aria-label={`Tag ${index}`} value={row.tag} onChange={(event) => setRows(edit(rows, index, { tag: event.target.value }))} />
                <select aria-label={`Direction ${index}`} value={row.direction} onChange={(event) => setRows(edit(rows, index, { direction: event.target.value as "in" | "out" }))}>
                  <option value="in">in</option>
                  <option value="out">out</option>
                </select>
                <input aria-label={`Safe ${index}`} value={row.safe} onChange={(event) => setRows(edit(rows, index, { safe: event.target.value }))} />
              </li>
            ))}
          </ul>
        )}
        <button type="button" onClick={() => setRows([...rows, { name: "", address: "%QX0.0", tag: "", type: "BOOL", direction: "out", safe: "false" }])}>Add mapping</button>
        <button type="button" onClick={() => void saveMapping().catch((err: unknown) => setError(message(err)))}>Save mapping</button>
        <h2>Runtime</h2>
        <p aria-label="Logic status">{status}</p>
        <p>{stats}</p>
        <div className="oip-logic-actions">
          <button type="button" onClick={() => void client.logicStart().then(refreshStatus).catch((err: unknown) => setError(message(err)))}>Start</button>
          <button type="button" onClick={() => void client.logicStop().then(refreshStatus).catch((err: unknown) => setError(message(err)))}>Stop</button>
        </div>
        {forcesActive ? <p className="oip-logic-forces" role="status">Forces are active</p> : null}
        <ul aria-label="Watched variables">
          {watched.map((item) => (
            <li key={item.name} data-forced={item.forced ? "yes" : "no"}>
              <span>{item.name}: {shown(item.value)}{item.forced ? " forced" : ""}</span>
              <input aria-label={`Force value ${item.name}`} value={forceText[item.name] ?? ""} onChange={(event) => setForceText({ ...forceText, [item.name]: event.target.value })} />
              <button type="button" onClick={() => void client.logicForce(item.name, forceInput(forceText[item.name]), item.output).then(refreshStatus)}>Force</button>
              <button type="button" onClick={() => void client.logicUnforce(item.name).then(refreshStatus)}>Unforce</button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function edit(rows: MapRow[], index: number, patch: Partial<MapRow>): MapRow[] {
  return rows.map((row, at) => (at === index ? { ...row, ...patch } : row));
}

function shown(value: unknown): string {
  if (typeof value === "number" && Number.isFinite(value)) {
    const text = value.toPrecision(6);
    return text.replace(/\.?0+e/, "e").replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
  }
  return String(value);
}

function forceInput(text: string | undefined): unknown {
  const trimmed = (text ?? "").trim();
  if (trimmed === "" || trimmed === "true") return true;
  if (trimmed === "false") return false;
  const number = Number(trimmed);
  return Number.isFinite(number) ? number : true;
}

function message(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
