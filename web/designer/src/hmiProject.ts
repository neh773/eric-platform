import { parseCustomThemes, parsePage, parseStyleClassSet, type CornerPriority, type DockSpec, type HmiProject, type PageDocument, type StyleClassSet, type ViewDocument } from "@oip/hmi-runtime";
import type { ConfigResource, GatewayClient } from "./gatewayClient";
import { fromResource, type StyleClassesSpec, type ViewSpec } from "./persistence";

/**
 * What a `/hmi` session runs (H-1): a project saved on the gateway, read through the config API.
 *
 * URLs are `/hmi/<project>/<page url>`, for example `/hmi/plant/pumps` opens the page whose URL is `/pumps` in
 * project `plant`. `/hmi/` opens the gateway's `default` project, or its only project, or lists the projects.
 * `/hmi/?project=<name>` (the earlier form) is read as `/hmi/<name>/`. `/hmi/_sample/...` is the built-in sample,
 * which is also what a session shows when no gateway answers; `_` cannot start a project name, so it never hides one.
 *
 * A project's pages come from its `PageConfig` resource (`designer/project/<project>/PageConfig/page-config`). Without
 * one, every view is a page at `/<view path>`, and `/` shows the view named `home` (or `main`, `overview`, else the
 * first view by path). A page maps a URL (with `:name` path parameters) to the view in the middle of the screen,
 * plus docks: views fixed to an edge of the page.
 */

export const HMI_BASE = "/hmi";
export const SAMPLE_PROJECT = "_sample";
export const DEFAULT_HMI_PROJECT = "default";
export const PAGE_CONFIG_KIND = "PageConfig";
export const PAGE_CONFIG_RESOURCE = "page-config";
/** Pause between project reads when the gateway answers at once (a change, or no wait support). */
export const HMI_POLL_MS = 2000;
/** Slower retry while the gateway is unreachable or the caller may not read the project. */
export const HMI_RETRY_MS = 15_000;

const HOME_VIEWS = ["home", "main", "overview"];

export type HmiTarget =
  /** The built-in sample. [location] is the page path and query inside it. */
  | { kind: "sample"; location: string }
  /** A saved project. [location] is the page path and query inside it, such as `/pumps?line=3`. */
  | { kind: "project"; project: string; location: string }
  /** `/hmi/` with no project: the gateway's default or only project. [search] is kept for the page. */
  | { kind: "default"; search: string };

export function parseHmiUrl(pathname: string, search: string): HmiTarget {
  let rest = "";
  if (pathname.startsWith(`${HMI_BASE}/`)) rest = pathname.slice(HMI_BASE.length + 1);
  const slash = rest.indexOf("/");
  const head = slash < 0 ? rest : rest.slice(0, slash);
  const tail = slash < 0 ? "" : rest.slice(slash);
  const first = safeDecode(head);
  if (!first) {
    const params = new URLSearchParams(search);
    const legacy = params.get("project")?.trim();
    if (legacy) {
      params.delete("project");
      return { kind: "project", project: legacy, location: `/${query(params)}` };
    }
    return { kind: "default", search };
  }
  const page = tail.length > 1 ? tail.replace(/\/+$/, "") || "/" : "/";
  const location = `${page}${search}`;
  if (first === SAMPLE_PROJECT) return { kind: "sample", location };
  return { kind: "project", project: first, location };
}

/** Browser URL of [location] (page path and query) inside [project]. */
export function hmiUrl(project: string, location: string): string {
  const inside = location.startsWith("/") ? location : `/${location}`;
  return `${HMI_BASE}/${encodeURIComponent(project)}${inside}`;
}

/** The project `/hmi/` opens: `default` when it exists, else the only project, else none (the caller lists them). */
export function chooseDefaultProject(projects: readonly string[]): string | null {
  if (projects.includes(DEFAULT_HMI_PROJECT)) return DEFAULT_HMI_PROJECT;
  return projects.length === 1 ? projects[0]! : null;
}

export interface PageConfigSpec {
  pages: Array<{ name?: string; url: string; title?: string; primaryView: string; docks?: unknown[]; cornerPriority?: string }>;
  sharedDocks?: unknown[];
  cornerPriority?: string;
}

export interface ProjectPages {
  pages: PageDocument[];
  sharedDocks?: DockSpec[];
  cornerPriority?: CornerPriority;
  /** Entries that could not be read. They are left out; the rest of the project still runs. */
  errors: string[];
}

