import { useEffect, useMemo, useState, type FormEvent } from "react";
// The same gauge and chart the HMI runtime uses, so a dashboard tile looks like a screen component.
import "../../components/src/components.css";
import "../../components/src/components2.css";
import { TimeSeriesChart } from "../../components/src/charts";
import { SimpleGauge } from "../../components/src/display2";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import type { ResourceDocument } from "./api";
import type { AdminPageProps } from "./pages";
import type { MetricSeries } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * Metrics dashboards (GAT-024): the user adds gauge and time-series tiles for gateway metrics (CPU, heap, disk,
 * tag changes per second, sessions, history queue depth) and saves the layout as a `MetricsDashboard` config
 * resource. Reading needs gateway.read; saving or deleting needs gateway.write.
 * Doc source: FEATURES GAT-024.
 */

export const METRICS_REFRESH_MS = 5_000;
const MODULE = "org.oip.gateway";
const KIND = "MetricsDashboard";
const MAX_TILES = 24;

export interface Tile {
  id: string;
  type: "gauge" | "series";
  metric: string;
  title: string;
  max?: number;
}

export function parseTiles(spec: Record<string, unknown>): Tile[] {
  const raw = Array.isArray(spec.tiles) ? spec.tiles : [];
  const tiles: Tile[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const t = item as Record<string, unknown>;
    if (typeof t.id !== "string" || typeof t.metric !== "string" || (t.type !== "gauge" && t.type !== "series")) continue;
    tiles.push({
      id: t.id,
      type: t.type,
      metric: t.metric,
      title: typeof t.title === "string" ? t.title : "",
      ...(typeof t.max === "number" && t.max > 0 ? { max: t.max } : {}),
    });
  }
  return tiles;
}

/** The next tile id that no tile of the dashboard uses. */
export function nextTileId(tiles: readonly Tile[]): string {
  let n = tiles.length + 1;
  while (tiles.some((t) => t.id === `tile-${n}`)) n += 1;
  return `tile-${n}`;
}

function TileView({ tile, metric, times, onRemove, canEdit }: { tile: Tile; metric: MetricSeries | undefined; times: number[]; onRemove: () => void; canEdit: boolean }) {
  const title = tile.title || metric?.label || tile.metric;
  const rows = useMemo(
    () => (metric ? times.map((t, i) => ({ t, [title]: metric.values[i] ?? null })) : []),
    [metric, times, title],
  );
  const decimals = metric && (metric.unit === "%" || metric.unit === "/s") ? 1 : metric && metric.unit === "MiB" ? 0 : 1;
  return (
    <section className="metric-tile" aria-label={title} data-testid={`tile-${tile.id}`} data-type={tile.type}>
      <header className="inline-form">
        <h3>{title}</h3>
        {canEdit ? (
          <button type="button" className="quiet" aria-label={`Remove ${title}`} onClick={onRemove}>
            Remove
          </button>
        ) : null}
      </header>
      {!metric ? (
        <p role="status">This gateway does not publish the metric {tile.metric}.</p>
      ) : tile.type === "gauge" ? (
        <SimpleGauge
          nodeId={tile.id}
          locale="en"
          onChange={() => {}}
          props={{
            label: title,
            unit: metric.unit,
            value: metric.value ?? undefined,
            min: 0,
            max: tile.max ?? metric.max ?? Math.max(1, ...metric.values.map((v) => v ?? 0)) * 1.25,
            decimals,
          }}
        />
      ) : (
        <TimeSeriesChart
          nodeId={tile.id}
          locale="en"
          onChange={() => {}}
          props={{ title: `${title} (${metric.unit})`, data: rows, rangeSeconds: 0, renderer: "area", decimals, showLegend: false }}
        />
      )}
    </section>
  );
}

