import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { useGatewayClient } from "./gateway";
import {
  RANGE_CHOICES,
  appendWindowed,
  areaPath,
  interpolateAt,
  linePath,
  nearestIndex,
  niceExtent,
  niceTicks,
  numericOrNull,
  parseTimestamp,
  rangeLabel,
  rangeStats,
  scale,
  seriesFromRows,
  timeTicks,
  valueExtent,
  type Extent,
  type Sample,
} from "./chartMath";
import { formatNumber, readBoolean, readNumber, readString } from "./read";
import { HISTORY_MAX_LIMIT, useHistoryPens } from "./historyPens";

/**
 * Time series chart and power chart.
 *
 * Both draw with plain SVG (no chart dependency). One value axis per chart. Series colors come from
 * the fixed categorical order in components.css (--oip-series-1..8), never cycled by rank.
 */

const PLOT_W = 1000;
const PLOT_H = 1000;
const MAX_SERIES_COLORS = 8;

export type Renderer = "line" | "area" | "scatter";

export interface ChartSeries {
  key: string;
  name: string;
  /** Fixed slot 0..7 for the series color. Follows the entity, not its rank. */
  slot: number;
  samples: Sample[];
  visible: boolean;
  /** CSS color. Absent uses the series palette. */
  color?: string;
}

interface PlotProps {
  label: string;
  series: ChartSeries[];
  start: number;
  end: number;
  y: Extent;
  renderer: Renderer;
  locale: string;
  traceAt: number | null;
  onTrace: (t: number | null) => void;
  traceValue: (series: ChartSeries, t: number) => number | null;
  decimals: number;
  /** Fraction of the visible width dragged. Positive moves the window earlier. */
  onPan?: (fraction: number) => void;
  /** Multiply the visible range by this factor. */
  onZoom?: (factor: number) => void;
}