/** Pages from a stored page configuration, validated the way the runtime validates any page. */
export function pagesFromConfig(spec: PageConfigSpec): ProjectPages {
  const errors: string[] = [];
  const pages: PageDocument[] = [];
  for (const [index, entry] of (Array.isArray(spec.pages) ? spec.pages : []).entries()) {
    try {
      const name = entry.name ?? entry.title ?? entry.url;
      pages.push(
        parsePage({
          apiVersion: "oip.hmi/v1",
          kind: "Page",
          metadata: { name },
          spec: { url: entry.url, title: entry.title, primaryView: entry.primaryView, docks: entry.docks ?? [], cornerPriority: entry.cornerPriority },
        }),
      );
    } catch (error) {
      errors.push(`page ${String(entry?.url ?? index + 1)}: ${describe(error)}`);
    }
  }
  const result: ProjectPages = { pages, errors };
  if (spec.sharedDocks !== undefined) {
    try {
      // Shared docks have the same rules as a page's own docks.
      result.sharedDocks = parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "shared" }, spec: { url: "/", primaryView: "-", docks: spec.sharedDocks } }).spec.docks;
    } catch (error) {
      errors.push(`shared docks: ${describe(error)}`);
    }
  }
  if (spec.cornerPriority === "top-bottom" || spec.cornerPriority === "left-right") result.cornerPriority = spec.cornerPriority;
  else if (spec.cornerPriority !== undefined) errors.push(`corner priority: ${String(spec.cornerPriority)} is not top-bottom or left-right`);
  return result;
}

/**
 * Pages for a project without a page configuration: each view at `/<path>`, and a home view at `/`. [denied] view
 * paths (HMP-092) get pages too, titled by path, so their URL shows the refusal rather than "no page".
 */
export function derivedPages(views: readonly ViewDocument[], denied: readonly string[] = []): PageDocument[] {
  const entries = [
    ...views.map((view) => ({ path: view.metadata.path, title: view.metadata.name })),
    ...denied.filter((path) => !views.some((view) => view.metadata.path === path)).map((path) => ({ path, title: path })),
  ].sort((a, b) => a.path.localeCompare(b.path));
  const home = HOME_VIEWS.map((path) => entries.find((entry) => entry.path === path)).find((entry) => entry) ?? entries[0];
  const pages: PageDocument[] = [];
  if (home) pages.push(page("/", home));
  for (const entry of entries) {
    try {
      pages.push(page(`/${entry.path}`, entry));
    } catch {
      // A view path that is not a valid URL segment is still reachable from navigation and embedding.
    }
  }
  return pages;
}

function page(url: string, view: { path: string; title: string }): PageDocument {
  return parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: view.title }, spec: { url, title: view.title, primaryView: view.path, docks: [] } });
}

export interface LoadedHmiProject {
  project: HmiProject;
  /** Changes whenever a stored view, the page configuration or the style classes change. */
  signature: string;
  /** Where the pages came from. */
  pagesFrom: "config" | "views";
  /** Stored entries that could not be read; they are left untouched on the gateway. */
  errors: string[];
}

/** How long a session's project read waits at the gateway for a saved change (F12); then it asks again. */
export const HMI_WAIT_MS = 25_000;

/**
 * Read [name] as a session runs it: its views, page configuration and style classes, resolved through project
 * inheritance (PRO-002) on the gateway (`GET /api/v1/hmi/projects/{name}`, tags.read; review-4 F5). Null when the
 * project has neither views nor a page configuration (not saved yet, or deleted). Gateway errors (401, 403,
 * unreachable) are thrown; a disabled or inheritance-only project throws [ProjectUnavailable] (PRO-004).
 *
 * With [wait.after] (the signature of the project already shown) the gateway holds the read until the project
 * changes, for up to [wait.waitMs]; "unchanged" means it did not change in that time (F12: a change notification,
 * not a poll).
 */
