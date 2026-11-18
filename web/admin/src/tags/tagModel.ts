import { schemaItem, schemaType, type JsonSchema } from "../schema";
import { ACCESS_DENIED } from "./tagClient";

/**
 * Pure helpers for the tag browser and editor (C-16): path handling, resource naming, the property
 * categories the editor groups the `Tag` JSON Schema into, value formatting, and the security fields.
 */

export const DEFAULT_PROVIDER = "default";

export interface ParsedTagPath {
  provider: string;
  /** Path inside the provider, without the `[provider]` prefix. Empty for the provider root. */
  path: string;
}

export function parseTagPath(full: string): ParsedTagPath {
  const match = full.trim().match(/^\[([^\]]*)\](.*)$/);
  if (!match) return { provider: DEFAULT_PROVIDER, path: trimSlashes(full.trim()) };
  return { provider: match[1] || DEFAULT_PROVIDER, path: trimSlashes(match[2] ?? "") };
}

export function formatTagPath(provider: string, path: string): string {
  return `[${provider || DEFAULT_PROVIDER}]${trimSlashes(path)}`;
}

export function childPath(folder: string, name: string): string {
  const parsed = parseTagPath(folder);
  return formatTagPath(parsed.provider, parsed.path ? `${parsed.path}/${trimSlashes(name)}` : trimSlashes(name));
}

export function parentPath(full: string): string {
  const parsed = parseTagPath(full);
  const cut = parsed.path.lastIndexOf("/");
  return formatTagPath(parsed.provider, cut < 0 ? "" : parsed.path.slice(0, cut));
}

export function leafName(full: string): string {
  const parsed = parseTagPath(full);
  const cut = parsed.path.lastIndexOf("/");
  return cut < 0 ? parsed.path : parsed.path.slice(cut + 1);
}

function trimSlashes(value: string): string {
  return value.replace(/^\/+|\/+$/g, "");
}

/** Problems with a folder-relative tag path a user typed (a name, or `Folder/Name`). */
export function pathProblems(path: string): string[] {
  const trimmed = path.trim();
  if (!trimmed) return ["Enter a name."];
  if (/[[\]]/.test(trimmed)) return ["Names cannot contain [ or ]."];
  const segments = trimmed.split("/");
  if (segments.some((segment) => segment.trim() === "")) return ["Path segments cannot be empty."];
  if (segments.some((segment) => segment !== segment.trim())) return ["Path segments cannot start or end with a space."];
  if (segments.some((segment) => segment.startsWith("."))) return ["Path segments cannot start with a dot."];
  return [];
}

/**
 * A DNS-label resource name for a new tag: the provider (unless default) and the path segments,
 * lowercased and joined with `-`, the same shape the UDT wizard uses. Long paths keep a prefix and
 * add 8 hex digits of a hash of the path. `attempt` > 0 adds `-<attempt+1>` for a taken name.
 */
export function resourceNameFor(provider: string, path: string, attempt = 0): string {
  const source = (provider.toLowerCase() === DEFAULT_PROVIDER ? "" : `${provider}-`) + trimSlashes(path).split("/").join("-");
  let slug = source.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  const suffix = attempt > 0 ? `-${attempt + 1}` : "";
  if (!slug) slug = `tag-${hash8(path)}`;
  if (slug.length + suffix.length > 63) slug = `${slug.slice(0, 54 - suffix.length).replace(/-+$/, "")}-${hash8(`${provider}:${path}`)}`;
  return `${slug}${suffix}`;
}

/** FNV-1a, 32 bits, as 8 hex digits. Only used to shorten long resource names. */
function hash8(value: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, "0");
}

// ---- Editor categories ----------------------------------------------------------------------------

export type CategoryId = "basic" | "value" | "numeric" | "scaling" | "history" | "alarms" | "security" | "scripting" | "advanced";

export interface Category {
  id: CategoryId;
  label: string;
  /** Shown when the category has no schema properties on this gateway. */
  empty: string;
}

