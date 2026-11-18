import { formatReportNumber } from "./format";

/** Pie slices, mirrored from gateway/report-render PieSlices.kt (REP-004). */

export const MAX_SLICES = 12;

export interface PieSlice {
  label: string;
  value: number;
  fraction: number;
}

/** Rows without a positive finite value draw nothing; beyond 12 slices the smallest merge into a last "Other". */
export function pieSlices(rows: [string, number | null][]): PieSlice[] {
  const positive = rows.filter((r): r is [string, number] => r[1] !== null && Number.isFinite(r[1]) && r[1] > 0);
  const total = positive.reduce((sum, r) => sum + r[1], 0);
  if (positive.length === 0 || total <= 0) return [];
  let kept = positive;
  let other = 0;
  if (positive.length > MAX_SLICES) {
    const order = positive.map((_, i) => i).sort((a, b) => positive[b]![1] - positive[a]![1] || a - b);
    const keep = new Set(order.slice(0, MAX_SLICES - 1));
    kept = positive.filter((_, i) => keep.has(i));
    other = positive.filter((_, i) => !keep.has(i)).reduce((sum, r) => sum + r[1], 0);
  }
  const out = kept.map(([label, value]) => ({ label, value, fraction: value / total }));
  if (other > 0) out.push({ label: "Other", value: other, fraction: other / total });
  return out;
}

/** "42.5%": one decimal place. */
export function percentText(fraction: number): string {
  return `${formatReportNumber(fraction * 100, 1, false)}%`;
}

/** SVG path of a slice from fraction `from` to `to` (clockwise from 12 o'clock). A full circle is two half arcs. */
export function slicePath(cx: number, cy: number, r: number, from: number, to: number): string {
  const point = (f: number) => {
    const a = 2 * Math.PI * f;
    return [cx + r * Math.sin(a), cy - r * Math.cos(a)] as const;
  };
  const f = (n: number) => (Math.abs(n) < 0.005 ? 0 : n).toFixed(2);
  if (to - from >= 0.9999) {
    const [x0, y0] = point(0);
    const [x1, y1] = point(0.5);
    return `M${f(x0)} ${f(y0)} A${f(r)} ${f(r)} 0 1 1 ${f(x1)} ${f(y1)} A${f(r)} ${f(r)} 0 1 1 ${f(x0)} ${f(y0)} Z`;
  }
  const [x0, y0] = point(from);
  const [x1, y1] = point(to);
  const large = to - from > 0.5 ? 1 : 0;
  return `M${f(cx)} ${f(cy)} L${f(x0)} ${f(y0)} A${f(r)} ${f(r)} 0 ${large} 1 ${f(x1)} ${f(y1)} Z`;
}
