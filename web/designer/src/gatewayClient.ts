/**
 * Browser client for the gateway REST API, used by the Designer (persistence and tag browser).
 *
 * Same session rules as the admin UI: the session cookie is HttpOnly and set by the gateway; the
 * CSRF token from login or GET /api/v1/auth/session is held only in memory and sent as
 * X-CSRF-Token on POST, PUT, PATCH, and DELETE.
 */

import type { LogicUdtView, MappingFile } from "./logicModel";
export interface GatewayLogRecord {
  ts: string;
  level: string;
  logger: string;
  message: string;
}

const UNSAFE = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * Sent on background polls (a running /hmi session re-reading its project). The gateway authenticates and expires
 * such a request as usual, but it does not count as activity for the idle timeout.
 */
export const BACKGROUND_HEADER = "X-OIP-Background";

export interface ReadOptions {
  /** A background poll, not user activity: does not keep the login alive. */
  background?: boolean;
  /** Cancels the request (a session closing a waiting project read). */
  signal?: AbortSignal;
  /** Extra request headers, such as `If-Match`. */
  headers?: Record<string, string>;
}

export interface ResourceReadOptions extends ReadOptions {
  /**
   * Read through project inheritance (PRO-002): a project's own resources plus each ancestor's that no nearer project
   * overrides. A resource that comes from an ancestor carries `inheritedFrom`.
   */
  inherited?: boolean;
}

/** A project's settings (`GET /api/v1/projects/{name}`, PRO-004). */
export interface ProjectInfo {
  name: string;
  title: string;
  description: string;
  parent: string | null;
  enabled: boolean;
  inheritable: boolean;
  runnable: boolean;
  /** Enabled and runnable: the project opens at `/hmi`. */
  launchable: boolean;
  inheritanceChain: string[];
  defaultTagProvider?: string;
  defaultDatabase?: string;
  sessionTheme?: string;
  sessionLocale?: string;
  /** HMV-126: session inputs open an on-screen keyboard. */
  touchscreenMode?: boolean;
  /** Anonymous HMI. Off unless this project turns it on. */
  publicHmi?: boolean;
  sessionRoles?: string[];
  /** SEC-017: security levels a session user needs (one of them, or every one with sessionRequireAll). */
  sessionLevels?: string[];
  sessionRequireAll?: boolean;
  /** SEC-020: required Designer roles per action; empty lets every Designer user. */
  designerViewRoles?: string[];
  designerSaveRoles?: string[];
  designerDeleteRoles?: string[];
  designerProtectRoles?: string[];
  /** SEC-020: protected resources as `module/kind/name`. */
  protectedResources?: string[];
}

/** A project as a session runs it (`GET /api/v1/hmi/projects/{name}`). Resources are only present when launchable. */
export interface HmiProjectBundle {
  name: string;
  launchable: boolean;
  enabled: boolean;
  runnable: boolean;
  signature: string;
  views?: ConfigResource<unknown>[];
  pageConfig?: ConfigResource<unknown> | null;
  styleClasses?: ConfigResource<unknown> | null;
  /** Paths of views the caller may not open (HMP-092); nothing else of them is sent. */
  deniedViews?: string[];
  defaultTagProvider?: string;
  defaultDatabase?: string;
  sessionTheme?: string;
  sessionLocale?: string;
  /** HMV-126: the project asked sessions to open the on-screen keyboard. */
  touchscreenMode?: boolean;
  /** Terms for the session locale, and for every locale in `catalog` (HMP-125). */
  translations?: { locale?: string; messages?: Record<string, string>; locales?: string[]; catalog?: Record<string, Record<string, string>> };
  /** The gateway's default time zone (HMP-101). */
  gatewayTimeZone?: string;
}

export interface HttpFetchBody {
  ok: boolean;
  status?: number;
  body?: unknown;
  error?: string;
}

export class GatewayError extends Error {
  readonly status: number;
  /** The problem's slug (`urn:oip:problem:<slug>`), such as `session_refused`; absent when the answer named none. */
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = "GatewayError";
    this.status = status;
    if (code) this.code = code;
  }
}

/** The gateway could not be reached at all (no proxy, connection refused, proxy error). */
export class GatewayUnreachable extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GatewayUnreachable";
  }
}

