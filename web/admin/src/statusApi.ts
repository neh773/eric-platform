import { ApiError, type ApiClient } from "./api";

/**
 * Read and write helpers for the gateway status pages (Overview, Logs, Diagnostics, Projects).
 *
 * These wrap routes that already exist in gateway/server `Api.kt` (documented in `OpenApi.kt`):
 * `/api/v1/health`, `/api/v1/logs*`, `/api/v1/diagnostics/*`, and `/api/v1/projects*`.
 * Reads need gateway.read; level changes and imports need gateway.write and send the CSRF token
 * the session client already holds. Nothing here stores a token in browser storage.
 *
 * Public references (concepts only, no text copied):
 */

export const LOG_LEVELS = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"] as const;
export type LogLevelName = (typeof LOG_LEVELS)[number];

export interface LogRecordView {
  ts: string;
  level: string;
  logger: string;
  message: string;
  fields: Record<string, unknown>;
}

export interface LogQuery {
  minLevel?: string;
  logger?: string;
  q?: string;
  limit?: number;
}

export interface LogLevels {
  defaultLevel: string;
  levels: Record<string, string>;
}

export interface ClockDrift {
  intervalMs: number;
  drift: number;
  lateMillis: number;
  samples: number;
}

export interface PerformanceSample {
  cpuLoad: number;
  heapUsedBytes: number;
  diskUsableBytes: number;
  clockDrift: number;
}

export interface Performance {
  cpuLoad: number;
  processCpuLoad: number;
  heapUsedBytes: number;
  heapMaxBytes: number;
  disk: { path: string; usableBytes: number; totalBytes: number };
  clockDrift: ClockDrift;
  samples: PerformanceSample[];
}

export interface ThreadRow {
  id: number;
  name: string;
  state: string;
  daemon: boolean;
  cpuNanos: number;
  blockedCount: number;
  waitedCount: number;
}

export interface TaskRow {
  name: string;
  rateMs: number;
  state: string;
  lastDurationMillis: number;
  runs: number;
  lastError: string | null;
}

export interface MetricSeries {
  id: string;
  label: string;
  unit: string;
  /** Top of scale the gateway suggests for a gauge; null when it has none. */
  max: number | null;
  value: number | null;
  /** Aligned with `MetricsReport.times`; null marks a gap. */
  values: (number | null)[];
}

export interface MetricsReport {
  intervalMs: number;
  times: number[];
  metrics: MetricSeries[];
}

export interface ScriptRow {
  name: string;
  type: string;
  running: number;
  executions: number;
  failures: number;
  lastMillis: number;
  avgMillis: number;
  maxMillis: number;
  lastError: string | null;
  recentLog: string[];
}

export interface MemoryReport {
  heap: { usedBytes: number; committedBytes: number; maxBytes: number };
  nonHeap: { usedBytes: number; committedBytes: number };
  processors: number;
}

export interface ModuleRow {
  id: string;
  name: string;
  version: string;
  state: string;
  error: string | null;
}

/** One subscription on an OPC UA client connection. Item count is server-created items only. */
export interface OpcUaSubscriptionStatus {
  name: string;
  samplingIntervalMs: number;
  requestedPublishingIntervalMs: number;
  revisedPublishingIntervalMs: number;
  monitoredItemCount: number;
}

/**
 * One OPC UA client connection, as the connections page shows it.
 * Doc source: FEATURES DEV-016.
 */
export interface OpcUaConnectionStatus {
  name: string;
  enabled: boolean;
  description: string;
  endpointUrl: string;
  state: string;
  connected: boolean;
  serverState: string | null;
  sessionId: string | null;
  sessionName: string | null;
  uptimeMs: number | null;
  subscriptionCount: number;
  monitoredItemCount: number;
  samplingIntervalMs: number | null;
  publishIntervalMs: number | null;
  requestCount: number;
  errorCount: number;
  timeoutCount: number;
  averageResponseTimeMs: number | null;
  lastError: string | null;
  subscriptions: OpcUaSubscriptionStatus[];
}

/** One row of the Connections list (GAT-022). `health` is ok, pending, fault or disabled. */
export interface ConnectionItem {
  id: string;
  kind: "device" | "database" | "service" | "opc";
  type: string;
  name: string;
  status: string;
  health: "ok" | "pending" | "fault" | "disabled";
  detail: string;
  /** The admin section that edits this connection. */
  section: string;
}

