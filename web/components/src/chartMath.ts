/**
 * Shared data plumbing for the trend, time series chart, and power chart: bounded sample buffers,
 * timestamp parsing, scales, ticks, paths, x-trace lookup, and range statistics.
 */

export interface Sample {
  /** Epoch milliseconds. */
  t: number;
  /** Null marks a gap (bad quality or a non-numeric value). */
  v: number | null;
}

/** Append and keep the newest `capacity` items. Used by the trend's point buffer. */
export function pushBounded<T>(list: readonly T[], item: T, capacity: number): T[] {
  const next = [...list, item];
  return next.length > capacity ? next.slice(next.length - capacity) : next;
}

/** Append a sample, drop samples older than `windowMs` before `now`, and cap the count. */
export function appendWindowed(list: readonly Sample[], sample: Sample, windowMs: number, maxPoints: number): Sample[] {
  const cutoff = sample.t - windowMs;
  const kept = list.filter((entry) => entry.t >= cutoff && entry.t <= sample.t);
  if (kept.length && kept[kept.length - 1]!.t === sample.t) kept[kept.length - 1] = sample;
  else kept.push(sample);
  return kept.length > maxPoints ? kept.slice(kept.length - maxPoints) : kept;
}

/** Epoch millis, ISO-8601 text, or a Date. Anything else is null. */
export function parseTimestamp(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value.getTime();
  if (typeof value === "string" && value.trim()) {
    const millis = Date.parse(value);
    return Number.isNaN(millis) ? null : millis;
  }
  return null;
}

export function numericOrNull(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "boolean") return value ? 1 : 0;
  return null;
}

const TIME_KEYS = ["t", "time", "timestamp", "t_stamp"];

/**
 * Rows like `[{ t: 1730000000000, flow: 3.2, level: 41 }, ...]` become one sample list per
 * value key, sorted by time. The time key is `t`, `time`, `timestamp`, or `t_stamp`.
 */
export function seriesFromRows(rows: unknown): Map<string, Sample[]> {
  const out = new Map<string, Sample[]>();
  if (!Array.isArray(rows)) return out;
  for (const row of rows) {
    if (!row || typeof row !== "object" || Array.isArray(row)) continue;
    const record = row as Record<string, unknown>;
    const timeKey = TIME_KEYS.find((key) => key in record);
    if (!timeKey) continue;
    const t = parseTimestamp(record[timeKey]);
    if (t === null) continue;
    for (const [key, raw] of Object.entries(record)) {
      if (key === timeKey) continue;
      const list = out.get(key) ?? [];
      list.push({ t, v: numericOrNull(raw) });
      out.set(key, list);
    }
  }
  for (const list of out.values()) list.sort((a, b) => a.t - b.t);
  return out;
}

export interface Extent {
  min: number;
  max: number;
}

/** Min and max of the non-null values in [start, end]. A flat or empty range is widened by 1. */
export function valueExtent(series: readonly (readonly Sample[])[], start: number, end: number): Extent {
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (const samples of series) {
    for (const sample of samples) {
      if (sample.v === null || sample.t < start || sample.t > end) continue;
      if (sample.v < min) min = sample.v;
      if (sample.v > max) max = sample.v;
    }
  }
  if (!Number.isFinite(min)) return { min: 0, max: 1 };
  if (min === max) return { min: min - 1, max: max + 1 };
  return { min, max };
}

/** Round tick values covering [min, max], about `count` of them. */
export function niceTicks(min: number, max: number, count = 5): number[] {
  if (!(max > min)) return [min];
  const raw = (max - min) / Math.max(1, count);
  const power = Math.pow(10, Math.floor(Math.log10(raw)));
  const fraction = raw / power;
  const step = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * power;
  const first = Math.ceil(min / step - 1e-9) * step;
  const ticks: number[] = [];
  for (let value = first; value <= max + step * 1e-9; value += step) ticks.push(Number(value.toPrecision(12)));
  return ticks;
}

/** Expand an extent to the enclosing nice ticks so lines do not touch the plot edge. */
export function niceExtent(extent: Extent, count = 5): Extent {
  const ticks = niceTicks(extent.min, extent.max, count);
  if (ticks.length < 2) return extent;
  const step = ticks[1]! - ticks[0]!;
  return {
    min: ticks[0]! > extent.min ? ticks[0]! - step : ticks[0]!,
    max: ticks[ticks.length - 1]! < extent.max ? ticks[ticks.length - 1]! + step : ticks[ticks.length - 1]!,
  };
}

const TIME_STEPS = [1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000, 21_600_000, 43_200_000, 86_400_000];

