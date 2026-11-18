import type { ViewDocument } from "@oip/hmi-runtime";

/** Project-wide find and replace (DES-007). Pure: walks view documents, never touches the gateway. */

export interface FindQuery {
  /** Plain text; `*` matches any run of characters and `?` any one character. */
  text: string;
  caseSensitive?: boolean;
}

export interface FindHit {
  viewPath: string;
  /** The component or container the hit sits in, or null for view-level fields (params, custom, methods, view events). */
  nodeId: string | null;
  nodeName: string | null;
  /** Keys from the view document root to the matched string. */
  keys: (string | number)[];
  /** Dotted property path below the node, e.g. `bindings.value.path` or `events.0.actions.0.script`. */
  property: string;
  value: string;
}

/** Keys that are structure, not authored text. */
const SKIPPED = new Set(["id", "kind", "type", "apiVersion", "layout"]);

/** The query as a global regular expression, or null for an empty query. */
export function queryRegExp(query: FindQuery): RegExp | null {
  if (query.text === "") return null;
  const source = [...query.text]
    .map((ch) => (ch === "*" ? ".*?" : ch === "?" ? "." : ch.replace(/[.+^${}()|[\]\\/-]/g, "\\$&")))
    .join("");
  return new RegExp(source, query.caseSensitive ? "gs" : "gis");
}

function matches(re: RegExp, value: string): boolean {
  re.lastIndex = 0;
  let found = false;
  // A pattern of only `*` can match the empty string; require at least one real character.
  for (let m = re.exec(value); m; m = re.exec(value)) {
    if (m[0].length > 0) {
      found = true;
      break;
    }
    re.lastIndex += 1;
  }
  return found;
}

export function findInProject(views: readonly ViewDocument[], query: FindQuery): FindHit[] {
  const re = queryRegExp(query);
  if (!re) return [];
  const hits: FindHit[] = [];
  for (const view of views) {
    const walk = (value: unknown, keys: (string | number)[], node: { id: string; name: string; base: number } | null) => {
      if (typeof value === "string") {
        if (matches(re, value)) {
          hits.push({
            viewPath: view.metadata.path,
            nodeId: node?.id ?? null,
            nodeName: node?.name ?? null,
            keys,
            property: keys.slice(node ? node.base : 1).join("."),
            value,
          });
        }
        return;
      }
      if (Array.isArray(value)) {
        value.forEach((item, index) => walk(item, [...keys, index], node));
        return;
      }
      if (value === null || typeof value !== "object") return;
      const record = value as Record<string, unknown>;
      const here =
        (record.kind === "component" || record.kind === "container") && typeof record.id === "string"
          ? { id: record.id, name: typeof record.name === "string" ? record.name : record.id, base: keys.length }
          : node;
      for (const [key, child] of Object.entries(record)) {
        if (SKIPPED.has(key)) continue;
        walk(child, [...keys, key], here);
      }
    };
    walk(view.spec, ["spec"], null);
  }
  return hits;
}

function replaceIn(value: string, query: FindQuery, replacement: string): string {
  const re = queryRegExp(query);
  if (!re) return value;
  return value.replace(re, (found) => (found.length === 0 ? found : replacement));
}

/** The view with the string at each hit's keys replaced. Hits for other views are ignored. */
export function replaceHits(view: ViewDocument, hits: readonly FindHit[], query: FindQuery, replacement: string): ViewDocument {
  const next = structuredClone(view);
  for (const hit of hits) {
    if (hit.viewPath !== view.metadata.path) continue;
    let target: unknown = next;
    for (const key of hit.keys.slice(0, -1)) target = (target as Record<string | number, unknown>)?.[key];
    const last = hit.keys[hit.keys.length - 1]!;
    const holder = target as Record<string | number, unknown> | undefined;
    if (holder && typeof holder[last] === "string") holder[last] = replaceIn(holder[last] as string, query, replacement);
  }
  return next;
}

/** Hits grouped by view path, in project order. */
export function groupByView(hits: readonly FindHit[]): Map<string, FindHit[]> {
  const groups = new Map<string, FindHit[]>();
  for (const hit of hits) groups.set(hit.viewPath, [...(groups.get(hit.viewPath) ?? []), hit]);
  return groups;
}
