/**
 * JSON Schema draft 2020-12 subset the gateway registry validates:
 * type, properties, required, enum, minimum/maximum, minLength/maxLength, pattern,
 * default, nested objects, and arrays.
 * https://json-schema.org/draft/2020-12/json-schema-core
 * The admin form is generated from the document served at GET /api/v1/schemas/{module}/{kind} (SPEC §4).
 */

export interface JsonSchema {
  type?: string | string[];
  title?: string;
  description?: string;
  properties?: Record<string, JsonSchema>;
  required?: string[];
  enum?: unknown[];
  const?: unknown;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  default?: unknown;
  items?: JsonSchema | boolean;
  additionalProperties?: boolean | JsonSchema;
  format?: string;
  writeOnly?: boolean;
}

export interface FieldError {
  path: string;
  message: string;
}

export function schemaType(schema: JsonSchema): string | undefined {
  if (Array.isArray(schema.type)) return schema.type.find((item) => item !== "null");
  return schema.type;
}

/** Credential-shaped fields stay in the spec on save and are not drawn. */
export function isSensitive(key: string, schema: JsonSchema): boolean {
  if (schema.format === "secret" || schema.writeOnly === true) return true;
  return /password|secret|token|credential/i.test(key);
}

export function fieldLabel(key: string, schema: JsonSchema): string {
  if (isSensitive(key, schema)) return "Stored credential";
  if (schema.title && schema.title.trim()) return schema.title;
  const spaced = key.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function validateAgainstSchema(schema: JsonSchema, value: unknown, path = ""): FieldError[] {
  if (schema.enum && !schema.enum.some((candidate) => candidate === value)) {
    return [{ path, message: `Must be one of ${schema.enum.map(String).join(", ")}` }];
  }
  const type = schemaType(schema);
  if (type === "string") return validateString(schema, value, path);
  if (type === "boolean") {
    return typeof value === "boolean" ? [] : [{ path, message: "Must be true or false" }];
  }
  if (type === "number" || type === "integer") return validateNumber(schema, value, path, type);
  if (type === "array") return validateArray(schema, value, path);
  if (type === "object" || schema.properties) return validateObject(schema, value, path);
  return [];
}

function validateString(schema: JsonSchema, value: unknown, path: string): FieldError[] {
  if (typeof value !== "string") return [{ path, message: "Must be text" }];
  const errors: FieldError[] = [];
  if (schema.minLength !== undefined && value.length < schema.minLength) {
    errors.push({ path, message: `At least ${schema.minLength} character${schema.minLength === 1 ? "" : "s"}` });
  }
  if (schema.maxLength !== undefined && value.length > schema.maxLength) {
    errors.push({ path, message: `At most ${schema.maxLength} characters` });
  }
  if (schema.pattern && !matchesPattern(schema.pattern, value)) {
    errors.push({ path, message: "Does not match the required pattern" });
  }
  return errors;
}

function matchesPattern(pattern: string, value: string): boolean {
  try {
    return new RegExp(pattern).test(value);
  } catch {
    return false;
  }
}

function validateNumber(schema: JsonSchema, value: unknown, path: string, type: string): FieldError[] {
  if (typeof value !== "number" || !Number.isFinite(value)) return [{ path, message: "Must be a number" }];
  const errors: FieldError[] = [];
  if (type === "integer" && !Number.isInteger(value)) errors.push({ path, message: "Must be an integer" });
  if (schema.minimum !== undefined && value < schema.minimum) errors.push({ path, message: `Minimum ${schema.minimum}` });
  if (schema.maximum !== undefined && value > schema.maximum) errors.push({ path, message: `Maximum ${schema.maximum}` });
  return errors;
}

function validateArray(schema: JsonSchema, value: unknown, path: string): FieldError[] {
  if (!Array.isArray(value)) return [{ path, message: "Must be a list" }];
  const itemSchema = schemaItem(schema);
  if (!itemSchema) return [];
  const errors: FieldError[] = [];
  value.forEach((item, index) => {
    const child = path ? `${path}.${index}` : String(index);
    errors.push(...validateAgainstSchema(itemSchema, item, child));
  });
  return errors;
}

function validateObject(schema: JsonSchema, value: unknown, path: string): FieldError[] {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return [{ path, message: "Must be an object" }];
  }
  const record = value as Record<string, unknown>;
  const errors: FieldError[] = [];
  for (const key of schema.required ?? []) {
    if (record[key] === undefined || record[key] === null) {
      errors.push({ path: joinPath(path, key), message: "Required" });
    }
  }
  for (const [key, child] of Object.entries(schema.properties ?? {})) {
    if (record[key] === undefined) continue;
    errors.push(...validateAgainstSchema(child, record[key], joinPath(path, key)));
  }
  return errors;
}

