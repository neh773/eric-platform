import { parseCustomThemes, parseStyleClassSet, parseView, type CustomThemeSet, type StyleClassSet, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";
import type { ConfigResource, GatewayClient, ReadOptions } from "./gatewayClient";
import { GatewayError } from "./gatewayClient";

/**
 * Designer views as gateway config resources (SPEC §4, ADR 0005):
 * `/api/v1/config/designer/project/{project}/View/{name}`, schema registered by the gateway
 * (`DesignerSchemas.kt`). `spec.path` is the view path (a slug), `spec.title` the tree label, and
 * `spec.document` the `ViewDocument.spec` the HMI runtime renders.
 *
 * "The Designer saves all your projects to the Gateway"; editing is lock-free and conflicting edits are
 * resolved when the project is saved (keep mine, take theirs, or cancel):
 */

export const DESIGNER_MODULE = "designer";
export const VIEW_KIND = "View";
/** One resource per project holds its style classes (M4.2): `.../StyleClasses/style-classes`. */
export const STYLE_CLASSES_KIND = "StyleClasses";
export const STYLE_CLASSES_RESOURCE = "style-classes";
const RESOURCE_API_VERSION = "oip/v1";
const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

export interface ViewSpec {
  path: string;
  title?: string;
  document: ViewDocument["spec"];
}

/** Where a view lives on the gateway. `version` 0 means not saved yet. */
export interface StoredRef {
  name: string;
  version: number;
}

export function projectScope(project: string): string {
  return `project/${project}`;
}

export function validProjectName(name: string): boolean {
  return NAME.test(name);
}

/**
 * A free DNS-label name for a new view. It is both the runtime view path (a slug, hmi-runtime `parseView`)
 * and the config resource name, so a new view's resource is `.../View/<path>`.
 */
export function resourceNameFor(path: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  let base = path
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 55)
    .replace(/-+$/g, "");
  if (!base) base = "view";
  if (!used.has(base)) return base;
  for (let index = 2; ; index += 1) {
    const candidate = `${base}-${index}`;
    if (!used.has(candidate)) return candidate;
  }
}

export function toResource(view: ViewDocument, ref: StoredRef): ConfigResource<ViewSpec> {
  return {
    apiVersion: RESOURCE_API_VERSION,
    kind: VIEW_KIND,
    metadata: { name: ref.name, module: DESIGNER_MODULE, version: ref.version },
    spec: { path: view.metadata.path, title: view.metadata.name, document: view.spec },
  };
}

export function fromResource(resource: ConfigResource<ViewSpec>, allowedTypes = allowedComponentTypes()): ViewDocument {
  const spec = resource.spec;
  const title = spec.title && spec.title.trim() ? spec.title : spec.path;
  return parseView(
    { apiVersion: "oip.hmi/v1", kind: "View", metadata: { name: title, path: spec.path }, spec: spec.document },
    { allowedTypes },
  );
}

/** A new, empty coordinate view. [path] is a runtime view slug; [name] is the label in the project tree. */
export function blankView(path: string, name: string): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name, path },
      spec: {
        params: {},
        width: 680,
        height: 360,
        root: { id: "root", kind: "container", name: "Root", layout: "coordinate", props: { mode: "fixed" }, children: [] },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

/** Problem with a new view's display name, or null. Its path is derived with [resourceNameFor]. */
export function viewNameProblem(name: string, existingNames: Iterable<string>): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Enter a view name.";
  if (trimmed.length > 100) return "View name is too long.";
  for (const other of existingNames) {
    if (other.trim().toLowerCase() === trimmed.toLowerCase()) return "A view with that name already exists.";
  }
  return null;
}

export interface LoadedView {
  view: ViewDocument;
  ref: StoredRef;
  /**
   * The ancestor project that stores this view, when the project inherits it (PRO-002). An inherited view is read-only
   * in the Designer until it is overridden: saved as the project's own copy under the same resource name.
   */
  inheritedFrom?: string;
}

export interface LoadResult {
  views: LoadedView[];
  /** Views that are stored but could not be parsed. They are left untouched on the gateway. */
  errors: string[];
}

