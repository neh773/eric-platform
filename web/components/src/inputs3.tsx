import { useEffect, useId, useRef, useState } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Input components, batch 3: momentary button (HMV-013), two-state toggle (HMV-028), formatted text field (HMV-053),
 * spinner (HMV-059), timer (HMV-073) and barcode scanner input (HMP-057). Every write goes through `onChange`, so a
 * bidirectional tag binding on the property writes the tag; every event goes through `onEvent`, so the node's own
 * event actions and scripts run. Nothing here talks to the gateway on its own.
 *
 * Doc source: FEATURES HMV-013, HMV-028, HMV-053, HMV-059, HMV-073, HMP-057.
 */

const SCHEMA = "https://json-schema.org/draft/2020-12/schema";

export const momentaryButtonSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Momentary button",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    text: { type: "string", title: "Text", default: "Hold" },
    pressValue: { type: "number", title: "Value while pressed", default: 1 },
    releaseValue: { type: "number", title: "Value on release", default: 0 },
    holdMs: { type: "integer", title: "Hold time (ms, 0 = while pressed)", minimum: 0, maximum: 600000, default: 0 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const stateToggleSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "State toggle",
  additionalProperties: false,
  properties: {
    value: { type: "boolean", title: "Written state", default: false },
    confirmed: { type: "boolean", title: "Confirmed state", default: false },
    label: { type: "string", title: "Label", default: "Toggle" },
    onText: { type: "string", title: "Text when on", default: "On" },
    offText: { type: "string", title: "Text when off", default: "Off" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const formattedTextFieldSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Formatted text field",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Value", default: "" },
    label: { type: "string", title: "Label", default: "Code" },
    mask: { type: "string", title: "Mask (# digit, A letter, * any, others literal)", default: "" },
    pattern: { type: "string", title: "Pattern (regular expression)", default: "" },
    hint: { type: "string", title: "Message when refused", default: "" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const spinnerSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Spinner",
  additionalProperties: false,
  properties: {
    value: { title: "Value", default: 0 },
    label: { type: "string", title: "Label", default: "Value" },
    mode: { type: "string", title: "Mode", enum: ["number", "date"], default: "number" },
    minimum: { title: "Minimum", default: 0 },
    maximum: { title: "Maximum", default: 100 },
    step: { type: "number", title: "Step (days for dates)", minimum: 0, default: 1 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const timerSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Timer",
  additionalProperties: false,
  properties: {
    running: { type: "boolean", title: "Running", default: true },
    intervalMs: { type: "integer", title: "Interval (ms)", minimum: 50, maximum: 3600000, default: 1000 },
    ticks: { type: "integer", title: "Tick count", default: 0 },
  },
};

export const barcodeScannerSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Barcode scanner input",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Last scan", default: "" },
    label: { type: "string", title: "Label", default: "Scanner" },
    minLength: { type: "integer", title: "Shortest code", minimum: 1, maximum: 200, default: 4 },
    maxGapMs: { type: "integer", title: "Longest gap between keys (ms)", minimum: 5, maximum: 1000, default: 50 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** Momentary button (HMV-013): writes the press value on press, the release value on release or after the hold time. */
export function MomentaryButton({ props, onChange, onEvent }: HmiComponentProps) {
  const enabled = readBoolean(props, "enabled", true);
  const holdMs = Math.max(0, Math.round(readNumber(props, "holdMs", 0)));
  const pressValue = "pressValue" in props ? props.pressValue : 1;
  const releaseValue = "releaseValue" in props ? props.releaseValue : 0;
  const [down, setDown] = useState(false);
  const downRef = useRef(false);
  const timer = useRef<number | undefined>(undefined);
  const latest = useRef({ onChange, onEvent, releaseValue });
  latest.current = { onChange, onEvent, releaseValue };

  function release() {
    if (!downRef.current) return;
    downRef.current = false;
    window.clearTimeout(timer.current);
    timer.current = undefined;
    setDown(false);
    latest.current.onChange("value", latest.current.releaseValue);
    latest.current.onEvent?.("release");
  }
  function press() {
    if (!enabled || downRef.current) return;
    downRef.current = true;
    setDown(true);
    onChange("value", pressValue);
    onEvent?.("press");
    if (holdMs > 0) timer.current = window.setTimeout(release, holdMs);
  }
  // A button that goes away while held must not leave the value pressed.
  useEffect(() => () => {
    if (downRef.current) {
      downRef.current = false;
      latest.current.onChange("value", latest.current.releaseValue);
    }
    window.clearTimeout(timer.current);
  }, []);
  // Disabling while held releases.
  useEffect(() => {
    if (!enabled) release();
  }, [enabled]);
  return (
    <button
      type="button"
      className="oip-button oip-momentary"
      disabled={!enabled}
      aria-pressed={down}
      data-state={down ? "pressed" : "released"}
      onPointerDown={press}
      onPointerUp={() => {
        if (holdMs === 0) release();
      }}
      onPointerLeave={() => {
        if (holdMs === 0) release();
      }}
      onPointerCancel={() => {
        if (holdMs === 0) release();
      }}
      onBlur={() => {
        if (holdMs === 0) release();
      }}
      onKeyDown={(event) => {
        if ((event.key === " " || event.key === "Enter") && !event.repeat) {
          event.preventDefault();
          press();
        }
      }}
      onKeyUp={(event) => {
        if ((event.key === " " || event.key === "Enter") && holdMs === 0) release();
      }}
    >
      {readString(props, "text", "Hold")}
    </button>
  );
}

/** Two-state toggle (HMV-028): a click writes the opposite of the written state; the lamp shows the confirmed state. */
export function StateToggle({ props, onChange }: HmiComponentProps) {
  const enabled = readBoolean(props, "enabled", true);
  const written = readBoolean(props, "value", false);
  const confirmed = readBoolean(props, "confirmed", false);
  const pending = written !== confirmed;
  return (
    <button
      type="button"
      className="oip-button oip-state-toggle"
      role="switch"
      aria-checked={confirmed}
      disabled={!enabled}
      data-confirmed={confirmed ? "on" : "off"}
      data-pending={pending ? "true" : undefined}
      onClick={() => onChange("value", !written)}
    >
      <span className="oip-state-toggle-lamp" aria-hidden="true" />
      <span>{readString(props, "label", "Toggle")}</span>
      <span className="oip-state-toggle-text">
        {confirmed ? readString(props, "onText", "On") : readString(props, "offText", "Off")}
        {pending ? " (waiting)" : ""}
      </span>
    </button>
  );
}

/** True when [text] fits [mask]: `#` a digit, `A` a letter, `*` any character, anything else itself. */
export function matchesMask(text: string, mask: string): boolean {
  if (text.length !== mask.length) return false;
  for (let i = 0; i < mask.length; i++) {
    const m = mask[i];
    const c = text[i];
    if (m === "#") {
      if (!/^[0-9]$/.test(c)) return false;
    } else if (m === "A") {
      if (!/^\p{L}$/u.test(c)) return false;
    } else if (m !== "*" && m !== c) return false;
  }
  return true;
}

export type PatternResult = { ok: true } | { ok: false; reason: string };

/** Checks [text] against an optional mask and an optional regular expression that must match the whole text. */
export function checkPattern(text: string, mask: string, pattern: string, hint: string): PatternResult {
  if (mask && !matchesMask(text, mask)) return { ok: false, reason: hint || `Enter it as ${mask}.` };
  if (pattern) {
    let regex: RegExp;
    try {
      regex = new RegExp(`^(?:${pattern})$`, "u");
    } catch {
      return { ok: false, reason: "The pattern is not a valid regular expression." };
    }
    if (!regex.test(text)) return { ok: false, reason: hint || "The text does not match the required format." };
  }
  return { ok: true };
}

/** Formatted text field (HMV-053): a commit is written only when it fits the mask and pattern. */
export function FormattedTextField({ nodeId, props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Code");
  const enabled = readBoolean(props, "enabled", true);
  const mask = readString(props, "mask");
  const pattern = readString(props, "pattern");
  const hint = readString(props, "hint");
  const value = readString(props, "value");
  const [draft, setDraft] = useState(value);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancelled = useRef(false);
  const fieldId = useId() || nodeId;
  // A new bound value replaces the draft unless the operator is typing or a refused entry is still on show.
  const lastValue = useRef(value);
  useEffect(() => {
    if (lastValue.current === value) return;
    lastValue.current = value;
    if (!editing && !error) setDraft(value);
  }, [value, editing, error]);
  function commit() {
    const result = checkPattern(draft, mask, pattern, hint);
    if (!result.ok) {
      setError(result.reason);
      return;
    }
    setError(null);
    if (draft !== value) onChange("value", draft);
  }
  return (
    <div className="oip-numeric-entry oip-formatted" data-invalid={error ? "true" : undefined}>
      <label htmlFor={fieldId}>{label}</label>
      <input
        id={fieldId}
        type="text"
        autoComplete="off"
        disabled={!enabled}
        value={draft}
        placeholder={mask || undefined}
        maxLength={mask ? mask.length : undefined}
        aria-invalid={error ? true : undefined}
        onFocus={() => {
          cancelled.current = false;
          setEditing(true);
        }}
        onBlur={() => {
          setEditing(false);
          if (cancelled.current) {
            cancelled.current = false;
            return;
          }
          commit();
        }}
        onChange={(event) => {
          setDraft(event.target.value);
          if (error) setError(null);
        }}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          } else if (event.key === "Escape") {
            event.preventDefault();
            cancelled.current = true;
            setError(null);
            setDraft(value);
            event.currentTarget.blur();
          }
        }}
      />
      {error ? (
        <span className="oip-numeric-entry-error" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

const DAY_MS = 86_400_000;

function parseDay(text: unknown): number | null {
  if (typeof text !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return null;
  const ms = Date.parse(`${text}T00:00:00Z`);
  return Number.isFinite(ms) ? ms : null;
}

function formatDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/** Decimal places in [n] as written, so 0.1 steps do not accumulate float noise. */
function places(n: number): number {
  const text = String(n);
  const dot = text.indexOf(".");
  return dot < 0 ? 0 : text.length - dot - 1;
}

/**
 * The value after [steps] steps (negative goes down), clamped to the bounds. `null` means the current value is not
 * usable. For dates the value is `YYYY-MM-DD` and the step is a whole number of days (at least 1).
 */
export function stepValue(
  value: unknown,
  steps: number,
  options: { mode: "number" | "date"; minimum: unknown; maximum: unknown; step: number },
): number | string | null {
  if (options.mode === "date") {
    const current = parseDay(value);
    if (current === null) return null;
    let next = current + steps * Math.max(1, Math.round(options.step)) * DAY_MS;
    const min = parseDay(options.minimum);
    const max = parseDay(options.maximum);
    if (min !== null) next = Math.max(min, next);
    if (max !== null) next = Math.min(max, next);
    return formatDay(next);
  }
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  const step = options.step > 0 ? options.step : 1;
  let next = value + steps * step;
  next = Number(next.toFixed(Math.max(places(step), places(value))));
  if (typeof options.minimum === "number") next = Math.max(options.minimum, next);
  if (typeof options.maximum === "number") next = Math.min(options.maximum, next);
  return next;
}

/** Spinner (HMV-059): up and down buttons and arrow keys step a number or a date within its bounds. */
export function Spinner({ props, onChange }: HmiComponentProps) {
  const enabled = readBoolean(props, "enabled", true);
  const mode = props.mode === "date" ? "date" : "number";
  const options = { mode, minimum: props.minimum, maximum: props.maximum, step: readNumber(props, "step", 1) } as const;
  const current = props.value;
  const usable = stepValue(current, 0, options) !== null;
  const atMin = usable && stepValue(current, -1, options) === stepValue(current, 0, options);
  const atMax = usable && stepValue(current, 1, options) === stepValue(current, 0, options);
  function step(direction: 1 | -1) {
    if (!enabled) return;
    const next = stepValue(current, direction, options);
    if (next !== null && next !== current) onChange("value", next);
  }
  return (
    <div
      className="oip-spinner"
      role="spinbutton"
      tabIndex={enabled ? 0 : -1}
      aria-label={readString(props, "label", "Value")}
      aria-valuenow={mode === "number" && typeof current === "number" ? current : undefined}
      aria-valuetext={String(current ?? "")}
      aria-valuemin={typeof props.minimum === "number" ? props.minimum : undefined}
      aria-valuemax={typeof props.maximum === "number" ? props.maximum : undefined}
      aria-disabled={!enabled || undefined}
      data-mode={mode}
      onKeyDown={(event) => {
        if (event.key === "ArrowUp") {
          event.preventDefault();
          step(1);
        } else if (event.key === "ArrowDown") {
          event.preventDefault();
          step(-1);
        }
      }}
    >
      <output className="oip-spinner-value">{String(current ?? "")}</output>
      <button type="button" aria-label="Increase" tabIndex={-1} disabled={!enabled || !usable || atMax} onClick={() => step(1)}>
        +
      </button>
      <button type="button" aria-label="Decrease" tabIndex={-1} disabled={!enabled || !usable || atMin} onClick={() => step(-1)}>
        -
      </button>
    </div>
  );
}

/** Timer (HMV-073): draws nothing; while `running`, fires the `tick` event each interval and counts into `ticks`. */
export function Timer({ props, onChange, onEvent }: HmiComponentProps) {
  const running = readBoolean(props, "running", true);
  const intervalMs = Math.max(50, Math.round(readNumber(props, "intervalMs", 1000)));
  const count = useRef(readNumber(props, "ticks", 0));
  count.current = readNumber(props, "ticks", count.current);
  const latest = useRef({ onChange, onEvent });
  latest.current = { onChange, onEvent };
  useEffect(() => {
    if (!running) return undefined;
    const id = window.setInterval(() => {
      count.current += 1;
      latest.current.onChange("ticks", count.current);
      latest.current.onEvent?.("tick");
    }, intervalMs);
    return () => window.clearInterval(id);
  }, [running, intervalMs]);
  return <span className="oip-timer" hidden data-running={running ? "true" : "false"} data-interval-ms={intervalMs} />;
}

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
}

/**
 * Barcode scanner input (HMP-057): a keyboard-wedge scanner types the code as a fast burst of keys ending in Enter.
 * A burst whose keys each follow the last within `maxGapMs` and that is at least `minLength` long is one scan:
 * `value` is written once and `scan` fires once. Slower typing, a short burst and keys typed into a text field are
 * not scans.
 */
export function BarcodeScannerInput({ props, onChange, onEvent }: HmiComponentProps) {
  const enabled = readBoolean(props, "enabled", true);
  const minLength = Math.max(1, Math.round(readNumber(props, "minLength", 4)));
  const maxGapMs = Math.max(5, Math.round(readNumber(props, "maxGapMs", 50)));
  const [last, setLast] = useState("");
  const latest = useRef({ onChange, onEvent });
  latest.current = { onChange, onEvent };
  useEffect(() => {
    if (!enabled) return undefined;
    let buffer = "";
    let at = 0;
    function onKey(event: KeyboardEvent) {
      if (isEditable(event.target) || event.ctrlKey || event.metaKey || event.altKey) return;
      const now = event.timeStamp;
      if (event.key === "Enter") {
        const code = buffer;
        buffer = "";
        if (code.length >= minLength) {
          setLast(code);
          latest.current.onChange("value", code);
          latest.current.onEvent?.("scan");
        }
        return;
      }
      if (event.key.length !== 1) return;
      if (buffer && now - at > maxGapMs) buffer = "";
      buffer += event.key;
      at = now;
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [enabled, minLength, maxGapMs]);
  const shown = typeof props.value === "string" && props.value ? props.value : last;
  return (
    <div className="oip-scanner" data-enabled={enabled ? "true" : "false"}>
      <span className="oip-scanner-label">{readString(props, "label", "Scanner")}</span>
      <output className="oip-scanner-value">{shown || "Ready to scan"}</output>
    </div>
  );
}
