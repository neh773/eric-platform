import { useEffect, useRef, useState } from "react";
import { numericOrNull, type Sample } from "./chartMath";
import type { GatewayClient } from "./gateway";

/**
 * Tag history pens for the time series chart (HMP-001): each pen is a tag path whose stored history is read from
 * `GET /api/v1/history/data` over the chart's range, oldest first.
 */

/** Range read when the chart's visible range is 0 ("all"): one day back from now. */
export const HISTORY_ALL_RANGE_SECONDS = 86_400;
/** The gateway's largest `limit` for one history read. */
export const HISTORY_MAX_LIMIT = 100_000;

export function historyUrl(path: string, from: number, to: number, limit: number): string {
  const query = new URLSearchParams({ tagPath: path, from: String(Math.floor(from)), to: String(Math.ceil(to)), limit: String(limit) });
  return `/api/v1/history/data?${query.toString()}`;
}

export interface HistoryRead {
  samples: Sample[];
  /** More rows matched than the limit; the newest are missing. */
  truncated: boolean;
  /** Set when the gateway refused the tag (the caller may not read it). */
  denied: boolean;
}

/** Rows of a history answer as chart samples; a row whose quality is not Good is a gap. */
export function parseHistory(body: unknown): HistoryRead {
  if (!body || typeof body !== "object") return { samples: [], truncated: false, denied: false };
  const record = body as Record<string, unknown>;
  const denied = typeof record.quality === "string" && !/^good/i.test(record.quality);
  const rows = Array.isArray(record.samples) ? record.samples : [];
  const samples: Sample[] = [];
  for (const row of rows) {
    if (!row || typeof row !== "object") continue;
    const entry = row as Record<string, unknown>;
    const t = typeof entry.timestamp === "string" ? Date.parse(entry.timestamp) : typeof entry.timestamp === "number" ? entry.timestamp : Number.NaN;
    if (!Number.isFinite(t)) continue;
    const quality = typeof entry.quality === "string" ? entry.quality : "Good";
    samples.push({ t, v: /^good/i.test(quality) ? numericOrNull(entry.value) : null });
  }
  samples.sort((a, b) => a.t - b.t);
  return { samples, truncated: record.truncated === true, denied };
}

export interface HistoryPenState {
  samples: Sample[];
  truncated: boolean;
  /** Why this pen has no history: a refused tag or a failed read. Empty when the read worked. */
  error: string;
}

/**
 * Reads each pen's history when the pens or range change, and again every `refreshSeconds` (0: once).
 * A read that fails keeps the pen's last samples and records the error.
 */
/** Replace `{name}` with the last segment of the page URL (`/station/CinderFlat` → CinderFlat). */
export function resolvePenPath(path: string): string {
  if (!path.includes("{name}") || typeof location === "undefined") return path;
  const name = decodeURIComponent(location.pathname.split("/").filter(Boolean).pop() ?? "");
  return name ? path.replaceAll("{name}", name) : path;
}

export function useHistoryPens(
  client: GatewayClient,
  paths: readonly string[],
  rangeSeconds: number,
  refreshSeconds: number,
  limit: number,
  /** How far the window's end sits behind now. Zero follows the latest samples. */
  endOffsetMs = 0,
): Record<string, HistoryPenState> {
  const [state, setState] = useState<Record<string, HistoryPenState>>({});
  const key = paths.join("\n");
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    const list = key ? key.split("\n").map((path) => ({ requested: path, resolved: resolvePenPath(path) })) : [];
    setState((current) => Object.fromEntries(Object.entries(current).filter(([path]) => list.some((entry) => entry.requested === path))));
    if (!list.length) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const span = (rangeSeconds > 0 ? rangeSeconds : HISTORY_ALL_RANGE_SECONDS) * 1000;
    const readAll = async () => {
      const to = Date.now() - Math.max(0, endOffsetMs);
      const from = to - span;
      const results = await Promise.all(
        list.map(async (entry): Promise<[string, HistoryPenState]> => {
          try {
            const read = parseHistory(await client.get(historyUrl(entry.resolved, from, to, limit)));
            if (read.denied) return [entry.requested, { samples: [], truncated: false, error: "Access denied" }];
            return [entry.requested, { samples: read.samples, truncated: read.truncated, error: "" }];
          } catch (failure) {
            return [entry.requested, { samples: [], truncated: false, error: failure instanceof Error ? failure.message : String(failure) }];
          }
        }),
      );
      if (stopped || !mounted.current) return;
      setState((current) => {
        const next: Record<string, HistoryPenState> = {};
        for (const [path, result] of results) {
          // A failed read keeps what the pen showed before.
          next[path] = result.error && current[path] ? { ...current[path]!, error: result.error } : result;
        }
        return next;
      });
    };
    const loop = async () => {
      await readAll();
      if (!stopped && refreshSeconds > 0) timer = setTimeout(() => void loop(), refreshSeconds * 1000);
    };
    void loop();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [client, key, rangeSeconds, refreshSeconds, limit, endOffsetMs]);
  return state;
}
