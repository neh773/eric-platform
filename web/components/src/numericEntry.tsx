import { useEffect, useId, useRef, useState } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Numeric entry field (HMP-055): an input for setpoints. The entry is an integer or a decimal number, shown in a
 * display format (decimals, digit grouping, prefix and suffix) while the field is not being edited, and checked
 * against an optional minimum and maximum. A valid entry is written on commit (Enter or leaving the field) through
 * the `value` property, so a bidirectional tag binding on `value` writes the tag. A non-numeric, non-integer (for
 * the integer type) or out-of-range entry is refused: nothing is written, the field keeps the entry, marks itself
 * invalid and says why. Escape cancels the edit and shows the current value again.
 *
 * Doc source: FEATURES HMP-055.
 */

export type NumericEntryType = "integer" | "decimal";

export const numericEntrySchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Numeric entry field",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    label: { type: "string", title: "Label", default: "Setpoint" },
    inputType: { type: "string", title: "Type", enum: ["integer", "decimal"], default: "decimal" },
    decimals: { type: "integer", title: "Decimals shown", minimum: 0, maximum: 6, default: 2 },
    grouping: { type: "boolean", title: "Digit grouping", default: false },
    prefix: { type: "string", title: "Prefix", default: "" },
    suffix: { type: "string", title: "Suffix", default: "" },
    limitMin: { type: "boolean", title: "Limit minimum", default: true },
    minimum: { type: "number", title: "Minimum", default: 0 },
    limitMax: { type: "boolean", title: "Limit maximum", default: true },
    maximum: { type: "number", title: "Maximum", default: 100 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export interface NumericEntryOptions {
  inputType: NumericEntryType;
  minimum: number | null;
  maximum: number | null;
}

export type ParsedEntry = { ok: true; value: number } | { ok: false; reason: string };

/** The decimal separator of [locale]. */
function decimalSeparator(locale: string): string {
  return new Intl.NumberFormat(locale).formatToParts(1.5).find((part) => part.type === "decimal")?.value ?? ".";
}

/**
 * Reads what the operator typed. The locale's decimal separator is accepted, and so is `.`; spaces around the
 * number are ignored. Digit grouping is not accepted while typing (the edit text shows none), so `1,234` is never
 * read as either 1.234 or 1234. Anything else that is not a plain number (letters, two separators, an exponent,
 * `Infinity`) is refused, as are a fraction for the integer type and a value outside the bounds.
 */
export function parseNumericEntry(text: string, locale: string, options: NumericEntryOptions): ParsedEntry {
  const decimal = decimalSeparator(locale);
  let cleaned = text.trim();
  if (decimal !== ".") {
    if (cleaned.includes(decimal) && cleaned.includes(".")) return { ok: false, reason: "Enter a number." };
    cleaned = cleaned.split(decimal).join(".");
  }
  if (!cleaned) return { ok: false, reason: "Enter a number." };
  if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(cleaned)) return { ok: false, reason: "Enter a number." };
  const value = Number(cleaned);
  if (!Number.isFinite(value)) return { ok: false, reason: "Enter a number." };
  if (options.inputType === "integer" && !Number.isInteger(value)) return { ok: false, reason: "Enter a whole number." };
  if (options.inputType === "integer" && !Number.isSafeInteger(value)) return { ok: false, reason: "The number is too large." };
  if (options.minimum !== null && value < options.minimum) return { ok: false, reason: `Enter ${String(options.minimum)} or more.` };
  if (options.maximum !== null && value > options.maximum) return { ok: false, reason: `Enter ${String(options.maximum)} or less.` };
  return { ok: true, value: Object.is(value, -0) ? 0 : value };
}

/** The value as the field shows it while not being edited. */
export function formatNumericEntry(
  value: number | null,
  locale: string,
  format: { inputType: NumericEntryType; decimals: number; grouping: boolean; prefix?: string; suffix?: string },
): string {
  if (value === null) return "";
  const digits = format.inputType === "integer" ? 0 : Math.min(6, Math.max(0, Math.round(format.decimals)));
  const text = new Intl.NumberFormat(locale, { minimumFractionDigits: digits, maximumFractionDigits: digits, useGrouping: format.grouping }).format(value);
  return `${format.prefix ?? ""}${text}${format.suffix ?? ""}`;
}

/** The value as the field shows it while being edited: every digit, the locale's decimal separator, no grouping. */
function editText(value: number | null, locale: string): string {
  if (value === null) return "";
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 20, useGrouping: false }).format(value);
}

export function optionsFrom(props: Record<string, unknown>): NumericEntryOptions {
  const inputType: NumericEntryType = props.inputType === "integer" ? "integer" : "decimal";
  const minimum = readBoolean(props, "limitMin", true) && typeof props.minimum === "number" && Number.isFinite(props.minimum) ? props.minimum : null;
  const maximum = readBoolean(props, "limitMax", true) && typeof props.maximum === "number" && Number.isFinite(props.maximum) ? props.maximum : null;
  return { inputType, minimum, maximum };
}

export function NumericEntryField({ nodeId, props, locale, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Setpoint");
  const enabled = readBoolean(props, "enabled", true);
  const options = optionsFrom(props);
  const value = typeof props.value === "number" && Number.isFinite(props.value) ? props.value : null;
  const shown = formatNumericEntry(value, locale, {
    inputType: options.inputType,
    decimals: readNumber(props, "decimals", 2),
    grouping: readBoolean(props, "grouping", false),
    prefix: readString(props, "prefix"),
    suffix: readString(props, "suffix"),
  });
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const cancelled = useRef(false);
  const fieldId = useId() || nodeId;
  const errorId = `${fieldId}-error`;

  // A new value from the binding clears a refusal that no longer applies, unless the operator is still editing.
  const lastValue = useRef(value);
  useEffect(() => {
    if (lastValue.current === value) return;
    lastValue.current = value;
    if (!editing) setError(null);
  }, [value, editing]);

  /** Writes the entry when it is valid and returns it; otherwise shows why and returns null. */
  function commit(): number | null {
    const parsed = parseNumericEntry(draft, locale, options);
    if (!parsed.ok) {
      setError(parsed.reason);
      return null;
    }
    setError(null);
    if (parsed.value !== value) onChange("value", parsed.value);
    return parsed.value;
  }

  const hint = [options.minimum !== null ? `min ${String(options.minimum)}` : "", options.maximum !== null ? `max ${String(options.maximum)}` : ""]
    .filter(Boolean)
    .join(", ");
  return (
    <div className="oip-numeric-entry" data-invalid={error ? "true" : undefined} data-input-type={options.inputType}>
      <label htmlFor={fieldId}>{label}</label>
      <input
        id={fieldId}
        type="text"
        inputMode={options.inputType === "integer" ? "numeric" : "decimal"}
        autoComplete="off"
        disabled={!enabled}
        value={editing || error ? draft : shown}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        title={hint || undefined}
        data-value={value === null ? "" : String(value)}
        onFocus={() => {
          cancelled.current = false;
          if (!error) setDraft(editText(value, locale));
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
            const written = commit();
            if (written !== null) setDraft(editText(written, locale));
          } else if (event.key === "Escape") {
            event.preventDefault();
            cancelled.current = true;
            setError(null);
            setDraft(editText(value, locale));
            event.currentTarget.blur();
          }
        }}
      />
      {error ? (
        <span id={errorId} className="oip-numeric-entry-error" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}