export interface ConnectionsDocument {
  items: ConnectionItem[];
  summary: { total: number; ok: number; pending: number; fault: number; disabled: number };
}

/** Runtime facts for the Diagnostics Environment page (GAT-021). */
export interface EnvironmentReport {
  gatewayVersion: string;
  profile: string;
  javaVersion: string;
  javaVendor: string;
  os: string;
  architecture: string;
  processors: number;
  timeZone: string;
  dataDirectory: string;
  startedAt: string | null;
  uptimeMillis: number;
  deploymentMode: string;
  configResources: number;
}

/** One connected Designer (GAT-028). */
export interface DesignerSessionRow {
  id: string;
  user: string;
  project: string | null;
  address: string;
  openedAt: string;
  lastSeen: string;
}

export interface DesignerSessionsDocument {
  sessions: DesignerSessionRow[];
  ttlSeconds: number;
  launch: { label: string; href: string };
  download: { label: string; href: string; filename: string };
}

/** One SMTP email profile as the API reports it. Secrets are never returned, only whether they are set. */
export interface EmailProfile {
  name: string;
  enabled: boolean;
  hostname: string;
  port: number;
  useSsl: boolean;
  startTls: boolean;
  username: string;
  passwordSet: boolean;
  authType: "password" | "oauth2";
  fromAddress: string;
  timeoutMillis: number;
  oauthTokenUrl: string;
  oauthClientId: string;
  clientSecretSet: boolean;
  refreshTokenSet: boolean;
  oauthScope: string;
  /** SEC-029: the OAuth2 client registration that supplies the token, or empty for the inline settings. */
  oauthClient: string;
  version: number;
}

/** An OAuth2 client registration (SEC-029). Secrets and tokens are never returned, only whether they are set. */
export interface OAuth2Client {
  name: string;
  description: string;
  enabled: boolean;
  clientId: string;
  clientSecretSet: boolean;
  /** A secret store reference the admin chose instead of an embedded secret. */
  clientSecretRef: string | null;
  authorizationUrl: string;
  tokenUrl: string;
  scope: string;
  grantType: "client_credentials" | "refresh_token";
  refreshTokenSet: boolean;
  redirectBaseUrl: string;
  /** The callback to register with the identity provider. */
  redirectUri: string | null;
  httpVersion: "HTTP_2" | "HTTP_1_1";
  connectTimeoutMillis: number;
  requestTimeoutMillis: number;
  usedBy: string[];
  tokenCachedUntil: string | null;
  problems: string[];
  version: number;
}

/** Fields a save sends. A secret left out keeps the stored one; an empty string clears it. */
export interface OAuth2ClientInput {
  description?: string;
  enabled?: boolean;
  clientId?: string;
  clientSecret?: string;
  clientSecretRef?: string;
  authorizationUrl?: string;
  tokenUrl?: string;
  scope?: string;
  grantType?: string;
  refreshToken?: string;
  redirectBaseUrl?: string;
  httpVersion?: string;
  connectTimeoutMillis?: number;
  requestTimeoutMillis?: number;
}

/** What Verify Token reports: never the token itself. */
export interface OAuth2TokenCheck {
  grantType: string;
  tokenType: string;
  expiresInSeconds: number;
  scope: string;
  refreshTokenReceived: boolean;
  receivedAt: string;
  expiresAt: string;
}

/**
 * Fields a save sends. A secret field left out keeps the stored secret; an empty string clears it. The secret values go
 * to the gateway's secret store and are never read back.
 */
export interface EmailProfileInput {
  enabled?: boolean;
  hostname: string;
  port?: number;
  useSsl?: boolean;
  startTls?: boolean;
  username?: string;
  password?: string;
  fromAddress?: string;
  authType?: "password" | "oauth2";
  oauthTokenUrl?: string;
  oauthClientId?: string;
  oauthClientSecret?: string;
  oauthRefreshToken?: string;
  oauthScope?: string;
  oauthClient?: string;
}

export type ConflictPolicy = "skip" | "overwrite";

