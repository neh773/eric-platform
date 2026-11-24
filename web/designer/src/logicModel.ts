/** A row in the no-code tag mapping. The saved file is the JSON eric-plc reads. */
export interface MapRow {
  name: string;
  address: string;
  tag: string;
  type: string;
  direction: "in" | "out";
  safe: string;
}

export interface MappingFile {
  inputs: Array<Record<string, unknown>>;
  outputs: Array<Record<string, unknown>>;
  /** UDT instances bound to program structure variables (0.1.1). */
  udts?: UdtBinding[];
  periodMs?: number;
  maxConsecutiveOverruns?: number;
}

export const DEFAULT_PLC_PERIOD_MS = 100;
export const DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS = 10;

export interface ScanSettings {
  periodMs: number;
  maxConsecutiveOverruns: number;
}

/** Label for the Logic runtime status line. */
export function logicStatusLabel(state: string, message?: string | null, fault?: string | null): string {
  const reason = (fault ?? "").trim();
  const detail = (message ?? "").trim();
  if (state === "faulted" && (reason === "watchdog" || /watchdog/i.test(detail))) return "Faulted: watchdog";
  if (state === "faulted" && (reason || detail)) return `Faulted: ${reason || detail}`;
  if (state === "stopped" && detail) return `Stopped: ${detail}`;
  return state;
}

export function scanSettingsFromMapping(file: MappingFile | Record<string, unknown> | null | undefined): ScanSettings {
  const period = Number((file as MappingFile | undefined)?.periodMs);
  const overruns = Number((file as MappingFile | undefined)?.maxConsecutiveOverruns);
  return {
    periodMs: Number.isFinite(period) && period >= 1 ? Math.min(60_000, Math.floor(period)) : DEFAULT_PLC_PERIOD_MS,
    maxConsecutiveOverruns: Number.isFinite(overruns) && overruns >= 0 ? Math.floor(overruns) : DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS,
  };
}

/** A gateway UDT instance bound to a structure variable; `members` overrides a member's direction. */
export interface UdtBinding {
  variable: string;
  instance: string;
  members: Record<string, "in" | "out" | "none">;
}

export interface UdtMemberView {
  path: string;
  member: string;
  type: string;
  direction: "in" | "out" | "none";
  auto: "in" | "out" | "none";
  tag: string;
  address?: string | null;
  note?: string | null;
}

export interface UdtVariableView {
  variable: string;
  type: string;
  udt?: string | null;
  declaredInProgram: boolean;
  instance?: string | null;
  problems: string[];
  members: UdtMemberView[];
}

/** GET /api/v1/logic/programs/{name}/udts */
export interface LogicUdtView {
  types: Array<{ udt: string; st: string }>;
  instances: Array<{ path: string; udt: string }>;
  variables: UdtVariableView[];
}

export function udtBindings(file: MappingFile): UdtBinding[] {
  return (file.udts ?? []).map((item) => ({ variable: item.variable, instance: item.instance, members: { ...(item.members ?? {}) } }));
}

/** Sets (or clears, with an empty instance) the binding for [variable]. */
export function bindInstance(bindings: UdtBinding[], variable: string, instance: string): UdtBinding[] {
  const rest = bindings.filter((item) => item.variable.toLowerCase() !== variable.toLowerCase());
  if (!instance) return rest;
  const previous = bindings.find((item) => item.variable.toLowerCase() === variable.toLowerCase());
  return [...rest, { variable, instance, members: previous?.instance === instance ? previous.members : {} }];
}

/** "auto" removes the override, so the direction follows what the program writes. */
export function setMemberDirection(bindings: UdtBinding[], variable: string, member: string, direction: "auto" | "in" | "out" | "none"): UdtBinding[] {
  return bindings.map((item) => {
    if (item.variable.toLowerCase() !== variable.toLowerCase()) return item;
    const members = { ...item.members };
    if (direction === "auto") delete members[member];
    else members[member] = direction;
    return { ...item, members };
  });
}

/** Watched rows grouped by UDT variable; plain rows have the group "". */
export function groupWatched<T extends { udt?: string | null }>(rows: T[]): Array<{ udt: string; rows: T[] }> {
  const groups = new Map<string, T[]>();
  for (const row of rows) {
    const key = row.udt ?? "";
    groups.set(key, [...(groups.get(key) ?? []), row]);
  }
  return [...groups.entries()].map(([udt, items]) => ({ udt, rows: items }));
}

