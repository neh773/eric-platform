import { useEffect, useMemo, useRef, useState } from "react";
import { errorMessage } from "./access";
import type { ApiClient } from "./api";
import { StatusApi } from "./statusApi";

export function useStatusApi(client: ApiClient | undefined): StatusApi {
  return useMemo(() => new StatusApi(client), [client]);
}

export interface Loaded<T> {
  data: T | null;
  error: string | null;
  /** Wall-clock time of the last successful load. */
  updatedAt: Date | null;
  reload: () => void;
}

/**
 * Load once, and again every `intervalMs` when it is set. A failed refresh keeps the last good data
 * and shows the error next to it. Loading stops while `enabled` is false.
 */
export function useLoad<T>(
  load: () => Promise<T>,
  deps: readonly unknown[],
  opts: { intervalMs?: number; enabled?: boolean; fallback?: string } = {},
): Loaded<T> {
  const { intervalMs, enabled = true, fallback = "Could not load" } = opts;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [token, setToken] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = () => {
      loadRef
        .current()
        .then((next) => {
          if (cancelled) return;
          setData(next);
          setError(null);
          setUpdatedAt(new Date());
        })
        .catch((err: unknown) => {
          if (cancelled) return;
          setError(errorMessage(err, fallback));
        })
        .finally(() => {
          if (!cancelled && intervalMs) timer = setTimeout(tick, intervalMs);
        });
    };
    tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
    // `deps` stands in for the inputs `load` closes over; `load` itself is read through the ref.
  }, [...deps, intervalMs, enabled, token]);

  return { data, error, updatedAt, reload: () => setToken((value) => value + 1) };
}
