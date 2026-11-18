import { useState } from "react";
import { allows } from "./access";
import { ArchitecturesBody } from "./architecturesPage";
import { ConnectionsList } from "./connectionsPage";
import type { AdminPageProps } from "./pages";
import { sectionPath } from "./sections";
import { THREAD_DUMP_PATH, formatBytes, formatRatio } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * Gateway diagnostics: a live performance panel, the thread list with a dump download, scheduled
 * tasks, memory, and module states.
 *
 * Concepts from the public diagnostics pages; text and layout are our own.
 * (FEATURES GAT-017, GAT-020).
 */

export const PERFORMANCE_REFRESH_MS = 3_000;

/**
 * The page groups its views the way the public diagnostics section does: architecture, connection status,
 * performance and environment (GAT-021). Each group opens on its own; the performance group keeps the tab strip.
 * Doc source: FEATURES GAT-021.
 */
const GROUPS = [
  { id: "architecture", label: "Architecture" },
  { id: "connections", label: "Connections" },
  { id: "performance", label: "Performance" },
  { id: "environment", label: "Environment" },
] as const;

type GroupId = (typeof GROUPS)[number]["id"];

const TABS = [
  { id: "performance", label: "Summary" },
  { id: "threads", label: "Threads" },
  { id: "tasks", label: "Scheduled tasks" },
  { id: "scripts", label: "Scripts" },
  { id: "memory", label: "Memory" },
  { id: "modules", label: "Modules" },
] as const;

type TabId = (typeof TABS)[number]["id"];

function initialGroup(search: string | undefined): GroupId {
  const wanted = new URLSearchParams(search ?? "").get("group");
  return GROUPS.find((item) => item.id === wanted)?.id ?? "performance";
}

