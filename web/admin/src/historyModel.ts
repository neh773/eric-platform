/**
 * History provider and per-tag history drafts.
 * Extra JSON settings ride along so a JSON edit survives a form save.
 * Doc source: FEATURES HIS-001, HIS-002, HIS-004 through HIS-009.
 */

export type ProviderType = "sql" | "csv" | "simulator" | "splitter" | "table";

export interface ProviderDraft {
  type: ProviderType;
  enabled: boolean;
  connection: string;
  table: string;
  file: string;
  first: string;
  second: string;
  sourceTable: string;
  timestampColumn: string;
  tagColumn: string;
  valueColumn: string;
  forwardRateMillis: string;
  batchSize: string;
  maxPending: string;
  extra: Record<string, unknown>;
}

export interface HistoryTagDraft {
  tagPath: string;
  sampleMode: "onChange" | "periodic";
  sampleRateMillis: string;
  deadbandMode: "off" | "absolute" | "percent";
  deadbandValue: string;
  maxTimeBetweenSamplesMillis: string;
  storageProvider: string;
  extra: Record<string, unknown>;
}

const PROVIDER_OWNED = new Set(["type", "enabled", "connection", "table", "file", "first", "second", "sourceTable", "timestampColumn", "tagColumn", "valueColumn", "storeAndForward"]);

export function emptyProvider(type: ProviderType = "simulator"): ProviderDraft {
  return {
    type,
    enabled: true,
    connection: "",
    table: "sqlth_te",
    file: "history.csv",
    first: "",
    second: "",
    sourceTable: "",
    timestampColumn: "t_stamp",
    tagColumn: "tag",
    valueColumn: "value",
    forwardRateMillis: "1000",
    batchSize: "100",
    maxPending: "0",
    extra: {},
  };
}

export function specFromProvider(draft: ProviderDraft): Record<string, unknown> {
  const spec: Record<string, unknown> = { ...draft.extra, type: draft.type, enabled: draft.enabled };
  if (draft.type === "sql") {
    spec.connection = draft.connection.trim();
    spec.table = draft.table.trim() || "sqlth_te";
  }
  if (draft.type === "csv") spec.file = draft.file.trim();
  if (draft.type === "splitter") {
    spec.first = draft.first.trim();
    spec.second = draft.second.trim();
  }
  if (draft.type === "sql" || draft.type === "simulator") {
    spec.storeAndForward = {
      forwardRateMillis: Number(draft.forwardRateMillis) || 1000,
      batchSize: Number(draft.batchSize) || 100,
      maxPending: Number(draft.maxPending) || 0,
    };
  }
  if (draft.type === "table") {
    spec.connection = draft.connection.trim();
    spec.sourceTable = draft.sourceTable.trim();
    spec.timestampColumn = draft.timestampColumn.trim();
    spec.tagColumn = draft.tagColumn.trim();
    spec.valueColumn = draft.valueColumn.trim();
    spec.layout = "narrow";
  }
  return spec;
}

export function providerFromSpec(spec: Record<string, unknown>): ProviderDraft {
  const type = isProvider(spec.type) ? spec.type : "simulator";
  const base = emptyProvider(type);
  const extra: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(spec)) if (!PROVIDER_OWNED.has(key)) extra[key] = value;
  return {
    ...base,
    enabled: spec.enabled !== false,
    connection: text(spec.connection),
    table: text(spec.table) || base.table,
    file: text(spec.file) || base.file,
    first: text(spec.first),
    second: text(spec.second),
    sourceTable: text(spec.sourceTable),
    timestampColumn: text(spec.timestampColumn) || base.timestampColumn,
    tagColumn: text(spec.tagColumn) || base.tagColumn,
    valueColumn: text(spec.valueColumn) || base.valueColumn,
    forwardRateMillis: nestedNumber(spec.storeAndForward, "forwardRateMillis", base.forwardRateMillis),
    batchSize: nestedNumber(spec.storeAndForward, "batchSize", base.batchSize),
    maxPending: nestedNumber(spec.storeAndForward, "maxPending", base.maxPending),
    extra,
  };
}

export function providerProblem(name: string, draft: ProviderDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) return "Use a short lowercase name: letters, digits, and hyphens.";
  if ((draft.type === "sql" || draft.type === "table") && !draft.connection.trim()) return "This provider needs a database connection.";
  if (draft.type === "csv" && !draft.file.trim()) return "A CSV provider needs a file name under the history folder.";
  if (draft.type === "splitter" && (!draft.first.trim() || !draft.second.trim() || draft.first.trim() === draft.second.trim())) {
    return "A splitter needs two different providers.";
  }
  if (draft.type === "table" && (!draft.sourceTable.trim() || !draft.timestampColumn.trim() || !draft.tagColumn.trim() || !draft.valueColumn.trim())) {
    return "A table provider needs the source table, time column, tag column, and value column.";
  }
  return null;
}

export function emptyHistoryTag(): HistoryTagDraft {
  return {
    tagPath: "",
    sampleMode: "onChange",
    sampleRateMillis: "1000",
    deadbandMode: "off",
    deadbandValue: "0",
    maxTimeBetweenSamplesMillis: "0",
    storageProvider: "local",
    extra: {},
  };
}

export function specFromHistoryTag(draft: HistoryTagDraft): Record<string, unknown> {
  return {
    ...draft.extra,
    tagPath: draft.tagPath.trim(),
    sampleMode: draft.sampleMode,
    sampleRateMillis: Number(draft.sampleRateMillis) || 1000,
    deadband: { mode: draft.deadbandMode, value: Number(draft.deadbandValue) || 0 },
    maxTimeBetweenSamplesMillis: Number(draft.maxTimeBetweenSamplesMillis) || 0,
    storageProvider: draft.storageProvider.trim() || "local",
  };
}

export function historyTagProblem(name: string, draft: HistoryTagDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) return "Use a short lowercase name: letters, digits, and hyphens.";
  if (!draft.tagPath.trim()) return "A tag path is required.";
  return null;
}

function isProvider(value: unknown): value is ProviderType {
  return value === "sql" || value === "csv" || value === "simulator" || value === "splitter" || value === "table";
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function nestedNumber(value: unknown, key: string, fallback: string): string {
  if (!value || typeof value !== "object" || Array.isArray(value)) return fallback;
  const raw = (value as Record<string, unknown>)[key];
  return typeof raw === "number" ? String(raw) : fallback;
}