/** The ST type an address size letter implies: X bit, W word, D double word (REAL), L long word (LREAL). */
export function typeForAddress(address: string): string | null {
  const m = /^%[IQM]([XBWDL])/i.exec(address.trim());
  if (!m) return null;
  return ({ X: "BOOL", B: "INT", W: "INT", D: "REAL", L: "LREAL" } as Record<string, string>)[m[1]!.toUpperCase()] ?? null;
}

function shown(value: unknown): string {
  return typeof value === "boolean" ? (value ? "TRUE" : "FALSE")
    : typeof value === "number" ? (Number.isInteger(value) ? String(value) : value.toFixed(2))
    : String(value);
}

export interface OnlineMark {
  text: string;
  kind: "true" | "false" | "number" | "struct" | "other";
  forced: boolean;
}

/**
 * Online mode: what the editor shows beside each variable, by lower-case name (`level`, `os1a.inhibitcmd`).
 * A structure variable's root (`os1a`) gets its members, `Level 96, InhibitCmd TRUE`, for its declaration.
 */
export function onlineMarks(rows: Array<{ name: string; value: unknown; forced?: boolean; type?: string | null }>): Record<string, OnlineMark> {
  const out: Record<string, OnlineMark> = {};
  const members = new Map<string, string[]>();
  for (const row of rows) {
    const bool = typeof row.value === "boolean" || (row.type ?? "").toUpperCase() === "BOOL";
    const on = row.value === true || row.value === 1 || row.value === "true";
    const mark: OnlineMark = bool
      ? { text: on ? "TRUE" : "FALSE", kind: on ? "true" : "false", forced: !!row.forced }
      : { text: shown(row.value), kind: typeof row.value === "number" ? "number" : "other", forced: !!row.forced };
    out[row.name.toLowerCase()] = mark;
    const dot = row.name.indexOf(".");
    if (dot > 0) {
      const root = row.name.slice(0, dot).toLowerCase();
      members.set(root, [...(members.get(root) ?? []), `${row.name.slice(dot + 1)} ${mark.text}`]);
    }
  }
  for (const [root, items] of members) if (!out[root]) out[root] = { text: items.join(", "), kind: "struct", forced: false };
  return out;
}

export const ST_COMPLETION = [
  "PROGRAM", "END_PROGRAM", "FUNCTION_BLOCK", "END_FUNCTION_BLOCK",
  "VAR", "END_VAR", "VAR_INPUT", "VAR_OUTPUT",
  "IF", "THEN", "ELSIF", "ELSE", "END_IF",
  "BOOL", "INT", "DINT", "REAL", "LREAL",
  "TON", "TOF", "TP", "ERIC_PID",
  "ERIC_AI_SCALE", "ERIC_ALARM_LIMITS", "ERIC_MOTOR", "ERIC_VALVE", "ERIC_RAMP", "ERIC_FILTER",
  "ERIC_TOTALIZER", "ERIC_RUNTIME", "ERIC_HYSTERESIS", "ERIC_SELECT", "ERIC_SPLIT_RANGE",
  "ERIC_TPO", "ERIC_LEADLAG", "ERIC_DEADTIME", "ERIC_SEQUENCER", "ERIC_ALTERNATOR",
];

export function mappingDocument(rows: MapRow[], udts: UdtBinding[] = [], settings?: ScanSettings): MappingFile {
  const point = (row: MapRow) => ({
    name: row.name,
    address: row.address,
    tag: row.tag,
    type: row.type,
    safe: row.type === "BOOL" ? row.safe === "true" : Number(row.safe || "0"),
  });
  const scan = settings ?? { periodMs: DEFAULT_PLC_PERIOD_MS, maxConsecutiveOverruns: DEFAULT_PLC_MAX_CONSECUTIVE_OVERRUNS };
  const file: MappingFile = {
    inputs: rows.filter((row) => row.direction === "in").map(point),
    outputs: rows.filter((row) => row.direction === "out").map(point),
    periodMs: scan.periodMs,
    maxConsecutiveOverruns: scan.maxConsecutiveOverruns,
  };
  if (udts.length > 0) file.udts = udts;
  return file;
}

export function rowsFromMapping(file: MappingFile): MapRow[] {
  const side = (items: Array<Record<string, unknown>>, direction: "in" | "out"): MapRow[] =>
    items.filter((item) => item.udt === undefined || item.udt === null).map((item) => ({
      name: String(item.name ?? ""),
      address: String(item.address ?? ""),
      tag: String(item.tag ?? ""),
      type: String(item.type ?? "BOOL"),
      direction,
      safe: String(item.safe ?? (String(item.type ?? "BOOL") === "BOOL" ? "false" : "0")),
    }));
  return [...side(file.inputs ?? [], "in"), ...side(file.outputs ?? [], "out")];
}
