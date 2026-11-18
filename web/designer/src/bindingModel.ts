import { evaluateExpression, walkNodes } from "@oip/hmi-runtime";
import type { Binding, BindingRef, ComponentNode, PropertySource, ViewDocument } from "@oip/hmi-runtime";

/**
 * Pure helpers behind the Designer binding editor. Bindings are edited as whole `Binding` values from
 * hmi-runtime, so any field the editor does not show (for example `publishInitialUncertain`, `pollMs`)
 * is carried over unchanged.
 */

export type BindingKind = Binding["kind"];

export const BINDING_KINDS: Array<{ kind: BindingKind; label: string }> = [
  { kind: "tag", label: "Tag" },
  { kind: "property", label: "Property" },
  { kind: "expression", label: "Expression" },
  { kind: "expression-structure", label: "Expression structure" },
  { kind: "query", label: "Query" },
  { kind: "history", label: "Tag history" },
];

export function kindLabel(kind: BindingKind): string {
  return BINDING_KINDS.find((entry) => entry.kind === kind)?.label ?? kind;
}

/** A fresh binding of [kind]. Transforms and the enabled flag carry over from [previous]. */
export function defaultBinding(kind: BindingKind, previous?: Binding): Binding {
  const carried: Pick<Binding, "transforms" | "enabled"> = {};
  if (previous?.transforms) carried.transforms = previous.transforms;
  if (previous?.enabled !== undefined) carried.enabled = previous.enabled;
  if (kind === "tag") return { kind: "tag", mode: "direct", path: "", ...carried };
  if (kind === "property") return { kind: "property", source: { scope: "param", name: "" }, ...carried };
  if (kind === "expression") return { kind: "expression", expression: "", ...carried };
  if (kind === "expression-structure") return { kind: "expression-structure", expressions: {}, ...carried };
  if (kind === "query") return { kind: "query", path: "", ...carried };
  return { kind: "history", path: "", rangeMs: 3_600_000, intervalMs: 60_000, aggregate: "Average", ...carried };
}

/** Short one-line summary for the bound-property row. */
export function bindingSummary(binding: Binding): string {
  if (binding.kind === "tag") return binding.path || "(no tag path)";
  if (binding.kind === "expression") return binding.expression || "(empty expression)";
  if (binding.kind === "expression-structure") {
    const keys = Object.keys(binding.expressions);
    return keys.length > 0 ? `{${keys.join(", ")}}` : "(no expressions)";
  }
  if (binding.kind === "query") return binding.path || "(no query)";
  if (binding.kind === "history") return binding.path || "(no tag path)";
  if (binding.kind === "http") return `${binding.source}${binding.path}`;
  return sourceSummary(binding.source);
}

export function sourceSummary(source: PropertySource): string {
  if (source.scope === "prop") return `${source.nodeId || "?"}.props.${source.prop || "?"}`;
  if (source.scope === "param") return `view.params.${source.name || "?"}`;
  if (source.scope === "custom") return `view.custom.${source.name || "?"}`;
  if (source.scope === "session") return `session.props.${source.path || "?"}`;
  return `page.props.${source.path || "?"}`;
}

/** Return [node]'s bindings with [prop] set to [binding], or removed when [binding] is undefined. Other props are untouched. */
export function withBinding(node: ComponentNode, prop: string, binding: Binding | undefined): NonNullable<ComponentNode["bindings"]> {
  const next: NonNullable<ComponentNode["bindings"]> = { ...(node.bindings ?? {}) };
  if (binding) next[prop] = binding;
  else delete next[prop];
  return next;
}

/** `{name}` placeholders of an indirect tag path, in order of first use. */
export function placeholders(path: string): string[] {
  const names: string[] = [];
  for (const match of path.matchAll(/\{([A-Za-z0-9_]+)\}/g)) {
    const name = match[1]!;
    if (!names.includes(name)) names.push(name);
  }
  return names;
}

export function defaultRef(): BindingRef {
  return { source: "param", name: "" };
}

/** Syntax check for an expression. Every reference reads as null, so only parse and name errors show. */
export function expressionError(source: string): string | null {
  if (source.trim() === "") return "Expression is empty";
  try {
    evaluateExpression(source, { resolve: () => null });
    return null;
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
}

/** Component nodes of the view, for the property and reference pickers. */
export function componentNodes(view: ViewDocument): ComponentNode[] {
  const nodes: ComponentNode[] = [];
  walkNodes(view.spec.root, (node) => {
    if (node.kind === "component") nodes.push(node);
  });
  return nodes;
}

/** Session paths a property binding can read (hmi-runtime `sessionPath`). */
export const SESSION_PATHS = [
  "id",
  "theme",
  "locale",
  "timeZoneId",
  "auth.authenticated",
  "auth.userName",
  "auth.roles",
  "device.type",
  "device.id",
  "device.userAgent",
  "device.screenWidth",
  "device.screenHeight",
  "device.timeZone",
  "gateway.connected",
  "custom.",
];

/** Page paths a property binding can read (hmi-runtime `pagePath`). */
export const PAGE_PATHS = ["pageId", "path", "primaryView", "title", "urlParams."];

/**
 * Text for a literal value in a one-line input. Strings show as typed; everything else as JSON.
 * A string that would read back as another type (such as "12") is shown quoted so it stays a string.
 */
export function literalText(value: unknown): string {
  if (value === undefined) return "";
  if (typeof value === "string") return parseLiteral(value) === value ? value : JSON.stringify(value);
  return JSON.stringify(value);
}

/** Read a literal typed in a one-line input: JSON when it parses, otherwise the raw text. */
export function parseLiteral(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === "") return "";
  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return text;
  }
}

/** Compact display of a preview value. */
export function showValue(value: unknown): string {
  if (value === undefined) return "—";
  if (typeof value === "string") return JSON.stringify(value);
  try {
    const text = JSON.stringify(value);
    return text.length > 80 ? `${text.slice(0, 77)}...` : text;
  } catch {
    return String(value);
  }
}
