import type { CSSProperties } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { readNumber, readString } from "./read";

/**
 * Chart and display pieces for the phase-4 palette: pie, range selector, LED, linear scale,
 * thermometer, and a small markdown subset that never inserts HTML.
 */

export const pieChartSchema: JsonSchema = {
  type: "object",
  title: "Pie chart",
  additionalProperties: false,
  properties: {
    title: { type: "string", title: "Title", default: "Pie" },
    slices: { type: "string", title: "Slices (Name:value, Name:value)", default: "Run:3, Idle:1" },
  },
};

export const rangeSelectorSchema: JsonSchema = {
  type: "object",
  title: "Range selector",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Window" },
    points: { type: "string", title: "Points", default: "1,2,3,2,4" },
    start: { type: "number", title: "Start", default: 0 },
    end: { type: "number", title: "End", default: 4 },
  },
};

export const ledSchema: JsonSchema = {
  type: "object",
  title: "LED display",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Value", default: "12.4" },
    digits: { type: "integer", title: "Digits", minimum: 1, maximum: 16, default: 6 },
  },
};

export const linearScaleSchema: JsonSchema = {
  type: "object",
  title: "Linear scale",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Scale" },
    value: { type: "number", title: "Value", default: 40 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
  },
};

export const thermometerSchema: JsonSchema = {
  type: "object",
  title: "Thermometer",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Temperature" },
    value: { type: "number", title: "Value", default: 21 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
  },
};

export const markdownSchema: JsonSchema = {
  type: "object",
  title: "Markdown",
  additionalProperties: false,
  properties: {
    text: { type: "string", title: "Text", default: "# Note\nA **short** note." },
  },
};

export const viewCanvasSchema: JsonSchema = {
  type: "object",
  title: "View canvas",
  additionalProperties: false,
  properties: {
    instances: { type: "string", title: "Instances (path x y width height)", default: "" },
  },
};

export function parseSlices(text: string): Array<{ name: string; value: number }> {
  return text.split(",").flatMap((part) => {
    const [name, raw] = part.split(":");
    const value = Number(raw);
    if (!name?.trim() || !Number.isFinite(value) || value < 0) return [];
    return [{ name: name.trim(), value }];
  });
}

export function PieChart({ props }: HmiComponentProps) {
  const title = readString(props, "title", "Pie");
  const slices = parseSlices(readString(props, "slices"));
  const total = slices.reduce((sum, slice) => sum + slice.value, 0);
  let angle = -Math.PI / 2;
  const paths = slices.map((slice) => {
    const sweep = total === 0 ? 0 : (slice.value / total) * Math.PI * 2;
    const start = angle;
    angle += sweep;
    const large = sweep > Math.PI ? 1 : 0;
    const x1 = 50 + 40 * Math.cos(start);
    const y1 = 50 + 40 * Math.sin(start);
    const x2 = 50 + 40 * Math.cos(start + sweep);
    const y2 = 50 + 40 * Math.sin(start + sweep);
    return { name: slice.name, d: `M 50 50 L ${x1} ${y1} A 40 40 0 ${large} 1 ${x2} ${y2} Z` };
  });
  return (
    <figure className="oip-pie" aria-label={title}>
      <figcaption>{title}</figcaption>
      <svg viewBox="0 0 100 100" role="img" aria-label={title}>
        {paths.map((path, index) => (
          <path key={path.name} d={path.d} data-slice={path.name} fill={`var(--oip-color-${index % 2 === 0 ? "accent" : "muted"})`} />
        ))}
      </svg>
    </figure>
  );
}