export interface ImportOutcome {
  project: string;
  policy: string;
  created: number;
  overwritten: number;
  skipped: number;
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

const UNSAFE = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** Same-origin download links. The session cookie goes with them, the same way the backups page works. */
export const LOG_DOWNLOAD_PATH = "/api/v1/logs/download";
export const THREAD_DUMP_PATH = "/api/v1/diagnostics/threads/dump";

/** A project and its settings (PRO-004, PRO-002). */
export interface ProjectItem {
  name: string;
  title: string;
  description: string;
  parent: string | null;
  enabled: boolean;
  inheritable: boolean;
  runnable: boolean;
  launchable: boolean;
  /** Anonymous HMI. Off unless the project turns it on. */
  publicHmi: boolean;
  resources: number;
  version: number;
  children: string[];
  inheritanceChain: string[];
}

export interface ProjectInput {
  title?: string;
  description?: string;
  /** Empty string or null clears the parent. */
  parent?: string | null;
  enabled?: boolean;
  inheritable?: boolean;
  runnable?: boolean;
  publicHmi?: boolean;
}

export interface HomeLink {
  id: string;
  label: string;
  href: string;
}

export interface HomeProject {
  name: string;
  title: string;
  description: string;
  hmi: string;
  /** Null when the caller may not edit projects. */
  designer: string | null;
}

export interface SetupStep {
  id: string;
  label: string;
  done: boolean;
  href: string;
}

/** `GET /api/v1/home` (GAT-023): already filtered by the caller's permissions. */
export interface HomeDocument {
  systemName: string;
  user: string;
  links: HomeLink[];
  projects: HomeProject[];
  /** The caller may not read projects. */
  projectsHidden: boolean;
  /** First-run steps; null for a caller who may not change the gateway. */
  setup: { complete: boolean; steps: SetupStep[] } | null;
}

export interface HmiSessionRow {
  id: string;
  user: string;
  connected: boolean;
  project: string;
  page: string;
  views: string[];
}

export function projectExportPath(name: string): string {
  return `/api/v1/projects/${encodeURIComponent(name)}/export`;
}

/** Field keys that might carry credential material. Their values are never shown. */
const SENSITIVE_KEY = /pass(word)?|secret|token|hash|credential|private|api[-_]?key|csrf|cookie|authorization/i;

export function redactFields(fields: Record<string, unknown>): Array<[string, string]> {
  return Object.entries(fields).map(([key, value]) => {
    if (SENSITIVE_KEY.test(key)) return [key, "[hidden]"];
    if (value === null || value === undefined) return [key, ""];
    if (typeof value === "object") return [key, JSON.stringify(value)];
    return [key, String(value)];
  });
}

export class StatusApi {
  private readonly client: ApiClient | undefined;
  private readonly fetchImpl: FetchLike;

  constructor(client: ApiClient | undefined, fetchImpl?: FetchLike) {
    this.client = client;
    this.fetchImpl = fetchImpl ?? ((input, init) => fetch(input, init));
  }

  async logs(query: LogQuery): Promise<LogRecordView[]> {
    const params = new URLSearchParams();
    if (query.minLevel) params.set("minLevel", query.minLevel);
    if (query.logger) params.set("logger", query.logger);
    if (query.q) params.set("q", query.q);
    params.set("limit", String(query.limit ?? 200));
    const body = record(await this.getJson(`/api/v1/logs?${params.toString()}`));
    return list(body.records).map(parseLogRecord);
  }

  async logLevels(): Promise<LogLevels> {
    const body = record(await this.getJson("/api/v1/logs/levels"));
    const levels: Record<string, string> = {};
    const raw = body.levels;
    if (raw && typeof raw === "object" && !Array.isArray(raw)) {
      for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
        if (typeof value === "string") levels[key] = value;
      }
    }
    return { defaultLevel: text(body.default), levels };
  }

  async setLogLevel(logger: string, level: string): Promise<{ logger: string; level: string }> {
    const res = await this.send("PUT", "/api/v1/logs/levels", JSON.stringify({ logger, level }), "application/json");
    const body = record(await res.json());
    return { logger: text(body.logger), level: text(body.level) };
  }

