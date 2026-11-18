/** PRF-011 run sizes and latency statistics; the session farm itself is prf011.spec.ts. */

export interface Prf011Config {
  size: "ci" | "full";
  sessions: number;
  /** Sessions that are real rendered browser pages, all timed; the rest are protocol sessions on the socket. */
  rendered: number;
  tags: number;
  /** One write batch every batchMs. */
  batchMs: number;
  /** Each tag changes once per tagPeriodMs. */
  tagPeriodMs: number;
  warmupMs: number;
  measureMs: number;
  timeoutMs: number;
}

/** ADR 0010 load: 50 sessions of 300 tags. The rate is ours: every tag changes once a second. */
export const FULL: Prf011Config = {
  size: "full",
  sessions: 50,
  rendered: 20,
  tags: 300,
  batchMs: 100,
  tagPeriodMs: 1_000,
  warmupMs: 10_000,
  measureMs: 60_000,
  timeoutMs: 600_000,
};

/** The every-PR run: the same farm and rate, with a shorter window. */
export const CI: Prf011Config = { ...FULL, size: "ci", warmupMs: 5_000, measureMs: 20_000, timeoutMs: 420_000 };

/**
 * `OIP_PRF011_SIZE=full` picks [FULL]; anything else [CI]. `OIP_PRF011_SESSIONS`, `OIP_PRF011_RENDERED`,
 * `OIP_PRF011_TAGS`, `OIP_PRF011_MEASURE_MS` and `OIP_PRF011_WARMUP_MS` override one field (for exploring; the gate
 * is a named size).
 */
export function prf011Config(env: Record<string, string | undefined>): Prf011Config {
  const chosen = env.OIP_PRF011_SIZE === "full" ? FULL : CI;
  const number = (key: string, fallback: number) => {
    const raw = env[key];
    if (raw === undefined || raw === "") return fallback;
    const value = Number(raw);
    if (!Number.isInteger(value) || value <= 0) throw new Error(`${key} must be a positive integer, got ${raw}`);
    return value;
  };
  const sessions = number("OIP_PRF011_SESSIONS", chosen.sessions);
  return {
    ...chosen,
    sessions,
    rendered: Math.min(sessions, number("OIP_PRF011_RENDERED", chosen.rendered)),
    tags: number("OIP_PRF011_TAGS", chosen.tags),
    warmupMs: number("OIP_PRF011_WARMUP_MS", chosen.warmupMs),
    measureMs: number("OIP_PRF011_MEASURE_MS", chosen.measureMs),
  };
}

/**
 * Accepted PRF-011 baseline (p95 ms, lower is better), `result.baseline` in docs/agent/perf-requirements.json: the
 * CI size on a 4 vCPU / 16 GB runner. PRF-013 fails a later same-hardware result more than 15% worse.
 */
export const BASELINE_P95_MS = 50;

/** Percent by which [measured] is worse than [baseline] (latency: higher is worse). */
export function worsePercent(baseline: number, measured: number): number {
  return ((measured - baseline) / baseline) * 100;
}

/** PRF-013: more than 15% worse fails; exactly 15% does not. */
export function regressionFails(baseline: number, measured: number): boolean {
  return worsePercent(baseline, measured) > 15;
}

export interface LatencyStats {
  count: number;
  p50: number;
  p95: number;
  p99: number;
  max: number;
  mean: number;
}

/** Nearest-rank percentile of an ascending array: the smallest sample with at least [p] of the samples at or below it. */
export function percentile(sorted: readonly number[], p: number): number {
  if (sorted.length === 0) return Number.NaN;
  const rank = Math.ceil(p * sorted.length);
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1]!;
}

export function latencyStats(samples: readonly number[]): LatencyStats {
  if (samples.length === 0) return { count: 0, p50: Number.NaN, p95: Number.NaN, p99: Number.NaN, max: Number.NaN, mean: Number.NaN };
  const sorted = Float64Array.from(samples).sort();
  const list = Array.from(sorted);
  let sum = 0;
  for (const sample of list) sum += sample;
  const round = (value: number) => Number(value.toFixed(1));
  return {
    count: list.length,
    p50: round(percentile(list, 0.5)),
    p95: round(percentile(list, 0.95)),
    p99: round(percentile(list, 0.99)),
    max: round(list[list.length - 1]!),
    mean: round(sum / list.length),
  };
}