export async function loadHmiProject(
  client: GatewayClient,
  name: string,
  wait: { after?: string; waitMs?: number; signal?: AbortSignal } = {},
): Promise<LoadedHmiProject | null | "unchanged"> {
  // A background read: a session that only shows values must not keep its login alive.
  const bundle = await client.hmiProject(name, { background: true, ...wait });
  if (bundle === "unchanged") return bundle;
  if (bundle === null) return null;
  if (!bundle.launchable) throw new ProjectUnavailable(name, bundle.enabled ? "not-runnable" : "disabled");
  const loaded = buildHmiProject(
    name,
    (bundle.views ?? []) as ConfigResource<ViewSpec>[],
    (bundle.pageConfig ?? null) as ConfigResource<PageConfigSpec> | null,
    (bundle.styleClasses ?? null) as ConfigResource<StyleClassesSpec> | null,
    bundle.deniedViews ?? [],
  );
  if (!loaded) return null;
  loaded.project.defaultTagProvider = bundle.defaultTagProvider;
  loaded.project.defaultDatabase = bundle.defaultDatabase;
  loaded.project.sessionTheme = bundle.sessionTheme;
  loaded.project.sessionLocale = bundle.sessionLocale;
  loaded.project.touchscreenMode = bundle.touchscreenMode === true;
  if (bundle.translations?.catalog) loaded.project.translations = bundle.translations.catalog;
  if (bundle.gatewayTimeZone) loaded.project.gatewayTimeZone = bundle.gatewayTimeZone;
  return { ...loaded, signature: bundle.signature };
}

/** The project exists but is not launched: it is disabled, or it is not runnable (an inheritance-only parent). */
export class ProjectUnavailable extends Error {
  readonly project: string;
  readonly reason: "disabled" | "not-runnable";

  constructor(project: string, reason: "disabled" | "not-runnable") {
    super(reason === "disabled" ? `project ${project} is disabled` : `project ${project} is not runnable`);
    this.name = "ProjectUnavailable";
    this.project = project;
    this.reason = reason;
  }
}

/** The project's session refuses this user (HMP-092): its session roles or identity provider do not match. */
export const SESSION_REFUSED = "session_refused";

/**
 * The runtime project for stored resources (see [loadHmiProject]). [denied] are the paths of views the user may not
 * open (HMP-092): without a page configuration each still gets its page, which shows the refusal.
 */
export function buildHmiProject(
  name: string,
  items: readonly ConfigResource<ViewSpec>[],
  config: ConfigResource<PageConfigSpec> | null,
  styles: ConfigResource<StyleClassesSpec> | null,
  denied: readonly string[] = [],
): LoadedHmiProject | null {
  if (items.length === 0 && denied.length === 0 && !config) return null;
  const errors: string[] = [];
  const views: ViewDocument[] = [];
  for (const item of items) {
    try {
      views.push(fromResource(item));
    } catch (error) {
      errors.push(`view ${item.metadata.name}: ${describe(error)}`);
    }
  }
  views.sort((a, b) => a.metadata.path.localeCompare(b.metadata.path));
  let styleClasses: StyleClassSet = {};
  let themes: HmiProject["themes"];
  if (styles) {
    try {
      styleClasses = parseStyleClassSet(styles.spec.classes ?? {});
      const parsed = parseCustomThemes(styles.spec.themes);
      if (Object.keys(parsed).length > 0) themes = parsed;
    } catch (error) {
      errors.push(`style classes: ${describe(error)}`);
    }
  }
  const project: HmiProject = { name, views, pages: [], styleClasses, ...(themes ? { themes } : {}) };
  if (denied.length > 0) project.deniedViews = [...denied];
  let pagesFrom: LoadedHmiProject["pagesFrom"] = "views";
  if (config) {
    pagesFrom = "config";
    const fromConfig = pagesFromConfig(config.spec);
    project.pages = fromConfig.pages;
    if (fromConfig.sharedDocks) project.sharedDocks = fromConfig.sharedDocks;
    if (fromConfig.cornerPriority) project.cornerPriority = fromConfig.cornerPriority;
    errors.push(...fromConfig.errors);
  } else {
    project.pages = derivedPages(views, denied);
  }
  const signature = [
    ...denied.map((path) => `denied:${path}`),
    ...items.map((item) => `${item.metadata.name}@${stamp(item)}`).sort(),
    `pages@${config ? stamp(config) : 0}`,
    `styles@${styles ? stamp(styles) : 0}`,
  ].join(",");
  return { project, signature, pagesFrom, errors };
}

/** Version plus modification time, so a resource deleted and created again (version back to 1) still differs. */
function stamp(resource: ConfigResource<unknown>): string {
  return `${resource.metadata.version}:${resource.metadata.modified ?? ""}`;
}

function query(params: URLSearchParams): string {
  const text = params.toString();
  return text ? `?${text}` : "";
}

function safeDecode(segment: string): string {
  try {
    return decodeURIComponent(segment).trim();
  } catch {
    return segment.trim();
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