  async performance(): Promise<Performance> {
    const body = record(await this.getJson("/api/v1/diagnostics/performance"));
    const disk = optionalRecord(body.disk);
    const drift = optionalRecord(body.clockDrift);
    return {
      cpuLoad: num(body.cpuLoad, -1),
      processCpuLoad: num(body.processCpuLoad, -1),
      heapUsedBytes: num(body.heapUsedBytes),
      heapMaxBytes: num(body.heapMaxBytes, -1),
      disk: { path: text(disk.path), usableBytes: num(disk.usableBytes), totalBytes: num(disk.totalBytes) },
      clockDrift: {
        intervalMs: num(drift.intervalMs),
        drift: num(drift.drift, -1),
        lateMillis: num(drift.lateMillis),
        samples: num(drift.samples),
      },
      samples: list(body.samples).map((item) => {
        const sample = optionalRecord(item);
        return {
          cpuLoad: num(sample.cpuLoad, -1),
          heapUsedBytes: num(sample.heapUsedBytes),
          diskUsableBytes: num(sample.diskUsableBytes),
          clockDrift: num(sample.clockDrift, -1),
        };
      }),
    };
  }

  async threads(): Promise<ThreadRow[]> {
    const body = record(await this.getJson("/api/v1/diagnostics/threads"));
    return list(body.threads).map((item) => {
      const row = optionalRecord(item);
      return {
        id: num(row.id),
        name: text(row.name),
        state: text(row.state),
        daemon: row.daemon === true,
        cpuNanos: num(row.cpuNanos, -1),
        blockedCount: num(row.blockedCount),
        waitedCount: num(row.waitedCount),
      };
    });
  }

  async tasks(): Promise<TaskRow[]> {
    const body = record(await this.getJson("/api/v1/diagnostics/tasks"));
    return list(body.tasks).map((item) => {
      const row = optionalRecord(item);
      return {
        name: text(row.name),
        rateMs: num(row.rateMs),
        state: text(row.state),
        lastDurationMillis: num(row.lastDurationMillis),
        runs: num(row.runs),
        lastError: typeof row.lastError === "string" ? row.lastError : null,
      };
    });
  }

  async metrics(): Promise<MetricsReport> {
    const body = record(await this.getJson("/api/v1/diagnostics/metrics"));
    return {
      intervalMs: num(body.intervalMs, 5000),
      times: list(body.times).map((t) => num(t)),
      metrics: list(body.metrics).map((item) => {
        const row = optionalRecord(item);
        return {
          id: text(row.id),
          label: text(row.label),
          unit: text(row.unit),
          max: numOrNull(row.max),
          value: numOrNull(row.value),
          values: list(row.values).map(numOrNull),
        };
      }),
    };
  }

  async scripts(): Promise<ScriptRow[]> {
    const body = record(await this.getJson("/api/v1/diagnostics/scripts"));
    return list(body.scripts).map((item) => {
      const row = optionalRecord(item);
      return {
        name: text(row.name),
        type: text(row.type),
        running: num(row.running),
        executions: num(row.executions),
        failures: num(row.failures),
        lastMillis: num(row.lastMillis),
        avgMillis: num(row.avgMillis),
        maxMillis: num(row.maxMillis),
        lastError: typeof row.lastError === "string" ? row.lastError : null,
        recentLog: list(row.recentLog).map((line) => text(line)),
      };
    });
  }

  async memory(): Promise<MemoryReport> {
    const body = record(await this.getJson("/api/v1/diagnostics/memory"));
    const heap = optionalRecord(body.heap);
    const nonHeap = optionalRecord(body.nonHeap);
    return {
      heap: { usedBytes: num(heap.usedBytes), committedBytes: num(heap.committedBytes), maxBytes: num(heap.maxBytes, -1) },
      nonHeap: { usedBytes: num(nonHeap.usedBytes), committedBytes: num(nonHeap.committedBytes) },
      processors: num(body.processors),
    };
  }

  async opcUaConnections(): Promise<OpcUaConnectionStatus[]> {
    const body = record(await this.getJson("/api/v1/diagnostics/opcua/connections"));
    return list(body.connections).map(parseOpcUaConnection);
  }

