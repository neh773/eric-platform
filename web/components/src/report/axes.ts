import { zoneOffsetMillis } from "./format";

/** Axis ticks, mirrored from gateway/report-render Axes.kt. */

export interface NumericAxis {
  min: number;
  max: number;
  step: number;
  ticks: number[];
  decimals: number;
}

function niceNum(range: number, round: boolean): number {
  const exp = Math.floor(Math.log10(range));
  const f = range / 10 ** exp;
  let nf: number;
  if (round) nf = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
  else nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return nf * 10 ** exp;
}

export function numericAxis(lo: number, hi: number, fixedMin?: number, fixedMax?: number, target = 5): NumericAxis {
  let a = fixedMin ?? lo;
  let b = fixedMax ?? hi;
  if (!Number.isFinite(a) || !Number.isFinite(b)) {
    a = 0;
    b = 1;
  }
  if (a > b) [a, b] = [b, a];
  if (a === b) {
    const pad = a === 0 ? 1 : Math.abs(a) * 0.1;
    if (fixedMin === undefined) a -= pad;
    if (fixedMax === undefined) b += pad;
    if (a === b) b = a + 1;
  }
  const step = niceNum(niceNum(b - a, false) / Math.max(1, target), true);
  const min = fixedMin ?? Math.floor(a / step) * step;
  const max = fixedMax ?? Math.ceil(b / step) * step;
  const ticks: number[] = [];
  let t = Math.ceil(min / step - 1e-9) * step;
  while (t <= max + step * 1e-9 && ticks.length < 50) {
    ticks.push(Math.abs(t) < step * 1e-9 ? 0 : t);
    t += step;
  }
  const decimals = Math.max(0, -Math.floor(Math.log10(step) + 1e-9));
  return { min, max, step, ticks, decimals };
}

const S = 1000;
const M = 60 * S;
const H = 60 * M;
const D = 24 * H;
const TIME_STEPS = [
  S, 2 * S, 5 * S, 10 * S, 15 * S, 30 * S, M, 2 * M, 5 * M, 10 * M, 15 * M, 30 * M,
  H, 2 * H, 3 * H, 6 * H, 12 * H, D, 2 * D, 7 * D, 14 * D, 30 * D, 90 * D, 365 * D,
];

export interface TimeAxis {
  ticks: number[];
  pattern: string;
}

export function timeAxis(from: number, to: number, timeZone: string, maxTicks: number, pattern?: string): TimeAxis {
  const span = Math.max(1, to - from);
  const limit = Math.max(1, maxTicks);
  const step = TIME_STEPS.find((s) => Math.floor(span / s) <= limit) ?? TIME_STEPS[TIME_STEPS.length - 1]!;
  const offset = zoneOffsetMillis(from, timeZone);
  let t = Math.floor((from + offset) / step) * step - offset;
  if (t < from) t += step;
  const ticks: number[] = [];
  while (t <= to && ticks.length <= maxTicks) {
    ticks.push(t);
    t += step;
  }
  const chosen = pattern ?? (step < M ? "HH:mm:ss" : step >= D ? "MM-dd" : span > D ? "MM-dd HH:mm" : "HH:mm");
  return { ticks, pattern: chosen };
}
