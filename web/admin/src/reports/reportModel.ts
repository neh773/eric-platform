/**
 * The report workspace's draft: one report spec (`reporting/Report`) edited in memory by the overview, data, design
 * and schedule tabs, previewed through the gateway, and saved back as the resource. Every helper here is pure and
 * returns a new spec, so the tabs share one value and the preview always runs what is on screen.
 * doc source: FEATURES REP-058, REP-028.
 */

export type Json = string | number | boolean | null | Json[] | { [key: string]: Json };
export type Obj = Record<string, Json>;
export type ReportSpec = Record<string, unknown>;

export const PARAMETER_TYPES = ["string", "number", "boolean", "timestamp", "timeRange"] as const;
export type ParameterType = (typeof PARAMETER_TYPES)[number];

export interface ParameterDraft {
  name: string;
  type: ParameterType;
  label?: string;
  required?: boolean;
  default?: unknown;
  expression?: string;
}

export function isObj(value: unknown): value is Obj {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function asList(value: unknown): Obj[] {
  return Array.isArray(value) ? value.filter(isObj) : [];
}

export function text(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

/** A spec that saves: a title and one placeholder data source for a named query. */
export function newSpec(title: string, query: string): ReportSpec {
  return {
    title,
    description: "",
    enabled: true,
    parameters: [],
    dataSources: [{ name: "rows", type: "namedQuery", query }],
    layout: { layoutVersion: 1, components: [] },
  };
}

// ---------------------------------------------------------------- layout

export function layoutOf(spec: ReportSpec): Obj {
  return isObj(spec.layout) ? spec.layout : { layoutVersion: 1, components: [] };
}

export function withLayout(spec: ReportSpec, change: (layout: Obj) => Obj): ReportSpec {
  return { ...spec, layout: { layoutVersion: 1, ...change(layoutOf(spec)) } };
}

export function componentsOf(spec: ReportSpec): Obj[] {
  return asList(layoutOf(spec).components);
}

export function withComponents(spec: ReportSpec, change: (components: Obj[]) => Obj[]): ReportSpec {
  return withLayout(spec, (layout) => ({ ...layout, components: change(asList(layout.components)) }));
}

/** Set `a.b` inside an object, removing the key (and a parent left empty) when [value] is undefined. */
export function setPath(obj: Obj, path: string, value: Json | undefined): Obj {
  const [head, ...rest] = path.split(".");
  if (!head) return obj;
  if (rest.length === 0) {
    const next = { ...obj };
    if (value === undefined) delete next[head];
    else next[head] = value;
    return next;
  }
  const child = isObj(obj[head]) ? (obj[head] as Obj) : {};
  const updated = setPath(child, rest.join("."), value);
  const next = { ...obj };
  if (Object.keys(updated).length === 0) delete next[head];
  else next[head] = updated;
  return next;
}

export function getPath(obj: Obj, path: string): Json | undefined {
  let cursor: Json | undefined = obj;
  for (const part of path.split(".")) {
    if (!isObj(cursor)) return undefined;
    cursor = cursor[part];
  }
  return cursor;
}

export interface PageMarginsDraft {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

/** The four margins of `page.margins` (a number, or a partial object), in points. */
export function marginsOf(layout: Obj): PageMarginsDraft {
  const raw = getPath(layout, "page.margins");
  const base = typeof raw === "number" ? raw : 40;
  const side = (name: keyof PageMarginsDraft) => (isObj(raw) && typeof raw[name] === "number" ? (raw[name] as number) : base);
  return { top: side("top"), right: side("right"), bottom: side("bottom"), left: side("left") };
}

export function withMargins(layout: Obj, margins: PageMarginsDraft): Obj {
  const { top, right, bottom, left } = margins;
  const uniform = top === right && right === bottom && bottom === left;
  const value: Json = uniform ? top : { top, right, bottom, left };
  return setPath(layout, "page.margins", value);
}

// ---------------------------------------------------------------- parameters

export function parametersOf(spec: ReportSpec): ParameterDraft[] {
  return asList(spec.parameters).map((p) => ({
    name: text(p.name),
    type: (PARAMETER_TYPES as readonly string[]).includes(text(p.type)) ? (text(p.type) as ParameterType) : "string",
    label: typeof p.label === "string" ? p.label : undefined,
    required: p.required === true,
    default: "default" in p ? p.default : undefined,
    expression: typeof p.expression === "string" ? p.expression : undefined,
  }));
}

export function withParameters(spec: ReportSpec, parameters: ParameterDraft[]): ReportSpec {
  return {
    ...spec,
    parameters: parameters.map((p) => {
      const out: Obj = { name: p.name, type: p.type };
      if (p.label) out.label = p.label;
      if (p.required) out.required = true;
      if (p.expression) out.expression = p.expression;
      else if (p.default !== undefined && p.default !== "") out.default = p.default as Json;
      return out;
    }),
  };
}

/** The editor's text for a parameter default, and the value a text becomes for [type]. */
export function defaultToText(type: ParameterType, value: unknown): string {
  if (value === undefined || value === null) return "";
  if (type === "timeRange" && isObj(value) && typeof value.last === "string") return value.last;
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

export function textToDefault(type: ParameterType, input: string): unknown {
  const t = input.trim();
  if (t === "") return undefined;
  switch (type) {
    case "number": {
      const n = Number(t);
      return Number.isFinite(n) ? n : t;
    }
    case "boolean":
      return t === "true" ? true : t === "false" ? false : t;
    case "timeRange":
      return /^\d+[smhdw]$/.test(t) ? { last: t } : t;
    case "timestamp":
      return /^-?\d+$/.test(t) ? Number(t) : t;
    default:
      return t;
  }
}

/** Parameter names and `name.from` / `name.to` for time ranges, as a data source binding may point at them. */
export function bindingTargets(parameters: ParameterDraft[]): { value: string; label: string }[] {
  return parameters.flatMap((p) =>
    p.type === "timeRange"
      ? [{ value: `${p.name}.from`, label: `${p.name}.from` }, { value: `${p.name}.to`, label: `${p.name}.to` }]
      : [{ value: p.name, label: p.name }],
  );
}

// ---------------------------------------------------------------- data sources

export type BindingDraft = { param: string } | { value: Json };

export function bindingToTarget(binding: Json | undefined): string {
  if (!isObj(binding) || typeof binding.param !== "string") return "";
  return typeof binding.field === "string" ? `${binding.param}.${binding.field}` : binding.param;
}

export function targetToBinding(target: string, literal: string): Obj {
  if (target === "") return { value: parseLiteral(literal) };
  const dot = target.lastIndexOf(".");
  const last = dot >= 0 ? target.slice(dot + 1) : "";
  if (last === "from" || last === "to") return { param: target.slice(0, dot), field: last };
  return { param: target };
}

export function parseLiteral(input: string): Json {
  const t = input.trim();
  if (t === "true") return true;
  if (t === "false") return false;
  if (t !== "" && Number.isFinite(Number(t))) return Number(t);
  return input;
}

/** `:name` placeholders of a SQL text, in order of first use. */
export function sqlPlaceholders(sql: string): string[] {
  const seen: string[] = [];
  for (const match of sql.matchAll(/(?<![:\w]):([A-Za-z_][A-Za-z0-9_-]*)/g)) {
    const name = match[1]!;
    if (!seen.includes(name)) seen.push(name);
  }
  return seen;
}

export function uniqueName(base: string, taken: string[]): string {
  if (!taken.includes(base)) return base;
  for (let i = 2; ; i++) if (!taken.includes(`${base}${i}`)) return `${base}${i}`;
}

export function newDataSource(type: "namedQuery" | "sql" | "tagHistory" | "alarmJournal", taken: string[], defaults: { query?: string; connection?: string; range?: string }): Obj {
  const name = uniqueName(type === "tagHistory" ? "history" : type === "alarmJournal" ? "alarms" : "rows", taken);
  if (type === "alarmJournal") return { name, type, range: defaults.range ? { param: defaults.range } : { value: { last: "24h" } } };
  if (type === "namedQuery") return { name, type, query: defaults.query ?? "" };
  if (type === "sql") return { name, type, connection: defaults.connection ?? "", sql: "SELECT 1 AS value" };
  return { name, type, mode: "raw", tags: [{ path: "" }], range: defaults.range ? { param: defaults.range } : { value: { last: "1h" } } };
}

// ---------------------------------------------------------------- data keys

export interface ResultLike {
  datasets?: { name: string; columns?: { name: string }[]; rows?: unknown[][] }[];
}

export interface DataKey {
  key: string;
  group: "built-in" | "parameter" | "data";
}

/** The `@key@` names a text block may use: built-ins, parameters, and `dataset.column` of the last preview. */
export function availableDataKeys(spec: ReportSpec, result?: ResultLike | null): DataKey[] {
  const keys: DataKey[] = ["Date", "Report", "Page"].map((key) => ({ key, group: "built-in" as const }));
  for (const p of parametersOf(spec)) keys.push({ key: p.name, group: "parameter" });
  for (const ds of result?.datasets ?? []) {
    for (const column of ds.columns ?? []) keys.push({ key: `${ds.name}.${column.name}`, group: "data" });
  }
  return keys;
}

/** [value] with `@key@` inserted over the selection (or at the end when the field has no selection yet). */
export function insertDataKey(value: string, key: string, start: number | null, end: number | null): { value: string; caret: number } {
  const at = start ?? value.length;
  const to = end ?? at;
  const token = `@${key}@`;
  return { value: value.slice(0, at) + token + value.slice(to), caret: at + token.length };
}

/** Data keys written as `@name@` in [value] that nothing in [known] provides. */
export function unknownDataKeys(value: string, known: DataKey[]): string[] {
  const names = new Set(known.map((k) => k.key));
  const found: string[] = [];
  for (const match of value.matchAll(/@([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)@/g)) {
    const name = match[1]!;
    // A column name of one dataset's first row also works unqualified; those are known by their dotted form.
    const plain = [...names].some((n) => n.endsWith(`.${name}`));
    if (!names.has(name) && !plain && !found.includes(name)) found.push(name);
  }
  return found;
}

export function stableJson(spec: ReportSpec): string {
  return JSON.stringify(spec);
}