  async connections(): Promise<ConnectionsDocument> {
    const body = record(await this.getJson("/api/v1/connections"));
    const kinds = new Set(["device", "database", "service", "opc"]);
    const healths = new Set(["ok", "pending", "fault", "disabled"]);
    const items = list(body.items).map((raw): ConnectionItem => {
      const row = optionalRecord(raw);
      return {
        id: text(row.id),
        kind: (kinds.has(text(row.kind)) ? text(row.kind) : "service") as ConnectionItem["kind"],
        type: text(row.type),
        name: text(row.name),
        status: text(row.status),
        health: (healths.has(text(row.health)) ? text(row.health) : "fault") as ConnectionItem["health"],
        detail: text(row.detail),
        section: text(row.section),
      };
    });
    const summary = optionalRecord(body.summary);
    return {
      items,
      summary: {
        total: num(summary.total, items.length),
        ok: num(summary.ok),
        pending: num(summary.pending),
        fault: num(summary.fault),
        disabled: num(summary.disabled),
      },
    };
  }

  async designerSessions(): Promise<DesignerSessionsDocument> {
    const body = record(await this.getJson("/api/v1/designer/sessions"));
    const launch = optionalRecord(body.launch);
    const download = optionalRecord(body.download);
    return {
      sessions: list(body.sessions).map((raw) => {
        const row = optionalRecord(raw);
        return {
          id: text(row.id),
          user: text(row.user),
          project: typeof row.project === "string" ? row.project : null,
          address: text(row.address),
          openedAt: text(row.openedAt),
          lastSeen: text(row.lastSeen),
        };
      }),
      ttlSeconds: num(body.ttlSeconds, 45),
      launch: { label: text(launch.label) || "Launch Control Canvas", href: text(launch.href) || "/studio" },
      download: {
        label: text(download.label) || "Download Control Canvas shortcut",
        href: text(download.href) || "/api/v1/designer/launcher",
        filename: text(download.filename) || "control-canvas.url",
      },
    };
  }

  async emailProfiles(): Promise<EmailProfile[]> {
    const body = record(await this.getJson("/api/v1/email/profiles"));
    return list(body.items).map(parseEmailProfile);
  }

  async saveEmailProfile(name: string, input: EmailProfileInput): Promise<EmailProfile> {
    return parseEmailProfile(await this.sendJson("PUT", `/api/v1/email/profiles/${encodeURIComponent(name)}`, input));
  }

  async deleteEmailProfile(name: string): Promise<void> {
    await this.send("DELETE", `/api/v1/email/profiles/${encodeURIComponent(name)}`);
  }

  async oauth2Clients(): Promise<OAuth2Client[]> {
    const body = record(await this.getJson(OAUTH2_CLIENTS));
    return list(body.items).map(parseOAuth2Client);
  }

  async saveOAuth2Client(name: string, input: OAuth2ClientInput): Promise<OAuth2Client> {
    return parseOAuth2Client(await this.sendJson("PUT", `${OAUTH2_CLIENTS}/${encodeURIComponent(name)}`, input));
  }

  async deleteOAuth2Client(name: string): Promise<void> {
    await this.send("DELETE", `${OAUTH2_CLIENTS}/${encodeURIComponent(name)}`);
  }

  async verifyOAuth2Token(
    name: string,
    body: { grantType: string; scope?: string; code?: string; redirectUri?: string; refreshToken?: string; parameters?: Record<string, string> },
  ): Promise<OAuth2TokenCheck> {
    const row = optionalRecord(await this.sendJson("POST", `${OAUTH2_CLIENTS}/${encodeURIComponent(name)}/verify-token`, body));
    return {
      grantType: text(row.grantType),
      tokenType: text(row.tokenType),
      expiresInSeconds: num(row.expiresInSeconds),
      scope: text(row.scope),
      refreshTokenReceived: row.refreshTokenReceived === true,
      receivedAt: text(row.receivedAt),
      expiresAt: text(row.expiresAt),
    };
  }

  /** Start the authorization code flow; the answer is the provider's page to open. */
  async authorizeOAuth2Client(name: string, body: { scope?: string; parameters?: Record<string, string> }): Promise<string> {
    const row = optionalRecord(await this.sendJson("POST", `${OAUTH2_CLIENTS}/${encodeURIComponent(name)}/authorize`, body));
    return text(row.authorizationUrl);
  }

