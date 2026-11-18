import { DEFAULT_TIMESTAMP_PATTERN, formatTimestamp } from "./format";
import type { ReportResult } from "./types";

/** `@Name@` in report text. Unknown names stay as written. Built-ins are Date, Report, and Page. */
const KEY = /@([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)@/g;

export function dataKeyValues(result: ReportResult, zone: string, page = 1): Record<string, string> {
  const values: Record<string, string> = {
    Date: formatTimestamp(result.generatedAt, DEFAULT_TIMESTAMP_PATTERN, zone),
    Report: result.report.name,
    Page: String(Math.max(1, page)),
  };
  for (const parameter of result.parameters) {
    if (parameter.value === null || parameter.value === undefined) continue;
    values[parameter.name] = typeof parameter.value === "object" ? JSON.stringify(parameter.value) : String(parameter.value);
  }
  const plain = new Map<string, string>();
  const duplicate = new Set<string>();
  for (const dataset of result.datasets) {
    const row = dataset.rows[0];
    if (!row) continue;
    dataset.columns.forEach((column, index) => {
      const cell = row[index];
      const text = cell === null || cell === undefined ? "" : typeof cell === "object" ? JSON.stringify(cell) : String(cell);
      values[`${dataset.name}.${column.name}`] = text;
      if (duplicate.has(column.name)) return;
      if (plain.has(column.name)) {
        duplicate.add(column.name);
        plain.delete(column.name);
      } else {
        plain.set(column.name, text);
      }
    });
  }
  for (const [name, text] of plain) values[name] = text;
  return values;
}

export function applyDataKeys(text: string, values: Record<string, string>): string {
  return text.replace(KEY, (whole, name: string) => (Object.prototype.hasOwnProperty.call(values, name) ? values[name]! : whole));
}
