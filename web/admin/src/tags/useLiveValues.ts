import { useEffect, useState } from "react";
import type { ApiClient } from "../api";
import { readValues, type TagValue } from "./tagClient";

export const LIVE_INTERVAL_MS = 1000;
/** One read request per tick. The tree shows at most this many live tag rows at once. */
export const MAX_LIVE_PATHS = 1000;

/**
 * Polls POST /api/v1/tags/read for `paths` while mounted. A failed tick keeps the last values and
 * reports the error; the next tick tries again. Each read is the signed-in caller's, so a tag the
 * caller may not read stays Bad_AccessDenied.
 */
export function useLiveValues(client: ApiClient | undefined, paths: readonly string[], intervalMs = LIVE_INTERVAL_MS) {
  const [values, setValues] = useState<Map<string, TagValue>>(() => new Map());
  const [error, setError] = useState<string | null>(null);
  const key = paths.slice(0, MAX_LIVE_PATHS).join("\n");

  useEffect(() => {
    if (!client || key === "") return;
    const wanted = key.split("\n");
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const rows = await readValues(client, wanted);
        if (cancelled) return;
        setValues((prev) => {
          const next = new Map(prev);
          rows.forEach((row, index) => next.set(wanted[index] ?? row.path, row));
          return next;
        });
        setError(null);
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : "Live values are unavailable");
      } finally {
        if (!cancelled) timer = setTimeout(() => void tick(), intervalMs);
      }
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [client, key, intervalMs]);

  return { values, error };
}