export const CATEGORIES: readonly Category[] = [
  { id: "basic", label: "Basic", empty: "" },
  { id: "value", label: "Value", empty: "" },
  { id: "numeric", label: "Numeric", empty: "" },
  { id: "scaling", label: "Scaling", empty: "" },
  {
    id: "history",
    label: "History",
    empty: "The tag schema on this gateway has no per-tag history properties. History collection is configured outside the tag.",
  },
  { id: "alarms", label: "Alarms", empty: "" },
  { id: "security", label: "Security", empty: "" },
  {
    id: "scripting",
    label: "Scripting",
    empty: "Tag event scripts are not edited here yet. This section is a read-only placeholder.",
  },
  { id: "advanced", label: "Advanced", empty: "" },
];

const BY_PROPERTY: Record<string, CategoryId> = {
  provider: "basic",
  path: "basic",
  name: "basic",
  type: "basic",
  dataType: "basic",
  documentation: "basic",
  tagGroup: "basic",
  executionMode: "basic",
  executionRateMillis: "basic",
  value: "value",
  expression: "value",
  sourcePath: "value",
  readExpression: "value",
  writeExpression: "value",
  typeId: "value",
  parameters: "value",
  opcServer: "value",
  opcItemPath: "value",
  scanPeriodMillis: "value",
  commsTimeoutMillis: "value",
  commsLoss: "value",
  deadband: "numeric",
  engLow: "numeric",
  engHigh: "numeric",
  engUnit: "numeric",
  scaling: "scaling",
  alarms: "alarms",
  readOnly: "security",
  writable: "security",
  readPermissions: "security",
  writePermissions: "security",
};

export function categoryOf(property: string): CategoryId {
  const known = BY_PROPERTY[property];
  if (known) return known;
  if (/^histor/i.test(property)) return "history";
  if (/script|event/i.test(property)) return "scripting";
  if (/alarm/i.test(property)) return "alarms";
  if (/permission/i.test(property)) return "security";
  return "advanced";
}

/** Property names of the Tag schema per category, in schema order. Every property lands in exactly one. */
export function categorize(schema: JsonSchema): Record<CategoryId, string[]> {
  const out = Object.fromEntries(CATEGORIES.map((c) => [c.id, [] as string[]])) as Record<CategoryId, string[]>;
  for (const key of Object.keys(schema.properties ?? {})) out[categoryOf(key)].push(key);
  return out;
}

/** True when the shared SchemaForm can draw and edit this schema without losing a value. */
export function formCanEdit(schema: JsonSchema | undefined): boolean {
  if (!schema) return false;
  if (schema.enum) return true;
  const raw = schema as Record<string, unknown>;
  if (raw.anyOf || raw.oneOf || raw.allOf) return false;
  const type = schemaType(schema);
  if (type === "string" || type === "number" || type === "integer" || type === "boolean") return true;
  if (type === "array") return formCanEdit(schemaItem(schema) ?? undefined);
  if (type === "object" || schema.properties) {
    const props = Object.values(schema.properties ?? {});
    if (props.length === 0) return false;
    return props.every((child) => formCanEdit(child));
  }
  return false;
}

// ---- Values ---------------------------------------------------------------------------------------

