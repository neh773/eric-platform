/**
 * C-9 report contract (contractVersion 1) as seen by the browser preview, see
 * /workspace/handoff/C9-REPORT-CONTRACT.md and gateway/report-render. Only the fields rendering reads are typed;
 * unknown fields are ignored.
 */

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export type ColumnType = "string" | "number" | "boolean" | "timestamp";

export interface ReportColumn {
  name: string;
  type: ColumnType;
  unit?: string;
}

export interface ReportSeries {
  tagPath: string;
  label?: string;
  unit?: string;
  /** `[epochMillis, value]`; value is null for an empty aggregate window. */
  points: [number, number | null][];
}

export interface ReportDataset {
  name: string;
  sourceType: "namedQuery" | "tagHistory" | "sql" | "alarmJournal";
  columns: ReportColumn[];
  rows: JsonValue[][];
  truncated?: boolean;
  series?: ReportSeries[];
  excludedTags?: string[];
}

export interface ReportParameterValue {
  name: string;
  type: string;
  value: JsonValue;
}

export interface ReportRun {
  runId: string;
  trigger: string;
  user: string;
  startedAt: number;
  durationMillis: number;
  status: string;
  warnings?: string[];
}

export interface ReportResult {
  contractVersion: number;
  report: { id: string; name: string; title: string; version: number };
  parameters: ReportParameterValue[];
  generatedAt: number;
  datasets: ReportDataset[];
  run?: ReportRun;
  layout?: Record<string, JsonValue>;
}

/** The parts of a Report definition spec rendering reads. */
export interface ReportDefinitionSpec {
  title?: string;
  description?: string;
  layout?: Record<string, JsonValue>;
}
