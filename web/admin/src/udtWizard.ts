/**
 * Pure helpers for the UDT multi-instance wizard (TAG-024).
 *
 * The wizard expands a name pattern (or an explicit list) into instance names, keeps one parameter
 * row per instance, and builds the body for POST /api/v1/tags/udt-instances. The gateway validates
 * the request again; these helpers only shape it and catch obvious mistakes before a round trip.
 */

export const MAX_INSTANCES = 1000;
export const PATTERN_TOKEN = "{n}";

export interface NamePattern {
  template: string;
  start: number;
  count: number;
  step: number;
  /** Zero-pad the number to this many digits (0 = no padding). */
  pad: number;
}

export type CollisionPolicy = "fail" | "skip" | "rename";

/** One editable row: values typed into the parameter table, by parameter name. Empty means "use the default". */
export type ParameterRow = Record<string, string>;

const SEGMENT = /^[A-Za-z0-9_\- ]+$/;

export function patternProblems(pattern: NamePattern): string[] {
  const out: string[] = [];
  if (!pattern.template.includes(PATTERN_TOKEN)) out.push("The pattern must contain {n}.");
  if (!Number.isInteger(pattern.count) || pattern.count < 1 || pattern.count > MAX_INSTANCES) {
    out.push(`Count must be 1 to ${MAX_INSTANCES}.`);
  }
  if (!Number.isInteger(pattern.start) || pattern.start < 0) out.push("Start must be a whole number, 0 or more.");
  if (!Number.isInteger(pattern.step) || pattern.step < 1) out.push("Step must be a whole number, 1 or more.");
  if (!Number.isInteger(pattern.pad) || pattern.pad < 0 || pattern.pad > 9) out.push("Padding must be 0 to 9 digits.");
  return out;
}

/** `Pump_{n}`, start 1, count 3, pad 2 gives Pump_01, Pump_02, Pump_03. Invalid patterns give no names. */
export function expandPattern(pattern: NamePattern): string[] {
  if (patternProblems(pattern).length > 0) return [];
  const names: string[] = [];
  for (let i = 0; i < pattern.count; i++) {
    const number = String(pattern.start + i * pattern.step).padStart(pattern.pad, "0");
    names.push(pattern.template.split(PATTERN_TOKEN).join(number));
  }
  return names;
}

/** One name per line. Blank lines are dropped and names are trimmed. */
export function parseNameList(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/** Problems the gateway would also report: bad characters and duplicates (case-insensitive). */
export function nameProblems(names: readonly string[]): Map<number, string> {
  const out = new Map<number, string>();
  const seen = new Map<string, number>();
  names.forEach((name, index) => {
    if (!SEGMENT.test(name)) {
      out.set(index, "Use letters, digits, '_', '-', and spaces.");
      return;
    }
    const key = name.toLowerCase();
    const first = seen.get(key);
    if (first !== undefined) out.set(index, `Same name as row ${first + 1}.`);
    else seen.set(key, index);
  });
  if (names.length > MAX_INSTANCES) out.set(MAX_INSTANCES, `At most ${MAX_INSTANCES} instances per request.`);
  return out;
}

/** Keep existing rows, add empty rows, or drop rows from the end so there is one row per name. */
export function resizeTable(table: readonly ParameterRow[], count: number): ParameterRow[] {
  const next = table.slice(0, count).map((row) => ({ ...row }));
  while (next.length < count) next.push({});
  return next;
}

export function setCell(table: readonly ParameterRow[], row: number, parameter: string, value: string): ParameterRow[] {
  return table.map((current, index) => (index === row ? { ...current, [parameter]: value } : current));
}

/** Copy [value] into [parameter] on every row. */
export function fillColumn(table: readonly ParameterRow[], parameter: string, value: string): ParameterRow[] {
  return table.map((row) => ({ ...row, [parameter]: value }));
}

export interface UdtInstanceRequestBody {
  typeId: string;
  provider: string;
  folder: string;
  onCollision: CollisionPolicy;
  dryRun: boolean;
  rows: { name: string; parameters: Record<string, string> }[];
}

/**
 * Explicit names and only the non-empty cells. Empty cells are left out so the instance takes the
 * UDT default (and keeps following it when the definition changes).
 */
export function buildRequest(input: {
  typeId: string;
  provider?: string;
  folder: string;
  names: readonly string[];
  table: readonly ParameterRow[];
  parameters: readonly string[];
  onCollision: CollisionPolicy;
  dryRun: boolean;
}): UdtInstanceRequestBody {
  return {
    typeId: input.typeId,
    provider: input.provider ?? "default",
    folder: input.folder.trim().replace(/^\/+|\/+$/g, ""),
    onCollision: input.onCollision,
    dryRun: input.dryRun,
    rows: input.names.map((name, index) => {
      const row = input.table[index] ?? {};
      const parameters: Record<string, string> = {};
      for (const parameter of input.parameters) {
        const value = row[parameter];
        if (value !== undefined && value !== "") parameters[parameter] = value;
      }
      return { name, parameters };
    }),
  };
}

/** Stable key of everything a preview depends on, so Create is only offered for what was previewed. */
export function requestKey(body: UdtInstanceRequestBody): string {
  return JSON.stringify({ ...body, dryRun: false });
}
