import type { JsonValue } from "./types";

/** Table helpers mirrored from gateway/report-render TableGrouping.kt: row groups (REP-050) and auto-sized columns (REP-016). */

export interface RowGroup {
  key: JsonValue | undefined;
  rows: JsonValue[][];
}

/** Rows grouped by the value in column `index`, groups in order of first appearance, rows in original order. */
export function groupRows(rows: JsonValue[][], index: number): RowGroup[] {
  const groups = new Map<string, RowGroup>();
  for (const row of rows) {
    const raw = row[index];
    const key = raw === null || raw === undefined ? undefined : raw;
    const id = key === undefined ? "\u0000" : JSON.stringify(key);
    const group = groups.get(id);
    if (group) group.rows.push(row);
    else groups.set(id, { key, rows: [row] });
  }
  return [...groups.values()];
}

/** Rows looked at when sizing columns. */
export const AUTO_SAMPLE = 200;
const MIN_CHARS = 4;
const MAX_CHARS = 40;

/** Characters that decide a column's width: the longest of its header and sampled cells. */
export function textLength(header: string, cells: string[]): number {
  let longest = header.length;
  for (const cell of cells) if (cell.length > longest) longest = cell.length;
  return longest;
}

/** Relative widths from text lengths, each clamped to 4..40 characters. */
export function autoWeights(lengths: number[]): number[] {
  return lengths.map((n) => Math.min(MAX_CHARS, Math.max(MIN_CHARS, n)));
}
