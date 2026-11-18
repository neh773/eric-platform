/** Shared helpers for C-11 components: list props, value comparison, and theme-aware colors. */

export interface Choice {
  value: string | number | boolean;
  label: string;
}

/** Reads a list of choices from a bound array (`[{ value, label }]`, `[{ value, text }]` or plain values)
 * or from inspector text (`"a:Alpha, b:Beta"`). Duplicate values keep the first entry.
 * With `numeric`, text values that look like numbers become numbers (multi-state buttons write numbers).
 */
export function readChoices(raw: unknown, numeric = false): Choice[] {
  const out: Choice[] = [];
  const seen = new Set<string>();
  const push = (value: unknown, label: unknown) => {
    if (typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") return;
    if (typeof value === "number" && !Number.isFinite(value)) return;
    const key = String(value);
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ value, label: typeof label === "string" && label !== "" ? label : key });
  };
  if (Array.isArray(raw)) {
    for (const entry of raw) {
      if (entry !== null && typeof entry === "object" && !Array.isArray(entry)) {
        const record = entry as Record<string, unknown>;
        push(record.value, record.label ?? record.text);
      } else {
        push(entry, undefined);
      }
    }
    return out;
  }
  if (typeof raw !== "string") return out;
  for (const part of raw.split(",")) {
    const item = part.trim();
    if (!item) continue;
    const colon = item.indexOf(":");
    const valueText = (colon < 0 ? item : item.slice(0, colon)).trim();
    const label = colon < 0 ? valueText : item.slice(colon + 1).trim();
    if (!valueText) continue;
    const asNumber = Number(valueText);
    push(numeric && Number.isFinite(asNumber) ? asNumber : valueText, label);
  }
  return out;
}

/** Reads numeric samples from a bound array (numbers or `{ value }` records) or from text (`"1, 2.5, 3"`). */
export function readPoints(raw: unknown): number[] {
  const items: unknown[] = Array.isArray(raw) ? raw : typeof raw === "string" ? raw.split(",") : [];
  const out: number[] = [];
  for (const item of items) {
    const candidate =
      item !== null && typeof item === "object" && !Array.isArray(item) ? (item as Record<string, unknown>).value : item;
    if (typeof candidate === "string" && candidate.trim() === "") continue;
    const value = typeof candidate === "number" ? candidate : typeof candidate === "string" ? Number(candidate) : Number.NaN;
    if (Number.isFinite(value)) out.push(value);
  }
  return out;
}

/** Tag values arrive as numbers, strings or booleans. A one-shot written `1` matches a tag reading `1` or `"1"`. */
export function sameValue(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  const primitive = (value: unknown) => typeof value === "number" || typeof value === "string" || typeof value === "boolean";
  return primitive(left) && primitive(right) && String(left) === String(right);
}

const tokens = new Set(["ink", "muted", "accent", "accent-ink", "ok", "alarm", "warning", "idle", "line", "surface", "canvas"]);

/** A theme token name maps to the session's CSS variable, so the color follows daylight / nightwatch.
 * Literal colors are accepted only in plain hex, rgb()/hsl() or keyword form. Anything else uses the fallback.
 */
export function themeColor(value: unknown, fallback: string): string {
  if (typeof value !== "string") return fallback;
  const color = value.trim();
  if (!color) return fallback;
  if (tokens.has(color)) return `var(--oip-color-${color})`;
  if (/^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(color)) return color;
  if (/^(?:rgb|rgba|hsl|hsla)\([0-9.,%\s/]+\)$/i.test(color)) return color;
  if (/^[a-z]{3,20}$/i.test(color)) return color;
  return fallback;
}
