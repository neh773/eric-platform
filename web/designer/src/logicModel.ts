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

export function mappingDocument(rows: MapRow[]): MappingFile {
  const point = (row: MapRow) => ({
    name: row.name,
    address: row.address,
    tag: row.tag,
    type: row.type,
    safe: row.type === "BOOL" ? row.safe === "true" : Number(row.safe || "0"),
  });
  return {
    inputs: rows.filter((row) => row.direction === "in").map(point),
    outputs: rows.filter((row) => row.direction === "out").map(point),
  };
}

export function rowsFromMapping(file: MappingFile): MapRow[] {
  const side = (items: Array<Record<string, unknown>>, direction: "in" | "out"): MapRow[] =>
    items.map((item) => ({
      name: String(item.name ?? ""),
      address: String(item.address ?? ""),
      tag: String(item.tag ?? ""),
      type: String(item.type ?? "BOOL"),
      direction,
      safe: String(item.safe ?? (String(item.type ?? "BOOL") === "BOOL" ? "false" : "0")),
    }));
  return [...side(file.inputs ?? [], "in"), ...side(file.outputs ?? [], "out")];
}