export function DiagnosticsPage({ section, client, session, search, onVisit }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  const [group, setGroup] = useState<GroupId>(() => initialGroup(search));
  const [tab, setTab] = useState<TabId>("performance");

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {!canRead ? (
        <p role="status" data-testid="diagnostics-no-read">
          Your roles do not include gateway.read, so diagnostics are hidden.
        </p>
      ) : null}
      <div role="group" aria-label="Diagnostics groups" className="inline-form">
        {GROUPS.map((item) => (
          <button
            key={item.id}
            type="button"
            aria-pressed={group === item.id}
            data-testid={`diagnostics-group-${item.id}`}
            className={group === item.id ? undefined : "quiet"}
            onClick={() => setGroup(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      {group === "architecture" ? (
        <section aria-label="Architecture status" data-testid="diagnostics-architecture">
          <ArchitecturesBody client={client} canRead={canRead} />
        </section>
      ) : null}
      {group === "connections" ? (
        <section aria-label="Connection status" data-testid="diagnostics-connections">
          <ConnectionsList client={client} onVisit={onVisit} compact />
          <p>
            <a
              href={sectionPath("connections")}
              onClick={(event) => {
                if (!onVisit) return;
                event.preventDefault();
                onVisit(sectionPath("connections"));
              }}
            >
              Open the Connections section
            </a>
          </p>
        </section>
      ) : null}
      {group === "environment" ? <EnvironmentPanel client={client} /> : null}
      {group === "performance" ? (
        <>
          <div role="tablist" aria-label="Diagnostics views" className="report-tabs">
            {TABS.map((item) => (
              <button
                key={item.id}
                type="button"
                role="tab"
                id={`diag-tab-${item.id}`}
                aria-selected={tab === item.id}
                aria-controls={`diag-panel-${item.id}`}
                onClick={() => setTab(item.id)}
              >
                {item.label}
              </button>
            ))}
          </div>
          <div role="tabpanel" id={`diag-panel-${tab}`} aria-labelledby={`diag-tab-${tab}`}>
            {tab === "performance" ? <PerformancePanel client={client} /> : null}
            {tab === "threads" ? <ThreadsPanel client={client} /> : null}
            {tab === "tasks" ? <TasksPanel client={client} /> : null}
            {tab === "scripts" ? <ScriptsPanel client={client} /> : null}
            {tab === "memory" ? <MemoryPanel client={client} /> : null}
            {tab === "modules" ? <ModulesPanel client={client} /> : null}
          </div>
        </>
      ) : null}
    </article>
  );
}

type PanelProps = Pick<AdminPageProps, "client">;

function PerformancePanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const perf = useLoad(() => api.performance(), [api], {
    intervalMs: PERFORMANCE_REFRESH_MS,
    enabled: Boolean(client),
    fallback: "Could not read performance figures",
  });
  const p = perf.data;
  return (
    <section aria-label="Performance">
      <h2>Performance</h2>
      <p>Refreshes every {PERFORMANCE_REFRESH_MS / 1000} seconds.</p>
      {perf.error ? <p role="alert">{perf.error}</p> : null}
      {!p && !perf.error ? <p>Loading performance figures.</p> : null}
      {p ? (
        <>
          <dl className="meta" data-testid="performance-panel" data-updated={perf.updatedAt?.getTime()}>
            <dt>System CPU</dt>
            <dd>{formatRatio(p.cpuLoad)}</dd>
            <dt>Gateway process CPU</dt>
            <dd>{formatRatio(p.processCpuLoad)}</dd>
            <dt>Heap used</dt>
            <dd>
              {formatBytes(p.heapUsedBytes)} of {formatBytes(p.heapMaxBytes)}
            </dd>
            <dt>Data disk</dt>
            <dd>
              {formatBytes(p.disk.usableBytes)} free of {formatBytes(p.disk.totalBytes)}
            </dd>
            <dt>Clock drift</dt>
            <dd>
              {formatRatio(p.clockDrift.drift)} ({p.clockDrift.lateMillis.toFixed(2)} ms late, {p.clockDrift.samples} samples)
            </dd>
            <dt>Last refresh</dt>
            <dd data-testid="performance-updated">{perf.updatedAt?.toLocaleTimeString()}</dd>
          </dl>
          <table className="data-table" aria-label="Recent samples">
            <thead>
              <tr>
                <th>Sample</th>
                <th>System CPU</th>
                <th>Heap used</th>
                <th>Disk free</th>
                <th>Clock drift</th>
              </tr>
            </thead>
            <tbody>
              {p.samples
                .slice(-10)
                .reverse()
                .map((sample, index) => (
                  <tr key={index}>
                    <td>{index === 0 ? "latest" : `-${index}`}</td>
                    <td>{formatRatio(sample.cpuLoad)}</td>
                    <td>{formatBytes(sample.heapUsedBytes)}</td>
                    <td>{formatBytes(sample.diskUsableBytes)}</td>
                    <td>{formatRatio(sample.clockDrift)}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </>
      ) : null}
    </section>
  );
}

function ThreadsPanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const threads = useLoad(() => api.threads(), [api], { enabled: Boolean(client), fallback: "Could not read threads" });
  const [filter, setFilter] = useState("");
  const rows = (threads.data ?? []).filter((row) => !filter || row.name.toLowerCase().includes(filter.toLowerCase()));
  const states = new Map<string, number>();
  for (const row of threads.data ?? []) states.set(row.state, (states.get(row.state) ?? 0) + 1);
  return (
    <section aria-label="Threads">
      <h2>Threads</h2>
      <div className="inline-form">
        <label>
          Thread name
          <input type="search" value={filter} onChange={(event) => setFilter(event.target.value)} />
        </label>
        <button type="button" className="quiet" onClick={threads.reload}>
          Refresh threads
        </button>
        <a href={THREAD_DUMP_PATH} download="thread-dump.txt">
          Download thread dump
        </a>
      </div>
      {threads.error ? <p role="alert">{threads.error}</p> : null}
      {threads.data ? (
        <>
          <p data-testid="thread-summary">
            {threads.data.length} threads:{" "}
            {Array.from(states.entries())
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([state, count]) => `${count} ${state}`)
              .join(", ")}
          </p>
          <table className="data-table" aria-label="Threads list">
            <thead>
              <tr>
                <th>Id</th>
                <th>Name</th>
                <th>State</th>
                <th>Daemon</th>
                <th>CPU time</th>
                <th>Blocked</th>
                <th>Waited</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td>{row.id}</td>
                  <td>{row.name}</td>
                  <td>{row.state}</td>
                  <td>{row.daemon ? "yes" : "no"}</td>
                  <td>{row.cpuNanos < 0 ? "n/a" : `${(row.cpuNanos / 1e6).toFixed(1)} ms`}</td>
                  <td>{row.blockedCount}</td>
                  <td>{row.waitedCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : null}
    </section>
  );
}

function TasksPanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const tasks = useLoad(() => api.tasks(), [api], {
    intervalMs: PERFORMANCE_REFRESH_MS,
    enabled: Boolean(client),
    fallback: "Could not read scheduled tasks",
  });
  return (
    <section aria-label="Scheduled tasks">
      <h2>Scheduled tasks</h2>
      {tasks.error ? <p role="alert">{tasks.error}</p> : null}
      {tasks.data ? (
        <table className="data-table" aria-label="Scheduled tasks list">
          <thead>
            <tr>
              <th>Task</th>
              <th>Period</th>
              <th>State</th>
              <th>Last run</th>
              <th>Runs</th>
              <th>Last error</th>
            </tr>
          </thead>
          <tbody>
            {tasks.data.length === 0 ? (
              <tr>
                <td colSpan={6}>No tasks are registered.</td>
              </tr>
            ) : null}
            {tasks.data.map((row) => (
              <tr key={row.name}>
                <td>{row.name}</td>
                <td>{row.rateMs} ms</td>
                <td>{row.state}</td>
                <td>{row.lastDurationMillis.toFixed(2)} ms</td>
                <td>{row.runs}</td>
                <td>{row.lastError ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}

/** Gateway scripts that have run or are running now: counts, timing and the last log lines (GAT-029). */
function ScriptsPanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const scripts = useLoad(() => api.scripts(), [api], {
    intervalMs: PERFORMANCE_REFRESH_MS,
    enabled: Boolean(client),
    fallback: "Could not read scripts",
  });
  return (
    <section aria-label="Scripts">
      <h2>Scripts</h2>
      {scripts.error ? <p role="alert">{scripts.error}</p> : null}
      {scripts.data ? (
        <table className="data-table" aria-label="Scripts list">
          <thead>
            <tr>
              <th>Script</th>
              <th>Type</th>
              <th>Running</th>
              <th>Executions</th>
              <th>Last</th>
              <th>Average</th>
              <th>Max</th>
              <th>Recent log</th>
            </tr>
          </thead>
          <tbody>
            {scripts.data.length === 0 ? (
              <tr>
                <td colSpan={8}>No script has run yet.</td>
              </tr>
            ) : null}
            {scripts.data.map((row) => (
              <tr key={row.name} data-testid={`script-${row.name}`}>
                <td>{row.name}</td>
                <td>{row.type}</td>
                <td>{row.running}</td>
                <td>
                  {row.executions}
                  {row.failures > 0 ? ` (${row.failures} failed)` : ""}
                </td>
                <td>{row.lastMillis} ms</td>
                <td>{row.avgMillis.toFixed(1)} ms</td>
                <td>{row.maxMillis} ms</td>
                <td>
                  {row.recentLog.map((line, i) => (
                    <div key={i}>
                      <code>{line}</code>
                    </div>
                  ))}
                  {row.lastError ? <div role="note">Last error: {row.lastError}</div> : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}

function MemoryPanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const memory = useLoad(() => api.memory(), [api], {
    intervalMs: PERFORMANCE_REFRESH_MS,
    enabled: Boolean(client),
    fallback: "Could not read memory",
  });
  const m = memory.data;
  return (
    <section aria-label="Memory">
      <h2>Memory</h2>
      {memory.error ? <p role="alert">{memory.error}</p> : null}
      {m ? (
        <dl className="meta" data-testid="memory-panel">
          <dt>Heap used</dt>
          <dd>{formatBytes(m.heap.usedBytes)}</dd>
          <dt>Heap committed</dt>
          <dd>{formatBytes(m.heap.committedBytes)}</dd>
          <dt>Heap maximum</dt>
          <dd>{formatBytes(m.heap.maxBytes)}</dd>
          <dt>Non-heap used</dt>
          <dd>{formatBytes(m.nonHeap.usedBytes)}</dd>
          <dt>Non-heap committed</dt>
          <dd>{formatBytes(m.nonHeap.committedBytes)}</dd>
          <dt>Processors</dt>
          <dd>{m.processors}</dd>
        </dl>
      ) : null}
    </section>
  );
}

function ModulesPanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const modules = useLoad(() => api.modules(), [api], { enabled: Boolean(client), fallback: "Could not read modules" });
  return (
    <section aria-label="Modules">
      <h2>Modules</h2>
      {modules.error ? <p role="alert">{modules.error}</p> : null}
      {modules.data ? (
        <table className="data-table" aria-label="Module states">
          <thead>
            <tr>
              <th>Module</th>
              <th>Id</th>
              <th>Version</th>
              <th>State</th>
              <th>Error</th>
            </tr>
          </thead>
          <tbody>
            {modules.data.length === 0 ? (
              <tr>
                <td colSpan={5}>No modules are installed.</td>
              </tr>
            ) : null}
            {modules.data.map((row) => (
              <tr key={row.id}>
                <td>{row.name}</td>
                <td>{row.id}</td>
                <td>{row.version}</td>
                <td>{row.state}</td>
                <td>{row.error ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}

function EnvironmentPanel({ client }: PanelProps) {
  const api = useStatusApi(client);
  const env = useLoad(() => api.environment(), [api], { enabled: Boolean(client), fallback: "Could not read the environment" });
  const e = env.data;
  return (
    <section aria-label="Environment" data-testid="diagnostics-environment">
      <h2>Environment</h2>
      {env.error ? <p role="alert">{env.error}</p> : null}
      {!e && !env.error ? <p>Loading the environment.</p> : null}
      {e ? (
        <dl className="meta" data-testid="environment-panel">
          <dt>Gateway version</dt>
          <dd>{e.gatewayVersion}</dd>
          <dt>Profile</dt>
          <dd>{e.profile}</dd>
          <dt>Deployment mode</dt>
          <dd>{e.deploymentMode || "none"}</dd>
          <dt>Java runtime</dt>
          <dd>
            {e.javaVersion} ({e.javaVendor})
          </dd>
          <dt>Operating system</dt>
          <dd>
            {e.os} on {e.architecture}, {e.processors} processors
          </dd>
          <dt>Time zone</dt>
          <dd>{e.timeZone}</dd>
          <dt>Data directory</dt>
          <dd>{e.dataDirectory}</dd>
          <dt>Started</dt>
          <dd>{e.startedAt ?? "unknown"}</dd>
          <dt>Uptime</dt>
          <dd>{Math.floor(e.uptimeMillis / 1000)} seconds</dd>
          <dt>Config resources</dt>
          <dd>{e.configResources}</dd>
        </dl>
      ) : null}
    </section>
  );
}
