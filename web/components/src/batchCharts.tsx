import { useEffect, useState } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { useGatewayClient } from "./gateway";
import { readNumber, readString } from "./read";

export interface XyPoint {
  x: number;
  y: number;
}

export interface XySeries {
  name: string;
  /** `y` is the left axis. `y2` is the right axis. */
  axis: "y" | "y2";
  points: XyPoint[];
}

/** `Name:x,y;x,y | Name:x,y`. A bad pair is skipped. */
export function parseXySeries(value: unknown): XySeries[] {
  if (typeof value !== "string") return [];
  return value.split("|").flatMap((chunk) => {
    const text = chunk.trim();
    if (!text) return [];
    const colon = text.indexOf(":");
    const head = (colon < 0 ? text : text.slice(0, colon)).trim() || "Series";
    const axis: XySeries["axis"] = head.endsWith("@y2") ? "y2" : "y";
    const name = head.replace(/@y2$/, "") || "Series";
    const body = colon < 0 ? "" : text.slice(colon + 1);
    const points: XyPoint[] = [];
    for (const pair of body.split(";")) {
      const [rawX, rawY] = pair.split(",");
      const x = Number(rawX);
      const y = Number(rawY);
      if (Number.isFinite(x) && Number.isFinite(y)) points.push({ x, y });
    }
    return points.length > 0 ? [{ name, axis, points }] : [];
  });
}

export interface GaugeRange {
  from: number;
  to: number;
  token: string;
}

export function parseGaugeRanges(value: unknown, min: number, max: number): GaugeRange[] {
  if (typeof value !== "string" || value.trim() === "") return [{ from: min, to: max, token: "accent" }];
  const ranges: GaugeRange[] = [];
  for (const part of value.split(",")) {
    const [rawFrom, rawTo, token] = part.split(":");
    const from = Number(rawFrom);
    const to = Number(rawTo);
    if (!Number.isFinite(from) || !Number.isFinite(to) || !token?.trim()) continue;
    ranges.push({ from, to, token: token.trim() });
  }
  return ranges.length > 0 ? ranges : [{ from: min, to: max, token: "accent" }];
}

function extent(series: XySeries[], axis: XySeries["axis"]): { minX: number; maxX: number; minY: number; maxY: number } {
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  const rows = series.filter((entry) => entry.axis === axis);
  const used = rows.length > 0 ? rows : axis === "y" ? series : [];
  for (const entry of used) {
    for (const point of entry.points) {
      minX = Math.min(minX, point.x);
      maxX = Math.max(maxX, point.x);
      minY = Math.min(minY, point.y);
      maxY = Math.max(maxY, point.y);
    }
  }
  if (!Number.isFinite(minX)) return { minX: 0, maxX: 1, minY: 0, maxY: 1 };
  if (minX === maxX) maxX += 1;
  if (minY === maxY) maxY += 1;
  return { minX, maxX, minY, maxY };
}

