/** HIS-003: pure helpers for the Studio tag-history query tool. */

export const AGGREGATE_MODES = ["Average", "SimpleAverage", "Sum", "Minimum", "Maximum", "Range", "LastValue", "Count"] as const;
export type AggregateMode = (typeof AGGREGATE_MODES)[number] | "Raw";

const UNIT_MS: Record<string, number> = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000, w: 604_800_000 };

/** Replaces `{name}` tokens with the named value; an unbound token is an error so a typo never queries the wrong thing. */
export function bindParameters(text: string, params: Record<string, string>): string {
  return text.replace(/\{([A-Za-z0-9_-]+)\}/g, (_all, name: string) => {
    if (!(name in params)) throw new Error(`Parameter ${name} has no value`);
    return params[name];
  });
}

/** "last 1 h", "last 30 min", "now", an ISO instant, or epoch milliseconds -> epoch milliseconds. */
export function resolveTime(text: string, now: number): number {
  const t = text.trim();
  if (t.toLowerCase() === "now") return now;
  const rel = /^last\s+(\d+(?:\.\d+)?)\s*(s|sec|secs|m|min|mins|h|hr|hrs|d|day|days|w|wk)$/i.exec(t);
  if (rel) {
    const unit = rel[2].toLowerCase()[0];
    return now - Math.round(Number(rel[1]) * UNIT_MS[unit]);
  }
  if (/^\d+$/.test(t)) return Number(t);
  const parsed = Date.parse(t);
  if (Number.isNaN(parsed)) throw new Error(`Cannot read time "${t}"`);
  return parsed;
}

export interface HistoryQuerySpec {
  tagPaths: string[];
  start: string;
  end: string;
  mode: AggregateMode;
  intervalSeconds: number;
}

export interface HistoryRequest {
  tagPath: string;
  path: string;
}

/** Builds one gateway request per tag path, using the existing /api/v1/history routes. */
export function buildRequests(spec: HistoryQuerySpec, params: Record<string, string>, now: number): HistoryRequest[] {
  const from = resolveTime(bindParameters(spec.start, params), now);
  const to = resolveTime(bindParameters(spec.end, params), now);
  if (to <= from) throw new Error("End must be after start");
  return spec.tagPaths
    .map((p) => bindParameters(p.trim(), params))
    .filter((p) => p !== "")
    .map((tagPath) => {
      const q = new URLSearchParams({ tagPath, from: String(from), to: String(to) });
      if (spec.mode === "Raw") return { tagPath, path: `/api/v1/history/data?${q}` };
      if (!(spec.intervalSeconds > 0)) throw new Error("Interval must be greater than zero");
      q.set("interval", String(Math.round(spec.intervalSeconds * 1000)));
      q.set("aggregate", spec.mode);
      return { tagPath, path: `/api/v1/history/aggregate?${q}` };
    });
}

export interface HistoryRow {
  tagPath: string;
  time: string;
  value: string;
  extra: string;
}

export function toRows(tagPath: string, body: unknown): HistoryRow[] {
  const b = body as { quality?: string; samples?: Array<{ timestamp: string; value: unknown; quality?: string }>; windows?: Array<{ start: string; value: unknown; rows?: number }> };
  if (b.quality) return [{ tagPath, time: "", value: "", extra: b.quality }];
  if (b.samples) return b.samples.map((s) => ({ tagPath, time: s.timestamp, value: String(s.value), extra: s.quality ?? "" }));
  return (b.windows ?? []).map((w) => ({ tagPath, time: w.start, value: w.value === null || w.value === undefined ? "" : String(w.value), extra: `${w.rows ?? 0} rows` }));
}
