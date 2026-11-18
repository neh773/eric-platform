import type { CSSProperties } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { formatNumber, readNumber, readString } from "./read";

/**
 * Moving analog indicator (HMP-043): a bar with a pointer that moves along it as the value changes, colored bands
 * for the alarm and warning ranges, and an optional setpoint mark. Written from the public component page; the
 * drawing, code and wording are our own, and every color is a theme token.
 * doc source: FEATURES HMP-043
 *
 * Limits are four optional numbers, `lowLow`, `low`, `high` and `highHigh`. The ranges outside `low` and `high` are
 * warning bands, and the ranges beyond `lowLow` and `highHigh` are alarm bands; a limit left out has no band on its
 * side. The state is written out (`High-high`, `Low`) as well as colored, so it does not depend on color alone.
 */

export const analogIndicatorSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Moving analog indicator",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    label: { type: "string", title: "Label", default: "Level" },
    unit: { type: "string", title: "Unit", default: "" },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
    orientation: { type: "string", title: "Orientation", enum: ["horizontal", "vertical"], default: "horizontal" },
    lowLow: { type: "number", title: "Low-low limit (alarm below)" },
    low: { type: "number", title: "Low limit (warning below)" },
    high: { type: "number", title: "High limit (warning above)" },
    highHigh: { type: "number", title: "High-high limit (alarm above)" },
    setpoint: { type: "number", title: "Setpoint" },
    showBands: { type: "boolean", title: "Show bands", default: true },
  },
};

export type AnalogState = "none" | "normal" | "low" | "low-low" | "high" | "high-high";

export interface AnalogLimits {
  lowLow: number | null;
  low: number | null;
  high: number | null;
  highHigh: number | null;
}

export interface AnalogBand {
  from: number;
  to: number;
  kind: "alarm" | "warning" | "normal";
  zone: "low-low" | "low" | "normal" | "high" | "high-high";
}

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export function readLimits(props: Record<string, unknown>): AnalogLimits {
  return { lowLow: finite(props.lowLow), low: finite(props.low), high: finite(props.high), highHigh: finite(props.highHigh) };
}

/**
 * The bands between [min] and [max], low to high. A band outside the range is clipped to it and a band with no
 * width is left out, so limits that are out of order or outside the scale never draw a negative or reversed band.
 */
export function analogBands(min: number, max: number, limits: AnalogLimits): AnalogBand[] {
  const clip = (value: number) => Math.min(max, Math.max(min, value));
  const lowEdge = limits.low ?? limits.lowLow ?? min;
  const highEdge = limits.high ?? limits.highHigh ?? max;
  const zones: AnalogBand[] = [];
  if (limits.lowLow !== null) zones.push({ from: min, to: limits.lowLow, kind: "alarm", zone: "low-low" });
  if (limits.low !== null) zones.push({ from: limits.lowLow ?? min, to: limits.low, kind: "warning", zone: "low" });
  zones.push({ from: lowEdge, to: highEdge, kind: "normal", zone: "normal" });
  if (limits.high !== null) zones.push({ from: limits.high, to: limits.highHigh ?? max, kind: "warning", zone: "high" });
  if (limits.highHigh !== null) zones.push({ from: limits.highHigh, to: max, kind: "alarm", zone: "high-high" });
  return zones.map((zone) => ({ ...zone, from: clip(zone.from), to: clip(zone.to) })).filter((zone) => zone.to > zone.from);
}

/** Where [value] sits against the limits. `none` when there is no numeric value. */
export function analogState(value: number | null, limits: AnalogLimits): AnalogState {
  if (value === null) return "none";
  if (limits.highHigh !== null && value > limits.highHigh) return "high-high";
  if (limits.high !== null && value > limits.high) return "high";
  if (limits.lowLow !== null && value < limits.lowLow) return "low-low";
  if (limits.low !== null && value < limits.low) return "low";
  return "normal";
}

const STATE_TEXT: Record<AnalogState, string> = {
  none: "No value",
  normal: "Normal",
  low: "Low",
  "low-low": "Low-low",
  high: "High",
  "high-high": "High-high",
};

export function AnalogIndicator({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Level");
  const unit = readString(props, "unit");
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const lower = readNumber(props, "min", 0);
  const upper = readNumber(props, "max", 100);
  const [min, max] = upper > lower ? [lower, upper] : [lower, lower + 1];
  const vertical = props.orientation === "vertical";
  const showBands = props.showBands !== false;
  const limits = readLimits(props);
  const value = finite(props.value);
  const state = analogState(value, limits);
  const span = max - min;
  const pct = (point: number) => ((Math.min(max, Math.max(min, point)) - min) / span) * 100;
  const along = (from: number, to: number): CSSProperties => {
    const start = `${pct(from).toFixed(3)}%`;
    const size = `${Math.max(0, pct(to) - pct(from)).toFixed(3)}%`;
    return vertical ? { bottom: start, height: size } : { left: start, width: size };
  };
  const setpoint = finite(props.setpoint);
  const shown = value === null ? "—" : `${formatNumber(value, decimals, locale)}${unit ? ` ${unit}` : ""}`;
  return (
    <div
      className="oip-analog"
      role="meter"
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value === null ? undefined : Math.min(max, Math.max(min, value))}
      aria-valuetext={value === null ? "No value" : `${shown}, ${STATE_TEXT[state].toLowerCase()}`}
      data-state={state}
      data-orientation={vertical ? "vertical" : "horizontal"}
    >
      <div className="oip-analog-head">
        <span className="oip-analog-label" title={label}>
          {label}
        </span>
        <span className="oip-analog-value" title={shown}>
          {shown}
        </span>
        {state !== "normal" && state !== "none" ? <span className="oip-analog-state">{STATE_TEXT[state]}</span> : null}
      </div>
      <div className="oip-analog-track" aria-hidden="true">
        {showBands
          ? analogBands(min, max, limits).map((band) => (
              <span key={band.zone} className="oip-analog-band" data-kind={band.kind} data-zone={band.zone} style={along(band.from, band.to)} />
            ))
          : null}
        {setpoint !== null ? <span className="oip-analog-setpoint" data-setpoint={setpoint} style={along(setpoint, setpoint)} /> : null}
        {value !== null ? <span className="oip-analog-pointer" style={along(value, value)} /> : null}
      </div>
    </div>
  );
}