export interface NamedQueryBody {
  type?: string;
  columns?: string[];
  rows?: unknown[][];
  value?: unknown;
  rowsAffected?: number;
}

export interface GatewaySession {
  username: string;
  csrfToken: string | null;
  /** The caller's effective permissions, when the gateway reports them (`GET /api/v1/auth/session`). */
  permissions?: string[];
  /** The signed-in user's roles, when the gateway reports them (`GET /api/v1/auth/session`). */
  roles?: string[];
}

/** The permission a Studio user needs (the gateway's designer roles grant it; review-4 F4). */
export const DESIGNER_LOGIN = "designer.login";

export interface ConfigResource<S = Record<string, unknown>> {
  apiVersion: string;
  kind: string;
  metadata: { name: string; module: string; version: number; modified?: string; modifiedBy?: string; labels?: Record<string, string> };
  spec: S;
  /** Set on an inherited read when the resource is stored in an ancestor project, not the project asked for. */
  inheritedFrom?: string;
}

export interface BrowseNode {
  name: string;
  path: string;
  kind: "folder" | "tag" | "udt_instance" | "udt_definition";
  hasChildren: boolean;
  dataType?: string;
  value?: unknown;
  quality?: string;
  timestamp?: string;
  /** False when the signed-in user may not read this tag: no data type, value or quality is sent. */
  readable?: boolean;
}

export interface BrowsePage {
  nodes: BrowseNode[];
  more: boolean;
}

export interface TagReading {
  path: string;
  value: unknown;
  quality: string;
  timestamp: string;
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

/** A project resource a Designer lock or protection names (SEC-020). */
export interface ResourceRef {
  module: string;
  kind: string;
  name: string;
}

/** A live resource lock (SEC-020). [session] is shown only for the caller's own locks. */
export interface ResourceLock extends ResourceRef {
  path: string;
  user: string;
  acquiredAt: string;
  mine: boolean;
  session?: string;
}

export type DesignerAction = "view" | "save" | "delete" | "protect";

/** What the caller may do with a project in the Designer (`GET /api/v1/designer/projects/{project}/access`). */
export interface ProjectAccess {
  project: string;
  user: string;
  allowed: Record<DesignerAction, boolean>;
  requiredRoles: Record<DesignerAction, string[]>;
  breakLocks: boolean;
  /** Protected resources as `module/kind/name`. */
  protected: string[];
  locks: ResourceLock[];
}

/** The header that tells the gateway which Designer session a save comes from, so a lock it holds is its own. */
export const DESIGNER_SESSION_HEADER = "X-OIP-Designer-Session";

export class GatewayClient {
  private csrf: string | null = null;
  private designerSession: string | null = null;

  constructor(private readonly fetcher: FetchLike = (input, init) => fetch(input, init)) {}

  /** Current caller, or null when not signed in. Throws [GatewayUnreachable] when there is no gateway. */
  async session(): Promise<GatewaySession | null> {
    const res = await this.raw("GET", "/api/v1/auth/session");
    if (res.status === 401) return null;
    const body = await this.json(res);
    const session = body as { username?: string; principal?: string; csrfToken?: string; permissions?: unknown; roles?: unknown };
    this.csrf = session.csrfToken ?? null;
    const result: GatewaySession = { username: session.username ?? session.principal ?? "", csrfToken: this.csrf };
    if (Array.isArray(session.permissions)) result.permissions = session.permissions.filter((entry): entry is string => typeof entry === "string");
    if (Array.isArray(session.roles)) result.roles = session.roles.filter((entry): entry is string => typeof entry === "string");
    return result;
  }

  async login(username: string, password: string): Promise<GatewaySession> {
    const res = await this.raw("POST", "/api/v1/auth/login", { username, password });
    const body = (await this.json(res)) as { username?: string; csrfToken?: string };
    this.csrf = body.csrfToken ?? null;
    return { username: body.username ?? username, csrfToken: this.csrf };
  }

  /** Ends the login (`POST /api/v1/auth/logout`). A 401 means it had already ended. */
  async logout(): Promise<void> {
    const res = await this.raw("POST", "/api/v1/auth/logout");
    if (!res.ok && res.status !== 401) await this.json(res);
    this.csrf = null;
  }