export function RangeSelector({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Window");
  const points = readString(props, "points")
    .split(",")
    .map((part) => Number(part.trim()))
    .filter((value) => Number.isFinite(value));
  const start = Math.max(0, Math.round(readNumber(props, "start", 0)));
  const end = Math.max(start, Math.round(readNumber(props, "end", Math.max(0, points.length - 1))));
  return (
    <div className="oip-range" aria-label={label}>
      <svg viewBox="0 0 100 40" role="img" aria-label={label}>
        {points.map((point, index) => {
          const x = points.length <= 1 ? 0 : (index / (points.length - 1)) * 100;
          const y = 36 - Math.min(32, Math.max(0, point));
          return <circle key={index} cx={x} cy={y} r={1.5} data-in-window={index >= start && index <= end ? "true" : "false"} />;
        })}
      </svg>
      <label>
        Start
        <input aria-label="Range start" type="number" value={start} onChange={(event) => onChange("start", Number(event.target.value))} />
      </label>
      <label>
        End
        <input aria-label="Range end" type="number" value={end} onChange={(event) => onChange("end", Number(event.target.value))} />
      </label>
    </div>
  );
}

export function LedDisplay({ props }: HmiComponentProps) {
  const digits = Math.max(1, Math.min(16, Math.round(readNumber(props, "digits", 6))));
  const raw = readString(props, "value");
  const shown = raw.slice(0, digits).padStart(digits, " ");
  return (
    <div className="oip-led" data-digits={digits} aria-label={raw || "LED"}>
      {shown}
    </div>
  );
}

export function LinearScale({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Scale");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const value = readNumber(props, "value", min);
  const span = max === min ? 1 : max - min;
  const x = Math.min(100, Math.max(0, ((value - min) / span) * 100));
  return (
    <div className="oip-scale" aria-label={label}>
      <span>{label}</span>
      <svg viewBox="0 0 100 16" role="img" aria-label={`${label} ${value}`}>
        <line x1="0" y1="8" x2="100" y2="8" />
        <circle cx={x} cy="8" r="3" data-value={value} />
      </svg>
    </div>
  );
}

export function Thermometer({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Temperature");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const value = readNumber(props, "value", min);
  const span = max === min ? 1 : max - min;
  const height = Math.min(36, Math.max(0, ((value - min) / span) * 36));
  return (
    <figure className="oip-thermometer" aria-label={label}>
      <figcaption>{label}</figcaption>
      <svg viewBox="0 0 20 48" role="img" aria-label={`${label} ${value}`}>
        <rect x="7" y="2" width="6" height="36" fill="none" stroke="currentColor" />
        <rect x="8" y={38 - height} width="4" height={height} data-fill="level" />
      </svg>
    </figure>
  );
}

/** A tiny markdown subset: headings, paragraphs, and **bold**. HTML is shown as text. */
export function renderMarkdown(text: string): Array<{ kind: "h" | "p"; parts: Array<{ bold: boolean; text: string }> }> {
  return text.split("\n").flatMap((line) => {
    const trimmed = line.trim();
    if (!trimmed) return [];
    const heading = /^#{1,3}\s+(.*)$/.exec(trimmed);
    const body = heading ? heading[1]! : trimmed;
    const parts: Array<{ bold: boolean; text: string }> = [];
    const pattern = /\*\*([^*]+)\*\*/g;
    let cursor = 0;
    for (const match of body.matchAll(pattern)) {
      const index = match.index ?? 0;
      if (index > cursor) parts.push({ bold: false, text: body.slice(cursor, index) });
      parts.push({ bold: true, text: match[1]! });
      cursor = index + match[0].length;
    }
    if (cursor < body.length) parts.push({ bold: false, text: body.slice(cursor) });
    return [{ kind: heading ? "h" as const : "p" as const, parts }];
  });
}

export function Markdown({ props }: HmiComponentProps) {
  const blocks = renderMarkdown(readString(props, "text"));
  return (
    <div className="oip-markdown">
      {blocks.map((block, index) => {
        const children = block.parts.map((part, partIndex) => (part.bold ? <strong key={partIndex}>{part.text}</strong> : <span key={partIndex}>{part.text}</span>));
        return block.kind === "h" ? <h3 key={index}>{children}</h3> : <p key={index}>{children}</p>;
      })}
    </div>
  );
}

export function ViewCanvasPlaceholder({ props }: HmiComponentProps) {
  const instances = readString(props, "instances");
  const style: CSSProperties = { minHeight: 40 };
  return (
    <div className="oip-view-canvas" style={style} data-instances={instances ? "yes" : "no"}>
      {instances || "No views"}
    </div>
  );
}
