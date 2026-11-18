import { evaluateExpression, type ExpressionScope } from "./expression";
import type { FormatTransform, MapTransform, QualityCode, ScriptTransform, Transform } from "./types";

/** Map, format, and script-hook transforms. Applied in order on the way into a property.
 * They are not reversed on a bidirectional write.
 */

export interface TransformContext {
  locale: string;
  timeZone: string;
  scope: ExpressionScope;
  script?: (hook: string, value: unknown, quality: QualityCode, timestamp: number) => unknown;
  quality: QualityCode;
  timestamp: number;
}

export interface TransformResult {
  value: unknown;
  quality: QualityCode;
  failed: boolean;
  /** A script transform has not answered yet; `value` is its last answer for this property, if any. */
  pending?: boolean;
  /** Why the chain stopped, when the quality code alone is ambiguous. */
  reason?: "script_not_connected";
}

/**
 * What a script host returns while a script transform's answer is on its way (the gateway runs it asynchronously).
 * With [hasLast], the property shows [last], the previous answer, at uncertain quality; without, the authored value.
 */
export class ScriptPending {
  constructor(
    readonly hasLast: boolean = false,
    readonly last: unknown = undefined,
  ) {}
}

/** What a script host returns when it cannot reach the gateway that runs script transforms. */
export class ScriptUnavailable {
  constructor(readonly reason: string = "script host is not connected") {}
}

export function applyTransforms(value: unknown, transforms: readonly Transform[] | undefined, ctx: TransformContext): TransformResult {
  if (!transforms || transforms.length === 0) return { value, quality: ctx.quality, failed: false };
  let current = value;
  const quality = ctx.quality;
  let pending = false;
  for (const transform of transforms) {
    // No script host (the Designer, or a session without one): a clear "not connected" quality, not an exception.
    if (transform.kind === "script" && !ctx.script) return { value, quality: "bad_not_connected", failed: true, reason: "script_not_connected" };
    try {
      current = applyOne(current, transform, quality, ctx);
    } catch {
      return { value, quality: "bad", failed: true };
    }
    // A script transform the gateway has not answered yet, or cannot run now.
    if (current instanceof ScriptUnavailable) return { value, quality: "bad_not_connected", failed: true, reason: "script_not_connected" };
    if (current instanceof ScriptPending) {
      if (!current.hasLast) return { value, quality: "uncertain", failed: true, pending: true };
      // Show the previous answer, run through the rest of the chain, at uncertain quality until the new one arrives.
      current = current.last;
      pending = true;
    }
  }
  if (pending) return { value: current, quality: quality === "good" ? "uncertain" : quality, failed: false, pending: true };
  return { value: current, quality, failed: false };
}

/** One transform step as the Designer previews it: the value going in and the value coming out. */
export interface TransformStep {
  kind: Transform["kind"];
  input: unknown;
  output: unknown;
  /** `not_connected`: a script step with no script host. `skipped`: an earlier step stopped the chain. */
  status: "ok" | "error" | "not_connected" | "skipped";
  error?: string;
}

/** Same evaluation as `applyTransforms`, step by step. Never throws. */
export function transformSteps(value: unknown, transforms: readonly Transform[] | undefined, ctx: TransformContext): TransformStep[] {
  const steps: TransformStep[] = [];
  let current = value;
  let stopped = false;
  for (const transform of transforms ?? []) {
    if (stopped) {
      steps.push({ kind: transform.kind, input: undefined, output: undefined, status: "skipped" });
      continue;
    }
    if (transform.kind === "script" && !ctx.script) {
      steps.push({ kind: "script", input: current, output: undefined, status: "not_connected" });
      stopped = true;
      continue;
    }
    try {
      const output = applyOne(current, transform, ctx.quality, ctx);
      if (output instanceof ScriptUnavailable) {
        steps.push({ kind: transform.kind, input: current, output: undefined, status: "not_connected" });
        stopped = true;
        continue;
      }
      if (output instanceof ScriptPending && !output.hasLast) {
        steps.push({ kind: transform.kind, input: current, output: undefined, status: "error", error: "waiting for the gateway to answer" });
        stopped = true;
        continue;
      }
      const shown = output instanceof ScriptPending ? output.last : output;
      steps.push({ kind: transform.kind, input: current, output: shown, status: "ok" });
      current = shown;
    } catch (error) {
      steps.push({ kind: transform.kind, input: current, output: undefined, status: "error", error: error instanceof Error ? error.message : String(error) });
      stopped = true;
    }
  }
  return steps;
}

