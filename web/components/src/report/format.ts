import type { ColumnType, JsonValue } from "./types";

/**
 * Locale-neutral formatting, identical to gateway/report-render Formatting.kt:
 * numbers use `.` and optional `,` grouping with HALF_UP rounding (default: at most 3 decimals, trailing zeros
 * removed); timestamps use the tokens yyyy MM dd HH mm ss SSS in the layout timezone.
 */

export const DEFAULT_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";
const AUTO_DECIMALS = 3;

export type FormatKind = "auto" | "text" | "number" | "timestamp" | "boolean";

export interface CellFormat {
  type: FormatKind;
  decimals?: number;
  grouping: boolean;
  pattern?: string;
  nullText: string;
}

export const defaultCellFormat: CellFormat = { type: "auto", grouping: false, nullText: "" };

/** Plain decimal digits of the shortest round-trip representation (no exponent). */
function plainDecimal(value: number): { negative: boolean; int: string; frac: string } {
  const negative = value < 0 || Object.is(value, -0);
  const text = Math.abs(value).toString();
  const match = /^(\d+)(?:\.(\d+))?(?:e([+-]\d+))?$/.exec(text);
  if (!match) return { negative, int: text, frac: "" };
  const digits = (match[1] ?? "") + (match[2] ?? "");
  let point = (match[1] ?? "").length + Number(match[3] ?? 0);
  let all = digits;
  if (point <= 0) {
    all = "0".repeat(1 - point) + all;
    point = 1;
  }
  if (point > all.length) all += "0".repeat(point - all.length);
  return { negative, int: all.slice(0, point).replace(/^0+(?=\d)/, ""), frac: all.slice(point) };
}

function roundHalfUp(int: string, frac: string, decimals: number): { int: string; frac: string } {
  if (frac.length <= decimals) return { int, frac: frac.padEnd(decimals, "0") };
  const keep = int + frac.slice(0, decimals);
  const roundUp = (frac.charCodeAt(decimals) - 48) >= 5;
  let digits = keep;
  if (roundUp) {
    const arr = keep.split("");
    let i = arr.length - 1;
    while (i >= 0) {
      if (arr[i] === "9") {
        arr[i] = "0";
        i--;
      } else {
        arr[i] = String(Number(arr[i]) + 1);
        break;
      }
    }
    digits = (i < 0 ? "1" : "") + arr.join("");
  }
  const intLen = digits.length - decimals;
  return { int: digits.slice(0, intLen) || "0", frac: digits.slice(intLen) };
}

export function formatReportNumber(value: number, decimals: number | undefined, grouping: boolean): string {
  if (Number.isNaN(value)) return "NaN";
  if (!Number.isFinite(value)) return value > 0 ? "Infinity" : "-Infinity";
  const { negative, int, frac } = plainDecimal(value);
  let rounded = roundHalfUp(int, frac, decimals === undefined ? AUTO_DECIMALS : Math.min(10, Math.max(0, decimals)));
  if (decimals === undefined) rounded = { int: rounded.int, frac: rounded.frac.replace(/0+$/, "") };
  const isZero = /^0*$/.test(rounded.int + rounded.frac);
  const intText = grouping ? rounded.int.replace(/\B(?=(\d{3})+(?!\d))/g, ",") : rounded.int;
  return (negative && !isZero ? "-" : "") + intText + (rounded.frac ? `.${rounded.frac}` : "");
}

const partsCache = new Map<string, Intl.DateTimeFormat>();

function zoneFormat(timeZone: string): Intl.DateTimeFormat {
  let format = partsCache.get(timeZone);
  if (!format) {
    format = new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    });
    partsCache.set(timeZone, format);
  }
  return format;
}

function zonedParts(epochMillis: number, timeZone: string) {
  const parts: Record<string, string> = {};
  for (const part of zoneFormat(timeZone).formatToParts(new Date(epochMillis))) parts[part.type] = part.value;
  const ms = ((epochMillis % 1000) + 1000) % 1000;
  return {
    yyyy: (parts.year ?? "").padStart(4, "0"),
    MM: parts.month ?? "",
    dd: parts.day ?? "",
    HH: parts.hour === "24" ? "00" : (parts.hour ?? ""),
    mm: parts.minute ?? "",
    ss: parts.second ?? "",
    SSS: String(ms).padStart(3, "0"),
  };
}

/** Offset of [timeZone] from UTC at [epochMillis], in milliseconds. */
export function zoneOffsetMillis(epochMillis: number, timeZone: string): number {
  const p = zonedParts(epochMillis, timeZone);
  const local = Date.UTC(Number(p.yyyy), Number(p.MM) - 1, Number(p.dd), Number(p.HH), Number(p.mm), Number(p.ss));
  return local - (epochMillis - (((epochMillis % 1000) + 1000) % 1000));
}

const TOKENS = ["yyyy", "SSS", "MM", "dd", "HH", "mm", "ss"] as const;

export function formatTimestamp(epochMillis: number, pattern: string, timeZone: string): string {
  const parts = zonedParts(epochMillis, timeZone);
  let out = "";
  let i = 0;
  while (i < pattern.length) {
    const token = TOKENS.find((t) => pattern.startsWith(t, i));
    if (token) {
      out += parts[token];
      i += token.length;
    } else {
      out += pattern[i];
      i++;
    }
  }
  return out;
}

export function formatCell(value: JsonValue | undefined, columnType: ColumnType, format: CellFormat, timeZone: string): string {
  if (value === null || value === undefined) return format.nullText;
  let kind = format.type;
  if (kind === "auto") {
    kind = columnType === "number" ? "number" : columnType === "timestamp" ? "timestamp" : columnType === "boolean" ? "boolean" : "text";
  }
  switch (kind) {
    case "number":
      return typeof value === "number" ? formatReportNumber(value, format.decimals, format.grouping) : textOf(value);
    case "timestamp":
      return typeof value === "number" && Number.isInteger(value)
        ? formatTimestamp(value, format.pattern ?? DEFAULT_TIMESTAMP_PATTERN, timeZone)
        : textOf(value);
    case "boolean":
      return typeof value === "boolean" ? String(value) : textOf(value);
    default:
      return textOf(value);
  }
}

function textOf(value: JsonValue): string {
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}