  async testEmailProfile(name: string, to: string): Promise<void> {
    await this.sendJson("POST", `/api/v1/email/profiles/${encodeURIComponent(name)}/test`, { to });
  }

  async environment(): Promise<EnvironmentReport> {
    const body = record(await this.getJson("/api/v1/diagnostics/environment"));
    const config = optionalRecord(body.configResources);
    return {
      gatewayVersion: text(body.gatewayVersion),
      profile: text(body.profile),
      javaVersion: text(body.javaVersion),
      javaVendor: text(body.javaVendor),
      os: text(body.os),
      architecture: text(body.architecture),
      processors: num(body.processors),
      timeZone: text(body.timeZone),
      dataDirectory: text(body.dataDirectory),
      startedAt: typeof body.startedAt === "string" ? body.startedAt : null,
      uptimeMillis: num(body.uptimeMillis),
      deploymentMode: text(body.deploymentMode),
      configResources: num(config.resources),
    };
  }

  async modules(): Promise<ModuleRow[]> {
    const body = record(await this.getJson("/api/v1/diagnostics/modules"));
    return list(body.modules).map((item) => {
      const row = optionalRecord(item);
      return {
        id: text(row.id),
        name: text(row.name) || text(row.id),
        version: text(row.version),
        state: text(row.state),
        error: typeof row.error === "string" ? row.error : null,
      };
    });
  }

  async projects(): Promise<string[]> {
    const body = record(await this.getJson("/api/v1/projects"));
    return list(body.projects).filter((item): item is string => typeof item === "string");
  }

  /** Every project with its settings (PRO-004). */
  async listExamples(): Promise<Array<{ id: string; title: string; description: string; project: string }>> {
    const res = await this.send("GET", "/api/v1/examples");
    const body = (await res.json()) as { examples?: Array<{ id: string; title: string; description: string; project: string }> };
    return body.examples ?? [];
  }

  async installExample(id: string, replace = false): Promise<{ project: string }> {
    const query = replace ? "?replace=true" : "";
    const res = await this.send("POST", `/api/v1/examples/${encodeURIComponent(id)}/install${query}`);
    return (await res.json()) as { project: string };
  }

  async projectItems(): Promise<ProjectItem[]> {
    const body = record(await this.getJson("/api/v1/projects"));
    // A gateway from before PRO-004 lists names only; those read with default settings.
    if (!Array.isArray(body.items)) return list(body.projects).filter((item): item is string => typeof item === "string").map((name) => parseProject({ name }));
    return list(body.items).map(parseProject);
  }

  async createProject(input: ProjectInput & { name: string }): Promise<ProjectItem> {
    return parseProject(await this.sendJson("POST", "/api/v1/projects", input));
  }

  /** Change some settings of [name]: enable or disable it, its title, description, parent, or flags. */
  async updateProject(name: string, changes: ProjectInput): Promise<ProjectItem> {
    return parseProject(await this.sendJson("PATCH", projectPath(name), changes));
  }

  async renameProject(name: string, newName: string): Promise<ProjectItem> {
    return parseProject(await this.sendJson("POST", `${projectPath(name)}/rename`, { name: newName }));
  }

  async copyProject(name: string, newName: string, title?: string): Promise<ProjectItem> {
    return parseProject(await this.sendJson("POST", `${projectPath(name)}/copy`, title === undefined ? { name: newName } : { name: newName, title }));
  }

  async deleteProject(name: string): Promise<void> {
    await this.send("DELETE", projectPath(name));
  }

  /** The gateway home page for the signed-in caller (GAT-023). */
  async home(): Promise<HomeDocument> {
    return parseHome(await this.getJson("/api/v1/home"));
  }

  /** Live HMI sessions (`GET /api/v1/hmi/sessions`, gateway.read). */
  async hmiSessions(): Promise<HmiSessionRow[]> {
    const body = (await this.getJson("/api/v1/hmi/sessions")) as { sessions?: HmiSessionRow[] };
    return body.sessions ?? [];
  }

  async setSetupComplete(complete: boolean): Promise<HomeDocument> {
    return parseHome(await this.sendJson("PUT", "/api/v1/home/setup", { complete }));
  }

  private async sendJson(method: string, path: string, body: unknown): Promise<unknown> {
    const res = await this.send(method, path, JSON.stringify(body), "application/json");
    return res.json();
  }