function applyOne(value: unknown, transform: Transform, quality: QualityCode, ctx: TransformContext): unknown {
  if (transform.kind === "map") return applyMap(value, transform, ctx.scope);
  if (transform.kind === "format") return applyFormat(value, transform, ctx.locale, ctx.timeZone);
  if (transform.kind === "expression") return applyExpression(value, transform.expression, ctx.scope);
  return applyScript(value, transform, quality, ctx);
}

/** `{value}` is the incoming value. An empty expression or a failed one stops the chain. */
export function applyExpression(value: unknown, expression: string, scope: ExpressionScope): unknown {
  if (expression.trim() === "") throw new Error("expression is empty");
  return evaluateExpression(expression, valueScope(scope, value));
}

export function applyMap(value: unknown, transform: MapTransform, scope: ExpressionScope): unknown {
  for (const entry of transform.entries) {
    if (!mapMatches(value, entry.input, scope)) continue;
    if (entry.output.type === "value") return entry.output.value;
    return evaluateExpression(entry.output.expression, valueScope(scope, value));
  }
  if (transform.hasFallback) return transform.fallback;
  return value;
}

function mapMatches(value: unknown, input: MapTransform["entries"][number]["input"], scope: ExpressionScope): boolean {
  if (input.type === "value") return Object.is(value, input.value);
  if (input.type === "expression") return truthy(evaluateExpression(input.expression, valueScope(scope, value)));
  if (typeof value !== "number" || !Number.isFinite(value)) return false;
  if (input.min !== null) {
    if (input.minInclusive ? value < input.min : value <= input.min) return false;
  }
  if (input.max !== null) {
    if (input.maxInclusive ? value > input.max : value >= input.max) return false;
  }
  return true;
}

function valueScope(scope: ExpressionScope, value: unknown): ExpressionScope {
  return {
    resolve(ref: string) {
      if (ref === "value") return value;
      return scope.resolve(ref);
    },
  };
}

function truthy(value: unknown): boolean {
  return value !== null && value !== false && value !== 0 && value !== "";
}

export function applyFormat(value: unknown, transform: FormatTransform, locale: string, sessionTimeZone: string): string {
  if (transform.type === "datetime") return formatDate(value, transform, locale, sessionTimeZone);
  return formatNumber(value, transform, locale);
}

function formatNumber(value: unknown, transform: FormatTransform, locale: string): string {
  const numeric = typeof value === "number" ? value : typeof value === "string" && value.trim() !== "" ? Number(value) : Number.NaN;
  if (!Number.isFinite(numeric)) return "";
  if (transform.mode === "integer") return String(roundHalfAway(numeric, 0));
  if (transform.mode === "percent") {
    const scaled = roundHalfAway(numeric * 100, 1);
    const text = Number.isInteger(scaled) ? String(scaled) : scaled.toFixed(1);
    return `${trimTrailingZero(text)}%`;
  }
  if (transform.mode === "currency") {
    return new Intl.NumberFormat(locale, { style: "currency", currency: transform.currency ?? "USD" }).format(numeric);
  }
  if (transform.mode === "number") {
    return new Intl.NumberFormat(locale, { maximumFractionDigits: 6 }).format(numeric);
  }
  return formatNumericPattern(numeric, transform.pattern ?? "#");
}

/** `0` / `#` / grouping pattern. Half away from zero. Extra fraction digits are rounded, not kept.
 * The public example `#.00` applied to -35.893925 is "-35.89".
 */
export function formatNumericPattern(value: number, pattern: string): string {
  const sections = pattern.split(";");
  const negative = value < 0 && sections.length > 1;
  const chosen = negative ? sections[1]! : sections[0]!;
  const body = formatAbsolute(Math.abs(value), chosen);
  if (value < 0 && !negative) return body === "" ? "" : `-${body}`;
  return body;
}

