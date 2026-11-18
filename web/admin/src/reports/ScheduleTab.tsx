import { useEffect, useState } from "react";
import type { ApiClient, ReportRunInfo, ReportStatusInfo } from "../api";
import { CheckField, NumberField, Panel, SelectField, TextField, errorText } from "./controls";
import { asList, defaultToText, isObj, parametersOf, text, textToDefault, type Json, type Obj, type ReportSpec } from "./reportModel";

/**
 * Schedule tab: when the report runs (cron and time zone), with which parameter values, and what is done with each
 * run (save the PDF, email it, print it, upload by FTP). Shows the next run and the latest runs of the saved report.
 * doc source: FEATURES REP-058, REP-028, REP-046, REP-048.
 */
const PRESETS = [
  { value: "0 6 * * *", label: "Every day at 06:00" },
  { value: "0 * * * *", label: "Every hour" },
  { value: "0 6 * * 1", label: "Every Monday at 06:00" },
  { value: "0 0 1 * *", label: "First day of the month" },
];

const ACTION_TYPES = [
  { value: "save", label: "Save the PDF on the gateway" },
  { value: "email", label: "Email the PDF" },
  { value: "print", label: "Print" },
  { value: "ftp", label: "Upload by FTP" },
];

export function ScheduleTab({ client, name, version, isNew, dirty, spec, setSpec }: {
  client: ApiClient;
  name: string;
  /** Version of the saved report; the status is read again after a save. */
  version: number;
  isNew: boolean;
  dirty: boolean;
  spec: ReportSpec;
  setSpec: (change: (spec: ReportSpec) => ReportSpec) => void;
}) {
  const schedule: Obj | null = isObj(spec.schedule) ? spec.schedule : null;
  const parameters = parametersOf(spec);
  const [status, setStatus] = useState<ReportStatusInfo | null>(null);
  const [runs, setRuns] = useState<ReportRunInfo[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

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
  }, [client, name, version, isNew, reload]);

  const setSchedule = (change: (s: Obj) => Obj) => setSpec((s) => ({ ...s, schedule: change(isObj(s.schedule) ? s.schedule : { cron: "0 6 * * *" }) }));
  const setKey = (key: string, value: Json | undefined) =>
    setSchedule((s) => {
      const next = { ...s };
      if (value === undefined) delete next[key];
      else next[key] = value;
      return next;
    });
  const actions = asList(schedule?.actions);
  const setAction = (index: number, key: string, value: Json | undefined) =>
    setKey("actions", actions.map((a, i) => {
      if (i !== index) return a;
      const next = { ...a };
      if (value === undefined || value === "") delete next[key];
      else next[key] = value;
      return next;
    }));
  const values = isObj(schedule?.parameters) ? (schedule!.parameters as Obj) : {};

  async function runNow() {
    setError(null);
    setMessage(null);
    try {
      await client.runReport(name, {});
      setMessage("Run finished.");
      setReload((n) => n + 1);
    } catch (reason) {
      setError(errorText(reason, "The run failed"));
      setReload((n) => n + 1);
    }
  }

  return (
    <div className="report-tab" role="tabpanel" aria-label="Schedule">
      <Panel title="Schedule">
        <CheckField
          label="Run on a schedule"
          checked={schedule !== null && schedule.enabled !== false}
          onChange={(on) => (on ? setSchedule((s) => ({ ...s, enabled: true })) : setSpec((s) => ({ ...s, schedule: undefined })))}
        />
        {schedule ? (
          <>
            <SelectField label="Schedule preset" value={PRESETS.some((p) => p.value === schedule.cron) ? text(schedule.cron) : ""} options={PRESETS} blank="Custom" onChange={(v) => v && setKey("cron", v)} />
            <TextField label="Cron expression" value={text(schedule.cron)} onChange={(v) => setKey("cron", v)} />
            <TextField label="Schedule time zone" value={text(schedule.timezone, "UTC")} onChange={(v) => setKey("timezone", v)} />
          </>
        ) : (
          <p className="hint">The report runs only when someone runs it.</p>
        )}
      </Panel>
      {schedule && parameters.length > 0 ? (
        <Panel title="Parameter values for scheduled runs">
          <div className="inline-form">
            {parameters.map((p) => (
              <TextField
                key={p.name}
                label={`Scheduled ${p.name}`}
                value={defaultToText(p.type, values[p.name])}
                placeholder={p.expression ? `= ${p.expression}` : defaultToText(p.type, p.default)}
                onChange={(v) => {
                  const next = { ...values };
                  const parsed = textToDefault(p.type, v);
                  if (parsed === undefined) delete next[p.name];
                  else next[p.name] = parsed as Json;
                  setKey("parameters", Object.keys(next).length ? next : undefined);
                }}
              />
            ))}
          </div>
        </Panel>
      ) : null}
      {schedule ? (
        <Panel title="Actions">
          {actions.length === 0 ? <p className="hint">With no action a scheduled run is recorded but its PDF is kept only on the gateway.</p> : null}
          {actions.map((action, index) => {
            const n = index + 1;
            const type = text(action.type);
            return (
              <div className="report-row" role="group" aria-label={`Action ${n}`} key={index}>
                <SelectField label={`Action ${n} type`} value={type} options={ACTION_TYPES} onChange={(v) => setKey("actions", actions.map((a, i) => (i === index ? { type: v } : a)))} />
                {type === "email" ? (
                  <>
                    <TextField label={`Action ${n} to`} value={text(action.to)} onChange={(v) => setAction(index, "to", v)} />
                    <TextField label={`Action ${n} from`} value={text(action.from)} onChange={(v) => setAction(index, "from", v)} />
                    <TextField label={`Action ${n} subject`} value={text(action.subject)} onChange={(v) => setAction(index, "subject", v)} />
                    <TextField label={`Action ${n} mail host`} value={text(action.host)} onChange={(v) => setAction(index, "host", v)} />
                    <NumberField label={`Action ${n} mail port`} value={typeof action.port === "number" ? action.port : undefined} min={1} max={65535} onChange={(v) => setAction(index, "port", v)} />
                  </>
                ) : null}
                {type === "print" ? (
                  <>
                    <TextField label={`Action ${n} printer`} value={text(action.printer)} onChange={(v) => setAction(index, "printer", v)} />
                    <NumberField label={`Action ${n} copies`} value={typeof action.copies === "number" ? action.copies : undefined} min={1} max={20} onChange={(v) => setAction(index, "copies", v)} />
                  </>
                ) : null}
                {type === "ftp" ? (
                  <>
                    <TextField label={`Action ${n} FTP host`} value={text(action.host)} onChange={(v) => setAction(index, "host", v)} />
                    <TextField label={`Action ${n} FTP user`} value={text(action.username)} onChange={(v) => setAction(index, "username", v)} />
                    <TextField label={`Action ${n} FTP path`} value={text(action.path)} onChange={(v) => setAction(index, "path", v)} />
                  </>
                ) : null}
                <button type="button" onClick={() => setKey("actions", actions.filter((_, i) => i !== index))}>Remove action {n}</button>
              </div>
            );
          })}
          <button type="button" onClick={() => setKey("actions", [...actions, { type: "save" }])}>Add action</button>
          <p className="hint">Passwords are secret:// references to the gateway's secret store; set them in the report's configuration resource.</p>
        </Panel>
      ) : null}
      <Panel title="Status">
        {isNew ? (
          <p className="hint">Save the report to run it on its schedule.</p>
        ) : (
          <>
            <p aria-live="polite">
              {status?.scheduled && status.nextFireAt ? `Next run ${new Date(status.nextFireAt).toISOString()}.` : "Not scheduled."}{" "}
              {status?.lastRunAt ? `Last run ${new Date(status.lastRunAt).toISOString()}: ${status.lastStatus}${status.lastError ? `, ${status.lastError}` : ""}.` : "No runs yet."}
            </p>
            <div className="inline-form">
              <button type="button" onClick={() => void runNow()} disabled={dirty}>Run now</button>
              {dirty ? <span className="hint">Save the changes first; Run now uses the saved report.</span> : null}
            </div>
            {message ? <p aria-live="polite">{message}</p> : null}
            {error ? <p role="alert">{error}</p> : null}
            {runs.length > 0 ? (
              <table className="data-table" aria-label="Latest runs">
                <thead>
                  <tr><th>Started</th><th>Trigger</th><th>Status</th><th>Rows</th></tr>
                </thead>
                <tbody>
                  {runs.map((run) => (
                    <tr key={run.runId}>
                      <td>{new Date(run.startedAt).toISOString()}</td>
                      <td>{run.trigger}</td>
                      <td>{run.status}{run.error ? `: ${run.error}` : ""}</td>
                      <td>{run.rows}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : null}
          </>
        )}
      </Panel>
    </div>
  );
}
