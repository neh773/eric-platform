/**
 * Transaction group drafts. Extra JSON settings ride along in `extra`.
 * Doc source: FEATURES DAT-002, DAT-004, DAT-006 through DAT-013, DAT-039.
 */

export type GroupType = "standard" | "historical" | "block" | "storedProcedure";
export type UpdateMode = "tagToDb" | "dbToTag" | "bidirectionalTagWins" | "bidirectionalDbWins";
export type ItemSource = "tag" | "expression" | "static";

export interface GroupItemDraft {
  name: string;
  source: ItemSource;
  tag: string;
  expression: string;
  value: string;
  column: string;
  meter: "" | "hour" | "event";
}

export interface GroupDraft {
  enabled: boolean;
  connection: string;
  table: string;
  groupType: GroupType;
  updateMode: UpdateMode;
  tableAction: "insert" | "update";
  rateMillis: string;
  triggerTag: string;
  triggerMode: "" | "rising" | "falling" | "while" | "change";
  rowMode: "first" | "last" | "key" | "where";
  keyColumn: string;
  keyItem: string;
  procedure: string;
  items: GroupItemDraft[];
  blockColumn: string;
  blockTags: string;
  extra: Record<string, unknown>;
}

const OWNED = new Set([
  "enabled", "connection", "table", "groupType", "updateMode", "tableAction", "execution", "row", "procedure", "items", "blockItems",
]);

export function emptyItem(): GroupItemDraft {
  return { name: "value", source: "tag", tag: "", expression: "", value: "", column: "value", meter: "" };
}

export function emptyGroup(): GroupDraft {
  return {
    enabled: true,
    connection: "<default>",
    table: "",
    groupType: "standard",
    updateMode: "tagToDb",
    tableAction: "insert",
    rateMillis: "1000",
    triggerTag: "",
    triggerMode: "",
    rowMode: "last",
    keyColumn: "",
    keyItem: "",
    procedure: "",
    items: [emptyItem()],
    blockColumn: "value",
    blockTags: "",
    extra: {},
  };
}

export function specFromDraft(draft: GroupDraft): Record<string, unknown> {
  const execution: Record<string, unknown> = { rateMillis: numberOr(draft.rateMillis, 1000) };
  if (draft.triggerTag.trim()) {
    const trigger: Record<string, unknown> = { tag: draft.triggerTag.trim() };
    if (draft.triggerMode) trigger.mode = draft.triggerMode;
    execution.trigger = trigger;
  }
  const spec: Record<string, unknown> = {
    ...draft.extra,
    enabled: draft.enabled,
    connection: draft.connection.trim() || "<default>",
    groupType: draft.groupType,
    updateMode: draft.updateMode,
    tableAction: draft.tableAction,
    execution,
  };
  if (draft.groupType !== "storedProcedure") spec.table = draft.table.trim();
  if (draft.tableAction === "update") {
    const row: Record<string, unknown> = { mode: draft.rowMode };
    if (draft.rowMode === "key") {
      if (draft.keyColumn.trim()) row.keyColumn = draft.keyColumn.trim();
      if (draft.keyItem.trim()) row.keyItem = draft.keyItem.trim();
    }
    spec.row = row;
  }
  if (draft.groupType === "storedProcedure" && draft.procedure.trim()) {
    spec.procedure = { name: draft.procedure.trim(), routine: "procedure" };
  }
  if (draft.groupType === "block") {
    spec.blockItems = [{
      name: "block",
      column: draft.blockColumn.trim() || "value",
      tags: draft.blockTags.split("\n").map((item) => item.trim()).filter(Boolean),
    }];
  } else {
    spec.items = draft.items.map((item) => {
      const row: Record<string, unknown> = { name: item.name.trim(), source: item.source };
      if (item.source === "tag") row.tag = item.tag.trim();
      if (item.source === "expression") row.expression = item.expression.trim();
      if (item.source === "static") row.value = item.value;
      if (item.column.trim()) row.column = item.column.trim();
      if (item.meter) row.meter = item.meter;
      return row;
    });
  }
  return spec;
}