function formatAbsolute(value: number, pattern: string): string {
  const match = /^(.*?)([0#,.]*)(.*)$/.exec(pattern);
  const prefix = match?.[1] ?? "";
  const number = match?.[2] ?? "";
  const suffix = match?.[3] ?? "";
  if (!/[0#]/.test(number)) return `${prefix}${value}${suffix}`;
  const dot = number.indexOf(".");
  const intPat = dot >= 0 ? number.slice(0, dot) : number;
  const fracPat = dot >= 0 ? number.slice(dot + 1) : "";
  const scale = fracPat.length;
  const requiredFrac = fracPat.match(/0/g)?.length ?? 0;
  const minInt = intPat.match(/0/g)?.length ?? (intPat.length === 0 ? 0 : 1);
  const grouping = intPat.includes(",");
  const scaled = roundHalfAway(value, scale);
  const negativeZero = Object.is(scaled, -0);
  const abs = Math.abs(scaled);
  const factor = 10 ** scale;
  const digits = Math.round(abs * factor + 1e-8);
  let intDigits = scale === 0 ? String(digits) : String(Math.floor(digits / factor));
  let fracDigits = scale === 0 ? "" : String(digits % factor).padStart(scale, "0");
  if (intDigits === "0" && minInt === 0 && intPat.length === 0) intDigits = "";
  while (intDigits.length < minInt) intDigits = `0${intDigits}`;
  if (requiredFrac < fracDigits.length) {
    let keep = fracDigits.length;
    while (keep > requiredFrac && fracDigits[keep - 1] === "0") keep -= 1;
    fracDigits = fracDigits.slice(0, keep);
  }
  if (grouping) intDigits = groupThousands(intDigits);
  const core = fracDigits.length > 0 ? `${intDigits || "0"}.${fracDigits}` : intDigits || (minInt > 0 ? "0" : "0");
  return `${negativeZero ? "-" : ""}${prefix}${core}${suffix}`;
}

function groupThousands(digits: string): string {
  const sign = digits.startsWith("-") ? "-" : "";
  const body = sign ? digits.slice(1) : digits;
  const parts: string[] = [];
  for (let i = body.length; i > 0; i -= 3) parts.unshift(body.slice(Math.max(0, i - 3), i));
  return `${sign}${parts.join(",")}`;
}

function roundHalfAway(value: number, digits: number): number {
  const factor = 10 ** digits;
  const signed = value < 0 ? -1 : 1;
  const rounded = Math.round(Math.abs(value) * factor + 1e-8) / factor;
  return signed * rounded;
}

function trimTrailingZero(text: string): string {
  return text.endsWith(".0") ? text.slice(0, -2) : text;
}

function formatDate(value: unknown, transform: FormatTransform, locale: string, sessionTimeZone: string): string {
  const date = coerceDate(value);
  if (!date) return "";
  const timeZone = transform.timeZone && transform.timeZone !== "auto" ? transform.timeZone : sessionTimeZone;
  if (transform.mode === "pattern") return formatDatePattern(date, transform.pattern ?? "yyyy-MM-dd HH:mm:ss", timeZone);
  const style = transform.style ?? "medium";
  const options: Intl.DateTimeFormatOptions = { timeZone };
  if (transform.mode === "date") options.dateStyle = style;
  else if (transform.mode === "time") options.timeStyle = style;
  else {
    options.dateStyle = style;
    options.timeStyle = style;
  }
  return new Intl.DateTimeFormat(locale, options).format(date);
}

function coerceDate(value: unknown): Date | null {
  if (typeof value === "number" && Number.isFinite(value)) return new Date(value);
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = new Date(value);
    if (!Number.isNaN(parsed.getTime())) return parsed;
  }
  return null;
}

export function formatDatePattern(date: Date, pattern: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const bag: Record<string, string> = {};
  for (const part of parts) bag[part.type] = part.value;
  const year = bag.year ?? "0000";
  const tokens: Record<string, string> = {
    yyyy: year,
    MM: bag.month ?? "01",
    dd: bag.day ?? "01",
    HH: bag.hour ?? "00",
    mm: bag.minute ?? "00",
    ss: bag.second ?? "00",
  };
  return pattern.replace(/yyyy|MM|dd|HH|mm|ss/g, (token) => tokens[token] ?? token);
}

function applyScript(value: unknown, transform: ScriptTransform, quality: QualityCode, ctx: TransformContext): unknown {
  if (!ctx.script) throw new Error("script hook is not connected");
  return ctx.script(transform.hook, value, quality, ctx.timestamp);
}

export function parseRange(text: string): { min: number | null; max: number | null; minInclusive: boolean; maxInclusive: boolean } {
  const match = /^([([])([^,]*),([^,]*)([)\]])$/.exec(text.trim());
  if (!match) throw new Error(`range must look like (0,25] : ${text}`);
  const minText = match[2]!.trim();
  const maxText = match[3]!.trim();
  const min = minText === "" ? null : Number(minText);
  const max = maxText === "" ? null : Number(maxText);
  if ((min !== null && !Number.isFinite(min)) || (max !== null && !Number.isFinite(max))) {
    throw new Error(`range endpoints must be numbers: ${text}`);
  }
  return { min, max, minInclusive: match[1] === "[", maxInclusive: match[4] === "]" };
}