export function formatValue(value: unknown, quality?: string): string {
  if (quality === ACCESS_DENIED) return "Access denied";
  if (quality === undefined) return "";
  if (value === null || value === undefined) return "null";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function isGood(quality: string | undefined): boolean {
  return !!quality && quality.startsWith("Good");
}

/** A value typed into a write box, converted for the tag's data type. */
export function parseValueInput(raw: string, dataType: string | undefined): { value?: unknown; error?: string } {
  const text = raw.trim();
  const type = (dataType ?? "").toLowerCase();
  if (type === "boolean") {
    if (/^(true|1)$/i.test(text)) return { value: true };
    if (/^(false|0)$/i.test(text)) return { value: false };
    return { error: "Enter true or false." };
  }
  if (["int32", "int64", "float", "double", "int", "integer", "number"].includes(type)) {
    if (text === "") return { error: "Enter a number." };
    const n = Number(text);
    if (!Number.isFinite(n)) return { error: "Enter a number." };
    if (type.startsWith("int") && !Number.isInteger(n)) return { error: "Enter a whole number." };
    return { value: n };
  }
  if (type === "string" || type === "datetime") return { value: raw };
  if (type === "dataset") {
    if (text === "") return { error: "Enter a dataset as JSON." };
    try {
      const value = JSON.parse(text) as unknown;
      if (value === null || typeof value !== "object" || Array.isArray(value)) return { error: "Enter a dataset as JSON." };
      return { value };
    } catch {
      return { error: "Enter a dataset as JSON." };
    }
  }
  if (text === "") return { value: "" };
  try {
    return { value: JSON.parse(text) as unknown };
  } catch {
    return { value: raw };
  }
}

/** JSON text for an editor field that SchemaForm cannot draw. Undefined is an empty box. */
export function toJsonText(value: unknown): string {
  return value === undefined ? "" : JSON.stringify(value, null, 2);
}

/** Parse a JSON field. An empty box removes the property. Invalid JSON is an error, not a string. */
export function fromJsonText(raw: string): { value?: unknown; remove?: boolean; error?: string } {
  if (raw.trim() === "") return { remove: true };
  try {
    return { value: JSON.parse(raw) as unknown };
  } catch {
    return { error: "Not valid JSON." };
  }
}

// ---- Security -------------------------------------------------------------------------------------

export interface AccessRule {
  /** False: the key is absent and every caller with the route permission is admitted. */
  restricted: boolean;
  roles: string[];
  zones: string[];
}

/**
 * Reads `readPermissions` / `writePermissions` in any shape the gateway accepts: absent (unrestricted),
 * `{roles, zones}`, a role list, a comma-separated string, or null/""/[]/{} (restricted to nobody).
 */
export function toAccessRule(raw: unknown): AccessRule {
  if (raw === undefined) return { restricted: false, roles: [], zones: [] };
  if (raw === null) return { restricted: true, roles: [], zones: [] };
  if (typeof raw === "string") return { restricted: true, roles: splitNames(raw), zones: [] };
  if (Array.isArray(raw)) return { restricted: true, roles: raw.filter((r): r is string => typeof r === "string"), zones: [] };
  if (typeof raw === "object") {
    const rec = raw as Record<string, unknown>;
    const names = (v: unknown) => (Array.isArray(v) ? v.filter((r): r is string => typeof r === "string") : []);
    return { restricted: true, roles: names(rec.roles), zones: names(rec.zones) };
  }
  return { restricted: true, roles: [], zones: [] };
}

/** The spec value for a rule: undefined removes the key (unrestricted). */
export function fromAccessRule(rule: AccessRule): { roles: string[]; zones: string[] } | undefined {
  if (!rule.restricted) return undefined;
  return { roles: rule.roles, zones: rule.zones };
}

export function splitNames(raw: string): string[] {
  return raw
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function describeAccess(rule: AccessRule): string {
  if (!rule.restricted) return "Anyone with the gateway permission";
  if (rule.roles.length === 0 && rule.zones.length === 0) return "Nobody";
  const parts: string[] = [];
  if (rule.roles.length > 0) parts.push(`roles ${rule.roles.join(", ")}`);
  if (rule.zones.length > 0) parts.push(`zones ${rule.zones.join(", ")}`);
  return parts.join(" in ");
}

/** Set or remove one spec key without mutating the input. */
export function withKey(spec: Record<string, unknown>, key: string, value: unknown): Record<string, unknown> {
  const next = { ...spec };
  if (value === undefined) delete next[key];
  else next[key] = value;
  return next;
}
