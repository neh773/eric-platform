import { useId, type ReactNode } from "react";
import { numericAxis, timeAxis } from "./axes";
import { bindComponent, imageSource } from "./chartBinding";
import { applyDataKeys, dataKeyValues } from "./dataKeys";
import { DEFAULT_TIMESTAMP_PATTERN, formatCell, formatReportNumber, formatTimestamp } from "./format";
import { percentText, pieSlices, slicePath } from "./pie";
import { autoWeights, AUTO_SAMPLE, groupRows, textLength } from "./tableGroups";
import {
  effectiveComponents,
  LayoutError,
  PAGE_SIZES,
  parseLayout,
  type BarSpec,
  type ComponentSpec,
  type ImageSpec,
  type PieSpec,
  type ReportLayout,
  type TableSpec,
  type TextSpec,
  type TimeSeriesSpec,
  type XySpec,
} from "./layout";
import type { JsonValue, ReportDataset, ReportDefinitionSpec, ReportParameterValue, ReportResult } from "./types";

/**
 * In-browser preview of a report: the same layout (header, tables, time-series and bar charts) the gateway renders
 * to PDF in gateway/report-render. Charts are SVG with the PDF's proportions (content width of the page in points).
 */

/** Colour-blind-safe palette, same order as the PDF renderer. */
export const REPORT_PALETTE = ["#0072B2", "#E69F00", "#009E73", "#D55E00", "#CC79A7", "#56B4E9", "#333333"];

export function contentWidth(layout: ReportLayout): number {
  const size = PAGE_SIZES[layout.page.size];
  const w = layout.page.orientation === "landscape" ? size.height : size.width;
  return w - layout.page.margins.left - layout.page.margins.right;
}

export interface ReportPreviewProps {
  result: ReportResult;
  /** Definition spec; its layout wins over the copy in the result, as on the gateway. */
  definition?: ReportDefinitionSpec;
}

export function ReportPreview({ result, definition }: ReportPreviewProps) {
  let layout: ReportLayout;
  try {
    layout = parseLayout(definition?.layout ?? result.layout);
  } catch (error) {
    const message = error instanceof LayoutError ? error.message : String(error);
    return (
      <div className="oip-report" role="alert">
        Invalid report layout: {message}
      </div>
    );
  }
  const zone = layout.timezone;
  const keys = dataKeyValues(result, zone, 1);
  const fill = (text: string) => applyDataKeys(text, keys);
  const title = fill(layout.header.title ?? definition?.title ?? (result.report.title || result.report.name));
  const subtitleRaw = layout.header.subtitle ?? (definition?.description || undefined);
  const subtitle = subtitleRaw ? fill(subtitleRaw) : undefined;
  const width = contentWidth(layout);
  return (
    <article className="oip-report" aria-label={title} data-orientation={layout.page.orientation}>
      <header className="oip-report-header">
        <h1>{title}</h1>
        {subtitle && <p className="oip-report-subtitle">{subtitle}</p>}
        {layout.header.showParameters && result.parameters.length > 0 && (
          <p className="oip-report-params">
            {result.parameters.map((p) => `${p.name}: ${parameterText(p, zone)}`).join("   ")}
          </p>
        )}
        {layout.header.showGeneratedAt && (
          <p className="oip-report-generated">
            Generated {formatTimestamp(result.generatedAt, DEFAULT_TIMESTAMP_PATTERN, zone)} {zone}
            {result.run && `   run ${result.run.runId.slice(0, 8)} (${result.run.trigger}, ${result.run.user}, ${result.run.status})`}
          </p>
        )}
        {(result.run?.warnings ?? []).map((w) => (
          <p key={w} className="oip-report-warning">
            Warning: {w}
          </p>
        ))}
      </header>
      {effectiveComponents(layout, result).map((raw, i) => {
        const { spec: c, problems } = bindComponent(raw, fill);
        return (
          <section key={i} className="oip-report-component" data-type={c.type}>
            {c.title && <h2>{fill(c.title)}</h2>}
            {problems.map((p) => (
              <Note key={p}>{p}</Note>
            ))}
            <ReportComponent spec={c} dataset={"dataset" in c ? result.datasets.find((d) => d.name === c.dataset) : undefined} zone={zone} width={width} keys={fill} />
          </section>
        );
      })}
    </article>
  );
}

