import { useEffect, useId, useRef, useState } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { pushBounded } from "./chartMath";
import { formatNumber, labelText, readBoolean, readNumber, readString } from "./read";

export function Label({ props }: HmiComponentProps) {
  const align = readString(props, "align", "start");
  return (
    <div className={`oip-label oip-align-${align}`} data-align={align}>
      {labelText(props, "text", "")}
    </div>
  );
}

export function NumericDisplay({ nodeId, props, locale }: HmiComponentProps) {
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const raw = props.value;
  const numeric = typeof raw === "number" && Number.isFinite(raw) ? raw : null;
  const text = numeric === null ? "—" : formatNumber(numeric, decimals, locale);
  const prefix = readString(props, "prefix");
  const suffix = readString(props, "suffix");
  const announce = readBoolean(props, "announce", true);
  // One line that never grows past the frame: a value too long for it ends in an ellipsis, and the tooltip has it whole.
  return (
    <div className="oip-numeric">
      <span
        id={`${nodeId}-value`}
        data-testid="numeric-value"
        data-value={numeric === null ? "" : String(numeric)}
        title={`${prefix}${text}${suffix}`}
        aria-live={announce ? "polite" : "off"}
      >
        {prefix}
        {text}
        {suffix}
      </span>
    </div>
  );
}

export function Button({ props, onChange }: HmiComponentProps) {
  const enabled = readBoolean(props, "enabled", true);
  const text = readString(props, "text", "Button");
  return (
    <button
      type="button"
      className="oip-button"
      disabled={!enabled}
      onClick={() => {
        if (!enabled || !("writeValue" in props)) return;
        onChange("value", props.writeValue);
      }}
    >
      {text}
    </button>
  );
}

export function Toggle({ props, onChange }: HmiComponentProps) {
  const value = readBoolean(props, "value", false);
  const label = readString(props, "label", "Toggle");
  return (
    <button
      type="button"
      className="oip-toggle"
      role="switch"
      aria-checked={value}
      onClick={() => onChange("value", !value)}
    >
      <span className="oip-toggle-track" aria-hidden="true">
        <span className="oip-toggle-knob" />
      </span>
      <span>{label}</span>
    </button>
  );
}

export function TextInput({ nodeId, props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Text");
  const value = readString(props, "value", "");
  const placeholder = readString(props, "placeholder");
  const maxLength = Math.round(readNumber(props, "maxLength", 120));
  const [draft, setDraft] = useState(value);
  const [focused, setFocused] = useState(false);
  const fieldId = useId();
  useEffect(() => {
    if (!focused) setDraft(value);
  }, [value, focused]);
  function commit() {
    onChange("value", draft);
  }
  return (
    <div className="oip-text">
      <label htmlFor={fieldId || nodeId}>{label}</label>
      <input
        id={fieldId || nodeId}
        value={draft}
        placeholder={placeholder}
        maxLength={maxLength}
        onFocus={() => setFocused(true)}
        onBlur={() => {
          setFocused(false);
          commit();
        }}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          }
        }}
      />
    </div>
  );
}

export function Indicator({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Status");
  const onText = readString(props, "onText", "On");
  const offText = readString(props, "offText", "Off");
  const known = typeof props.value === "boolean";
  const on = props.value === true;
  const state = !known ? "unknown" : on ? "on" : "off";
  const caption = !known ? "Unknown" : on ? onText : offText;
  return (
    <div className="oip-indicator" role="status" aria-label={`${label}: ${caption}`} data-state={state}>
      <span className="oip-indicator-lamp" aria-hidden="true" />
      <span className="oip-indicator-copy">
        <span className="oip-indicator-label">{label}</span>
        <span className="oip-indicator-state">{caption}</span>
      </span>
    </div>
  );
}

export function LinearGauge({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Level");
  const unit = readString(props, "unit");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const span = max === min ? 1 : max - min;
  const raw = props.value;
  const numeric = typeof raw === "number" && Number.isFinite(raw) ? raw : null;
  const clamped = numeric === null ? min : Math.min(max, Math.max(min, numeric));
  const percent = ((clamped - min) / span) * 100;
  const shown = numeric === null ? "—" : `${formatNumber(numeric, 1, locale)}${unit ? ` ${unit}` : ""}`;
  return (
    <div
      className="oip-gauge"
      role="meter"
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={numeric === null ? undefined : clamped}
      aria-valuetext={shown}
    >
      <div className="oip-gauge-head">
        <span className="oip-gauge-label" title={label}>
          {label}
        </span>
        <span className="oip-gauge-value" title={shown}>
          {shown}
        </span>
      </div>
      <div className="oip-gauge-track" aria-hidden="true">
        <div className="oip-gauge-fill" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

export function TrendLine({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Trend");
  const capacity = Math.max(2, Math.round(readNumber(props, "capacity", 30)));
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const raw = props.value;
  const numeric = typeof raw === "number" && Number.isFinite(raw) ? raw : null;
  const points = useRef<number[]>([]);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (numeric === null) return;
    points.current = pushBounded(points.current, numeric, capacity);
    setTick((value) => value + 1);
  }, [numeric, capacity]);
  const series = points.current;
  const high = max === min ? min + 1 : max;
  const coords = series
    .map((value, index) => {
      const x = series.length === 1 ? 0 : (index / (series.length - 1)) * 100;
      const y = 100 - ((Math.min(high, Math.max(min, value)) - min) / (high - min)) * 100;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(" ");
  const latest = series.length === 0 ? "no samples" : formatNumber(series[series.length - 1]!, 1, locale);
  return (
    <div className="oip-trend" role="img" aria-label={`${label}, latest ${latest}`} data-points={series.length} data-tick={tick}>
      <div className="oip-trend-head">
        <span>{label}</span>
        <span>{latest}</span>
      </div>
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <polyline points={coords} />
      </svg>
    </div>
  );
}