export function draftFromSpec(spec: Record<string, unknown>): GroupDraft {
  const base = emptyGroup();
  const execution = record(spec.execution);
  const trigger = record(execution?.trigger);
  const row = record(spec.row);
  const procedure = record(spec.procedure);
  const block = Array.isArray(spec.blockItems) ? record(spec.blockItems[0]) : undefined;
  const items = Array.isArray(spec.items) ? spec.items.map(itemFrom).filter((item): item is GroupItemDraft => item != null) : base.items;
  const extra: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(spec)) if (!OWNED.has(key)) extra[key] = value;
  return {
    ...base,
    enabled: spec.enabled !== false,
    connection: text(spec.connection) || "<default>",
    table: text(spec.table),
    groupType: isGroupType(spec.groupType) ? spec.groupType : "standard",
    updateMode: isUpdate(spec.updateMode) ? spec.updateMode : "tagToDb",
    tableAction: spec.tableAction === "update" ? "update" : "insert",
    rateMillis: execution?.rateMillis === undefined ? "1000" : String(execution.rateMillis),
    triggerTag: text(trigger?.tag),
    triggerMode: isTrigger(trigger?.mode) ? trigger.mode : "",
    rowMode: isRow(row?.mode) ? row.mode : "last",
    keyColumn: text(row?.keyColumn),
    keyItem: text(row?.keyItem),
    procedure: text(procedure?.name),
    items: items.length > 0 ? items : base.items,
    blockColumn: text(block?.column) || "value",
    blockTags: Array.isArray(block?.tags) ? block.tags.map(String).join("\n") : "",
    extra,
  };
}

export function groupProblem(name: string, draft: GroupDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) return "Use a short lowercase name: letters, digits, and hyphens.";
  if (!/^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?|<default>)$/.test(draft.connection.trim() || "<default>")) {
    return "The connection must be a connection name or <default>.";
  }
  if (draft.groupType !== "storedProcedure" && !/^[A-Za-z_][A-Za-z0-9_]{0,62}(\.[A-Za-z_][A-Za-z0-9_]{0,62})?$/.test(draft.table.trim())) {
    return "The table name must be a SQL name, optionally schema-qualified.";
  }
  if (draft.groupType === "storedProcedure" && !draft.procedure.trim()) return "A stored-procedure group needs the procedure name.";
  const rate = numberOr(draft.rateMillis, 0);
  if (rate < 100) return "The rate must be at least 100 milliseconds.";
  if (draft.groupType !== "block") {
    for (const item of draft.items) {
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,62}$/.test(item.name.trim())) return "Each item needs a name of letters, digits, and underscores.";
      if (item.source === "tag" && !item.tag.trim()) return `Item ${item.name} needs a tag path.`;
    }
  }
  return null;
}

function itemFrom(value: unknown): GroupItemDraft | null {
  const row = record(value);
  if (!row || typeof row.name !== "string") return null;
  return {
    name: row.name,
    source: row.source === "expression" || row.source === "static" ? row.source : "tag",
    tag: text(row.tag),
    expression: text(row.expression),
    value: row.value == null ? "" : String(row.value),
    column: text(row.column),
    meter: row.meter === "hour" || row.meter === "event" ? row.meter : "",
  };
}

function record(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberOr(value: string, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function isGroupType(value: unknown): value is GroupType {
  return value === "standard" || value === "historical" || value === "block" || value === "storedProcedure";
}

function isUpdate(value: unknown): value is UpdateMode {
  return value === "tagToDb" || value === "dbToTag" || value === "bidirectionalTagWins" || value === "bidirectionalDbWins";
}

function isTrigger(value: unknown): value is GroupDraft["triggerMode"] {
  return value === "rising" || value === "falling" || value === "while" || value === "change";
}

function isRow(value: unknown): value is GroupDraft["rowMode"] {
  return value === "first" || value === "last" || value === "key" || value === "where";
}
