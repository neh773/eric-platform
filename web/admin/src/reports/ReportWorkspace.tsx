import { useEffect, useState } from "react";
import type { AdminPageProps } from "../pages";
import type { DbConnection, NamedQueryInfo, ReportCatalog } from "../api";
import { DataTab } from "./DataTab";
import { DesignTab } from "./DesignTab";
import { OverviewTab } from "./OverviewTab";
import { PreviewTab } from "./PreviewTab";
import { ScheduleTab } from "./ScheduleTab";
import { TextField, errorText } from "./controls";
import { newSpec, stableJson, type Obj, type ReportSpec } from "./reportModel";

/**
 * Report workspace: one report open at a time with its overview, data, design, preview and schedule tabs. All five edit
 * the same draft spec; Save stores it as the `reporting/Report` resource, and Preview runs the draft on the gateway
 * before it is saved. Reading needs gateway.read, saving gateway.write, previewing gateway.write and tags.read.
 * doc source: FEATURES REP-058, REP-028.
 */

const TABS = ["Overview", "Data", "Design", "Preview", "Schedule"] as const;
type Tab = (typeof TABS)[number];

interface ReportRecord {
  name: string;
  version: number;
  spec: ReportSpec;
}

const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

export function ReportWorkspace({ client }: AdminPageProps) {
  const [reports, setReports] = useState<ReportRecord[]>([]);
  const [name, setName] = useState("");
  const [spec, setSpecState] = useState<ReportSpec>({});
  const [baseline, setBaseline] = useState("");
  const [savedFlag, setSavedFlag] = useState(false);
  const [tab, setTab] = useState<Tab>("Design");
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<ReportCatalog | null>(null);
  const [queries, setQueries] = useState<NamedQueryInfo[]>([]);
  const [connections, setConnections] = useState<DbConnection[]>([]);
  const [lastResult, setLastResult] = useState<Obj | null>(null);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [newQuery, setNewQuery] = useState("");

  function open(record: ReportRecord | undefined) {
    setName(record?.name ?? "");
    setSpecState(record?.spec ?? {});
    setBaseline(stableJson(record?.spec ?? {}));
    setSavedFlag(false);
    setLastResult(null);
  }

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    void client.listResources("reporting", "gateway", "Report").then(
      (items) => {
        if (cancelled) return;
        const records = items.map((item) => ({ name: item.metadata.name, version: item.metadata.version, spec: item.spec }));
        setReports(records);
        open(records[0]);
      },
      (reason: unknown) => {
        if (!cancelled) setError(errorText(reason, "Reports could not be loaded"));
      },
    );
    client.reportCatalog().then((c) => !cancelled && setCatalog(c), () => undefined);
    client.namedQueries().then((q) => !cancelled && setQueries(q), () => undefined);
    client.dbConnections().then((c) => !cancelled && setConnections(c), () => undefined);
    return () => {
      cancelled = true;
    };
  }, [client]);

  const dirty = stableJson(spec) !== baseline;
  const record = reports.find((item) => item.name === name);

  function setSpec(change: (spec: ReportSpec) => ReportSpec) {
    setSpecState((current) => change(current));
    setSavedFlag(false);
  }

  async function save() {
    if (!client || !record) return;
    setError(null);
    try {
      const stored = await client.putResource("reporting", "gateway", "Report", name, {
        apiVersion: "oip/v1",
        kind: "Report",
        metadata: { name, module: "reporting", version: record.version },
        spec,
      });
      setReports((current) => current.map((item) => (item.name === name ? { ...item, version: stored.metadata.version, spec: stored.spec } : item)));
      setSpecState(stored.spec);
      setBaseline(stableJson(stored.spec));
      setSavedFlag(true);
    } catch (reason) {
      setError(errorText(reason, "The report could not be saved"));
    }
  }

  async function create() {
    if (!client) return;
    setError(null);
    try {
      const stored = await client.createResource("reporting", "gateway", "Report", newName, newSpec(newName, newQuery));
      const created = { name: newName, version: stored.metadata.version, spec: stored.spec };
      setReports((current) => [...current, created].sort((a, b) => a.name.localeCompare(b.name)));
      open(created);
      setCreating(false);
      setNewName("");
    } catch (reason) {
      setError(errorText(reason, "The report could not be created"));
    }
  }

  const status = dirty ? "Unsaved changes" : savedFlag ? "Saved" : "";
  return (
    <article data-testid="report-canvas">
      <h1>Reports</h1>
      {!client ? <p role="alert">Reports are unavailable.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      <div className="inline-form">
        <label>
          Report
          <select aria-label="Report" value={name} onChange={(event) => open(reports.find((item) => item.name === event.target.value))}>
            {reports.map((report) => (
              <option key={report.name} value={report.name}>
                {report.name}
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={() => void save()} disabled={!name}>Save</button>
        <span role="status">{status}</span>
        <button type="button" onClick={() => setCreating((v) => !v)}>New report</button>
      </div>
      {creating ? (
        <form className="stack-form" aria-label="New report" onSubmit={(event) => { event.preventDefault(); void create(); }}>
          <TextField label="New report name" value={newName} onChange={setNewName} placeholder="line-1-daily" />
          <label>
            First data source
            <select aria-label="First data source" value={newQuery} onChange={(event) => setNewQuery(event.target.value)}>
              <option value="">Choose a named query</option>
              {queries.map((q) => <option key={q.name} value={q.name}>{q.name}</option>)}
            </select>
          </label>
          {queries.length === 0 ? <p className="hint">There is no named query yet. Create one on the Databases page first.</p> : null}
          <button type="submit" disabled={!NAME.test(newName) || !newQuery || reports.some((r) => r.name === newName)}>Create report</button>
        </form>
      ) : null}
      {reports.length === 0 && client && !error ? <p className="hint">No reports yet. Use New report to start one.</p> : null}
      {record && client ? (
        <>
          <div role="tablist" aria-label="Workspace tabs" className="report-tabs">
            {TABS.map((item) => (
              <button key={item} type="button" role="tab" aria-selected={tab === item} onClick={() => setTab(item)}>
                {item}
              </button>
            ))}
          </div>
          {tab === "Overview" ? <OverviewTab client={client} name={name} version={record.version} spec={spec} setSpec={setSpec} catalog={catalog} isNew={false} /> : null}
          {tab === "Data" ? <DataTab spec={spec} setSpec={setSpec} queries={queries} connections={connections} /> : null}
          {tab === "Design" ? <DesignTab spec={spec} setSpec={setSpec} catalog={catalog} lastResult={lastResult as never} /> : null}
          {tab === "Preview" ? <PreviewTab client={client} spec={spec} onResult={setLastResult} /> : null}
          {tab === "Schedule" ? <ScheduleTab client={client} name={name} version={record.version} isNew={false} dirty={dirty} spec={spec} setSpec={setSpec} /> : null}
        </>
      ) : null}
    </article>
  );
}