function Plot({ label, series, start, end, y, renderer, locale, traceAt, onTrace, traceValue, decimals, onPan, onZoom }: PlotProps) {
  const yTicks = niceTicks(y.min, y.max, 4).filter((tick) => tick >= y.min && tick <= y.max);
  const xTicks = timeTicks(start, end, 5);
  const span = end - start;
  const timeFormat = useMemo(
    () => new Intl.DateTimeFormat(locale, span > 86_400_000 ? { dateStyle: "short", timeStyle: "short" } : { timeStyle: "medium" }),
    [locale, span],
  );
  const shown = series.filter((entry) => entry.visible);

  const drag = useRef<{ id: number; x: number; y: number; panned: boolean } | null>(null);
  const pins = useRef(new Map<number, { x: number; y: number }>());
  const pinch = useRef(0);
  function traceFromPointer(event: ReactPointerEvent<SVGSVGElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    if (box.width <= 0) return;
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    onTrace(start + ratio * (end - start));
  }
  function pointerDown(event: ReactPointerEvent<SVGSVGElement>) {
    pins.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY, panned: false };
    traceFromPointer(event);
  }
  function pointerMove(event: ReactPointerEvent<SVGSVGElement>) {
    const pin = pins.current.get(event.pointerId);
    if (pin) pins.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    traceFromPointer(event);
    if (pins.current.size >= 2 && onZoom) {
      const pts = [...pins.current.values()];
      const dist = Math.hypot(pts[0]!.x - pts[1]!.x, pts[0]!.y - pts[1]!.y);
      if (pinch.current > 0 && dist > 0) onZoom(pinch.current / dist);
      pinch.current = dist;
      return;
    }
    const held = drag.current;
    if (!held || held.id !== event.pointerId || !onPan) return;
    const dx = event.clientX - held.x;
    if (!held.panned && Math.abs(dx) < 8) return;
    const box = event.currentTarget.getBoundingClientRect();
    if (box.width <= 0) return;
    held.panned = true;
    held.x = event.clientX;
    onPan(-dx / box.width);
  }
  function pointerUp(event: ReactPointerEvent<SVGSVGElement>) {
    pins.current.delete(event.pointerId);
    if (drag.current?.id === event.pointerId) drag.current = null;
  }

  return (
    <div className="oip-chart-plot">
      <div className="oip-chart-yaxis" aria-hidden="true">
        {yTicks.map((tick) => (
          <span key={tick} style={{ top: `${(scale(tick, y.min, y.max, 100, true)).toFixed(2)}%` }}>
            {formatNumber(tick, tickDecimals(yTicks), locale)}
          </span>
        ))}
      </div>
      <div className="oip-chart-area">
        <svg
          viewBox={`0 0 ${PLOT_W} ${PLOT_H}`}
          preserveAspectRatio="none"
          role="img"
          aria-label={label}
          data-testid="chart-plot"
          onPointerMove={pointerMove}
          onPointerDown={pointerDown}
          onPointerUp={pointerUp}
          onPointerCancel={pointerUp}
          onWheel={(event) => onZoom?.(event.deltaY > 0 ? 1.2 : 1 / 1.2)}
          onPointerLeave={(event) => {
            drag.current = null;
            pins.current.clear();
            pinch.current = 0;
            if (event.pointerType === "touch") return;
            onTrace(null);
          }}
        >
          {yTicks.map((tick) => {
            const yy = scale(tick, y.min, y.max, PLOT_H, true);
            return <line key={`g${tick}`} className="oip-chart-grid" x1={0} x2={PLOT_W} y1={yy} y2={yy} />;
          })}
          {shown.map((entry) => {
            const color = entry.color ?? `var(--oip-series-${(entry.slot % MAX_SERIES_COLORS) + 1})`;
            if (renderer === "scatter") {
              return (
                <g key={entry.key} data-series={entry.name} style={{ color }}>
                  {entry.samples
                    .filter((sample) => sample.v !== null && sample.t >= start && sample.t <= end)
                    .map((sample) => (
                      <circle
                        key={sample.t}
                        className="oip-chart-dot"
                        cx={scale(sample.t, start, end, PLOT_W)}
                        cy={scale(Math.min(y.max, Math.max(y.min, sample.v!)), y.min, y.max, PLOT_H, true)}
                        r={6}
                      />
                    ))}
                </g>
              );
            }
            return (
              <g key={entry.key} data-series={entry.name} style={{ color }}>
                {renderer === "area" ? (
                  <path className="oip-chart-fill" d={areaPath(entry.samples, start, end, y, PLOT_W, PLOT_H)} />
                ) : null}
                <path className="oip-chart-line" d={linePath(entry.samples, start, end, y, PLOT_W, PLOT_H)} />
              </g>
            );
          })}
          {traceAt !== null ? (
            <line
              className="oip-chart-trace"
              x1={scale(traceAt, start, end, PLOT_W)}
              x2={scale(traceAt, start, end, PLOT_W)}
              y1={0}
              y2={PLOT_H}
            />
          ) : null}
        </svg>
        {traceAt !== null ? (
          <div
            className="oip-chart-tooltip"
            role="status"
            data-testid="x-trace"
            style={scale(traceAt, start, end, 100) > 60 ? { right: `${(100 - scale(traceAt, start, end, 100)).toFixed(2)}%` } : { left: `${scale(traceAt, start, end, 100).toFixed(2)}%` }}
          >
            <strong>{timeFormat.format(traceAt)}</strong>
            {shown.map((entry) => {
              const value = traceValue(entry, traceAt);
              return (
                <span key={entry.key}>
                  <i className="oip-chart-swatch" style={{ background: `var(--oip-series-${(entry.slot % MAX_SERIES_COLORS) + 1})` }} />
                  {`${entry.name}: ${value === null ? "—" : formatNumber(value, decimals, locale)}`}
                </span>
              );
            })}
          </div>
        ) : null}
      </div>
      <div className="oip-chart-xaxis" aria-hidden="true">
        {xTicks.map((tick) => (
          <span key={tick} style={{ left: `${scale(tick, start, end, 100).toFixed(2)}%` }}>
            {timeFormat.format(tick)}
          </span>
        ))}
      </div>
    </div>
  );
}

function tickDecimals(ticks: number[]): number {
  if (ticks.length < 2) return 0;
  const step = Math.abs(ticks[1]! - ticks[0]!);
  if (step >= 1) return 0;
  return Math.min(6, Math.ceil(-Math.log10(step)));
}

