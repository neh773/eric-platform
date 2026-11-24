import { useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { HealthReport } from "./api";
import type { AdminPageProps } from "./pages";
import { formatBytes, formatDuration, formatRatio, type ModuleRow, type Performance } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * Gateway overview: health, version, profile, uptime, CPU, heap, disk, clock drift, and modules.
 * A banner appears when a figure crosses its cutoff.
 *
 * Concepts from the public gateway web interface page (home/status view) and the system performance
 * page; the wording and layout here are our own.
 * (FEATURES GAT-016, GAT-017).
 *
 * The active deployment mode is read and switched here too (GAT-033). A switch applies to the next
 * effective-config read without a restart (DIFFERENCES 210).
 */

/** Refresh period for the live figures. */
export const OVERVIEW_REFRESH_MS = 5_000;

/** Cutoffs for the status banners. The drift cutoff is the ADR 0010 +/-10% rule (ClockDriftDetector.outsideCutoff). */
export const CUTOFFS = {
  cpuLoad: 0.9,
  heapUsed: 0.9,
  diskFree: 0.1,
  clockDrift: 0.1,
} as const;

export interface OverviewBanner {
  id: string;
  text: string;
}

export function overviewBanners(
  health: HealthReport | null,
  perf: Performance | null,
  modules: ModuleRow[] | null,
): OverviewBanner[] {
  const out: OverviewBanner[] = [];
  if (health && health.status !== "UP") {
    out.push({ id: "health", text: `Gateway health is ${health.status}, not UP.` });
  }
  if (perf) {
    if (perf.cpuLoad >= CUTOFFS.cpuLoad) {
      out.push({ id: "cpu", text: `System CPU is at ${formatRatio(perf.cpuLoad)}, at or above the ${formatRatio(CUTOFFS.cpuLoad)} cutoff.` });
    }
    if (perf.heapMaxBytes > 0 && perf.heapUsedBytes / perf.heapMaxBytes >= CUTOFFS.heapUsed) {
      out.push({
        id: "heap",
        text: `Heap use is ${formatRatio(perf.heapUsedBytes / perf.heapMaxBytes)} of the maximum, at or above the ${formatRatio(CUTOFFS.heapUsed)} cutoff.`,
      });
    }
    if (perf.disk.totalBytes > 0 && perf.disk.usableBytes / perf.disk.totalBytes < CUTOFFS.diskFree) {
      out.push({
        id: "disk",
        text: `Only ${formatRatio(perf.disk.usableBytes / perf.disk.totalBytes)} of the data disk is free, below the ${formatRatio(CUTOFFS.diskFree)} cutoff.`,
      });
    }
    const drift = perf.clockDrift.drift;
    // -1 is the API's "not finite" sentinel.
    const unmeasured = drift === -1;
    if (perf.clockDrift.samples > 0 && (unmeasured || Math.abs(drift) > CUTOFFS.clockDrift)) {
      out.push({
        id: "drift",
        text: unmeasured
          ? "Clock drift could not be measured."
          : `Clock drift is ${formatRatio(Math.abs(drift))} of the sample interval, above the ${formatRatio(CUTOFFS.clockDrift)} cutoff.`,
      });
    }
  }
  for (const module of modules ?? []) {
    if (module.state !== "RUNNING" || module.error) {
      out.push({
        id: `module-${module.id}`,
        text: `Module ${module.name} is ${module.state || "in an unknown state"}${module.error ? `: ${module.error}` : ""}.`,
      });
    }
  }
  return out;
}

export function OverviewPage({ section, client, session }: AdminPageProps) {
  const api = useStatusApi(client);
  const canRead = session ? allows(session, "gateway.read") : true;

  const health = useLoad(() => (client ? client.health() : Promise.reject(new Error("no client"))), [client], {
    intervalMs: OVERVIEW_REFRESH_MS,
    enabled: Boolean(client),
    fallback: "Could not read gateway health",
  });
  const perf = useLoad(() => api.performance(), [api], {
    intervalMs: OVERVIEW_REFRESH_MS,
    enabled: Boolean(client) && canRead,
    fallback: "Could not read performance figures",
  });
  const modules = useLoad(() => api.modules(), [api], {
    intervalMs: OVERVIEW_REFRESH_MS * 6,
    enabled: Boolean(client) && canRead,
    fallback: "Could not read module states",
  });
  const deployment = useLoad(() => (client ? client.deploymentMode() : Promise.reject(new Error("no client"))), [client], {
    enabled: Boolean(client) && canRead,
    fallback: "Could not read the deployment mode",
  });
  const canWrite = session ? allows(session, "gateway.write") : false;
  const [modeInput, setModeInput] = useState("");
  const [modeError, setModeError] = useState<string | null>(null);
  const [modePending, setModePending] = useState(false);

  async function switchMode(mode: string | null) {
    if (!client || modePending) return;
    setModePending(true);
    setModeError(null);
    try {
      await client.setDeploymentMode(mode);
      setModeInput("");
      deployment.reload();
    } catch (err) {
      setModeError(errorMessage(err, "Could not switch the deployment mode"));
    } finally {
      setModePending(false);
    }
  }

  function submitMode(event: FormEvent) {
    event.preventDefault();
    const mode = modeInput.trim();
    if (mode) void switchMode(mode);
  }

  const banners = overviewBanners(health.data, perf.data, modules.data);
  const p = perf.data;
  // `/api/v1/health` is public, so every signed-in role sees the uptime.
  const startedAt = health.data?.startedAt ? new Date(health.data.startedAt) : null;
  const now = health.updatedAt ?? new Date();
  const runningModules = modules.data?.filter((module) => module.state === "RUNNING" && !module.error).length;

  return (
    <article data-section={section.id} className="overview">
      <div className="overview-head">
        <h1>{section.label}</h1>
        <p className="overview-facts">
          {health.data ? `${health.data.version} · ${health.data.profile}` : "Reading the gateway"}
        </p>
      </div>
      <div className="banners" data-testid="overview-banners" aria-label="Overview alerts">
        {banners.length === 0 && (health.data || p) ? (
          <p role="status" className="banner banner-ok" data-testid="overview-all-clear">
            No figure is past its cutoff.
          </p>
        ) : null}
        {banners.length > 0 ? (
          <div className="banner banner-warn" role="status">
            <p>
              {banners.length === 1 ? "1 thing needs attention" : `${banners.length} things need attention`}
            </p>
            <ul className="overview-causes">
              {banners.map((banner) => (
                <li key={banner.id} data-banner={banner.id}>
                  {banner.text}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
      {health.error ? <p role="alert">{health.error}</p> : null}
      <section aria-labelledby="overview-gateway">
        <h2 id="overview-gateway">Gateway</h2>
        <dl className="meta" data-testid="overview-gateway">
          <dt>Health</dt>
          <dd data-testid="overview-health">{health.data?.status ?? "Loading"}</dd>
          <dt>Version</dt>
          <dd data-testid="overview-version">{health.data?.version ?? "Not reported"}</dd>
          <dt>Profile</dt>
          <dd data-testid="overview-profile">{health.data?.profile ?? "Not reported"}</dd>
          <dt>Modules installed</dt>
          <dd data-testid="overview-modules">{health.data?.modules ?? "Not reported"}</dd>
          <dt>Uptime</dt>
          <dd data-testid="overview-uptime">
            {startedAt
              ? `${formatDuration(Math.max(0, now.getTime() - startedAt.getTime()))} (started ${startedAt.toISOString()})`
              : health.data
                ? "Not reported"
                : "Loading"}
          </dd>
        </dl>
        <p data-testid="overview-restart-help">
          To restart, stop ERIC and start it again. On Windows, open Services and restart the ERIC Gateway service. On
          Linux, stop the gateway (Ctrl+C in its terminal) and run the launcher again.
        </p>
      </section>
      <section aria-labelledby="overview-about">
        <h2 id="overview-about">About</h2>
        <dl className="meta" data-testid="overview-about">
          <dt>Product</dt>
          <dd>ERIC (Engineered Runtime Interface &amp; Control), version {health.data?.version ?? "not reported"}, beta</dd>
          <dt>Licence</dt>
          <dd>GNU Affero General Public License v3.0 (AGPL-3.0). Provided as is, with no warranty. Test hardware and safe conditions only.</dd>
          <dt>Source</dt>
          <dd><a href="https://github.com/neh773/eric-platform" target="_blank" rel="noreferrer">github.com/neh773/eric-platform</a></dd>
          <dt>Manual</dt>
          <dd><a href="/manual/" target="_blank" rel="noreferrer">Open the manual</a></dd>
        </dl>
      </section>
      {canRead ? (
        <section aria-labelledby="overview-deployment">
          <h2 id="overview-deployment">Deployment</h2>
          {deployment.error ? <p role="alert">{deployment.error}</p> : null}
          <dl className="meta">
            <dt>Active mode</dt>
            <dd data-testid="overview-deployment-mode">{deployment.data ? (deployment.data.mode ?? "None") : "Loading"}</dd>
            <dt>Modes with overrides</dt>
            <dd data-testid="overview-deployment-modes">
              {deployment.data ? (deployment.data.modes.length > 0 ? deployment.data.modes.join(", ") : "None") : "Loading"}
            </dd>
          </dl>
          {canWrite ? (
            <form className="inline-form" aria-label="Change the active mode" onSubmit={submitMode}>
              <label>
                Deployment mode
                <input
                  type="text"
                  list="overview-deployment-mode-options"
                  value={modeInput}
                  onChange={(event) => setModeInput(event.target.value)}
                />
              </label>
              <datalist id="overview-deployment-mode-options">
                {(deployment.data?.modes ?? []).map((mode) => (
                  <option key={mode} value={mode} />
                ))}
              </datalist>
              <button type="submit" disabled={modePending || !modeInput.trim()}>
                Switch mode
              </button>
              <button
                type="button"
                disabled={modePending || !deployment.data?.mode}
                onClick={() => void switchMode(null)}
              >
                Clear mode
              </button>
              {modeError ? (
                <p role="alert" data-testid="overview-deployment-mode-error">
                  {modeError}
                </p>
              ) : null}
            </form>
          ) : null}
        </section>
      ) : null}
      <section aria-labelledby="overview-performance">
        <h2 id="overview-performance">Performance</h2>
        {!canRead ? (
          <p role="status" data-testid="overview-no-read">
            Your roles do not include gateway.read, so live figures are hidden.
          </p>
        ) : null}
        {perf.error ? <p role="alert">{perf.error}</p> : null}
        {canRead && !p && !perf.error ? <p>Loading performance figures.</p> : null}
        {p ? (
          <div className="kpi-grid" data-testid="overview-performance">
            <Kpi label="System CPU" hot={p.cpuLoad >= CUTOFFS.cpuLoad} testId="overview-cpu" value={formatRatio(p.cpuLoad)} detail={`cutoff ${formatRatio(CUTOFFS.cpuLoad)}`} />
            <Kpi
              label="Data disk free"
              hot={p.disk.totalBytes > 0 && p.disk.usableBytes / p.disk.totalBytes < CUTOFFS.diskFree}
              testId="overview-disk"
              value={formatBytes(p.disk.usableBytes)}
              detail={`of ${formatBytes(p.disk.totalBytes)}`}
            />
            <Kpi
              label="Heap"
              hot={p.heapMaxBytes > 0 && p.heapUsedBytes / p.heapMaxBytes >= CUTOFFS.heapUsed}
              testId="overview-heap"
              value={formatBytes(p.heapUsedBytes)}
              detail={`of ${formatBytes(p.heapMaxBytes)}${p.heapMaxBytes > 0 ? ` (${formatRatio(p.heapUsedBytes / p.heapMaxBytes)})` : ""}`}
            />
            <Kpi
              label="Clock drift"
              hot={p.clockDrift.samples > 0 && (p.clockDrift.drift === -1 || Math.abs(p.clockDrift.drift) > CUTOFFS.clockDrift)}
              testId="overview-drift"
              value={formatRatio(p.clockDrift.drift)}
              detail={`${p.clockDrift.lateMillis.toFixed(2)} ms late on a ${p.clockDrift.intervalMs} ms sleep`}
            />
            <article className="kpi kpi-wide">
              <h3>CPU, recent samples</h3>
              <CpuTrend samples={p.samples.map((sample) => sample.cpuLoad)} current={p.cpuLoad} />
              <p className="kpi-detail">Gateway process {formatRatio(p.processCpuLoad)}</p>
            </article>
            <article className="kpi">
              <h3>Services</h3>
              <ul className="service-list">
                <li>
                  <span>Modules running</span>
                  <span>{runningModules ?? "…"}{modules.data ? ` of ${modules.data.length}` : ""}</span>
                </li>
                <li>
                  <span>Last refresh</span>
                  <span>{perf.updatedAt ? perf.updatedAt.toLocaleTimeString() : "…"}</span>
                </li>
              </ul>
            </article>
          </div>
        ) : null}
        {modules.data ? (
          <p data-testid="overview-module-states">
            {modules.data.filter((m) => m.state === "RUNNING" && !m.error).length} of {modules.data.length} modules running.
          </p>
        ) : null}
      </section>
    </article>
  );
}

function Kpi({ label, value, detail, hot, testId }: { label: string; value: string; detail: string; hot: boolean; testId: string }) {
  return (
    <article className={hot ? "kpi kpi-hot" : "kpi"} data-testid={testId}>
      <h3>{label}</h3>
      <p className="kpi-value oip-num">{value}</p>
      <p className="kpi-detail">{detail}</p>
    </article>
  );
}

/** Hairline CPU trace. No shadow, blur, or animated value. */
function CpuTrend({ samples, current }: { samples: number[]; current: number }) {
  const points = samples.length > 1 ? samples : [current, current];
  const width = 320;
  const height = 96;
  const min = 0;
  const max = 1;
  const coords = points.map((value, index) => {
    const x = (index / (points.length - 1)) * (width - 8) + 4;
    const y = 8 + (1 - (Math.min(max, Math.max(min, value)) - min) / (max - min)) * (height - 20);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  return (
    <svg className="cpu-trend" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="System CPU trend">
      <line x1="4" x2={width - 4} y1={8 + (1 - CUTOFFS.cpuLoad) * (height - 20)} y2={8 + (1 - CUTOFFS.cpuLoad) * (height - 20)} />
      <polyline points={coords.join(" ")} />
    </svg>
  );
}