/** String enum on an array property, used to fill scope and permission choices from a registered schema. */
export function enumList(schema: JsonSchema, property: string): string[] {
  const child = schema.properties?.[property];
  const items = child ? schemaItem(child) : null;
  if (!items?.enum) return [];
  return items.enum.filter((item): item is string => typeof item === "string");
}

export function schemaItem(schema: JsonSchema): JsonSchema | null {
  if (!schema.items || typeof schema.items !== "object" || Array.isArray(schema.items)) return null;
  return schema.items;
}

function joinPath(parent: string, key: string): string {
  return parent ? `${parent}.${key}` : key;
}

/**
 * Map RFC 9457 `errors` from the gateway schema validator onto field paths.
 * networknt 1.5 messages look like `$.greeting: must be at least 1 characters long`
 * and `$: required property 'greeting' not found`.
 */
export function indexProblemErrors(messages: readonly string[]): Record<string, string> {
  const out: Record<string, string> = {};
  const extra: string[] = [];
  for (const message of messages) {
    const required = message.match(/^(.*?):\s*required property '([^']+)' not found\s*$/);
    if (required) {
      const parent = jsonPointer(required[1] ?? "");
      const key = required[2] ?? "";
      out[joinPath(parent, key)] = "Required";
      continue;
    }
    const general = message.match(/^(\$[^:]*):\s*(.*)$/);
    if (general) {
      const path = jsonPointer(general[1] ?? "");
      const detail = (general[2] ?? message).trim();
      if (path) out[path] = detail;
      else extra.push(detail);
      continue;
    }
    extra.push(message);
  }
  if (extra.length > 0) out[""] = extra.join(" ");
  return out;
}

function jsonPointer(raw: string): string {
  let text = raw.trim();
  if (text === "$") return "";
  if (text.startsWith("$.")) text = text.slice(2);
  else if (text.startsWith("$")) text = text.slice(1);
  return text.replace(/\[(\d+)\]/g, ".$1").replace(/^\./, "");
}

export function emptyValue(schema: JsonSchema | undefined): unknown {
  if (!schema) return "";
  if (schema.default !== undefined) return schema.default;
  if (schema.enum && schema.enum.length > 0) return schema.enum[0];
  const type = schemaType(schema);
  switch (type) {
    case "string":
      return "";
    case "boolean":
      return false;
    case "number":
    case "integer":
      return schema.minimum ?? 0;
    case "array":
      return [];
    case "object": {
      const obj: Record<string, unknown> = {};
      for (const [key, child] of Object.entries(schema.properties ?? {})) {
        if (child.default !== undefined) obj[key] = child.default;
        else if ((schema.required ?? []).includes(key)) obj[key] = emptyValue(child);
      }
      return obj;
    }
    default:
      return "";
  }
}

export function mergeFieldErrors(
  structural: readonly FieldError[],
  drafts: Record<string, string>,
  server: Record<string, string>,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const error of structural) out[error.path] = error.message;
  for (const [path, message] of Object.entries(drafts)) out[path] = message;
  for (const [path, message] of Object.entries(server)) out[path] = message;
  return out;
}