function readRenderer(value: unknown): Renderer {
  return value === "area" || value === "scatter" ? value : "line";
}

function readYExtent(props: Record<string, unknown>, series: ChartSeries[], start: number, end: number): Extent {
  if (readBoolean(props, "autoScale", true)) {
    return niceExtent(valueExtent(series.filter((entry) => entry.visible).map((entry) => entry.samples), start, end));
  }
  const min = readNumber(props, "yMin", 0);
  const max = readNumber(props, "yMax", 100);
  return max > min ? { min, max } : { min, max: min + 1 };
}

function Legend({ series, onToggle }: { series: ChartSeries[]; onToggle: (key: string) => void }) {
  return (
    <ul className="oip-chart-legend" aria-label="Legend">
      {series.map((entry) => (
        <li key={entry.key}>
          <label>
            <input type="checkbox" checked={entry.visible} onChange={() => onToggle(entry.key)} aria-label={`Show ${entry.name}`} />
            <i className="oip-chart-swatch" style={{ background: `var(--oip-series-${(entry.slot % MAX_SERIES_COLORS) + 1})` }} />
            {entry.name}
          </label>
        </li>
      ))}
    </ul>
  );
}

/**
 * Time series chart. Data comes from `data` rows (`[{ t, seriesA, seriesB }, ...]`, for example from
 * a query), from history pens (`historyPens`: tag paths whose stored history is read from
 * `/api/v1/history/data` over the visible range, HMP-001), and/or a live `value` binding that is appended to a
 * rolling buffer named `valueName`, using the same bounded-buffer plumbing as the trend.
 */
const TREND_RANGES = [900, 3600, 28_800, 86_400];

function trendKey(nodeId: string): string {
  const path = typeof location === "undefined" ? "" : location.pathname;
  return `oip-trend:${path}:${nodeId}`;
}