export function XyChart({ props }: HmiComponentProps) {
  const title = readString(props, "title", "XY chart");
  const series = parseXySeries(props.series);
  const left = extent(series, "y");
  const right = extent(series, "y2");
  const width = 320;
  const height = 180;
  const pad = 28;
  const sx = (x: number) => pad + ((x - left.minX) / (left.maxX - left.minX)) * (width - pad * 2);
  const sy = (y: number, box: typeof left) => height - pad - ((y - box.minY) / (box.maxY - box.minY)) * (height - pad * 2);
  return (
    <figure className="oip-xy" aria-label={title}>
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${width} ${height}`} role="img">
        <line x1={pad} y1={height - pad} x2={width - 8} y2={height - pad} stroke="var(--oip-color-line)" />
        <line x1={pad} y1={8} x2={pad} y2={height - pad} stroke="var(--oip-color-line)" />
        {series.some((entry) => entry.axis === "y2") ? <line data-axis="y2" x1={width - 8} y1={8} x2={width - 8} y2={height - pad} stroke="var(--oip-color-accent)" /> : null}
        {series.map((entry, index) => (
          <polyline
            key={entry.name}
            data-series={entry.name}
            data-axis={entry.axis}
            fill="none"
            stroke={`var(--oip-series-${(index % 6) + 1}, var(--oip-color-accent))`}
            strokeWidth="2"
            points={entry.points.map((point) => `${sx(point.x)},${sy(point.y, entry.axis === "y2" ? right : left)}`).join(" ")}
          />
        ))}
      </svg>
      <p>{readString(props, "xLabel", "")} {readString(props, "yLabel", "")}</p>
    </figure>
  );
}

function arc(cx: number, cy: number, r: number, start: number, end: number): string {
  const a0 = Math.PI * (1 - start);
  const a1 = Math.PI * (1 - end);
  const large = end - start > 0.5 ? 1 : 0;
  const x0 = cx + r * Math.cos(a0);
  const y0 = cy - r * Math.sin(a0);
  const x1 = cx + r * Math.cos(a1);
  const y1 = cy - r * Math.sin(a1);
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
}

function needle(cx: number, cy: number, r: number, fraction: number): string {
  const angle = Math.PI * (1 - Math.min(1, Math.max(0, fraction)));
  const x = cx + (r - 8) * Math.cos(angle);
  const y = cy - (r - 8) * Math.sin(angle);
  return `M ${cx} ${cy} L ${x} ${y}`;
}

export function RadialGauge({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Gauge");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const span = max === min ? 1 : max - min;
  const value = readNumber(props, "value", 0);
  const value2 = readNumber(props, "value2", 0);
  const showSecond = props.showSecond !== false;
  const ranges = parseGaugeRanges(props.ranges, min, max);
  const fraction = (n: number) => (n - min) / span;
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 0))));
  return (
    <figure className="oip-radial-gauge" aria-label={label} data-value={value}>
      <svg viewBox="0 0 200 168" role="img" aria-label={label}>
        {ranges.map((range) => (
          <path
            key={`${range.from}-${range.to}-${range.token}`}
            data-range={range.token}
            d={arc(100, 100, 78, fraction(range.from), fraction(range.to))}
            fill="none"
            stroke={`var(--oip-color-${range.token}, var(--oip-color-accent))`}
            strokeWidth="14"
          />
        ))}
        <path data-needle="primary" d={needle(100, 100, 70, fraction(value))} stroke="var(--oip-color-ink)" strokeWidth="3" />
        {showSecond ? <path data-needle="secondary" d={needle(100, 100, 52, fraction(value2))} stroke="var(--oip-color-accent)" strokeWidth="2" /> : null}
        <text x="100" y="158" textAnchor="middle" fill="var(--oip-color-muted)" fontSize="13">{label}</text>
      </svg>
      <p>{value.toFixed(decimals)}{showSecond ? ` / ${value2.toFixed(decimals)}` : ""}</p>
    </figure>
  );
}

interface BrowseNode {
  name: string;
  path: string;
  kind: string;
  hasChildren: boolean;
}

export function TagBrowse({ props, onChange }: HmiComponentProps) {
  const client = useGatewayClient();
  const label = readString(props, "label", "Tags");
  const root = readString(props, "root", "[default]");
  const selected = readString(props, "value", "");
  const [nodes, setNodes] = useState<BrowseNode[]>([]);
  const [error, setError] = useState("");
  const [open, setOpen] = useState(root);

  useEffect(() => {
    let cancelled = false;
    void client.post("/api/v1/tags/browse", { path: open, limit: 100 }).then((body) => {
      if (cancelled) return;
      const list = (body as { nodes?: BrowseNode[] }).nodes ?? [];
      setNodes(list);
      setError("");
    }).catch((failure: unknown) => {
      if (!cancelled) setError(failure instanceof Error ? failure.message : "Browse failed");
    });
    return () => {
      cancelled = true;
    };
  }, [client, open]);

  return (
    <div className="oip-tag-browse" aria-label={label} data-root={root}>
      <p>{open}</p>
      {open !== root ? <button type="button" onClick={() => setOpen(root)}>Back</button> : null}
      {error ? <p role="alert">{error}</p> : null}
      <ul>
        {nodes.map((node) => (
          <li key={node.path}>
            {node.hasChildren || node.kind === "folder" ? (
              <button type="button" onClick={() => setOpen(node.path)}>{node.name}</button>
            ) : (
              <button
                type="button"
                aria-pressed={selected === node.path}
                onClick={() => onChange("value", node.path)}
              >
                {node.name}
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