  async listResources<S>(module: string, scope: string, kind: string, options: ResourceReadOptions = {}): Promise<ConfigResource<S>[]> {
    const res = await this.raw("GET", configPath(module, scope, kind) + inheritedQuery(options), undefined, options);
    const body = (await this.json(res)) as { items?: ConfigResource<S>[] };
    return body.items ?? [];
  }

  async getResource<S>(module: string, scope: string, kind: string, name: string, options: ResourceReadOptions = {}): Promise<ConfigResource<S> | null> {
    const res = await this.raw("GET", configPath(module, scope, kind, name) + inheritedQuery(options), undefined, options);
    if (res.status === 404) return null;
    return (await this.json(res)) as ConfigResource<S>;
  }

  /** PUT with optimistic concurrency: `metadata.version` 0 creates; the stored version updates. A stale version is 409. */
  async putResource<S>(scope: string, resource: ConfigResource<S>): Promise<ConfigResource<S>> {
    const { module, name } = resource.metadata;
    // A listed resource carries read-only `scope` and `inheritedFrom`; the gateway refuses unknown keys on a write.
    const { apiVersion, kind, metadata, spec } = resource;
    const res = await this.raw("PUT", configPath(module, scope, kind, name), { apiVersion, kind, metadata, spec });
    return (await this.json(res)) as ConfigResource<S>;
  }

  /** Deletes one resource. [version] is sent as `If-Match`, so a resource changed since it was read is 409. */
  async deleteResource(module: string, scope: string, kind: string, name: string, version: number): Promise<void> {
    const res = await this.raw("DELETE", configPath(module, scope, kind, name), undefined, { headers: { "If-Match": `"${version}"` } });
    if (!res.ok && res.status !== 404) await this.json(res);
  }

  /** Names of the projects stored on the gateway (GET /api/v1/projects, gateway.read). */
  async listProjects(): Promise<string[]> {
    const items = await this.listProjectItems();
    return items.map((item) => item.name);
  }

  /** Every project's settings (GET /api/v1/projects `items`). */
  async listProjectItems(): Promise<ProjectInfo[]> {
    const res = await this.raw("GET", "/api/v1/projects");
    const body = (await this.json(res)) as { items?: ProjectInfo[]; projects?: string[] };
    if (Array.isArray(body.items) && body.items.length > 0) return body.items;
    return (body.projects ?? []).map((name) => ({
      name,
      title: name,
      description: "",
      parent: null,
      enabled: true,
      inheritable: false,
      runnable: true,
      launchable: true,
      inheritanceChain: [name],
    }));
  }

  /**
   * Create a project (POST /api/v1/projects). Name, title, description and an optional parent are the Designer
   * creation settings: doc source: FEATURES DES-011
   */
  async createProject(body: { name: string; title?: string; description?: string; parent?: string; inheritable?: boolean; runnable?: boolean }): Promise<ProjectInfo> {
    const res = await this.raw("POST", "/api/v1/projects", body);
    return (await this.json(res)) as ProjectInfo;
  }

  /** Copy every stored resource of [from] into a new project [to] (POST /api/v1/projects/{from}/copy). */
  async copyProject(from: string, to: string, title?: string): Promise<ProjectInfo> {
    const body: { name: string; title?: string } = { name: to };
    if (title !== undefined) body.title = title;
    const res = await this.raw("POST", `/api/v1/projects/${encodeURIComponent(from)}/copy`, body);
    return (await this.json(res)) as ProjectInfo;
  }

  async listExamples(): Promise<Array<{ id: string; title: string; description: string; project: string }>> {
    const res = await this.raw("GET", "/api/v1/examples");
    const body = (await this.json(res)) as { examples?: Array<{ id: string; title: string; description: string; project: string }> };
    return body.examples ?? [];
  }

  async installExample(id: string, replace = false): Promise<{ project: string }> {
    const query = replace ? "?replace=true" : "";
    const res = await this.raw("POST", `/api/v1/examples/${encodeURIComponent(id)}/install${query}`);
    return (await this.json(res)) as { project: string };
  }