/** The project's views, its own and the ones it inherits (each marked with [LoadedView.inheritedFrom]). */
export async function loadProjectViews(client: GatewayClient, project: string): Promise<LoadResult> {
  const items = await client.listResources<ViewSpec>(DESIGNER_MODULE, projectScope(project), VIEW_KIND, { inherited: true });
  const views: LoadedView[] = [];
  const errors: string[] = [];
  // One component-type set for the whole list: opening a large project should spend its time on the views, not on
  // rebuilding the palette for every resource.
  const allowedTypes = allowedComponentTypes();
  for (const item of items) {
    try {
      const view = fromResource(item, allowedTypes);
      if (item.inheritedFrom) {
        // Not stored in this project yet: overriding it creates the project's copy (version 0).
        views.push({ view, ref: { name: item.metadata.name, version: 0 }, inheritedFrom: item.inheritedFrom });
        continue;
      }
      views.push({ view, ref: { name: item.metadata.name, version: item.metadata.version } });
    } catch (error) {
      errors.push(`${item.metadata.name}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  views.sort((a, b) => a.view.metadata.path.localeCompare(b.view.metadata.path));
  return { views, errors };
}

/** The stored copy changed since this Designer loaded it. */
export class SaveConflict extends Error {
  readonly path: string;
  readonly theirs: LoadedView | null;

  constructor(path: string, theirs: LoadedView | null) {
    super(`"${path}" was changed on the gateway since it was opened`);
    this.name = "SaveConflict";
    this.path = path;
    this.theirs = theirs;
  }
}

/**
 * Save one view. On 409 the latest stored copy is fetched and returned in a [SaveConflict] so the
 * caller can keep its edits (save again with the stored version) or take the stored copy.
 */
export async function saveView(client: GatewayClient, project: string, view: ViewDocument, ref: StoredRef): Promise<StoredRef> {
  const scope = projectScope(project);
  try {
    const stored = await client.putResource(scope, toResource(view, ref));
    return { name: stored.metadata.name, version: stored.metadata.version };
  } catch (error) {
    if (error instanceof GatewayError && error.status === 409) {
      const latest = await client.getResource<ViewSpec>(DESIGNER_MODULE, scope, VIEW_KIND, ref.name);
      if (!latest) throw new SaveConflict(view.metadata.path, null);
      let theirs: ViewDocument;
      try {
        theirs = fromResource(latest);
      } catch (parse) {
        throw new Error(`View "${view.metadata.name}" was changed on the gateway, and the gateway copy could not be read: ${describe(parse)}`);
      }
      throw new SaveConflict(view.metadata.path, { view: theirs, ref: { name: latest.metadata.name, version: latest.metadata.version } });
    }
    throw error;
  }
}

export interface StyleClassesSpec {
  classes: StyleClassSet;
  themes?: CustomThemeSet;
}

export interface LoadedStyleClasses {
  styleClasses: StyleClassSet;
  themes?: CustomThemeSet;
  ref: StoredRef;
}

export function styleClassesToResource(styleClasses: StyleClassSet, ref: StoredRef, themes?: CustomThemeSet): ConfigResource<StyleClassesSpec> {
  const spec: StyleClassesSpec = { classes: styleClasses };
  if (themes && Object.keys(themes).length > 0) spec.themes = themes;
  return {
    apiVersion: RESOURCE_API_VERSION,
    kind: STYLE_CLASSES_KIND,
    metadata: { name: ref.name, module: DESIGNER_MODULE, version: ref.version },
    spec,
  };
}

function loadedStyleClasses(resource: ConfigResource<StyleClassesSpec>): LoadedStyleClasses {
  const themes = parseCustomThemes(resource.spec.themes);
  return {
    styleClasses: parseStyleClassSet(resource.spec.classes ?? {}),
    ...(Object.keys(themes).length > 0 ? { themes } : {}),
    ref: { name: resource.metadata.name, version: resource.metadata.version },
  };
}

/** The project's stored style classes, or null when none are stored yet. */
export async function loadStyleClasses(client: GatewayClient, project: string, options: ReadOptions = {}): Promise<LoadedStyleClasses | null> {
  const stored = await client.getResource<StyleClassesSpec>(DESIGNER_MODULE, projectScope(project), STYLE_CLASSES_KIND, STYLE_CLASSES_RESOURCE, options);
  return stored ? loadedStyleClasses(stored) : null;
}

/** The stored style classes changed since this Designer loaded them. */
export class StyleClassesConflict extends Error {
  readonly theirs: LoadedStyleClasses | null;

  constructor(theirs: LoadedStyleClasses | null) {
    super("the project's style classes were changed on the gateway since they were opened");
    this.name = "StyleClassesConflict";
    this.theirs = theirs;
  }
}

/** Save the project's style classes. Same version rules as [saveView]: a 409 becomes a [StyleClassesConflict]. */
export async function saveStyleClasses(
  client: GatewayClient,
  project: string,
  styleClasses: StyleClassSet,
  ref: StoredRef,
  themes?: CustomThemeSet,
): Promise<StoredRef> {
  const scope = projectScope(project);
  try {
    const stored = await client.putResource(scope, styleClassesToResource(styleClasses, ref, themes));
    return { name: stored.metadata.name, version: stored.metadata.version };
  } catch (error) {
    if (error instanceof GatewayError && error.status === 409) {
      const latest = await client.getResource<StyleClassesSpec>(DESIGNER_MODULE, scope, STYLE_CLASSES_KIND, ref.name);
      if (!latest) throw new StyleClassesConflict(null);
      let theirs: LoadedStyleClasses;
      try {
        theirs = loadedStyleClasses(latest);
      } catch (parse) {
        throw new Error(`The style classes were changed on the gateway, and the gateway copy could not be read: ${describe(parse)}`);
      }
      throw new StyleClassesConflict(theirs);
    }
    throw error;
  }
}

/**
 * The project's script hooks (D-5a, D-5): one resource per project, `.../ScriptHooks/script-hooks`, whose
 * `spec.hooks.<name>.source` is the Python a script transform or event script runs when the view does not carry its
 * own source. The gateway reads it when a session calls the hook; the browser never runs it.
 */
export const SCRIPT_HOOKS_KIND = "ScriptHooks";
export const SCRIPT_HOOKS_RESOURCE = "script-hooks";

export type ScriptHookSet = Record<string, { source: string }>;

export interface ScriptHooksSpec {
  hooks: ScriptHookSet;
}

export interface LoadedScriptHooks {
  hooks: ScriptHookSet;
  ref: StoredRef;
}

export function scriptHooksToResource(hooks: ScriptHookSet, ref: StoredRef): ConfigResource<ScriptHooksSpec> {
  return {
    apiVersion: RESOURCE_API_VERSION,
    kind: SCRIPT_HOOKS_KIND,
    metadata: { name: ref.name, module: DESIGNER_MODULE, version: ref.version },
    spec: { hooks },
  };
}

/** A stored hook set, checked: every entry needs a string `source`. */
export function parseScriptHooks(input: unknown): ScriptHookSet {
  if (input === undefined || input === null) return {};
  if (typeof input !== "object" || Array.isArray(input)) throw new Error("hooks must be an object");
  const hooks: ScriptHookSet = {};
  for (const [name, entry] of Object.entries(input as Record<string, unknown>)) {
    const source = entry && typeof entry === "object" ? (entry as Record<string, unknown>).source : undefined;
    if (typeof source !== "string") throw new Error(`hook ${name} has no source`);
    hooks[name] = { source };
  }
  return hooks;
}

function loadedScriptHooks(resource: ConfigResource<ScriptHooksSpec>): LoadedScriptHooks {
  return { hooks: parseScriptHooks(resource.spec.hooks), ref: { name: resource.metadata.name, version: resource.metadata.version } };
}

/** The project's stored script hooks, or null when none are stored yet. */
export async function loadScriptHooks(client: GatewayClient, project: string, options: ReadOptions = {}): Promise<LoadedScriptHooks | null> {
  const stored = await client.getResource<ScriptHooksSpec>(DESIGNER_MODULE, projectScope(project), SCRIPT_HOOKS_KIND, SCRIPT_HOOKS_RESOURCE, options);
  return stored ? loadedScriptHooks(stored) : null;
}

/** The stored script hooks changed since this Designer loaded them. */
export class ScriptHooksConflict extends Error {
  readonly theirs: LoadedScriptHooks | null;

  constructor(theirs: LoadedScriptHooks | null) {
    super("the project's script hooks were changed on the gateway since they were opened");
    this.name = "ScriptHooksConflict";
    this.theirs = theirs;
  }
}

/** Save the project's script hooks. Same version rules as [saveView]: a 409 becomes a [ScriptHooksConflict]. */
export async function saveScriptHooks(client: GatewayClient, project: string, hooks: ScriptHookSet, ref: StoredRef): Promise<StoredRef> {
  const scope = projectScope(project);
  try {
    const stored = await client.putResource(scope, scriptHooksToResource(hooks, ref));
    return { name: stored.metadata.name, version: stored.metadata.version };
  } catch (error) {
    if (error instanceof GatewayError && error.status === 409) {
      const latest = await client.getResource<ScriptHooksSpec>(DESIGNER_MODULE, scope, SCRIPT_HOOKS_KIND, ref.name);
      if (!latest) throw new ScriptHooksConflict(null);
      let theirs: LoadedScriptHooks;
      try {
        theirs = loadedScriptHooks(latest);
      } catch (parse) {
        throw new Error(`The script hooks were changed on the gateway, and the gateway copy could not be read: ${describe(parse)}`);
      }
      throw new ScriptHooksConflict(theirs);
    }
    throw error;
  }
}

/**
 * A project's pages (H-1): one resource, `.../PageConfig/page-config`. The session reads the same shape.
 * doc source: FEATURES DES-011
 */
export const PAGE_CONFIG_KIND = "PageConfig";
export const PAGE_CONFIG_RESOURCE = "page-config";

export interface PageConfigEntry {
  name?: string;
  url: string;
  title?: string;
  primaryView: string;
  docks?: unknown[];
  cornerPriority?: string;
}

export interface PageConfigSpec {
  pages: PageConfigEntry[];
  sharedDocks?: unknown[];
  cornerPriority?: string;
}

export interface LoadedPageConfig {
  spec: PageConfigSpec;
  ref: StoredRef;
}

export function pageConfigToResource(spec: PageConfigSpec, ref: StoredRef): ConfigResource<PageConfigSpec> {
  return {
    apiVersion: RESOURCE_API_VERSION,
    kind: PAGE_CONFIG_KIND,
    metadata: { name: ref.name, module: DESIGNER_MODULE, version: ref.version },
    spec,
  };
}

/** A stored page configuration. A page that is not an object, or has no url and primary view, is refused. */
export function parsePageConfig(input: unknown): PageConfigSpec {
  if (input === undefined || input === null || typeof input !== "object" || Array.isArray(input)) throw new Error("page configuration must be an object");
  const spec = input as Record<string, unknown>;
  if (!Array.isArray(spec.pages)) throw new Error("pages must be a list");
  const pages = spec.pages.map((entry, index) => {
    if (entry === null || typeof entry !== "object" || Array.isArray(entry)) throw new Error(`page ${index + 1} must be an object`);
    const page = entry as Record<string, unknown>;
    if (typeof page.url !== "string" || typeof page.primaryView !== "string") throw new Error(`page ${index + 1} needs a url and a primary view`);
    const parsed: PageConfigEntry = { url: page.url, primaryView: page.primaryView };
    if (typeof page.title === "string") parsed.title = page.title;
    if (typeof page.name === "string") parsed.name = page.name;
    if (Array.isArray(page.docks)) parsed.docks = page.docks;
    if (typeof page.cornerPriority === "string") parsed.cornerPriority = page.cornerPriority;
    return parsed;
  });
  const result: PageConfigSpec = { pages };
  if (Array.isArray(spec.sharedDocks)) result.sharedDocks = spec.sharedDocks;
  if (typeof spec.cornerPriority === "string") result.cornerPriority = spec.cornerPriority;
  return result;
}

/** Problem with a page list the Studio is about to save, or null. */
export function pageConfigProblem(spec: PageConfigSpec): string | null {
  const urls = new Set<string>();
  for (const page of spec.pages) {
    const url = page.url.trim();
    if (!url.startsWith("/") || url.startsWith("//")) return `Page URL "${page.url}" must start with / and not //.`;
    if (!page.primaryView.trim()) return "Each page needs a primary view.";
    if (urls.has(url)) return `Page URL "${url}" is used more than once.`;
    urls.add(url);
  }
  return null;
}

function loadedPageConfig(resource: ConfigResource<PageConfigSpec>): LoadedPageConfig {
  return { spec: parsePageConfig(resource.spec), ref: { name: resource.metadata.name, version: resource.metadata.version } };
}

/** The project's stored page configuration, or null when none is stored yet. */
export async function loadPageConfig(client: GatewayClient, project: string, options: ReadOptions = {}): Promise<LoadedPageConfig | null> {
  const stored = await client.getResource<PageConfigSpec>(DESIGNER_MODULE, projectScope(project), PAGE_CONFIG_KIND, PAGE_CONFIG_RESOURCE, options);
  return stored ? loadedPageConfig(stored) : null;
}

/** The stored page configuration changed since this Designer loaded it. */
export class PageConfigConflict extends Error {
  readonly theirs: LoadedPageConfig | null;

  constructor(theirs: LoadedPageConfig | null) {
    super("the project's page configuration was changed on the gateway since it was opened");
    this.name = "PageConfigConflict";
    this.theirs = theirs;
  }
}

/** Save the project's pages. Same version rules as [saveView]: a 409 becomes a [PageConfigConflict]. */
export async function savePageConfig(client: GatewayClient, project: string, spec: PageConfigSpec, ref: StoredRef): Promise<StoredRef> {
  const problem = pageConfigProblem(spec);
  if (problem) throw new Error(problem);
  const scope = projectScope(project);
  try {
    const stored = await client.putResource(scope, pageConfigToResource(spec, ref));
    return { name: stored.metadata.name, version: stored.metadata.version };
  } catch (error) {
    if (error instanceof GatewayError && error.status === 409) {
      const latest = await client.getResource<PageConfigSpec>(DESIGNER_MODULE, scope, PAGE_CONFIG_KIND, ref.name);
      if (!latest) throw new PageConfigConflict(null);
      let theirs: LoadedPageConfig;
      try {
        theirs = loadedPageConfig(latest);
      } catch (parse) {
        throw new Error(`The page configuration was changed on the gateway, and the gateway copy could not be read: ${describe(parse)}`);
      }
      throw new PageConfigConflict(theirs);
    }
    throw error;
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
