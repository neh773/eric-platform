/**
 * UDT definition editor model (0.1.1). A definition is the `tags/gateway/Udt/<name>` config resource; its members
 * use the same fields as a tag. The editor keeps each member as the raw spec object, so fields it has no control
 * for (security, scripting, tag groups) survive an edit untouched.
 */

export type Spec = Record<string, unknown>;

export const MEMBER_KINDS: readonly { id: string; label: string }[] = [
  { id: "opc", label: "Device address" },
  { id: "memory", label: "Memory" },
  { id: "expression", label: "Expression" },
  { id: "reference", label: "Reference" },
  { id: "derived", label: "Derived (scaled from another tag)" },
  { id: "udt", label: "Nested UDT" },
];

export const DATA_TYPES = ["Boolean", "Int32", "Int64", "Float", "Double", "String", "DateTime", "Document"] as const;
export const PARAMETER_TYPES = ["String", "Int32", "Double", "Boolean"] as const;
export const ALARM_MODES = ["AboveValue", "BelowValue", "Equality", "Bit", "OutOfEngRange", "OutsideValues"] as const;
export const ALARM_PRIORITIES = ["Diagnostic", "Low", "Medium", "High", "Critical"] as const;

/** Returns a copy of [spec] with [patch] applied; an undefined or empty-string value removes the key. */
export function patchSpec(spec: Spec, patch: Spec): Spec {
  const next: Spec = { ...spec };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined || value === "") delete next[key];
    else next[key] = value;
  }
  return next;
}

export function str(value: unknown): string {
  return typeof value === "string" ? value : typeof value === "number" || typeof value === "boolean" ? String(value) : "";
}

export function numText(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : "";
}

/** "" → undefined (removes the key), otherwise a finite number, or the text itself so validation can say so. */
export function toNumber(text: string): number | undefined {
  if (text.trim() === "") return undefined;
  const n = Number(text);
  return Number.isFinite(n) ? n : undefined;
}

export function obj(value: unknown): Spec {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Spec) : {};
}

export function list(value: unknown): Spec[] {
  return Array.isArray(value) ? value.map(obj) : [];
}

/** Resource names are lowercase alphanumeric and '-', 1 to 63 characters. */
export function resourceNameForType(path: string): string {
  const leaf = path.split("/").filter(Boolean).join("-");
  const name = leaf
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 63)
    .replace(/-$/g, "");
  return name || "udt";
}

/** Problems that stop a save, in the order the form shows them. */
export function definitionProblems(spec: Spec): string[] {
  const problems: string[] = [];
  const path = str(spec.path).trim();
  if (!path) problems.push("Give the UDT a type path, for example Types/LiftStation.");
  const params = list(spec.parameters);
  const seenParams = new Set<string>();
  for (const p of params) {
    const name = str(p.name).trim();
    if (!name) problems.push("Every parameter needs a name.");
    else if (seenParams.has(name.toLowerCase())) problems.push(`Parameter ${name} is listed twice.`);
    seenParams.add(name.toLowerCase());
  }
  const members = list(spec.members);
  const seen = new Set<string>();
  members.forEach((m, index) => {
    const name = str(m.name).trim();
    const label = name || `Member ${index + 1}`;
    if (!name) problems.push(`${label} needs a name.`);
    else if (!/^[A-Za-z0-9_][A-Za-z0-9_ -]*$/.test(name)) problems.push(`${label}: use letters, digits, '_', '-' or spaces.`);
    else if (seen.has(name.toLowerCase())) problems.push(`Member ${name} is listed twice.`);
    seen.add(name.toLowerCase());
    const type = str(m.type) || "memory";
    if (type === "opc" && (!str(m.opcServer).trim() || !str(m.opcItemPath).trim())) problems.push(`${label}: pick the device and enter the address.`);
    if (type === "expression" && !str(m.expression).trim()) problems.push(`${label}: enter the expression.`);
    if ((type === "reference" || type === "derived") && !str(m.sourcePath).trim()) problems.push(`${label}: enter the source tag path.`);
    if (type === "udt" && !str(m.typeId).trim()) problems.push(`${label}: pick the nested UDT type.`);
    for (const alarm of list(m.alarms)) {
      if (!str(alarm.name).trim()) problems.push(`${label}: every alarm needs a name.`);
    }
  });
  return problems;
}

/** Cleans what the form leaves behind before save: empty alarm/parameter rows and empty nested objects. */
export function cleanDefinition(spec: Spec): Spec {
  const out: Spec = { ...spec, path: str(spec.path).trim() };
  const params = list(spec.parameters).filter((p) => str(p.name).trim());
  if (params.length) out.parameters = params.map((p) => patchSpec(p, { name: str(p.name).trim() }));
  else delete out.parameters;
  out.members = list(spec.members).map((m) => {
    let next = patchSpec(m, { name: str(m.name).trim() });
    const scaling = obj(next.scaling);
    if (!str(scaling.mode) || scaling.mode === "none") delete next.scaling;
    const alarms = list(next.alarms).filter((a) => str(a.name).trim());
    next = alarms.length ? { ...next, alarms } : patchSpec(next, { alarms: undefined });
    const deadband = obj(next.deadband);
    if (!str(deadband.mode) || deadband.mode === "off") delete next.deadband;
    return next;
  });
  if (!str(out.parent).trim()) delete out.parent;
  return out;
}

/** One-line description of where a member value comes from, for the members table. */
export function memberSummary(m: Spec): string {
  const type = str(m.type) || "memory";
  switch (type) {
    case "opc":
      return `${str(m.opcServer)} ${str(m.opcItemPath)}`.trim();
    case "expression":
      return str(m.expression);
    case "reference":
    case "derived":
      return str(m.sourcePath);
    case "udt":
      return str(m.typeId);
    default:
      return "memory";
  }
}