  /** Launchable project names for `/hmi/` (`GET /api/v1/hmi/projects`, tags.read; review-4 F5). */
  async hmiProjects(): Promise<string[]> {
    const res = await this.raw("GET", "/api/v1/hmi/projects");
    const body = (await this.json(res)) as { projects?: string[] };
    return body.projects ?? [];
  }

  /**
   * What a session runs (`GET /api/v1/hmi/projects/{name}`, tags.read; review-4 F5 and F12). Null when the gateway has
   * no such project. With [after] (a signature from an earlier answer) the gateway holds the request until the project
   * changes or [waitMs] passes; "unchanged" means it did not change.
   */
  async hmiProject(name: string, options: ReadOptions & { after?: string; waitMs?: number } = {}): Promise<HmiProjectBundle | null | "unchanged"> {
    const query = new URLSearchParams();
    if (options.after !== undefined) {
      query.set("after", options.after);
      query.set("waitMs", String(options.waitMs ?? 0));
    }
    const text = query.toString();
    const res = await this.raw("GET", `/api/v1/hmi/projects/${encodeURIComponent(name)}${text ? `?${text}` : ""}`, undefined, options);
    if (res.status === 304) return "unchanged";
    if (res.status === 404) return null;
    return (await this.json(res)) as HmiProjectBundle;
  }

  /** One project's settings, or null when the gateway has no such project (or no project settings API answers 404). */
  async projectInfo(name: string, options: ReadOptions = {}): Promise<ProjectInfo | null> {
    const res = await this.raw("GET", `/api/v1/projects/${encodeURIComponent(name)}`, undefined, options);
    if (res.status === 404) return null;
    return (await this.json(res)) as ProjectInfo;
  }

  /** Change some project settings. The gateway merges them into the stored settings. */
  async patchProject(name: string, changes: Partial<ProjectInfo>): Promise<ProjectInfo> {
    const res = await this.raw("PATCH", `/api/v1/projects/${encodeURIComponent(name)}`, changes);
    return (await this.json(res)) as ProjectInfo;
  }

  async browseTags(path: string, offset = 0, limit = 200): Promise<BrowsePage> {
    const res = await this.raw("POST", "/api/v1/tags/browse", { path, offset, limit });
    const body = (await this.json(res)) as { nodes?: BrowseNode[]; more?: boolean };
    return { nodes: body.nodes ?? [], more: body.more === true };
  }

  /** Tag provider names, default first (`GET /api/v1/tags/providers`). */
  async tagProviders(): Promise<string[]> {
    const res = await this.raw("GET", "/api/v1/tags/providers");
    const body = (await this.json(res)) as { providers?: Array<{ name?: unknown }> };
    return (body.providers ?? []).map((p) => (typeof p.name === "string" ? p.name : "")).filter((name) => name !== "");
  }

  /** Run one script in the designer console (`POST /api/v1/designer/console`). */
  async runConsole(source: string): Promise<{ ok: boolean; value?: unknown; output: string; error?: string | null; scope: string }> {
    const res = await this.raw("POST", "/api/v1/designer/console", { source });
    return (await this.json(res)) as { ok: boolean; value?: unknown; output: string; error?: string | null; scope: string };
  }

  /** Tail of the gateway log (`GET /api/v1/logs`, the admin Logs page's API), newest records as the gateway returns them. */
  async gatewayLogs(minLevel: string, limit = 200): Promise<GatewayLogRecord[]> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (minLevel) params.set("minLevel", minLevel);
    const res = await this.raw("GET", `/api/v1/logs?${params.toString()}`);
    const body = (await this.json(res)) as { records?: Array<Record<string, unknown>> };
    return (body.records ?? []).map((row) => ({
      ts: String(row.ts ?? ""),
      level: String(row.level ?? ""),
      logger: String(row.logger ?? ""),
      message: String(row.message ?? ""),
    }));
  }

  /** Run a named query as the signed-in user. The query's own role list still applies. */
  async executeNamedQuery(name: string, parameters: Record<string, unknown>, project?: string): Promise<NamedQueryBody> {
    // A session names its project, so the project's own query of that name (or a parent's) runs before the gateway's (PRO-001).
    const res = await this.raw("POST", `/api/v1/db/queries/${encodeURIComponent(name)}/execute`, project ? { parameters, project } : { parameters });
    return (await this.json(res)) as NamedQueryBody;
  }

