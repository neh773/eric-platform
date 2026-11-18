import { ViewModelError } from "./model";
import type { CornerPriority, DockContent, DockDisplay, DockEdge, DockHandle, DockSpec, HmiProject, PageDocument, ViewDocument } from "./types";

/** Page URLs, path parameters, and dock resolution.
 */

const EDGES = new Set<DockEdge>(["top", "bottom", "left", "right"]);
const DISPLAYS = new Set<DockDisplay>(["visible", "onDemand", "auto"]);
const CONTENTS = new Set<DockContent>(["push", "cover", "auto"]);
const HANDLES = new Set<DockHandle>(["show", "hide", "autoHide"]);
const PARAM = /^[A-Za-z_][A-Za-z0-9_]*$/;
const INVALID_PARAM = /[/?#%]/;

export function parsePage(input: unknown): PageDocument {
  const doc = asRecord(input, "page");
  if (doc.apiVersion !== "oip.hmi/v1") throw new ViewModelError(`unsupported apiVersion: ${String(doc.apiVersion)}`);
  if (doc.kind !== "Page") throw new ViewModelError(`unsupported kind: ${String(doc.kind)}`);
  const metadata = asRecord(doc.metadata, "metadata");
  const spec = asRecord(doc.spec, "spec");
  const url = requiredString(spec, "url", "spec.url");
  if (!url.startsWith("/")) throw new ViewModelError("spec.url must start with /");
  validateUrl(url);
  const docks = parseDocks(spec.docks ?? [], "spec.docks");
  const page: PageDocument = {
    apiVersion: "oip.hmi/v1",
    kind: "Page",
    metadata: { name: requiredString(metadata, "name", "metadata.name") },
    spec: {
      url,
      primaryView: requiredString(spec, "primaryView", "spec.primaryView"),
      docks,
    },
  };
  if (spec.title !== undefined) page.spec.title = requiredString(spec, "title", "spec.title");
  if (spec.cornerPriority !== undefined) page.spec.cornerPriority = parseCorner(spec.cornerPriority, "spec.cornerPriority");
  return page;
}

export function viewCatalog(views: readonly ViewDocument[]): Map<string, ViewDocument> {
  const map = new Map<string, ViewDocument>();
  for (const view of views) map.set(view.metadata.path, view);
  return map;
}

export interface PageMatch {
  page: PageDocument;
  /** Path parameters for the primary view. Values are decoded strings. */
  params: Record<string, string>;
  /** Query string. Read-only page.urlParams. Not copied onto the view. */
  urlParams: Record<string, string>;
  path: string;
}

export function matchPage(pages: readonly PageDocument[], location: string): PageMatch | null {
  const [pathPart, queryPart] = splitLocation(location);
  const path = pathPart === "" ? "/" : pathPart;
  const urlParams = parseQuery(queryPart ?? "");
  let best: { page: PageDocument; params: Record<string, string>; score: number } | null = null;
  for (const page of pages) {
    const matched = matchUrl(page.spec.url, path);
    if (!matched) continue;
    if (!best || matched.score > best.score) best = { page, params: matched.params, score: matched.score };
  }
  if (!best) return null;
  return { page: best.page, params: best.params, urlParams, path };
}

/** `:name` segments are replaced from `params`. Characters `/ ? # %` are rejected. */
export function fillPageUrl(url: string, params: Record<string, unknown> | undefined): string {
  return url.replace(/:([A-Za-z_][A-Za-z0-9_]*)/g, (_match, name: string) => {
    const raw = params?.[name];
    if (raw == null) throw new ViewModelError(`missing page parameter: ${name}`);
    const value = String(raw);
    if (INVALID_PARAM.test(value)) throw new ViewModelError(`page parameter ${name} contains an invalid character`);
    return encodeURIComponent(value);
  });
}

export function docksFor(page: PageDocument, project: Pick<HmiProject, "sharedDocks" | "cornerPriority">): { docks: DockSpec[]; corner: CornerPriority } {
  const byEdge = new Map<DockEdge, DockSpec>();
  for (const dock of project.sharedDocks ?? []) byEdge.set(dock.edge, dock);
  for (const dock of page.spec.docks) byEdge.set(dock.edge, dock);
  return {
    docks: [...byEdge.values()],
    corner: page.spec.cornerPriority ?? project.cornerPriority ?? "top-bottom",
  };
}

export interface ResolvedDock {
  open: boolean;
  content: "push" | "cover";
  showHandle: boolean;
  modal: boolean;
}

/** Display, content, and handle rules from the docked-view page.
 * `autoHide` shows the handle only while the dock is collapsed. The public description
 * ("hide when the page is not active") needs a page-focus signal this runtime does not track.
 */
export function resolveDock(dock: DockSpec, viewportWidth: number, userOpen: boolean | undefined): ResolvedDock {
  const breakpoint = dock.autoBreakpoint ?? 800;
  const autoOpen = viewportWidth >= breakpoint;
  let open: boolean;
  if (userOpen !== undefined) open = userOpen;
  else if (dock.display === "visible") open = true;
  else if (dock.display === "onDemand") open = false;
  else open = autoOpen;
  const narrow = viewportWidth < breakpoint;
  const content = dock.content === "auto" ? (narrow ? "cover" : "push") : dock.content;
  const showHandle = dock.handle === "show" ? true : dock.handle === "hide" ? false : !open;
  const modal = dock.display === "onDemand" && dock.modal === true && open;
  return { open, content, showHandle, modal };
}

function matchUrl(pattern: string, path: string): { params: Record<string, string>; score: number } | null {
  const patternParts = splitPath(pattern);
  const pathParts = splitPath(path);
  if (patternParts.length !== pathParts.length) return null;
  const params: Record<string, string> = {};
  let score = 0;
  for (let index = 0; index < patternParts.length; index += 1) {
    const token = patternParts[index]!;
    const part = decodeURIComponent(pathParts[index]!);
    if (token.startsWith(":")) {
      params[token.slice(1)] = part;
    } else if (token !== part) {
      return null;
    } else {
      score += 1;
    }
  }
  return { params, score };
}

function validateUrl(url: string): void {
  const parts = splitPath(url);
  for (const part of parts) {
    if (part.startsWith(":")) {
      if (!PARAM.test(part.slice(1))) throw new ViewModelError(`invalid page parameter: ${part}`);
    } else if (!/^[A-Za-z0-9._~-]*$/.test(part)) {
      throw new ViewModelError(`invalid page url segment: ${part}`);
    }
  }
}

function parseDocks(input: unknown, where: string): DockSpec[] {
  if (!Array.isArray(input)) throw new ViewModelError(`${where} must be an array`);
  const seen = new Set<string>();
  return input.map((entry, index) => {
    const record = asRecord(entry, `${where}[${index}]`);
    const edge = requiredString(record, "edge", `${where}[${index}].edge`) as DockEdge;
    if (!EDGES.has(edge)) throw new ViewModelError(`${where}[${index}].edge is unknown`);
    if (seen.has(edge)) throw new ViewModelError(`${where} has more than one ${edge} dock`);
    seen.add(edge);
    const display = (record.display === undefined ? "visible" : requiredString(record, "display", `${where}[${index}].display`)) as DockDisplay;
    const content = (record.content === undefined ? "push" : requiredString(record, "content", `${where}[${index}].content`)) as DockContent;
    const handle = (record.handle === undefined ? "hide" : requiredString(record, "handle", `${where}[${index}].handle`)) as DockHandle;
    if (!DISPLAYS.has(display)) throw new ViewModelError(`${where}[${index}].display is unknown`);
    if (!CONTENTS.has(content)) throw new ViewModelError(`${where}[${index}].content is unknown`);
    if (!HANDLES.has(handle)) throw new ViewModelError(`${where}[${index}].handle is unknown`);
    const size = requiredNumber(record, "size", `${where}[${index}].size`);
    if (size <= 0) throw new ViewModelError(`${where}[${index}].size must be positive`);
    const dock: DockSpec = {
      id: requiredString(record, "id", `${where}[${index}].id`),
      edge,
      view: requiredString(record, "view", `${where}[${index}].view`),
      size,
      display,
      content,
      handle,
    };
    if (record.params !== undefined) dock.params = { ...asRecord(record.params, `${where}[${index}].params`) };
    if (record.resizable !== undefined) dock.resizable = requiredBoolean(record, "resizable", `${where}[${index}].resizable`);
    if (record.modal !== undefined) dock.modal = requiredBoolean(record, "modal", `${where}[${index}].modal`);
    if (record.autoBreakpoint !== undefined) dock.autoBreakpoint = requiredNumber(record, "autoBreakpoint", `${where}[${index}].autoBreakpoint`);
    if (record.anchor !== undefined) {
      const anchor = requiredString(record, "anchor", `${where}[${index}].anchor`);
      if (anchor !== "fixed" && anchor !== "scrollable") throw new ViewModelError(`${where}[${index}].anchor is unknown`);
      dock.anchor = anchor;
    }
    return dock;
  });
}

function parseCorner(value: unknown, where: string): CornerPriority {
  if (value !== "top-bottom" && value !== "left-right") throw new ViewModelError(`${where} must be top-bottom or left-right`);
  return value;
}

function splitLocation(location: string): [string, string | undefined] {
  const hash = location.indexOf("#");
  const bare = hash >= 0 ? location.slice(0, hash) : location;
  const query = bare.indexOf("?");
  if (query < 0) return [bare, undefined];
  return [bare.slice(0, query), bare.slice(query + 1)];
}

function parseQuery(query: string): Record<string, string> {
  const params: Record<string, string> = {};
  if (!query) return params;
  for (const pair of query.split("&")) {
    if (!pair) continue;
    const eq = pair.indexOf("=");
    const key = decodeURIComponent(eq < 0 ? pair : pair.slice(0, eq));
    const value = decodeURIComponent(eq < 0 ? "" : pair.slice(eq + 1));
    if (key) params[key] = value;
  }
  return params;
}

function splitPath(url: string): string[] {
  const trimmed = url.length > 1 && url.endsWith("/") ? url.slice(0, -1) : url;
  if (trimmed === "/") return [];
  return trimmed.split("/").slice(1);
}

function asRecord(input: unknown, where: string): Record<string, unknown> {
  if (input === null || typeof input !== "object" || Array.isArray(input)) throw new ViewModelError(`${where} must be an object`);
  return input as Record<string, unknown>;
}

function requiredString(record: Record<string, unknown>, key: string, where: string): string {
  const value = record[key];
  if (typeof value !== "string" || value.length === 0) throw new ViewModelError(`${where} must be a non-empty string`);
  return value;
}

function requiredNumber(record: Record<string, unknown>, key: string, where: string): number {
  const value = record[key];
  if (typeof value !== "number" || !Number.isFinite(value)) throw new ViewModelError(`${where} must be a finite number`);
  return value;
}

function requiredBoolean(record: Record<string, unknown>, key: string, where: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") throw new ViewModelError(`${where} must be a boolean`);
  return value;
}