function readTrend(nodeId: string): { pens?: string; rangeSeconds?: number } | null {
  if (typeof localStorage === "undefined") return null;
  try {
    const raw = localStorage.getItem(trendKey(nodeId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { pens?: unknown; rangeSeconds?: unknown };
    return {
      pens: typeof parsed.pens === "string" ? parsed.pens : undefined,
      rangeSeconds: typeof parsed.rangeSeconds === "number" ? parsed.rangeSeconds : undefined,
    };
  } catch {
    return null;
  }
}

export function TimeSeriesChart({ nodeId, props, locale, onChange }: HmiComponentProps) {
  const client = useGatewayClient();
  const title = readString(props, "title", "Time series");
  const valueName = readString(props, "valueName", "Value");
  const saved = useMemo(() => readTrend(nodeId), [nodeId]);
  const [penText, setPenText] = useState(saved?.pens ?? readString(props, "historyPens", ""));
  const [rangeSeconds, setRangeSeconds] = useState(Math.max(0, Math.round(saved?.rangeSeconds ?? readNumber(props, "rangeSeconds", 900))));
  const rangeProp = props.rangeSeconds;
  useEffect(() => {
    if (typeof rangeProp !== "number" || !Number.isFinite(rangeProp)) return;
    setRangeSeconds(Math.max(0, Math.round(rangeProp)));
  }, [rangeProp]);
  const [offsetMs, setOffsetMs] = useState(0);
  const [draftPen, setDraftPen] = useState("");
  const maxPoints = Math.max(2, Math.round(readNumber(props, "maxPoints", 1000)));
  const renderer = readRenderer(props.renderer);
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const showLegend = readBoolean(props, "showLegend", true);
  const historyPens = useMemo(() => parsePens(penText), [penText]);
  const choices = useMemo(() => parsePens(readString(props, "penChoices", "")), [props.penChoices]);
  const historyRefresh = Math.min(3600, Math.max(0, Math.round(readNumber(props, "historyRefreshSeconds", 0))));
  const historyLimit = Math.min(HISTORY_MAX_LIMIT, Math.max(1, Math.round(readNumber(props, "historyLimit", 10_000))));
  const historyPaths = useMemo(() => historyPens.map((pen) => pen.path), [historyPens]);
  const history = useHistoryPens(client, historyPaths, rangeSeconds, historyRefresh, historyLimit, offsetMs);
  const remember = (pens: string, range: number) => {
    setPenText(pens);
    setRangeSeconds(range);
    onChange("historyPens", pens);
    onChange("rangeSeconds", range);
    if (typeof localStorage === "undefined") return;
    try {
      localStorage.setItem(trendKey(nodeId), JSON.stringify({ pens, rangeSeconds: range }));
    } catch {
      // A full or private store still leaves the change on this page.
    }
  };
  const rowsKey = JSON.stringify(props.data ?? null);
  const rowSeries = useMemo(() => seriesFromRows(JSON.parse(rowsKey) as unknown), [rowsKey]);
  const [live, setLive] = useState<Sample[]>([]);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [traceAt, setTraceAt] = useState<number | null>(null);
  const liveValue = numericOrNull(props.value);
  const liveTime = parseTimestamp(props.valueTimestamp);
  const windowMs = (rangeSeconds || 86_400) * 1000;

  useEffect(() => {
    if (liveValue === null) return;
    setLive((current) => appendWindowed(current, { t: liveTime ?? Date.now(), v: liveValue }, windowMs, maxPoints));
  }, [liveValue, liveTime, windowMs, maxPoints]);

  const series: ChartSeries[] = useMemo(() => {
    const out: ChartSeries[] = [];
    let slot = 0;
    for (const [key, samples] of rowSeries) {
      out.push({ key, name: key, slot: slot++, samples, visible: !hidden.has(key) });
    }
    for (const pen of historyPens) {
      const key = `history:${pen.path}`;
      const setpoint = /sp$|setpoint|leadon/i.test(pen.name);
      out.push({
        key, name: pen.name, slot: slot++, samples: history[pen.path]?.samples ?? [], visible: !hidden.has(key),
        color: setpoint ? "#2f8f4e" : "#222222",
      });
    }
    if (live.length || liveValue !== null) {
      out.push({ key: "__live", name: valueName, slot: slot++, samples: live, visible: !hidden.has("__live") });
    }
    return out;
  }, [rowSeries, historyPens, history, live, liveValue, valueName, hidden]);

  const { start, end } = useMemo(() => {
    let first = Number.POSITIVE_INFINITY;
    let last = Number.NEGATIVE_INFINITY;
    for (const entry of series) {
      if (!entry.samples.length) continue;
      first = Math.min(first, entry.samples[0]!.t);
      last = Math.max(last, entry.samples[entry.samples.length - 1]!.t);
    }
    if (!Number.isFinite(last)) {
      const now = Date.now();
      return { start: now - (rangeSeconds || 300) * 1000, end: now };
    }
    if (rangeSeconds === 0) return { start: first === last ? first - 1000 : first, end: last };
    const windowEnd = last - offsetMs;
    return { start: windowEnd - rangeSeconds * 1000, end: windowEnd };
  }, [series, rangeSeconds, offsetMs]);

  const y = readYExtent(props, series, start, end);
  const total = series.reduce((sum, entry) => sum + entry.samples.length, 0);
  const latest = series.map((entry) => {
    const last = [...entry.samples].reverse().find((sample) => sample.v !== null);
    return `${entry.name} ${last ? formatNumber(last.v!, decimals, locale) : "no samples"}`;
  });

  const historyErrors = historyPens.flatMap((pen) => {
    const state = history[pen.path];
    if (!state) return [];
    if (state.error) return [`${pen.name}: ${state.error}`];
    return state.truncated ? [`${pen.name}: more history than ${historyLimit} rows; the newest are not shown`] : [];
  });

  return (
    <div className="oip-chart oip-time-series" data-points={total} data-series-count={series.length}>
      <div className="oip-chart-head">
        <span className="oip-chart-title">{title}</span>
        <div className="oip-chart-controls">
          {TREND_RANGES.map((seconds) => (
            <button key={seconds} type="button" aria-pressed={rangeSeconds === seconds} onClick={() => { setOffsetMs(0); remember(penText, seconds); }}>
              {rangeLabel(seconds)}
            </button>
          ))}
          <button type="button" onClick={() => setOffsetMs(0)}>Now</button>
        </div>
      </div>
      {choices.length || historyPens.length ? (
        <div className="oip-chart-controls" role="group" aria-label="Pens">
          {(choices.length ? choices : historyPens).map((pen) => {
            const on = historyPens.some((entry) => entry.path === pen.path);
            return (
              <label key={pen.path}>
                <input
                  type="checkbox"
                  checked={on}
                  onChange={() => {
                    const next = on ? historyPens.filter((entry) => entry.path !== pen.path) : [...historyPens, pen];
                    remember(next.map((entry) => entry.path).join(", "), rangeSeconds);
                  }}
                />
                {pen.name}
              </label>
            );
          })}
          <input aria-label="Add pen" value={draftPen} placeholder="[default]tag" onChange={(event) => setDraftPen(event.target.value)} />
          <button
            type="button"
            onClick={() => {
              const path = draftPen.trim();
              if (!path || historyPens.some((pen) => pen.path === path)) return;
              remember([...historyPens.map((pen) => pen.path), path].join(", "), rangeSeconds);
              setDraftPen("");
            }}
          >
            Add
          </button>
        </div>
      ) : null}
      {historyErrors.length ? (
        <p role="alert" className="oip-chart-error">
          {historyErrors.join("; ")}
        </p>
      ) : null}
      <Plot
        label={`${title}: ${latest.join(", ") || "no data"}`}
        series={series}
        start={start}
        end={end}
        y={y}
        renderer={renderer}
        locale={locale}
        traceAt={traceAt}
        onTrace={setTraceAt}
        decimals={decimals}
        traceValue={(entry, t) => {
          const index = nearestIndex(entry.samples, t);
          return index < 0 ? null : entry.samples[index]!.v;
        }}
        onPan={(fraction) => setOffsetMs((current) => Math.max(0, current + fraction * Math.max(rangeSeconds, 60) * 1000))}
        onZoom={(factor) => {
          const next = Math.min(86_400 * 7, Math.max(60, Math.round(Math.max(rangeSeconds, 60) * factor)));
          setRangeSeconds(next);
          remember(penText, next);
        }}
      />
      {showLegend && series.length > 1 ? (
        <Legend
          series={series}
          onToggle={(key) => {
            const next = new Set(hidden);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            setHidden(next);
          }}
        />
      ) : null}
    </div>
  );
}

export interface Pen {
  path: string;
  name: string;
}

/** `"[default]tank/level, [default]tank/flow"` -> pens named after the last path segment. */
export function parsePens(value: unknown): Pen[] {
  if (typeof value !== "string") return [];
  const seen = new Set<string>();
  const out: Pen[] = [];
  for (const raw of value.split(",")) {
    const path = raw.trim();
    if (!path || seen.has(path)) continue;
    seen.add(path);
    const tail = path.replace(/^\[[^\]]*\]/, "").split("/").filter(Boolean).pop() ?? path;
    out.push({ path, name: tail });
  }
  return out;
}

interface TagReadRow {
  path: string;
  value: number | null;
}

export function parseTagRead(body: unknown): TagReadRow[] {
  if (!body || typeof body !== "object") return [];
  const values = (body as { values?: unknown }).values;
  if (!Array.isArray(values)) return [];
  return values.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const record = entry as Record<string, unknown>;
    if (typeof record.path !== "string") return [];
    const quality = typeof record.quality === "string" ? record.quality : "";
    const good = quality === "" || /^good/i.test(quality);
    return [{ path: record.path, value: good ? numericOrNull(record.value) : null }];
  });
}