  /** HTTP binding fetch (HMP-114): the gateway reads the project's stored HTTP source; no address or credential comes back. */
  async fetchHttpSource(project: string, source: string, path: string, query: Record<string, string | number | boolean>): Promise<HttpFetchBody> {
    const res = await this.raw("POST", `/api/v1/hmi/projects/${encodeURIComponent(project)}/http/${encodeURIComponent(source)}`, { path, query });
    return (await this.json(res)) as HttpFetchBody;
  }

  async dbConnections(): Promise<Array<{ name: string; allowBrowserWrites?: boolean }>> {
    const res = await this.raw("GET", "/api/v1/db/connections");
    const body = (await this.json(res)) as { items?: Array<{ name?: string; allowBrowserWrites?: boolean }> };
    return (body.items ?? []).flatMap((item) => (typeof item.name === "string" ? [{ name: item.name, allowBrowserWrites: item.allowBrowserWrites }] : []));
  }

  /** HIS-003: GET one of the /api/v1/history routes with a prebuilt path and query string. */
  async historyQuery(path: string): Promise<unknown> {
    return this.json(await this.raw("GET", path));
  }

  /** DAT-035: turn an ad hoc script call into a named query spec and the rewritten script. Nothing is stored. */
  async convertToNamedQuery(body: { name: string; script: string; connection?: string }): Promise<{ name: string; query: Record<string, unknown>; call: string; script: string }> {
    const res = await this.raw("POST", "/api/v1/db/queries/convert", body);
    return (await this.json(res)) as { name: string; query: Record<string, unknown>; call: string; script: string };
  }

  async browseDatabase(connection: string, sql: string, maxRows: number): Promise<{ type?: string; columns?: string[]; rows?: unknown[][]; rowsAffected?: number; truncated?: boolean }> {
    const res = await this.raw("POST", `/api/v1/db/connections/${encodeURIComponent(connection)}/browse`, { sql, maxRows });
    return (await this.json(res)) as { type?: string; columns?: string[]; rows?: unknown[][]; rowsAffected?: number; truncated?: boolean };
  }

  async listImages(project: string): Promise<Array<{ name: string; mediaType: string; bytes: number }>> {
    const res = await this.raw("GET", `/api/v1/projects/${encodeURIComponent(project)}/images`);
    const body = (await this.json(res)) as { items?: Array<{ name: string; mediaType: string; bytes: number }> };
    return body.items ?? [];
  }

  async saveImage(project: string, name: string, data: string): Promise<void> {
    await this.raw("POST", `/api/v1/projects/${encodeURIComponent(project)}/images`, { name, data });
  }

  async readTags(paths: string[]): Promise<TagReading[]> {
    const res = await this.raw("POST", "/api/v1/tags/read", { paths });
    const body = (await this.json(res)) as { values?: TagReading[] };
    return body.values ?? [];
  }

  /** Register this Designer with the gateway's session list (GAT-028). Needs designer.login. */
  async openDesignerSession(project: string | null): Promise<{ id: string; heartbeatSeconds: number }> {
    const res = await this.raw("POST", "/api/v1/designer/sessions", project ? { project } : {});
    const body = (await this.json(res)) as { id: string; heartbeatSeconds?: number };
    return { id: body.id, heartbeatSeconds: body.heartbeatSeconds ?? 15 };
  }

  /** Keep a registered Designer session listed. A 404 means the gateway no longer has it. */
  async heartbeatDesignerSession(id: string): Promise<void> {
    await this.json(await this.raw("PUT", `/api/v1/designer/sessions/${encodeURIComponent(id)}`, {}));
  }

  async closeDesignerSession(id: string): Promise<void> {
    await this.json(await this.raw("DELETE", `/api/v1/designer/sessions/${encodeURIComponent(id)}`));
  }

  /** Send [id] with every write from now on (null stops): the gateway then knows which Designer holds a lock. */
  setDesignerSession(id: string | null): void {
    this.designerSession = id;
  }