export function timeTicks(start: number, end: number, count = 6): number[] {
  if (!(end > start)) return [start];
  const target = (end - start) / Math.max(1, count);
  const step = TIME_STEPS.find((candidate) => candidate >= target) ?? Math.ceil(target / 86_400_000) * 86_400_000;
  const ticks: number[] = [];
  for (let t = Math.ceil(start / step) * step; t <= end; t += step) ticks.push(t);
  return ticks;
}

/** Map a value into [0, size]. Y grows downward, so callers pass `invert` for the value axis. */
export function scale(value: number, domainMin: number, domainMax: number, size: number, invert = false): number {
  const span = domainMax - domainMin || 1;
  const ratio = (value - domainMin) / span;
  return (invert ? 1 - ratio : ratio) * size;
}

/**
 * SVG path in a `width` x `height` box. Null values and samples outside [start, end] break the
 * line into segments.
 */
export function linePath(samples: readonly Sample[], start: number, end: number, y: Extent, width: number, height: number): string {
  let path = "";
  let pen = false;
  for (const sample of samples) {
    if (sample.t < start || sample.t > end || sample.v === null) {
      pen = false;
      continue;
    }
    const x = scale(sample.t, start, end, width).toFixed(2);
    const yy = scale(clamp(sample.v, y.min, y.max), y.min, y.max, height, true).toFixed(2);
    path += `${pen ? "L" : "M"}${x},${yy}`;
    pen = true;
  }
  return path;
}

/** Closed area under each segment of the line, down to the bottom of the box. */
export function areaPath(samples: readonly Sample[], start: number, end: number, y: Extent, width: number, height: number): string {
  const segments: string[] = [];
  let current: [string, string][] = [];
  const flush = () => {
    if (current.length) {
      const [x0] = current[0]!;
      const [x1] = current[current.length - 1]!;
      segments.push(`M${x0},${height}` + current.map(([x, yy]) => `L${x},${yy}`).join("") + `L${x1},${height}Z`);
    }
    current = [];
  };
  for (const sample of samples) {
    if (sample.t < start || sample.t > end || sample.v === null) {
      flush();
      continue;
    }
    current.push([
      scale(sample.t, start, end, width).toFixed(2),
      scale(clamp(sample.v, y.min, y.max), y.min, y.max, height, true).toFixed(2),
    ]);
  }
  flush();
  return segments.join("");
}

/** Index of the sample closest in time to `t`, or -1 for an empty list. Samples are time-sorted. */
export function nearestIndex(samples: readonly Sample[], t: number): number {
  if (!samples.length) return -1;
  let low = 0;
  let high = samples.length - 1;
  while (low < high) {
    const mid = (low + high) >> 1;
    if (samples[mid]!.t < t) low = mid + 1;
    else high = mid;
  }
  if (low > 0 && Math.abs(samples[low - 1]!.t - t) <= Math.abs(samples[low]!.t - t)) return low - 1;
  return low;
}

/** Linear interpolation at `t` between the surrounding good samples. Null outside the data or across a gap. */
export function interpolateAt(samples: readonly Sample[], t: number): number | null {
  if (!samples.length || t < samples[0]!.t || t > samples[samples.length - 1]!.t) return null;
  for (let index = 0; index < samples.length; index += 1) {
    const right = samples[index]!;
    if (right.t < t) continue;
    if (right.t === t) return right.v;
    const left = samples[index - 1];
    if (!left || left.v === null || right.v === null) return null;
    const ratio = (t - left.t) / (right.t - left.t);
    return left.v + (right.v - left.v) * ratio;
  }
  return null;
}

export interface RangeStats {
  min: number | null;
  max: number | null;
  average: number | null;
  last: number | null;
  count: number;
}

export function rangeStats(samples: readonly Sample[], start: number, end: number): RangeStats {
  let min: number | null = null;
  let max: number | null = null;
  let sum = 0;
  let count = 0;
  let last: number | null = null;
  for (const sample of samples) {
    if (sample.t < start || sample.t > end || sample.v === null) continue;
    min = min === null ? sample.v : Math.min(min, sample.v);
    max = max === null ? sample.v : Math.max(max, sample.v);
    sum += sample.v;
    count += 1;
    last = sample.v;
  }
  return { min, max, average: count ? sum / count : null, last, count };
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** Range picker choices shared by both charts, in seconds. */
export const RANGE_CHOICES = [60, 300, 900, 3600, 21_600, 86_400];

export function rangeLabel(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} h`;
  if (seconds % 60 === 0) return `${seconds / 60} min`;
  return `${seconds} s`;
}