export function MetricsPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  const canWrite = session ? allows(session, "gateway.write") : false;
  const api = useStatusApi(client);
  const metrics = useLoad(() => api.metrics(), [api], {
    intervalMs: METRICS_REFRESH_MS,
    enabled: Boolean(client) && canRead,
    fallback: "Could not read metrics",
  });
  const [dashboards, setDashboards] = useState<ResourceDocument[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [tiles, setTiles] = useState<Tile[]>([]);
  const [title, setTitle] = useState("");
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [newName, setNewName] = useState("");
  const [metricId, setMetricId] = useState("");
  const [tileType, setTileType] = useState<"gauge" | "series">("gauge");
  const [tileTitle, setTileTitle] = useState("");

  function open(doc: ResourceDocument | null) {
    setSelected(doc ? doc.metadata.name : null);
    setTiles(doc ? parseTiles(doc.spec) : []);
    setTitle(doc && typeof doc.spec.title === "string" ? doc.spec.title : "");
    setDirty(false);
  }

  async function refresh(keep?: string) {
    if (!client) return;
    try {
      const docs = await client.listResources(MODULE, "gateway", KIND);
      setDashboards(docs);
      const wanted = docs.find((d) => d.metadata.name === (keep ?? selected)) ?? docs[0] ?? null;
      open(wanted);
    } catch (err) {
      setError(errorMessage(err, "Could not load dashboards"));
    }
  }

  useEffect(() => {
    if (client && canRead) void refresh();
  }, [client, canRead]);

  const byId = useMemo(() => new Map((metrics.data?.metrics ?? []).map((m) => [m.id, m])), [metrics.data]);
  const current = dashboards?.find((d) => d.metadata.name === selected) ?? null;

  function edit(next: Tile[]) {
    setTiles(next);
    setDirty(true);
    setNotice(null);
  }

  function addTile(event: FormEvent) {
    event.preventDefault();
    const id = metricId || metrics.data?.metrics[0]?.id;
    if (!id || tiles.length >= MAX_TILES) return;
    edit([...tiles, { id: nextTileId(tiles), type: tileType, metric: id, title: tileTitle.trim() }]);
    setTileTitle("");
  }

  async function save() {
    if (!client || !current || !canWrite || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.putResource(MODULE, "gateway", KIND, current.metadata.name, {
        ...current,
        spec: { title, tiles: tiles.map((t) => ({ id: t.id, type: t.type, metric: t.metric, title: t.title, ...(t.max ? { max: t.max } : {}) })) },
      });
      setNotice("Dashboard saved.");
      await refresh(current.metadata.name);
    } catch (err) {
      setError(errorMessage(err, "Could not save the dashboard"));
    } finally {
      setPending(false);
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!client || !canWrite || pending || !RESOURCE_NAME.test(newName)) return;
    setPending(true);
    setError(null);
    try {
      await client.createResource(MODULE, "gateway", KIND, newName, { title: newName, tiles: [] });
      setNewName("");
      await refresh(newName);
    } catch (err) {
      setError(errorMessage(err, "Could not create the dashboard"));
    } finally {
      setPending(false);
    }
  }

  async function remove() {
    if (!client || !current || !canWrite || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.deleteResource(MODULE, "gateway", KIND, current.metadata.name, current.metadata.version);
      setSelected(null);
      await refresh();
    } catch (err) {
      setError(errorMessage(err, "Could not delete the dashboard"));
    } finally {
      setPending(false);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {!canRead ? <p role="status">Your roles do not include gateway.read, so metrics are hidden.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {metrics.error ? <p role="alert">{metrics.error}</p> : null}
      {canRead && dashboards ? (
        <>
          <div className="inline-form" role="group" aria-label="Dashboards">
            <label>
              Dashboard
              <select
                value={selected ?? ""}
                onChange={(event) => open(dashboards.find((d) => d.metadata.name === event.target.value) ?? null)}
                disabled={dashboards.length === 0}
              >
                {dashboards.length === 0 ? <option value="">None yet</option> : null}
                {dashboards.map((d) => (
                  <option key={d.metadata.name} value={d.metadata.name}>
                    {d.metadata.name}
                  </option>
                ))}
              </select>
            </label>
            {canWrite ? (
              <form className="inline-form" onSubmit={(event) => void create(event)} aria-label="New dashboard">
                <label>
                  New dashboard name
                  <input value={newName} onChange={(event) => setNewName(event.target.value)} placeholder="plant-overview" />
                </label>
                <button type="submit" disabled={pending || !RESOURCE_NAME.test(newName)}>
                  Create
                </button>
              </form>
            ) : null}
          </div>
          {current ? (
            <>
              <div className="inline-form">
                <label>
                  Title
                  <input value={title} maxLength={80} disabled={!canWrite} onChange={(event) => { setTitle(event.target.value); setDirty(true); }} />
                </label>
                {canWrite ? (
                  <>
                    <button type="button" onClick={() => void save()} disabled={!dirty || pending}>
                      Save dashboard
                    </button>
                    <button type="button" className="quiet" onClick={() => void remove()} disabled={pending}>
                      Delete dashboard
                    </button>
                  </>
                ) : null}
                {notice ? <span role="status">{notice}</span> : null}
              </div>
              {canWrite ? (
                <form className="inline-form" aria-label="Add a tile" onSubmit={addTile}>
                  <label>
                    Metric
                    <select value={metricId} onChange={(event) => setMetricId(event.target.value)}>
                      {(metrics.data?.metrics ?? []).map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.label} ({m.unit})
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Tile
                    <select value={tileType} onChange={(event) => setTileType(event.target.value as "gauge" | "series")}>
                      <option value="gauge">Gauge</option>
                      <option value="series">Time series</option>
                    </select>
                  </label>
                  <label>
                    Tile title
                    <input value={tileTitle} maxLength={80} onChange={(event) => setTileTitle(event.target.value)} placeholder="Metric name" />
                  </label>
                  <button type="submit" disabled={!metrics.data || tiles.length >= MAX_TILES}>
                    Add tile
                  </button>
                </form>
              ) : null}
              <div className="metric-grid" data-testid="metric-grid">
                {tiles.length === 0 ? <p>This dashboard has no tiles. Add a gauge or a time series above.</p> : null}
                {tiles.map((tile) => (
                  <TileView
                    key={tile.id}
                    tile={tile}
                    metric={byId.get(tile.metric)}
                    times={metrics.data?.times ?? []}
                    canEdit={canWrite}
                    onRemove={() => edit(tiles.filter((t) => t.id !== tile.id))}
                  />
                ))}
              </div>
            </>
          ) : (
            <p>No dashboard exists yet.{canWrite ? " Name one above to start." : ""}</p>
          )}
        </>
      ) : null}
    </article>
  );
}
