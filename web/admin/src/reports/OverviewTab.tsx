import { useEffect, useState } from "react";
import type { ApiClient, ReportCatalog, ReportRunInfo, ReportStatusInfo } from "../api";
import { CheckField, NumberField, Panel, SelectField, TextField } from "./controls";
import { getPath, layoutOf, marginsOf, setPath, text, withLayout, withMargins, type PageMarginsDraft, type ReportSpec } from "./reportModel";

/**
 * Overview tab: the report's name, description and enabled flag, the report object's page setup (size, orientation,
 * margins) and header, and how the report ran lately. doc source: FEATURES REP-058, REP-010.
 */
export function OverviewTab({ client, name, version, spec, setSpec, catalog, isNew }: {
  client: ApiClient;
  name: string;
  version: number;
  spec: ReportSpec;
  setSpec: (change: (spec: ReportSpec) => ReportSpec) => void;
  catalog: ReportCatalog | null;
  isNew: boolean;
}) {
  const layout = layoutOf(spec);
  const margins = marginsOf(layout);
  const sizes = catalog?.report.find((p) => p.name === "page.size")?.options ?? ["A4", "A5", "Letter", "Legal"];
  const [status, setStatus] = useState<ReportStatusInfo | null>(null);
  const [runs, setRuns] = useState<ReportRunInfo[]>([]);

  useEffect(() => {
    if (isNew) return;
    let cancelled = false;
    void Promise.all([client.reportStatus(name), client.reportRuns(name, 5)]).then(
      ([s, r]) => {
        if (cancelled) return;
        setStatus(s);
        setRuns(r);
      },
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, [client, name, version, isNew]);

  const setLayout = (path: string, value: string | number | boolean | undefined) => setSpec((s) => withLayout(s, (l) => setPath(l, path, value === "" ? undefined : value)));
  const setMargin = (side: keyof PageMarginsDraft, value: number | undefined) =>
    setSpec((s) => withLayout(s, (l) => withMargins(l, { ...marginsOf(l), [side]: value ?? 40 })));

  return (
    <div className="report-tab" role="tabpanel" aria-label="Overview">
      <Panel title="Report">
        <TextField label="Title" value={text(spec.title)} onChange={(v) => setSpec((s) => ({ ...s, title: v }))} />
        <TextField label="Description" value={text(spec.description)} onChange={(v) => setSpec((s) => ({ ...s, description: v }))} />
        <CheckField label="Enabled" checked={spec.enabled !== false} onChange={(v) => setSpec((s) => ({ ...s, enabled: v }))} />
      </Panel>
      <Panel title="Page">
        <SelectField label="Page size" value={text(getPath(layout, "page.size"), "A4")} options={sizes} onChange={(v) => setLayout("page.size", v)} />
        <SelectField
          label="Orientation"
          value={text(getPath(layout, "page.orientation"), "portrait")}
          options={["portrait", "landscape"]}
          onChange={(v) => setLayout("page.orientation", v)}
        />
        <div className="inline-form">
          <NumberField label="Margin top" value={margins.top} min={24} max={200} onChange={(v) => setMargin("top", v)} />
          <NumberField label="Margin right" value={margins.right} min={24} max={200} onChange={(v) => setMargin("right", v)} />
          <NumberField label="Margin bottom" value={margins.bottom} min={24} max={200} onChange={(v) => setMargin("bottom", v)} />
          <NumberField label="Margin left" value={margins.left} min={24} max={200} onChange={(v) => setMargin("left", v)} />
        </div>
        <p className="hint">Margins are in points (24 to 200). The page footer sits inside the bottom margin.</p>
        <TextField label="Time zone" value={text(layout.timezone, "UTC")} onChange={(v) => setLayout("timezone", v)} />
      </Panel>
      <Panel title="Header">
        <TextField label="Header title" value={text(getPath(layout, "header.title"))} onChange={(v) => setLayout("header.title", v)} />
        <TextField label="Header subtitle" value={text(getPath(layout, "header.subtitle"))} onChange={(v) => setLayout("header.subtitle", v)} />
        <CheckField label="Show parameters" checked={getPath(layout, "header.showParameters") !== false} onChange={(v) => setLayout("header.showParameters", v)} />
        <CheckField label="Show generation time" checked={getPath(layout, "header.showGeneratedAt") !== false} onChange={(v) => setLayout("header.showGeneratedAt", v)} />
      </Panel>
      {isNew ? (
        <p className="hint">Save the report to see its run history.</p>
      ) : (
        <Panel title="Runs">
          <p aria-live="polite">
            {status?.lastRunAt ? `Last run ${new Date(status.lastRunAt).toISOString()}, ${status.lastStatus}.` : "No runs yet."}
            {status?.scheduled && status.nextFireAt ? ` Next scheduled run ${new Date(status.nextFireAt).toISOString()}.` : ""}
          </p>
          {runs.length > 0 ? (
            <table className="data-table" aria-label="Recent runs">
              <thead>
                <tr>
                  <th>Started</th>
                  <th>Trigger</th>
                  <th>User</th>
                  <th>Status</th>
                  <th>Rows</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.runId}>
                    <td>{new Date(run.startedAt).toISOString()}</td>
                    <td>{run.trigger}</td>
                    <td>{run.user}</td>
                    <td>{run.status}{run.error ? `: ${run.error}` : ""}</td>
                    <td>{run.rows}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </Panel>
      )}
    </div>
  );
}
