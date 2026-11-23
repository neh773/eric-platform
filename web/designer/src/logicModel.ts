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

export function mappingDocument(rows: MapRow[], udts: UdtBinding[] = []): MappingFile {
  const point = (row: MapRow) => ({
    name: row.name,
    address: row.address,
    tag: row.tag,
    type: row.type,
    safe: row.type === "BOOL" ? row.safe === "true" : Number(row.safe || "0"),
  });
  const file: MappingFile = {
    inputs: rows.filter((row) => row.direction === "in").map(point),
    outputs: rows.filter((row) => row.direction === "out").map(point),
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
