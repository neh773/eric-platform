import { useEffect, useState } from "react";
import type { ViewDocument } from "@oip/hmi-runtime";
import { bindingStats, type DiagnosticsMeter } from "./diagnostics";
import type { GatewayClient, GatewayLogRecord } from "./gatewayClient";

const LEVELS = ["", "TRACE", "DEBUG", "INFO", "WARN", "ERROR"];
type DiagTab = "performance" | "console" | "log";

/** Designer diagnostics (DES-005): preview performance, the Studio console, and a gateway log tail. */
export function DiagnosticsPanel({
  view,
  meter,
  client,
  onOpenConsole,
}: {
  view: ViewDocument;
  meter: DiagnosticsMeter;
  /** Null when the Studio opened offline. */
  client: GatewayClient | null;
  onOpenConsole: () => void;
}) {
  const [tab, setTab] = useState<DiagTab>("performance");
  const tabs: Array<[DiagTab, string]> = [
    ["performance", "Performance"],
    ["console", "Console"],
    ["log", "Gateway log"],
  ];
  return (
    <section className="oip-diagnostics" data-testid="diagnostics" aria-label="Diagnostics">
      <div role="tablist" aria-label="Diagnostics sections">
        {tabs.map(([id, label]) => (
          <button key={id} type="button" role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      {tab === "performance" ? <Performance view={view} meter={meter} /> : null}
      {tab === "console" ? (
        <div>
          <p className="oip-binding-muted">The Python console runs scripts on the gateway as the signed-in designer.</p>
          <button type="button" onClick={onOpenConsole}>
            Open the Console panel
          </button>
        </div>
      ) : null}
      {tab === "log" ? <GatewayLog client={client} /> : null}
    </section>
  );
}

function Performance({ view, meter }: { view: ViewDocument; meter: DiagnosticsMeter }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, []);
  const stats = bindingStats(view);
  const last = meter.lastRenderMs();
  const average = meter.averageRenderMs();
  return (
    <dl data-testid="diag-performance">
      <dt>Open view render time</dt>
      <dd data-testid="diag-render">{last === null ? "not measured yet" : `${last.toFixed(1)} ms last, ${average?.toFixed(1)} ms average`}</dd>
      <dt>Bindings</dt>
      <dd data-testid="diag-bindings">{stats.bindings}</dd>
      <dt>Tag subscriptions</dt>
      <dd data-testid="diag-subscriptions">{stats.tagPaths}</dd>
      <dt>Tag messages</dt>
      <dd data-testid="diag-rate">{`${meter.rate().toFixed(1)} per second (${meter.total} total)`}</dd>
    </dl>
  );
}

function GatewayLog({ client }: { client: GatewayClient | null }) {
  const [level, setLevel] = useState("INFO");
  const [records, setRecords] = useState<GatewayLogRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [tail, setTail] = useState(true);
  useEffect(() => {
    if (!client) return undefined;
    let cancelled = false;
    const load = () =>
      client
        .gatewayLogs(level, 100)
        .then((rows) => {
          if (cancelled) return;
          setRecords(rows);
          setError(null);
        })
        .catch((caught: unknown) => {
          if (!cancelled) setError(caught instanceof Error ? caught.message : String(caught));
        });
    void load();
    if (!tail) return () => void (cancelled = true);
    const id = window.setInterval(() => void load(), 3000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [client, level, tail]);
  if (!client) return <p className="oip-binding-muted">Open a gateway project to read the gateway log.</p>;
  return (
    <div data-testid="diag-log">
      <label>
        Level
        <select value={level} onChange={(event) => setLevel(event.target.value)}>
          {LEVELS.map((name) => (
            <option key={name} value={name}>
              {name === "" ? "All" : name}
            </option>
          ))}
        </select>
      </label>
      <label>
        <input type="checkbox" checked={tail} onChange={(event) => setTail(event.target.checked)} /> Follow
      </label>
      {error ? <p role="alert">{error}</p> : null}
      <ul aria-label="Gateway log records">
        {records.map((row, index) => (
          <li key={`${row.ts}-${index}`} data-level={row.level}>
            {row.ts} {row.level} {row.logger} {row.message}
          </li>
        ))}
      </ul>
    </div>
  );
}