function parameterText(p: ReportParameterValue, zone: string): string {
  const v = p.value;
  const ts = (x: JsonValue | undefined) =>
    typeof x === "number" && Number.isInteger(x) ? formatTimestamp(x, "yyyy-MM-dd HH:mm", zone) : String(x);
  if (v === null || v === undefined) return "(none)";
  if (p.type === "timeRange" && typeof v === "object" && !Array.isArray(v)) return `${ts(v.from)} to ${ts(v.to)}`;
  if (p.type === "timestamp") return ts(v);
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

function Note({ children }: { children: string }) {
  return <p className="oip-report-note">{children}</p>;
}

function ReportComponent({
  spec,
  dataset,
  zone,
  width,
  keys,
}: {
  spec: ComponentSpec;
  dataset?: ReportDataset;
  zone: string;
  width: number;
  keys: (text: string) => string;
}) {
  if (spec.type === "text") return <ReportText spec={spec} keys={keys} />;
  if (spec.type === "image") return <ReportImage spec={spec} keys={keys} />;
  if (!dataset) return <Note>{`Dataset '${spec.dataset}' is not in this result.`}</Note>;
  switch (spec.type) {
    case "table":
      return <ReportTable spec={spec} dataset={dataset} zone={zone} />;
    case "timeSeries":
      return <ReportTimeSeriesChart spec={spec} dataset={dataset} zone={zone} width={width} />;
    case "bar":
      return <ReportBarChart spec={spec} dataset={dataset} zone={zone} width={width} />;
    case "pie":
      return <ReportPieChart spec={spec} dataset={dataset} zone={zone} width={width} />;
    case "xy":
      return <ReportXyChart spec={spec} dataset={dataset} width={width} />;
  }
}

export function ReportImage({ spec, keys }: { spec: ImageSpec; keys: (text: string) => string }) {
  const filled = keys(spec.src).trim();
  const src = imageSource(filled);
  if (!src) {
    const reason = filled === spec.src && spec.src.startsWith("@") ? `data key ${spec.src} has no value` : "the source is not a base64 PNG or JPEG data URI";
    return <Note>{`Image skipped: ${reason}.`}</Note>;
  }
  const justify = spec.align === "center" ? "center" : spec.align === "right" ? "flex-end" : "flex-start";
  return (
    <div className="oip-report-image" style={{ display: "flex", justifyContent: justify }}>
      <img src={src} alt={spec.alt ?? ""} width={spec.width} height={spec.height} style={{ maxWidth: "100%", height: spec.height ? undefined : "auto" }} />
    </div>
  );
}

function ReportText({ spec, keys }: { spec: TextSpec; keys: (text: string) => string }) {
  return <p className="oip-report-text">{keys(spec.text)}</p>;
}

function colIndex(d: ReportDataset, name: string) {
  return d.columns.findIndex((c) => c.name === name);
}

function missingColumns(d: ReportDataset, names: string[]) {
  return names.filter((n) => colIndex(d, n) < 0).map((n) => `'${n}'`).join(", ");
}

export function ReportTable({ spec, dataset, zone }: { spec: TableSpec; dataset: ReportDataset; zone: string }) {
  const specs = spec.columns.length > 0 ? spec.columns : dataset.columns.map((c) => ({ field: c.name, format: { type: "auto" as const, grouping: false, nullText: "" }, width: 1 }));
  const missing = missingColumns(dataset, [...specs.map((s) => s.field), ...(spec.groupBy ? [spec.groupBy] : [])]);
  if (missing) return <Note>{`Table skipped: dataset '${dataset.name}' has no column ${missing}.`}</Note>;
  const rows = spec.maxRows ? dataset.rows.slice(0, spec.maxRows) : dataset.rows;
  const resolved = specs.map((s) => {
    const index = colIndex(dataset, s.field);
    const col = dataset.columns[index]!;
    const unit = ("unit" in s ? s.unit : undefined) ?? col.unit;
    const numeric = s.format.type === "number" || (s.format.type === "auto" && col.type === "number");
    const label = ("label" in s ? s.label : undefined) ?? s.field;
    const align = ("align" in s ? s.align : undefined) ?? (numeric ? "right" : "left");
    return { index, header: unit ? `${label} (${unit})` : label, type: col.type, format: s.format, align };
  });
  const weights = spec.autoSize
    ? autoWeights(
        resolved.map((c) =>
          textLength(
            c.header,
            rows.slice(0, AUTO_SAMPLE).map((row) => formatCell(row[c.index], c.type, c.format, zone)),
          ),
        ),
      )
    : specs.map((s) => s.width);
  const total = weights.reduce((sum, w) => sum + w, 0);
  const cols = resolved.map((c, i) => ({ ...c, width: (100 * weights[i]!) / total }));
  const totals = (label: string, of: JsonValue[][], key: string) => (
    <tr key={key} className="oip-report-totals">
      {cols.map((c, i) => {
        const numeric = c.type === "number";
        const sum = numeric ? of.reduce((acc, row) => acc + (typeof row[c.index] === "number" ? (row[c.index] as number) : 0), 0) : null;
        const firstText = !numeric && cols.findIndex((col) => col.type !== "number") === i;
        return (
          <td key={i} style={{ textAlign: c.align }}>
            {numeric ? formatCell(sum, c.type, c.format, zone) : firstText ? label : ""}
          </td>
        );
      })}
    </tr>
  );
  const detail = (row: JsonValue[], shaded: boolean, key: string) => (
    <tr key={key} data-zebra={shaded ? "true" : undefined}>
      {cols.map((c, i) => (
        <td key={i} style={{ textAlign: c.align }}>
          {formatCell(row[c.index], c.type, c.format, zone)}
        </td>
      ))}
    </tr>
  );
  let body;
  if (spec.groupBy) {
    const gi = colIndex(dataset, spec.groupBy);
    const gcol = dataset.columns[gi]!;
    const format = specs.find((s) => s.field === spec.groupBy)?.format ?? { type: "auto" as const, grouping: false, nullText: "" };
    body = groupRows(rows, gi).flatMap((group, g) => {
      const key = group.key === undefined ? "(none)" : formatCell(group.key, gcol.type, format, zone);
      return [
        <tr key={`h${g}`} className="oip-report-group" data-group={key}>
          <th scope="rowgroup" colSpan={cols.length}>{`${gcol.name}: ${key} (${group.rows.length})`}</th>
        </tr>,
        ...group.rows.map((row, r) => detail(row, spec.zebra !== false && r % 2 === 1, `g${g}r${r}`)),
        ...(spec.groupSummary ? [totals("Subtotal", group.rows, `s${g}`)] : []),
      ];
    });
  } else {
    body = rows.map((row, r) => detail(row, spec.zebra !== false && r % 2 === 1, `r${r}`));
  }
  return (
    <>
      <table className="oip-report-table">
        <colgroup>
          {cols.map((c, i) => (
            <col key={i} style={{ width: `${c.width.toFixed(3)}%` }} />
          ))}
        </colgroup>
        {spec.showHeader === false ? null : (
          <thead>
            <tr>
              {cols.map((c, i) => (
                <th key={i} scope="col" style={{ textAlign: c.align }}>
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
        )}
        <tbody>
          {body}
          {spec.summary ? totals("Total", rows, "total") : null}
        </tbody>
      </table>
      {rows.length === 0 && <Note>No rows.</Note>}
      {rows.length < dataset.rows.length && <Note>{`Showing the first ${rows.length} of ${dataset.rows.length} rows.`}</Note>}
      {dataset.truncated && <Note>The data source was truncated by the backend row limit.</Note>}
    </>
  );
}

interface Line {
  label: string;
  unit?: string;
  points: [number, number | null][];
}

const CHAR_W = 4.3; // approximate advance of the 7.5pt axis font, used to size the y-label gutter

interface Frame {
  x: number;
  y: number;
  w: number;
  h: number;
  py: (v: number) => number;
}

/** min/max without spreading: tag history can hold hundreds of thousands of points. */
function extent(values: number[]): [number, number] {
  let lo = Infinity;
  let hi = -Infinity;
  for (const v of values) {
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  return [lo, hi];
}

function legendLabel(label: string, unit?: string) {
  return unit ? `${label} (${unit})` : label;
}

/** Shared chart scaffolding: y axis, grid, axis lines and legend. Coordinates are SVG (y grows downwards). */
function ChartFrame({
  width,
  height,
  lo,
  hi,
  spec,
  unit,
  legend,
  label,
  children,
}: {
  width: number;
  height: number;
  lo: number;
  hi: number;
  spec: { yAxis: { min?: number; max?: number; decimals?: number } };
  unit?: string;
  legend: string[];
  label: string;
  children: (frame: Frame, axis: ReturnType<typeof numericAxis>) => { plot: ReactNode; labels: ReactNode };
}) {
  const clipId = `oip-report-clip-${useId().replace(/[^A-Za-z0-9_-]/g, "")}`;
  const axis = numericAxis(lo, hi, spec.yAxis.min, spec.yAxis.max);
  const decimals = spec.yAxis.decimals ?? axis.decimals;
  const labels = axis.ticks.map((t) => formatReportNumber(t, decimals, false));
  const labelW = Math.max(...labels.map((l) => l.length * CHAR_W));
  const legendH = 12;
  const x = labelW + 8;
  const w = width - x - 6;
  const top = unit ? 18 : 6;
  const bottom = height - legendH - 18;
  const h = bottom - top;
  const py = (v: number) => bottom - ((v - axis.min) / (axis.max - axis.min)) * h;
  const frame: Frame = { x, y: top, w, h, py };
  let legendX = 0;
  const drawn = children(frame, axis);
  return (
    <svg className="oip-report-chart" viewBox={`0 0 ${width.toFixed(2)} ${height}`} role="img" aria-label={label}>
      {unit && (
        <text x={0} y={7} className="oip-report-axis-label">
          {unit}
        </text>
      )}
      {axis.ticks.map((t, i) => (
        <g key={i} data-tick={labels[i]}>
          <line x1={x} x2={x + w} y1={py(t)} y2={py(t)} className="oip-report-grid" />
          <text x={x - 4} y={py(t) + 2.5} textAnchor="end" className="oip-report-axis-label">
            {labels[i]}
          </text>
        </g>
      ))}
      <line x1={x} x2={x + w} y1={bottom} y2={bottom} className="oip-report-axis" />
      <line x1={x} x2={x} y1={top} y2={bottom} className="oip-report-axis" />
      <clipPath id={clipId}>
        <rect x={x} y={top} width={w} height={h} />
      </clipPath>
      <g clipPath={`url(#${clipId})`}>{drawn.plot}</g>
      {drawn.labels}
      <g className="oip-report-legend">
        {legend.map((l, i) => {
          const lx = legendX;
          legendX += 14 + l.length * CHAR_W + 12;
          return (
            <g key={i}>
              <rect x={lx} y={height - 9} width={10} height={3} fill={REPORT_PALETTE[i % REPORT_PALETTE.length]} />
              <text x={lx + 14} y={height - 5}>
                {l}
              </text>
            </g>
          );
        })}
      </g>
    </svg>
  );
}

export function ReportTimeSeriesChart({ spec, dataset, zone, width }: { spec: TimeSeriesSpec; dataset: ReportDataset; zone: string; width: number }) {
  let lines: Line[];
  if ((dataset.series ?? []).length > 0) {
    const wanted = new Set(spec.series);
    lines = (dataset.series ?? [])
      .filter((s) => wanted.size === 0 || wanted.has(s.tagPath) || (s.label !== undefined && wanted.has(s.label)))
      .map((s) => ({ label: s.label ?? s.tagPath, unit: s.unit, points: s.points }));
  } else {
    const xIdx = spec.x ? colIndex(dataset, spec.x) : dataset.columns.findIndex((c) => c.type === "timestamp");
    if (xIdx < 0) return <Note>{`Chart skipped: dataset '${dataset.name}' has no timestamp column (set x).`}</Note>;
    const yNames = spec.y.length > 0 ? spec.y : dataset.columns.filter((c) => c.type === "number").map((c) => c.name);
    const missing = missingColumns(dataset, yNames);
    if (missing) return <Note>{`Chart skipped: dataset '${dataset.name}' has no column ${missing}.`}</Note>;
    lines = yNames.map((name) => {
      const yi = colIndex(dataset, name);
      const points: [number, number | null][] = [];
      for (const row of dataset.rows) {
        const t = row[xIdx];
        if (typeof t !== "number" || !Number.isInteger(t)) continue;
        const v = row[yi];
        points.push([t, typeof v === "number" ? v : null]);
      }
      return { label: name, unit: dataset.columns[yi]!.unit, points };
    });
  }
  const all = lines.flatMap((l) => l.points);
  const values = all.map((p) => p[1]).filter((v): v is number => v !== null && Number.isFinite(v));
  if (all.length === 0 || values.length === 0) return <Note>No data to chart.</Note>;
  let [t0, t1] = extent(all.map((p) => p[0]));
  const [vLo, vHi] = extent(values);
  if (t0 === t1) {
    t0 -= 30 * 60_000;
    t1 += 30 * 60_000;
  }
  const units = [...new Set(lines.map((l) => l.unit).filter((u): u is string => !!u))];
  const unit = spec.yAxis.label ?? (units.length === 1 ? units[0] : undefined);
  const label = spec.title ?? `Time series of ${dataset.name}`;
  return (
    <ChartFrame
      width={width}
      height={spec.height}
      lo={vLo}
      hi={vHi}
      spec={spec}
      unit={unit}
      legend={lines.map((l) => legendLabel(l.label, l.unit))}
      label={label}
    >
      {(frame) => {
        const px = (t: number) => frame.x + ((t - t0) / (t1 - t0)) * frame.w;
        const axis = timeAxis(t0, t1, zone, Math.max(2, Math.floor(frame.w / 70)), spec.timePattern);
        const bottom = frame.y + frame.h;
        return {
          plot: lines.map((l, i) => {
              let d = "";
              let open = false;
              for (const [t, v] of [...l.points].sort((a, b) => a[0] - b[0])) {
                if (v === null || !Number.isFinite(v)) {
                  open = false;
                  continue;
                }
                d += `${open ? "L" : "M"}${px(t).toFixed(2)} ${frame.py(v).toFixed(2)} `;
                open = true;
              }
            return (
              <path key={i} d={d.trim()} fill="none" stroke={REPORT_PALETTE[i % REPORT_PALETTE.length]} strokeWidth={1.2} strokeLinejoin="round" data-series={l.label} />
            );
          }),
          labels: (
            <g className="oip-report-time-ticks">
              {axis.ticks.map((t) => (
                <text key={t} x={px(t)} y={bottom + 12} textAnchor="middle" className="oip-report-axis-label">
                  {formatTimestamp(t, axis.pattern, zone)}
                </text>
              ))}
            </g>
          ),
        };
      }}
    </ChartFrame>
  );
}

export function ReportBarChart({ spec, dataset, zone, width }: { spec: BarSpec; dataset: ReportDataset; zone: string; width: number }) {
  const missing = missingColumns(dataset, [spec.category, ...spec.values]);
  if (missing) return <Note>{`Chart skipped: dataset '${dataset.name}' has no column ${missing}.`}</Note>;
  if (dataset.rows.length === 0) return <Note>No data to chart.</Note>;
  const ci = colIndex(dataset, spec.category);
  const vi = spec.values.map((v) => colIndex(dataset, v));
  const cats = dataset.rows.map((r) => formatCell(r[ci], dataset.columns[ci]!.type, spec.categoryFormat, zone));
  const values = dataset.rows.map((r) => vi.map((i) => (typeof r[i] === "number" && Number.isFinite(r[i]) ? (r[i] as number) : null)));
  const finite = values.flat().filter((v): v is number => v !== null);
  const [fLo, fHi] = finite.length ? extent(finite) : [0, 1];
  const lo = Math.min(0, fLo);
  const hi = Math.max(0, fHi);
  const units = [...new Set(vi.map((i) => dataset.columns[i]!.unit).filter((u): u is string => !!u))];
  const unit = spec.yAxis.label ?? (units.length === 1 ? units[0] : undefined);
  const label = spec.title ?? `Bar chart of ${dataset.name}`;
  return (
    <ChartFrame
      width={width}
      height={spec.height}
      lo={lo}
      hi={hi}
      spec={spec}
      unit={unit}
      legend={vi.map((i) => legendLabel(dataset.columns[i]!.name, dataset.columns[i]!.unit))}
      label={label}
    >
      {(frame, axis) => {
        const group = frame.w / cats.length;
        const barW = (group * 0.7) / vi.length;
        const zeroY = frame.py(Math.min(axis.max, Math.max(axis.min, 0)));
        const every = Math.max(1, Math.ceil(40 / group));
        return {
          plot: cats.map((cat, i) =>
              values[i]!.map((v, j) => {
                if (v === null) return null;
                const vy = frame.py(v);
                return (
                  <rect
                    key={`${i}-${j}`}
                    x={frame.x + i * group + group * 0.15 + j * barW}
                    y={Math.min(zeroY, vy)}
                    width={barW}
                    height={Math.abs(vy - zeroY)}
                    fill={REPORT_PALETTE[j % REPORT_PALETTE.length]}
                    data-category={cat}
                    data-value={v}
                  >
                    <title>{`${cat} ${dataset.columns[vi[j]!]!.name}: ${formatReportNumber(v, undefined, true)}`}</title>
                  </rect>
                );
              }),
          ),
          labels: cats.map((cat, i) =>
            i % every === 0 ? (
              <text key={`l${i}`} x={frame.x + i * group + group / 2} y={frame.y + frame.h + 12} textAnchor="middle" className="oip-report-axis-label">
                {cat}
              </text>
            ) : null,
          ),
        };
      }}
    </ChartFrame>
  );
}

export function ReportPieChart({ spec, dataset, zone, width }: { spec: PieSpec; dataset: ReportDataset; zone: string; width: number }) {
  const missing = missingColumns(dataset, [spec.category, spec.value]);
  if (missing) return <Note>{`Chart skipped: dataset '${dataset.name}' has no column ${missing}.`}</Note>;
  const ci = colIndex(dataset, spec.category);
  const vi = colIndex(dataset, spec.value);
  const slices = pieSlices(
    dataset.rows.map((r) => [formatCell(r[ci], dataset.columns[ci]!.type, spec.categoryFormat, zone), typeof r[vi] === "number" ? (r[vi] as number) : null]),
  );
  if (slices.length === 0) return <Note>No data to chart.</Note>;
  const unit = dataset.columns[vi]!.unit;
  const legend = slices.map((s) => legendLabel(spec.showPercent ? `${s.label} (${percentText(s.fraction)})` : s.label, unit));
  // Legend rows wrap like the PDF's: 14 + text + 12 points per entry.
  let rows = 1;
  let used = 0;
  for (const l of legend) {
    const w = 14 + l.length * CHAR_W + 12;
    if (used > 0 && used + w > width) {
      rows++;
      used = 0;
    }
    used += w;
  }
  const legendH = rows * 12;
  const height = spec.height;
  const diameter = Math.max(40, Math.min(height - legendH - 14, width));
  const r = diameter / 2;
  const cx = width / 2;
  const cy = 6 + r;
  let from = 0;
  let legendX = 0;
  let legendRow = 0;
  const label = spec.title ?? `Pie chart of ${dataset.name}`;
  return (
    <svg className="oip-report-chart" viewBox={`0 0 ${width.toFixed(2)} ${height}`} role="img" aria-label={label}>
      {slices.map((s, i) => {
        const to = from + s.fraction;
        const path = slicePath(cx, cy, r, from, to);
        from = to;
        return (
          <path key={i} d={path} fill={REPORT_PALETTE[i % REPORT_PALETTE.length]} stroke="#ffffff" strokeWidth={0.75} data-slice={s.label} data-value={s.value}>
            <title>{`${s.label}: ${formatReportNumber(s.value, undefined, true)} (${percentText(s.fraction)})`}</title>
          </path>
        );
      })}
      <g className="oip-report-legend">
        {legend.map((l, i) => {
          const w = 14 + l.length * CHAR_W + 12;
          if (legendX > 0 && legendX + w > width) {
            legendX = 0;
            legendRow++;
          }
          const lx = legendX;
          legendX += w;
          const by = height - legendH + 9 + legendRow * 12 - 9;
          return (
            <g key={i}>
              <rect x={lx} y={by + 4} width={10} height={3} fill={REPORT_PALETTE[i % REPORT_PALETTE.length]} />
              <text x={lx + 14} y={by + 8}>
                {l}
              </text>
            </g>
          );
        })}
      </g>
    </svg>
  );
}

export function ReportXyChart({ spec, dataset, width }: { spec: XySpec; dataset: ReportDataset; width: number }) {
  const missing = missingColumns(dataset, [spec.x, ...spec.y]);
  if (missing) return <Note>{`Chart skipped: dataset '${dataset.name}' has no column ${missing}.`}</Note>;
  const xi = colIndex(dataset, spec.x);
  const yi = spec.y.map((n) => colIndex(dataset, n));
  const series = yi.map((col) => {
    const points: [number, number][] = [];
    for (const row of dataset.rows) {
      const xv = row[xi];
      const yv = row[col];
      if (typeof xv === "number" && Number.isFinite(xv) && typeof yv === "number" && Number.isFinite(yv)) points.push([xv, yv]);
    }
    return points;
  });
  const all = series.flat();
  if (all.length === 0) return <Note>No data to chart.</Note>;
  const [xLo, xHi] = extent(all.map((p) => p[0]));
  const [yLo, yHi] = extent(all.map((p) => p[1]));
  const xAxis = numericAxis(xLo, xHi, spec.xAxis.min, spec.xAxis.max);
  const xDecimals = spec.xAxis.decimals ?? xAxis.decimals;
  const units = [...new Set(yi.map((i) => dataset.columns[i]!.unit).filter((u): u is string => !!u))];
  const unit = spec.yAxis.label ?? (units.length === 1 ? units[0] : undefined);
  const xLabel = spec.xAxis.label ?? dataset.columns[xi]!.unit;
  const label = spec.title ?? `XY chart of ${dataset.name}`;
  return (
    <ChartFrame
      width={width}
      height={spec.height}
      lo={yLo}
      hi={yHi}
      spec={spec}
      unit={unit}
      legend={yi.map((i) => legendLabel(dataset.columns[i]!.name, dataset.columns[i]!.unit))}
      label={label}
    >
      {(frame) => {
        const px = (v: number) => frame.x + ((v - xAxis.min) / (xAxis.max - xAxis.min)) * frame.w;
        const bottom = frame.y + frame.h;
        return {
          plot: series.map((points, i) => {
            const color = REPORT_PALETTE[i % REPORT_PALETTE.length];
            return (
              <g key={i} data-series={dataset.columns[yi[i]!]!.name}>
                {spec.connect && points.length > 1 && (
                  <path
                    d={points.map(([xv, yv], j) => `${j === 0 ? "M" : "L"}${px(xv).toFixed(2)} ${frame.py(yv).toFixed(2)}`).join(" ")}
                    fill="none"
                    stroke={color}
                    strokeWidth={1.2}
                    strokeLinejoin="round"
                  />
                )}
                {points.map(([xv, yv], j) => (
                  <rect key={j} x={px(xv) - 2} y={frame.py(yv) - 2} width={4} height={4} fill={color} data-x={xv} data-y={yv} />
                ))}
              </g>
            );
          }),
          labels: (
            <g className="oip-report-x-ticks">
              {xAxis.ticks.map((t) => {
                const text = formatReportNumber(t, xDecimals, false);
                return (
                  <text key={t} x={px(t)} y={bottom + 12} textAnchor="middle" className="oip-report-axis-label">
                    {text}
                  </text>
                );
              })}
              {xLabel && (
                <text x={frame.x + frame.w} y={bottom - 3} textAnchor="end" className="oip-report-axis-label">
                  {xLabel}
                </text>
              )}
            </g>
          ),
        };
      }}
    </ChartFrame>
  );
}