/**
 * Power chart. Pens are tag paths. Realtime mode polls POST /api/v1/tags/read at `pollRate` and keeps
 * a rolling window per pen. Historical mode plots `history` rows (`[{ t, "<pen path>": value }]`)
 * supplied by a binding; it moves to the tag history API once that API is on main.
 */
export function PowerChart({ props, locale, onChange }: HmiComponentProps) {
  const client = useGatewayClient();
  const title = readString(props, "title", "Power chart");
  const pens = useMemo(() => parsePens(props.pens), [props.pens]);
  const mode = props.mode === "historical" ? "historical" : "realtime";
  const pollRate = Math.max(250, Math.round(readNumber(props, "pollRate", 1000)));
  const maxPoints = Math.max(2, Math.round(readNumber(props, "maxPoints", 2000)));
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const renderer = readRenderer(props.renderer);
  const [rangeSeconds, setRangeSeconds] = useState(Math.max(10, Math.round(readNumber(props, "rangeSeconds", 300))));
  const [paused, setPaused] = useState(false);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [traceAt, setTraceAt] = useState<number | null>(null);
  const [buffers, setBuffers] = useState<Record<string, Sample[]>>({});
  const [error, setError] = useState("");
  const mounted = useRef(true);
  const historyKey = JSON.stringify(props.history ?? null);
  const history = useMemo(() => seriesFromRows(JSON.parse(historyKey) as unknown), [historyKey]);
  const penKey = pens.map((pen) => pen.path).join("\n");

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const poll = useCallback(async () => {
    const paths = penKey ? penKey.split("\n") : [];
    if (!paths.length) return;
    try {
      const rows = parseTagRead(await client.post("/api/v1/tags/read", { paths }));
      if (!mounted.current) return;
      const t = Date.now();
      setBuffers((current) => {
        const next: Record<string, Sample[]> = {};
        for (const path of paths) {
          const row = rows.find((candidate) => candidate.path === path);
          next[path] = appendWindowed(current[path] ?? [], { t, v: row ? row.value : null }, RANGE_CHOICES[RANGE_CHOICES.length - 1]! * 1000, maxPoints);
        }
        return next;
      });
      setError("");
    } catch (failure) {
      if (mounted.current) setError(`Could not read pens: ${failure instanceof Error ? failure.message : String(failure)}`);
    }
  }, [client, penKey, maxPoints]);

  useEffect(() => {
    if (mode !== "realtime" || paused || !penKey) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const loop = async () => {
      await poll();
      if (!stopped) timer = setTimeout(() => void loop(), pollRate);
    };
    void loop();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [mode, paused, penKey, poll, pollRate]);

  const series: ChartSeries[] = pens.map((pen, slot) => ({
    key: pen.path,
    name: pen.name,
    slot,
    samples: mode === "realtime" ? buffers[pen.path] ?? [] : history.get(pen.path) ?? history.get(pen.name) ?? [],
    visible: !hidden.has(pen.path),
  }));

  const { start, end } = (() => {
    if (mode === "historical") {
      const from = parseTimestamp(props.startTime);
      const to = parseTimestamp(props.endTime);
      let first = Number.POSITIVE_INFINITY;
      let last = Number.NEGATIVE_INFINITY;
      for (const entry of series) {
        if (!entry.samples.length) continue;
        first = Math.min(first, entry.samples[0]!.t);
        last = Math.max(last, entry.samples[entry.samples.length - 1]!.t);
      }
      const end = to ?? (Number.isFinite(last) ? last : Date.now());
      const start = from ?? (Number.isFinite(first) && first < end ? first : end - rangeSeconds * 1000);
      return { start, end };
    }
    let last = Number.NEGATIVE_INFINITY;
    for (const entry of series) if (entry.samples.length) last = Math.max(last, entry.samples[entry.samples.length - 1]!.t);
    const end = Number.isFinite(last) ? last : Date.now();
    return { start: end - rangeSeconds * 1000, end };
  })();

  const y = readYExtent(props, series, start, end);
  const total = series.reduce((sum, entry) => sum + entry.samples.length, 0);

  return (
    <div className="oip-chart oip-power-chart" data-mode={mode} data-points={total} data-paused={paused}>
      <div className="oip-chart-head">
        <span className="oip-chart-title">{title}</span>
        <div className="oip-chart-controls">
          <label>
            Range
            <select
              aria-label="Time range"
              value={rangeSeconds}
              onChange={(event) => {
                const next = Number(event.target.value);
                setRangeSeconds(next);
                onChange("rangeSeconds", next);
              }}
            >
              {[...new Set([...RANGE_CHOICES, rangeSeconds])].sort((a, b) => a - b).map((seconds) => (
                <option key={seconds} value={seconds}>
                  {rangeLabel(seconds)}
                </option>
              ))}
            </select>
          </label>
          {mode === "realtime" ? (
            <button type="button" aria-pressed={paused} onClick={() => setPaused(!paused)}>
              {paused ? "Resume" : "Pause"}
            </button>
          ) : null}
          <PenBrowse
            pens={typeof props.pens === "string" ? props.pens : ""}
            onAdd={(path) => {
              const current = typeof props.pens === "string" ? props.pens : "";
              const next = current.trim() === "" ? path : `${current}, ${path}`;
              onChange("pens", next);
            }}
          />
        </div>
      </div>
      {error ? <p role="alert" className="oip-chart-error">{error}</p> : null}
      <Plot
        label={`${title}, ${mode}, ${pens.length} pen${pens.length === 1 ? "" : "s"}`}
        series={series}
        start={start}
        end={end}
        y={y}
        renderer={renderer}
        locale={locale}
        traceAt={traceAt}
        onTrace={setTraceAt}
        decimals={decimals}
        traceValue={(entry, t) => interpolateAt(entry.samples, t)}
      />
      <table className="oip-chart-pens" aria-label="Pens">
        <thead>
          <tr>
            <th scope="col">Show</th>
            <th scope="col">Pen</th>
            <th scope="col">Value</th>
            <th scope="col">Min</th>
            <th scope="col">Max</th>
            <th scope="col">Average</th>
          </tr>
        </thead>
        <tbody>
          {series.map((entry) => {
            const stats = rangeStats(entry.samples, start, end);
            const show = (value: number | null) => (value === null ? "—" : formatNumber(value, decimals, locale));
            return (
              <tr key={entry.key} data-pen={entry.key} data-samples={entry.samples.length}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`Show ${entry.name}`}
                    checked={entry.visible}
                    onChange={() => {
                      const next = new Set(hidden);
                      if (next.has(entry.key)) next.delete(entry.key);
                      else next.add(entry.key);
                      setHidden(next);
                    }}
                  />
                </td>
                <td title={entry.key}>
                  <i className="oip-chart-swatch" style={{ background: `var(--oip-series-${(entry.slot % MAX_SERIES_COLORS) + 1})` }} />
                  {entry.name}
                </td>
                <td data-testid="pen-value">{show(stats.last)}</td>
                <td>{show(stats.min)}</td>
                <td>{show(stats.max)}</td>
                <td>{show(stats.average)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PenBrowse({ pens, onAdd }: { pens: string; onAdd: (path: string) => void }) {
  const client = useGatewayClient();
  const [open, setOpen] = useState(false);
  const [nodes, setNodes] = useState<Array<{ name: string; path: string; kind: string; hasChildren?: boolean }>>([]);
  const [at, setAt] = useState("[default]");
  const [error, setError] = useState("");
  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    void client.post("/api/v1/tags/browse", { path: at, limit: 50 }).then((body) => {
      if (cancelled) return;
      setNodes((body as { nodes?: Array<{ name: string; path: string; kind: string; hasChildren?: boolean }> }).nodes ?? []);
      setError("");
    }).catch((failure: unknown) => {
      if (!cancelled) setError(failure instanceof Error ? failure.message : "Browse failed");
    });
    return () => {
      cancelled = true;
    };
  }, [client, open, at]);
  const taken = new Set(pens.split(",").map((part) => part.trim()).filter(Boolean));
  return (
    <div className="oip-pen-browse">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>Browse tags</button>
      {open ? (
        <div role="group" aria-label="Tag browse">
          {at !== "[default]" ? <button type="button" onClick={() => setAt("[default]")}>Back</button> : null}
          {error ? <p role="alert">{error}</p> : null}
          <ul>
            {nodes.map((node) => (
              <li key={node.path}>
                {node.hasChildren || node.kind === "folder" ? (
                  <button type="button" onClick={() => setAt(node.path)}>{node.name}</button>
                ) : (
                  <button type="button" disabled={taken.has(node.path)} onClick={() => onAdd(node.path)}>{node.name}</button>
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