  async importProject(name: string, archive: ArrayBuffer, policy: ConflictPolicy): Promise<ImportOutcome> {
    const path = `/api/v1/projects/${encodeURIComponent(name)}/import?policy=${policy}`;
    const res = await this.send("POST", path, archive, "application/zip");
    const body = record(await res.json());
    return {
      project: text(body.project),
      policy: text(body.policy),
      created: num(body.created),
      overwritten: num(body.overwritten),
      skipped: num(body.skipped),
    };
  }

  private async getJson(path: string): Promise<unknown> {
    const res = await this.send("GET", path);
    return res.json();
  }

  private async send(method: string, path: string, body?: BodyInit, contentType?: string): Promise<Response> {
    const headers = new Headers();
    if (contentType) headers.set("Content-Type", contentType);
    if (UNSAFE.has(method)) {
      const csrf = this.client?.csrfToken();
      if (!csrf) throw new ApiError("Missing CSRF token", 403);
      headers.set("X-CSRF-Token", csrf);
    }
    const res = await this.fetchImpl(path, { method, headers, body, credentials: "same-origin" });
    if (!res.ok) throw await problemError(res);
    return res;
  }
}

function parseEmailProfile(value: unknown): EmailProfile {
  const row = optionalRecord(value);
  return {
    name: text(row.name),
    enabled: row.enabled !== false,
    hostname: text(row.hostname),
    port: num(row.port, 25),
    useSsl: row.useSsl === true,
    startTls: row.startTls === true,
    username: text(row.username),
    passwordSet: row.passwordSet === true,
    authType: row.authType === "oauth2" ? "oauth2" : "password",
    fromAddress: text(row.fromAddress),
    timeoutMillis: num(row.timeoutMillis, 10_000),
    oauthTokenUrl: text(row.oauthTokenUrl),
    oauthClientId: text(row.oauthClientId),
    clientSecretSet: row.clientSecretSet === true,
    refreshTokenSet: row.refreshTokenSet === true,
    oauthScope: text(row.oauthScope),
    oauthClient: text(row.oauthClient),
    version: num(row.version),
  };
}

const OAUTH2_CLIENTS = "/api/v1/security/oauth2-clients";

function parseOAuth2Client(value: unknown): OAuth2Client {
  const row = optionalRecord(value);
  return {
    name: text(row.name),
    description: text(row.description),
    enabled: row.enabled !== false,
    clientId: text(row.clientId),
    clientSecretSet: row.clientSecretSet === true,
    clientSecretRef: typeof row.clientSecretRef === "string" ? row.clientSecretRef : null,
    authorizationUrl: text(row.authorizationUrl),
    tokenUrl: text(row.tokenUrl),
    scope: text(row.scope),
    grantType: row.grantType === "refresh_token" ? "refresh_token" : "client_credentials",
    refreshTokenSet: row.refreshTokenSet === true,
    redirectBaseUrl: text(row.redirectBaseUrl),
    redirectUri: typeof row.redirectUri === "string" ? row.redirectUri : null,
    httpVersion: row.httpVersion === "HTTP_1_1" ? "HTTP_1_1" : "HTTP_2",
    connectTimeoutMillis: num(row.connectTimeoutMillis, 30_000),
    requestTimeoutMillis: num(row.requestTimeoutMillis, 120_000),
    usedBy: list(row.usedBy).map(text),
    tokenCachedUntil: typeof row.tokenCachedUntil === "string" ? row.tokenCachedUntil : null,
    problems: list(row.problems).map(text),
    version: num(row.version),
  };
}

function projectPath(name: string): string {
  return `/api/v1/projects/${encodeURIComponent(name)}`;
}

function parseProject(value: unknown): ProjectItem {
  const row = optionalRecord(value);
  const names = (raw: unknown) => list(raw).filter((item): item is string => typeof item === "string");
  return {
    name: text(row.name),
    title: text(row.title),
    description: text(row.description),
    parent: typeof row.parent === "string" && row.parent ? row.parent : null,
    enabled: row.enabled !== false,
    inheritable: row.inheritable === true,
    runnable: row.runnable !== false,
    launchable: row.launchable !== false,
    publicHmi: row.publicHmi === true,
    resources: num(row.resources),
    version: num(row.version),
    children: names(row.children),
    inheritanceChain: names(row.inheritanceChain),
  };
}

