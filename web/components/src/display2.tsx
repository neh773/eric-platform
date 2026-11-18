import type { HmiComponentProps } from "@oip/hmi-runtime";
import { readPoints, themeColor } from "./choices";
import { useGatewayClient } from "./gateway";
import { useHistoryPens } from "./historyPens";
import { iconGlyph, type IconShape } from "./icons";
import { fitSvgText, formatNumber, readBoolean, readNumber, readString } from "./read";

/** C-11 batch 1 display components. Written from the public component pages below; the drawing,
 * code and wording are our own, and every color is a theme token unless a prop names a literal color.
 *
 * Simple gauge: -
 * Progress bar: -
 * Icon:         -
 * Sparkline:    -
 */

function readFinite(props: Record<string, unknown>, key: string): number | null {
  const value = props[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function polar(cx: number, cy: number, radius: number, degrees: number): { x: number; y: number } {
  const radians = (degrees * Math.PI) / 180;
  return { x: cx + radius * Math.cos(radians), y: cy + radius * Math.sin(radians) };
}

/** SVG arc from `from` to `to` degrees (0 = east, clockwise), used by the simple gauge. */
export function arcPath(cx: number, cy: number, radius: number, from: number, to: number): string {
  const start = polar(cx, cy, radius, from);
  const end = polar(cx, cy, radius, to);
  const large = to - from > 180 ? 1 : 0;
  return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${radius} ${radius} 0 ${large} 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
}

export function SimpleGauge({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Gauge");
  const unit = readString(props, "unit");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const sweep = Math.min(300, Math.max(90, readNumber(props, "sweep", 180)));
  const thickness = Math.min(30, Math.max(2, readNumber(props, "thickness", 10)));
  const color = themeColor(props.color, "var(--oip-color-running)");
  const numeric = readFinite(props, "value");
  const span = max === min ? 1 : max - min;
  const clamped = numeric === null ? min : Math.min(Math.max(min, max), Math.max(Math.min(min, max), numeric));
  const fraction = Math.min(1, Math.max(0, (clamped - min) / span));
  const start = -90 - sweep / 2;
  const radius = 50 - thickness / 2 - 2;
  const shown = numeric === null ? "—" : formatNumber(numeric, decimals, locale);
  // The value sits inside the arc: a long one is squeezed to the arc's inner width instead of crossing it.
  const valueWidth = Math.max(20, 2 * (radius - thickness / 2) - 6);
  return (
    <div
      className="oip-simple-gauge"
      role="meter"
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={numeric === null ? undefined : clamped}
      aria-valuetext={numeric === null ? "No value" : `${shown}${unit ? ` ${unit}` : ""}`}
      data-fraction={fraction.toFixed(4)}
    >
      <svg viewBox="0 0 100 118" aria-hidden="true">
        <path className="oip-simple-gauge-track" d={arcPath(50, 50, radius, start, start + sweep)} strokeWidth={thickness} />
        {fraction > 0 ? (
          <path
            className="oip-simple-gauge-arc"
            d={arcPath(50, 50, radius, start, start + sweep * Math.min(fraction, 0.9999))}
            strokeWidth={thickness}
            style={{ stroke: color }}
          />
        ) : null}
        <text x="50" y="52" textAnchor="middle" className="oip-simple-gauge-value" {...fitSvgText(shown, 16, valueWidth)}>
          {shown}
        </text>
        {unit ? (
          <text x="50" y="64" textAnchor="middle" className="oip-simple-gauge-unit">
            {unit}
          </text>
        ) : null}
        <text x="50" y="112" textAnchor="middle" className="oip-simple-gauge-label">
          {label}
        </text>
      </svg>
    </div>
  );
}

export function ProgressBar({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Progress");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const showValue = readBoolean(props, "showValue", true);
  const indeterminate = readBoolean(props, "indeterminate", false);
  const valueFormat = props.valueFormat === "value" ? "value" : "percent";
  const color = themeColor(props.color, "var(--oip-color-accent)");
  const numeric = readFinite(props, "value");
  const span = max === min ? 1 : max - min;
  const clamped = numeric === null ? min : Math.min(max, Math.max(min, numeric));
  const percent = Math.min(100, Math.max(0, ((clamped - min) / span) * 100));
  const text =
    numeric === null ? "—" : valueFormat === "percent" ? `${formatNumber(percent, 0, locale)}%` : formatNumber(clamped, 1, locale);
  return (
    <div className="oip-progress" data-indeterminate={indeterminate || undefined}>
      <div className="oip-progress-head">
        <span className="oip-progress-label" title={label}>
          {label}
        </span>
        {showValue && !indeterminate ? (
          <span className="oip-progress-value" title={text}>
            {text}
          </span>
        ) : null}
      </div>
      <div
        className="oip-progress-track"
        role="progressbar"
        aria-label={label}
        aria-valuemin={min}
        aria-valuemax={max}
        aria-valuenow={indeterminate || numeric === null ? undefined : clamped}
        aria-valuetext={indeterminate ? "Working" : text}
      >
        <div className="oip-progress-fill" style={{ width: indeterminate ? "35%" : `${percent}%`, background: color }} />
      </div>
    </div>
  );
}

function IconShapeView({ shape }: { shape: IconShape }) {
  const fill = shape.fill ? "currentColor" : "none";
  if (shape.kind === "circle") return <circle cx={shape.cx} cy={shape.cy} r={shape.r} fill={fill} />;
  if (shape.kind === "rect") {
    return <rect x={shape.x} y={shape.y} width={shape.width} height={shape.height} rx={shape.rx} fill={fill} />;
  }
  return <path d={shape.d} fill={fill} />;
}

export function Icon({ props }: HmiComponentProps) {
  const name = readString(props, "name", "circle");
  const label = readString(props, "label");
  const strokeWidth = Math.min(4, Math.max(0.5, readNumber(props, "strokeWidth", 2)));
  const color = themeColor(props.color, "currentColor");
  const glyph = iconGlyph(name);
  return (
    <svg
      className="oip-icon"
      viewBox="0 0 24 24"
      role={label ? "img" : undefined}
      aria-label={label || undefined}
      aria-hidden={label ? undefined : true}
      data-icon={glyph ? name : ""}
      style={{ color }}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {glyph?.shapes.map((shape, index) => <IconShapeView key={index} shape={shape} />)}
    </svg>
  );
}

/** Maps samples to 0..100 viewBox coordinates. With autoScale the range is the data range (padded when flat). */
export function sparkCoords(points: number[], low: number, high: number): { x: number; y: number }[] {
  const span = high === low ? 1 : high - low;
  return points.map((value, index) => ({
    x: points.length === 1 ? 50 : (index / (points.length - 1)) * 100,
    y: 100 - ((Math.min(high, Math.max(low, value)) - low) / span) * 100,
  }));
}

export function Sparkline({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Sparkline");
  const autoScale = readBoolean(props, "autoScale", true);
  const showLast = readBoolean(props, "showLast", true);
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const historyPath = readString(props, "historyPath", "");
  const client = useGatewayClient();
  const history = useHistoryPens(client, historyPath ? [historyPath] : [], 900, 10, 240);
  const fromHistory = historyPath
    ? (history[historyPath]?.samples ?? []).flatMap((sample) => (sample.v === null ? [] : [sample.v]))
    : [];
  const color = fromHistory.length && readNumber(props, "high", Number.POSITIVE_INFINITY) < (fromHistory[fromHistory.length - 1] ?? 0)
    ? "#c4473a"
    : themeColor(props.color, "#222222");
  const points = historyPath ? fromHistory : readPoints(props.points);
  let low = readNumber(props, "min", 0);
  let high = readNumber(props, "max", 100);
  if (autoScale && points.length > 0) {
    low = Math.min(...points);
    high = Math.max(...points);
    if (low === high) {
      low -= 1;
      high += 1;
    }
  }
  const coords = sparkCoords(points, low, high);
  const bandLow = readFinite(props, "desiredLow");
  const bandHigh = readFinite(props, "desiredHigh");
  const band =
    bandLow !== null && bandHigh !== null && bandHigh > bandLow
      ? sparkCoords([bandHigh, bandLow], low, high).map((point) => point.y)
      : null;
  const last = points.length > 0 ? points[points.length - 1]! : null;
  const lastText = last === null ? "no samples" : formatNumber(last, decimals, locale);
  const lastPoint = coords[coords.length - 1];
  return (
    <div className="oip-sparkline" role="img" aria-label={`${label}, ${points.length} points, last ${lastText}`} data-points={points.length}>
      <svg viewBox="-2 -4 104 108" preserveAspectRatio="none" aria-hidden="true">
        {band ? <rect className="oip-sparkline-band" x="0" y={band[0]} width="100" height={band[1]! - band[0]!} /> : null}
        {readFinite(props, "setpoint") !== null ? (
          <line x1="0" x2="100" y1={sparkCoords([readFinite(props, "setpoint")!], low, high)[0]!.y} y2={sparkCoords([readFinite(props, "setpoint")!], low, high)[0]!.y} stroke="#2f8f4e" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        ) : null}
        <polyline
          points={coords.map((point) => `${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(" ")}
          style={{ stroke: color }}
          vectorEffect="non-scaling-stroke"
        />
      </svg>
      {showLast && lastPoint ? (
        <span
          className="oip-sparkline-last"
          aria-hidden="true"
          style={{ left: `${(lastPoint.x + 2) / 1.04}%`, top: `${(lastPoint.y + 4) / 1.08}%`, background: color }}
        />
      ) : null}
    </div>
  );
}