  async projectAccess(project: string, options: ReadOptions = {}): Promise<ProjectAccess> {
    return (await this.json(await this.raw("GET", `${designerProjectPath(project)}/access`, undefined, options))) as ProjectAccess;
  }

  /** Take [ref]'s lock for Designer [session]. A 423 GatewayError means another Designer holds it. */
  async acquireLock(project: string, ref: ResourceRef, session: string): Promise<ResourceLock> {
    return (await this.json(await this.raw("POST", `${designerProjectPath(project)}/locks`, { ...ref, session }))) as ResourceLock;
  }

  /** Release this user's lock on [ref], or with [force] break another user's (needs users.admin). */
  async releaseLock(project: string, ref: ResourceRef, force = false): Promise<void> {
    await this.json(await this.raw("DELETE", `${designerProjectPath(project)}/locks/${resourcePath(ref)}${force ? "?break=true" : ""}`));
  }

  async logicPrograms(): Promise<string[]> {
    const body = (await this.json(await this.raw("GET", "/api/v1/logic/programs"))) as { items?: string[] };
    return body.items ?? [];
  }

  async logicCreate(name: string): Promise<{ name: string; source: string }> {
    return (await this.json(await this.raw("POST", "/api/v1/logic/programs", { name }))) as { name: string; source: string };
  }

  async logicProgram(name: string): Promise<{ name: string; source: string }> {
    return (await this.json(await this.raw("GET", `/api/v1/logic/programs/${encodeURIComponent(name)}`))) as { name: string; source: string };
  }

  async logicSave(name: string, source: string): Promise<void> {
    await this.json(await this.raw("PUT", `/api/v1/logic/programs/${encodeURIComponent(name)}`, { source }));
  }

  async logicDelete(name: string): Promise<void> {
    await this.json(await this.raw("DELETE", `/api/v1/logic/programs/${encodeURIComponent(name)}`));
  }

  async logicRename(name: string, next: string): Promise<void> {
    await this.json(await this.raw("POST", `/api/v1/logic/programs/${encodeURIComponent(name)}/rename`, { name: next }));
  }

  async logicCheck(name: string): Promise<Array<{ line: number; column: number; message: string }>> {
    const body = (await this.json(await this.raw("POST", `/api/v1/logic/programs/${encodeURIComponent(name)}/check`, {}))) as { diagnostics?: Array<{ line: number; column: number; message: string }> };
    return body.diagnostics ?? [];
  }

  async logicMapping(): Promise<MappingFile> {
    return (await this.json(await this.raw("GET", "/api/v1/logic/mapping"))) as MappingFile;
  }

  async logicSaveMapping(file: MappingFile): Promise<MappingFile> {
    return (await this.json(await this.raw("PUT", "/api/v1/logic/mapping", file))) as MappingFile;
  }

  /** Structure variables in [program], the UDT instances they can bind to, and each member's direction (0.1.1). */
  async logicUdts(program: string): Promise<LogicUdtView> {
    return (await this.json(await this.raw("GET", `/api/v1/logic/programs/${encodeURIComponent(program)}/udts`))) as LogicUdtView;
  }

  async logicDeploy(program: string): Promise<{ ok?: boolean; state?: string; message?: string; diagnostics?: Array<{ line: number; column: number; message: string }> }> {
    return (await this.json(await this.raw("POST", "/api/v1/logic/deploy", { program }))) as { ok?: boolean; state?: string; message?: string; diagnostics?: Array<{ line: number; column: number; message: string }> };
  }

  async logicStart(): Promise<void> {
    await this.json(await this.raw("POST", "/api/v1/logic/start", {}));
  }

  async logicStop(): Promise<void> {
    await this.json(await this.raw("POST", "/api/v1/logic/stop", {}));
  }

  async platform(): Promise<{ os: string; windowsPlc: boolean; banner: string }> {
    return (await this.json(await this.raw("GET", "/api/v1/platform"))) as { os: string; windowsPlc: boolean; banner: string };
  }

  async logicStatus(): Promise<{ state: string; message?: string; fault?: string | null; periodMs: number; jitterP99Ns: number; execP99Ns: number; overruns: number }> {
    return (await this.json(await this.raw("GET", "/api/v1/logic/status"))) as { state: string; message?: string; fault?: string | null; periodMs: number; jitterP99Ns: number; execP99Ns: number; overruns: number };
  }