function parseHome(value: unknown): HomeDocument {
  const body = optionalRecord(value);
  const setup = body.setup && typeof body.setup === "object" ? optionalRecord(body.setup) : null;
  return {
    systemName: text(body.systemName),
    user: text(body.user),
    links: list(body.links).map((item) => {
      const row = optionalRecord(item);
      return { id: text(row.id), label: text(row.label), href: text(row.href) };
    }),
    projects: list(body.projects).map((item) => {
      const row = optionalRecord(item);
      return {
        name: text(row.name),
        title: text(row.title),
        description: text(row.description),
        hmi: text(row.hmi),
        designer: typeof row.designer === "string" ? row.designer : null,
      };
    }),
    projectsHidden: body.projectsHidden === true,
    setup: setup
      ? {
          complete: setup.complete === true,
          steps: list(setup.steps).map((item) => {
            const row = optionalRecord(item);
            return { id: text(row.id), label: text(row.label), done: row.done === true, href: text(row.href) };
          }),
        }
      : null,
  };
}

function parseLogRecord(value: unknown): LogRecordView {
  const row = optionalRecord(value);
  const fields = row.fields && typeof row.fields === "object" && !Array.isArray(row.fields) ? (row.fields as Record<string, unknown>) : {};
  return { ts: text(row.ts), level: text(row.level), logger: text(row.logger), message: text(row.message), fields };
}

async function problemError(res: Response): Promise<ApiError> {
  const raw = await res.text();
  let detail = `Request failed (${res.status})`;
  try {
    const body = JSON.parse(raw) as { detail?: unknown; title?: unknown };
    if (typeof body.detail === "string" && body.detail) detail = body.detail;
    else if (typeof body.title === "string" && body.title) detail = body.title;
  } catch {
    // not problem+json
  }
  return new ApiError(detail, res.status);
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ApiError("Response was incomplete", 500);
  return value as Record<string, unknown>;
}

function optionalRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function num(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function numOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function parseOpcUaConnection(value: unknown): OpcUaConnectionStatus {
  const row = optionalRecord(value);
  return {
    name: text(row.name),
    enabled: row.enabled === true,
    description: text(row.description),
    endpointUrl: text(row.endpointUrl),
    state: text(row.state),
    connected: row.connected === true,
    serverState: typeof row.serverState === "string" ? row.serverState : null,
    sessionId: typeof row.sessionId === "string" ? row.sessionId : null,
    sessionName: typeof row.sessionName === "string" ? row.sessionName : null,
    uptimeMs: numOrNull(row.uptimeMs),
    subscriptionCount: num(row.subscriptionCount),
    monitoredItemCount: num(row.monitoredItemCount),
    samplingIntervalMs: numOrNull(row.samplingIntervalMs),
    publishIntervalMs: numOrNull(row.publishIntervalMs),
    requestCount: num(row.requestCount),
    errorCount: num(row.errorCount),
    timeoutCount: num(row.timeoutCount),
    averageResponseTimeMs: numOrNull(row.averageResponseTimeMs),
    lastError: typeof row.lastError === "string" ? row.lastError : null,
    subscriptions: list(row.subscriptions).map((item) => {
      const sub = optionalRecord(item);
      return {
        name: text(sub.name),
        samplingIntervalMs: num(sub.samplingIntervalMs),
        requestedPublishingIntervalMs: num(sub.requestedPublishingIntervalMs),
        revisedPublishingIntervalMs: num(sub.revisedPublishingIntervalMs),
        monitoredItemCount: num(sub.monitoredItemCount),
      };
    }),
  };
}

/** Display helpers shared by the status pages. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "n/a";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${unit === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
}

/** A load ratio (0..1) as a percentage. Negative means the JVM did not report one. */
export function formatRatio(ratio: number): string {
  if (!Number.isFinite(ratio) || ratio < 0) return "n/a";
  return `${(ratio * 100).toFixed(1)}%`;
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "n/a";
  const seconds = Math.floor(ms / 1000);
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m ${rest}s`;
  if (minutes > 0) return `${minutes}m ${rest}s`;
  return `${rest}s`;
}
