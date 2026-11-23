import { lazy, Suspense, useEffect, useRef, useState } from "react";
import type { GatewayClient } from "./gatewayClient";
import {
  ST_COMPLETION,
  bindInstance,
  groupWatched,
  mappingDocument,
  onlineMarks,
  rowsFromMapping,
  typeForAddress,
  setMemberDirection,
  udtBindings,
  type LogicUdtView,
  type MapRow,
  type UdtBinding,
} from "./logicModel";
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
  udt?: string | null;
  tag?: string;
  type?: string | null;
}

const PINNED_KEY = "eric.logic.watchTable";

function loadPinned(): string[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(PINNED_KEY) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

export function LogicWorkspace({ client }: { client: GatewayClient }) {
  const [programs, setPrograms] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [draftName, setDraftName] = useState("");
  const [source, setSource] = useState("");
  const sourceRef = useRef("");
  const [rows, setRowsState] = useState<MapRow[]>([]);
  // A mapping still loading must not replace rows the user already changed.
  const [mappingLoaded, setMappingLoaded] = useState(false);
  const rowsEdited = useRef(false);
  const setRows = (next: MapRow[]) => { rowsEdited.current = true; setRowsState(next); };
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("");
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const [status, setStatus] = useState("stopped");
  const [stats, setStats] = useState("");
  const [watched, setWatched] = useState<Watched[]>([]);
  const [online, setOnline] = useState(false);
  const [menu, setMenu] = useState<{ name: string; x: number; y: number } | null>(null);
  const [menuValue, setMenuValue] = useState("");
  const [pinned, setPinned] = useState<string[]>(loadPinned);
  const [pinPick, setPinPick] = useState("");
  const [forcesActive, setForcesActive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [symbol, setSymbol] = useState(ST_COMPLETION[0] ?? "PROGRAM");
  const [renameTo, setRenameTo] = useState("");
  const [forceText, setForceText] = useState<Record<string, string>>({});
  const [hostOs, setHostOs] = useState<string | null>(null);
  const [bindings, setBindings] = useState<UdtBinding[]>([]);
  const bindingsRef = useRef<UdtBinding[]>([]);
  const [udtView, setUdtView] = useState<LogicUdtView | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
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
      if (rowsEdited.current) return;
      const next = rowsFromMapping(file);
      setRowsState(next);
      replaceBindings(udtBindings(file));
      setJsonText(JSON.stringify(mappingDocument(next, udtBindings(file)), null, 2));
    }).catch(() => undefined).finally(() => setMappingLoaded(true));
    void refreshStatus().catch(() => undefined);
  }, []);

  useEffect(() => {
    if (status !== "running" && !online) return undefined;
    let tick = 0;
    const timer = window.setInterval(() => {
      if (++tick % 4 === 0) void client.logicStatus().then(showStats).catch(() => undefined);
      void client.logicWatch().then((body) => {
        setWatched(body.variables);
        setForcesActive(body.forcesActive);
        setStatus(body.state);
      }).catch(() => undefined);
    }, 250);
    return () => window.clearInterval(timer);
  }, [client, status, online]);

  useEffect(() => {
    window.localStorage.setItem(PINNED_KEY, JSON.stringify(pinned));
  }, [pinned]);

  /** Force, release and write go through here: the result refreshes the watch, a refusal (role, engine) shows. */
  function act(work: Promise<unknown>, done?: string) {
    setMenu(null);
    void work.then(async () => {
      if (done) setNotice(done);
      await refreshStatus();
    }).catch((err: unknown) => setError(message(err)));
  }

  function pointOf(name: string): Watched | undefined {
    return watched.find((item) => item.name.toLowerCase() === name.toLowerCase());
  }

  const forcedRows = watched.filter((item) => item.forced);

  async function openProgram(next: string) {
    const mine = ++ticket.current;
    setName(next);
    const program = await client.logicProgram(next);
    if (mine !== ticket.current) return;
    replaceSource(program.source);
    setDiagnostics([]);
    await refreshUdts(next);
  }

  function replaceBindings(next: UdtBinding[]) {
    bindingsRef.current = next;
    setBindings(next);
  }

  /** Structure variables of the program and the UDT instances they can be bound to. */
  async function refreshUdts(program = name) {
    if (!program) return;
    try {
      setUdtView(await client.logicUdts(program));
    } catch {
      setUdtView(null);
    }
  }

  /** Bindings change the mapping file; save it and re-read the member plan. */
  async function changeBindings(next: UdtBinding[]) {
    replaceBindings(next);
    await saveMapping();
    await refreshUdts();
  }

  async function createProgram() {
    setError(null);
    const wanted = draftName.trim();
    if (programs.includes(wanted)) {
      // Asking for a program that already exists opens it.
      setDraftName("");
      await openProgram(wanted);
      setNotice(`${wanted} already exists. Opened it.`);
      return;
    }
    const mine = ++ticket.current;
    const created = await client.logicCreate(wanted);
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
    setNotice(null);
    await saveSource();
    const found = await client.logicCheck(name);
    setDiagnostics(found);
    if (found.length === 0) setNotice("Check passed. No problems.");
    await refreshUdts();
  }

  async function deploy() {
    setError(null);
    await saveSource();
    await saveMapping();
    setNotice(null);
    const result = await client.logicDeploy(name);
    setDiagnostics(result.diagnostics ?? []);
    if (result.state) setStatus(result.state);
    if (result.ok === false) setError(result.message ? `Deploy failed: ${result.message}` : "Compile failed");
    else {
      setNotice(`Deployed ${name}.`);
      await refreshStatus();
    }
    await refreshUdts();
  }

  function showStats(body: { periodMs: number; jitterP99Ns: number; execP99Ns: number; overruns: number }) {
    setStats(`period ${body.periodMs} ms, jitter p99 ${body.jitterP99Ns} ns, exec ${body.execP99Ns} ns, overruns ${body.overruns}`);
  }

  async function refreshStatus() {
    const body = await client.logicStatus();
    setStatus(body.state);
    showStats(body);
    const watch = await client.logicWatch();
    setWatched(watch.variables);
    setForcesActive(watch.forcesActive);
  }

  async function saveMapping() {
    const file = jsonMode ? JSON.parse(jsonText) as ReturnType<typeof mappingDocument> : mappingDocument(rows, bindingsRef.current);
    const saved = await client.logicSaveMapping(file);
    const next = rowsFromMapping(saved);
    setRowsState(next);
    replaceBindings(udtBindings(saved));
    setJsonText(JSON.stringify(mappingDocument(next, udtBindings(saved)), null, 2));
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
              {(udtView?.types ?? []).map((item) => (
                <option key={`udt-${item.st}`} value={item.st}>{item.st} (UDT {item.udt})</option>
              ))}
            </select>
          </label>
          <button type="button" onClick={insertSymbol}>Insert symbol</button>
          <button type="button" onClick={() => void check().catch((err: unknown) => setError(message(err)))}>Check program</button>
          <button type="button" onClick={() => void deploy().catch((err: unknown) => setError(message(err)))}>Compile and deploy</button>
          <button type="button" aria-pressed={online} className={online ? "oip-logic-online-on" : undefined} onClick={() => { setOnline(!online); setMenu(null); }}>
            {online ? "Go offline" : "Go online"}
          </button>
        </div>
        {online ? (
          <p className="oip-logic-hint" role="note">
            Online: live values beside each variable{status === "running" ? "" : ` (the program is ${status}, so outputs show their safe values)`}. Right-click a variable to force or write it.
          </p>
        ) : null}
        {forcedRows.length > 0 ? (
          <div className="oip-logic-forced-banner" role="alert">
            <strong>{forcedRows.length === 1 ? "1 value forced" : `${forcedRows.length} values forced`}</strong>
            <span>{forcedRows.map((item) => item.name).join(", ")}</span>
            <button type="button" onClick={() => act(client.logicReleaseAll(), "Released all forces.")}>Release all</button>
          </div>
        ) : null}
        {menu ? (
          <div
            className="oip-logic-menu"
            role="menu"
            aria-label={`Online actions for ${menu.name}`}
            style={{ position: "fixed", left: menu.x, top: menu.y, zIndex: 50 }}
            onKeyDown={(event) => { if (event.key === "Escape") setMenu(null); }}
          >
            {(() => {
              const row = pointOf(menu.name);
              const bool = typeof row?.value === "boolean" || (row?.type ?? "BOOL").toUpperCase() === "BOOL";
              const output = row?.output ?? true;
              return (
                <>
                  <p className="oip-logic-menu-title">{menu.name}{row ? ` = ${shown(row.value)}` : ""}</p>
                  {bool ? (
                    <>
                      <button type="button" role="menuitem" onClick={() => act(client.logicForce(menu.name, true, output), `Forced ${menu.name} TRUE.`)}>Force TRUE</button>
                      <button type="button" role="menuitem" onClick={() => act(client.logicForce(menu.name, false, output), `Forced ${menu.name} FALSE.`)}>Force FALSE</button>
                    </>
                  ) : null}
                  <label>
                    Value
                    <input aria-label={`Value for ${menu.name}`} value={menuValue} autoFocus onChange={(event) => setMenuValue(event.target.value)} />
                  </label>
                  <button type="button" role="menuitem" disabled={menuValue.trim() === ""} onClick={() => act(client.logicForce(menu.name, forceInput(menuValue), output), `Forced ${menu.name}.`)}>Force value</button>
                  <button type="button" role="menuitem" disabled={menuValue.trim() === "" && !bool} onClick={() => act(client.logicWrite(menu.name, menuValue.trim() === "" ? !(row?.value === true) : forceInput(menuValue)), `Wrote ${menu.name} once.`)}>
                    {bool && menuValue.trim() === "" ? "Write once (toggle)" : "Write once"}
                  </button>
                  <button type="button" role="menuitem" disabled={!row?.forced} onClick={() => act(client.logicUnforce(menu.name), `Released ${menu.name}.`)}>Release force</button>
                  <button type="button" role="menuitem" disabled={pinned.includes(menu.name)} onClick={() => { setPinned([...pinned, menu.name]); setMenu(null); }}>Add to watch table</button>
                  <button type="button" role="menuitem" onClick={() => setMenu(null)}>Close</button>
                </>
              );
            })()}
          </div>
        ) : null}
        <Suspense fallback={<p>Loading the editor.</p>}>
          <StEditor
            source={source}
            diagnostics={diagnostics}
            onChange={replaceSource}
            online={online ? onlineMarks(watched) : null}
            onVariableMenu={(variable, x, y) => {
              setMenuValue("");
              setMenu({ name: pointOf(variable)?.name ?? variable, x, y });
            }}
          />
        </Suspense>
        <ul aria-label="Problems">
          {diagnostics.map((item, index) => (
            <li key={`${item.line}-${index}`}>Line {item.line}, column {item.column}: {item.message}</li>
          ))}
        </ul>
        {error ? <p role="alert">{error}</p> : null}
        {notice ? <p role="status">{notice}</p> : null}
      </section>
      <section className="oip-logic-side" aria-label="Tag mapping and runtime" data-mapping-loaded={mappingLoaded ? "yes" : "no"}>
        <h2>Tag mapping</h2>
        <button type="button" onClick={() => setJsonMode((on) => !on)}>{jsonMode ? "Edit as form" : "Edit as JSON"}</button>
        {jsonMode ? (
          <textarea aria-label="Mapping JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} />
        ) : (
          <ul>
            {rows.map((row, index) => (
              <li key={`${row.name}-${index}`} className="oip-logic-map">
                <input aria-label={`Variable ${index}`} value={row.name} onChange={(event) => setRows(edit(rows, index, { name: event.target.value }))} />
                <input aria-label={`Address ${index}`} value={row.address} onChange={(event) => {
                  const address = event.target.value;
                  const type = typeForAddress(address);
                  setRows(edit(rows, index, type ? { address, type } : { address }));
                }} />
                <input aria-label={`Tag ${index}`} value={row.tag} onChange={(event) => setRows(edit(rows, index, { tag: event.target.value }))} />
                <select aria-label={`Type ${index}`} value={row.type || "BOOL"} onChange={(event) => setRows(edit(rows, index, { type: event.target.value }))}>
                  {["BOOL", "INT", "DINT", "REAL", "LREAL"].map((type) => <option key={type} value={type}>{type}</option>)}
                </select>
                <select aria-label={`Direction ${index}`} value={row.direction} onChange={(event) => setRows(edit(rows, index, { direction: event.target.value as "in" | "out" }))}>
                  <option value="in">in</option>
                  <option value="out">out</option>
                </select>
                <input aria-label={`Safe ${index}`} value={row.safe} onChange={(event) => setRows(edit(rows, index, { safe: event.target.value }))} />
                <button type="button" aria-label={`Remove mapping ${index}`} onClick={() => setRows(rows.filter((_, other) => other !== index))}>Remove</button>
              </li>
            ))}
          </ul>
        )}
        <button type="button" onClick={() => setRows([...rows, { name: "", address: "%QX0.0", tag: "", type: "BOOL", direction: "out", safe: "false" }])}>Add mapping</button>
        <button type="button" onClick={() => void saveMapping().catch((err: unknown) => setError(message(err)))}>Save mapping</button>
        <section aria-label="UDT instances">
          <h2>UDT instances</h2>
          <p className="oip-logic-hint">
            Declare a variable of a gateway UDT type (or of a STRUCT in the program), then pick the UDT instance it stands for.
            Members the program writes go back to the tags; the rest are read each scan.
          </p>
          {(udtView?.variables ?? []).length === 0 ? (
            <p>No structure variables in this program yet. Example: <code>Station : {udtView?.types[0]?.st ?? "MyUdt"};</code></p>
          ) : null}
          {(udtView?.variables ?? []).map((variable) => {
            const binding = bindings.find((item) => item.variable.toLowerCase() === variable.variable.toLowerCase());
            const candidates = (udtView?.instances ?? []).filter((item) => !variable.udt || item.udt.toLowerCase() === variable.udt.toLowerCase());
            const choices = candidates.length > 0 ? candidates : udtView?.instances ?? [];
            return (
              <fieldset key={variable.variable} className="oip-logic-udt" aria-label={`UDT variable ${variable.variable}`}>
                <legend>{variable.variable} : {variable.type}</legend>
                <label>
                  UDT instance for {variable.variable}
                  <select
                    value={binding?.instance ?? ""}
                    onChange={(event) => void changeBindings(bindInstance(bindingsRef.current, variable.variable, event.target.value)).catch((err: unknown) => setError(message(err)))}
                  >
                    <option value="">Not bound</option>
                    {choices.map((item) => <option key={item.path} value={item.path}>{item.path}</option>)}
                  </select>
                </label>
                {variable.problems.map((problem) => <p key={problem} role="alert">{problem}</p>)}
                {binding && variable.members.length > 0 ? (
                  <table aria-label={`Members of ${variable.variable}`}>
                    <thead>
                      <tr><th>Member</th><th>Type</th><th>Direction</th><th>Tag</th></tr>
                    </thead>
                    <tbody>
                      {variable.members.map((member) => (
                        <tr key={member.path}>
                          <td>{member.path}</td>
                          <td>{member.type}</td>
                          <td>
                            {member.note ? (
                              <span>{member.note}</span>
                            ) : (
                              <select
                                aria-label={`Direction of ${member.path}`}
                                value={binding.members[member.member] ?? "auto"}
                                onChange={(event) => void changeBindings(setMemberDirection(bindingsRef.current, variable.variable, member.member, event.target.value as "auto" | "in" | "out" | "none")).catch((err: unknown) => setError(message(err)))}
                              >
                                <option value="auto">Automatic ({member.auto === "out" ? "program writes it" : "program reads it"})</option>
                                <option value="in">Read from tag</option>
                                <option value="out">Write to tag</option>
                                <option value="none">Not moved</option>
                              </select>
                            )}
                          </td>
                          <td>{member.tag}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : null}
              </fieldset>
            );
          })}
        </section>
        <h2>Runtime</h2>
        <p aria-label="Logic status">{status}</p>
        <p>{stats}</p>
        <div className="oip-logic-actions">
          <button type="button" onClick={() => void client.logicStart().then(refreshStatus).catch((err: unknown) => setError(message(err)))}>Start</button>
          <button type="button" onClick={() => void client.logicStop().then(refreshStatus).catch((err: unknown) => setError(message(err)))}>Stop</button>
        </div>
        {forcesActive && forcedRows.length === 0 ? <p className="oip-logic-forces" role="status">Forces are active</p> : null}
        <section aria-label="Watch table">
          <h2>Watch table</h2>
          <div className="oip-logic-actions">
            <select aria-label="Variable to watch" value={pinPick} onChange={(event) => setPinPick(event.target.value)}>
              <option value="">Choose a variable</option>
              {watched.filter((item) => !pinned.includes(item.name)).map((item) => <option key={item.name} value={item.name}>{item.name}</option>)}
            </select>
            <button type="button" disabled={!pinPick} onClick={() => { setPinned([...pinned, pinPick]); setPinPick(""); }}>Add to watch table</button>
          </div>
          {pinned.length === 0 ? <p className="oip-logic-hint">Add variables here, or right-click one in the code while online.</p> : (
            <table className="oip-logic-watch">
              <thead><tr><th>Variable</th><th>Value</th><th>New value</th><th>Actions</th></tr></thead>
              <tbody>
                {pinned.map((name) => {
                  const row = pointOf(name);
                  return (
                    <tr key={name} data-forced={row?.forced ? "yes" : "no"}>
                      <td>{name}</td>
                      <td aria-label={`Value of ${name}`}>{row ? shown(row.value) : "not mapped"}{row?.forced ? <b className="oip-logic-f" aria-label="forced"> F</b> : null}</td>
                      <td><input aria-label={`New value for ${name}`} value={forceText[name] ?? ""} onChange={(event) => setForceText({ ...forceText, [name]: event.target.value })} /></td>
                      <td>
                        <button type="button" disabled={!row} onClick={() => act(client.logicWrite(name, forceInput(forceText[name])), `Wrote ${name} once.`)}>Write once</button>
                        <button type="button" disabled={!row} onClick={() => act(client.logicForce(name, forceInput(forceText[name]), row?.output ?? true), `Forced ${name}.`)}>Force</button>
                        <button type="button" disabled={!row?.forced} onClick={() => act(client.logicUnforce(name), `Released ${name}.`)}>Release</button>
                        <button type="button" onClick={() => setPinned(pinned.filter((item) => item !== name))}>Remove</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>
        <h2>Program instances and I/O</h2>
        <ul aria-label="Watched variables">
          {groupWatched(watched).map((group) => {
            const items = group.rows.map((item) => (
              <li key={item.name} data-forced={item.forced ? "yes" : "no"}>
                <span>{item.name}: {shown(item.value)}{item.forced ? " forced" : ""}</span>
                <input aria-label={`Force value ${item.name}`} value={forceText[item.name] ?? ""} onChange={(event) => setForceText({ ...forceText, [item.name]: event.target.value })} />
                <button type="button" onClick={() => act(client.logicForce(item.name, forceInput(forceText[item.name]), item.output))}>Force</button>
                <button type="button" onClick={() => act(client.logicUnforce(item.name))}>Unforce</button>
              </li>
            ));
            if (!group.udt) return items;
            const instance = bindings.find((item) => item.variable === group.udt)?.instance;
            return (
              <li key={`udt-${group.udt}`} className="oip-logic-udt-watch">
                <details open>
                  <summary>{group.udt}{instance ? ` (${instance})` : ""}</summary>
                  <ul aria-label={`Members of ${group.udt} online`}>{items}</ul>
                </details>
              </li>
            );
          })}
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
  const trimmed = (text ?? "").trim().toLowerCase();
  if (trimmed === "" || trimmed === "true") return true;
  if (trimmed === "false") return false;
  const number = Number(trimmed);
  return Number.isFinite(number) ? number : true;
}

function message(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