  async logicWatch(): Promise<{ state: string; message?: string; fault?: string | null; forcesActive: boolean; variables: Array<{ name: string; value: unknown; forced: boolean; output: boolean; udt?: string | null; tag?: string; type?: string | null }> }> {
    return (await this.json(await this.raw("GET", "/api/v1/logic/watch", undefined, { background: true }))) as { state: string; message?: string; fault?: string | null; forcesActive: boolean; variables: Array<{ name: string; value: unknown; forced: boolean; output: boolean; udt?: string | null; tag?: string; type?: string | null }> };
  }

  async logicForce(name: string, value: unknown, output: boolean): Promise<void> {
    await this.json(await this.raw("POST", "/api/v1/logic/forces", { name, value, output }));
  }

  /** Releases every force (the online banner's Release all). */
  async logicReleaseAll(): Promise<void> {
    await this.json(await this.raw("DELETE", "/api/v1/logic/forces"));
  }

  /** Writes a mapped variable's tag once, without forcing it. */
  async logicWrite(name: string, value: unknown): Promise<void> {
    await this.json(await this.raw("POST", "/api/v1/logic/write", { name, value }));
  }

  async logicUnforce(name: string): Promise<void> {
    await this.json(await this.raw("DELETE", `/api/v1/logic/forces/${encodeURIComponent(name)}`));
  }

  async setProtected(project: string, ref: ResourceRef, on: boolean): Promise<string[]> {
    const res = await this.raw(on ? "PUT" : "DELETE", `${designerProjectPath(project)}/protected/${resourcePath(ref)}`, on ? {} : undefined);
    return ((await this.json(res)) as { protectedResources: string[] }).protectedResources;
  }

  private async raw(method: string, path: string, body?: unknown, options: ReadOptions = {}): Promise<Response> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (UNSAFE.has(method) && this.designerSession) headers[DESIGNER_SESSION_HEADER] = this.designerSession;
    if (options.background) headers[BACKGROUND_HEADER] = "1";
    if (options.headers) Object.assign(headers, options.headers);
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (UNSAFE.has(method) && this.csrf) headers["X-CSRF-Token"] = this.csrf;
    let res: Response;
    try {
      res = await this.fetcher(path, {
        method,
        headers,
        credentials: "same-origin",
        signal: options.signal,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (error) {
      throw new GatewayUnreachable(error instanceof Error ? error.message : String(error));
    }
    const type = res.headers.get("content-type") ?? "";
    // A dev proxy with no gateway behind it answers 5xx with a non-JSON body; a static server answers 404 HTML.
    if (!type.includes("json") && (res.status >= 500 || res.status === 404)) {
      throw new GatewayUnreachable(`gateway not reachable (HTTP ${res.status})`);
    }
    return res;
  }

  private async json(res: Response): Promise<unknown> {
    const text = await res.text();
    let body: unknown = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    if (!res.ok) {
      const problem = body as { detail?: string; title?: string; type?: string } | null;
      const code = typeof problem?.type === "string" && problem.type.startsWith("urn:oip:problem:") ? problem.type.slice("urn:oip:problem:".length) : undefined;
      throw new GatewayError(problem?.detail ?? problem?.title ?? `HTTP ${res.status}`, res.status, code);
    }
    return body;
  }
}

function designerProjectPath(project: string): string {
  return `/api/v1/designer/projects/${encodeURIComponent(project)}`;
}

function resourcePath(ref: ResourceRef): string {
  return [ref.module, ref.kind, ref.name].map(encodeURIComponent).join("/");
}

function inheritedQuery(options: ResourceReadOptions): string {
  return options.inherited ? "?inherited=true" : "";
}

function configPath(module: string, scope: string, kind: string, name?: string): string {
  const parts = ["/api/v1/config", module, scope, kind];
  if (name !== undefined) parts.push(name);
  return parts.map((part, index) => (index === 0 || index === 2 ? part : encodeURIComponent(part))).join("/");
}

/** One client per browser tab; the tag runtime and the Studio share its CSRF token. */
export const gateway = new GatewayClient();
