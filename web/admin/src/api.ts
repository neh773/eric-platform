import { parseStreamView, type EventStreamView } from "./eventStreamModel";
import { parseConfigHits } from "./search";
import type { JsonSchema } from "./schema";
import type { ConfigHit } from "./sections";
import type { UdtInstanceRequestBody } from "./udtWizard";

/**
 * Browser session client.
 *
 * The session cookie is HttpOnly (the gateway sets it). The CSRF token from login or
 * GET /api/v1/auth/session lives only in this object, never in localStorage or sessionStorage.
 * State-changing calls send X-CSRF-Token. See docs/ops/http-auth.md.
 */

const UNSAFE = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export class ApiError extends Error {
  readonly status: number;
  readonly errors: string[];

  constructor(message: string, status: number, errors: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.errors = errors;
  }
}

export interface SessionState {
  username: string;
  roles: string[];
  permissions: string[];
  csrfToken: string;
}

/** An enabled identity provider, as the sign-in page lists it (SEC-013). */
export interface LoginProvider {
  name: string;
  displayName: string;
  type: string;
  loginPath: string;
}

/** An identity provider resource as the registry API returns it (SEC-014). It never carries the client secret. */
export interface IdentityProviderRecord {
  name: string;
  type: "oidc" | "saml";
  displayName: string;
  enabled: boolean;
  clientSecretSet: boolean;
  callbackPath: string;
  version: number;
  fields: Record<string, unknown>;
}

/** The result of a user source check (SEC-027). */
export interface UserCheckResult {
  source: string;
  username: string;
  exists: boolean;
  enabled: boolean | null;
  roles: string[];
  passwordChecked: boolean;
  passwordValid: boolean | null;
  /** External sources (SEC-007, SEC-010): the directory groups or role column values read, before mapping. */
  groups?: string[];
  /** LDAP sources: the user's directory entry. */
  dn?: string | null;
  /** Why the source could not be asked (not reachable, TLS refused, service account refused). */
  unavailable?: string | null;
}

/** A user source resource (SEC-007, SEC-010, SEC-021, SEC-025). It never carries the bind password. */
export interface UserSourceRecord {
  name: string;
  type: "ldap" | "database" | "ldap-internal" | "ldap-database";
  enabled: boolean;
  bindPasswordSet: boolean;
  inUse: boolean;
  version: number;
  fields: Record<string, unknown>;
}

export interface HealthReport {
  status: string;
  profile?: string;
  version?: string;
  modules?: number;
  warnings: string[];
  /** ISO-8601 instant at which the gateway process started. */
  startedAt?: string;
}

export interface ModuleInfo {
  id: string;
  name: string;
  version: string;
  state: string;
  permissions: string[];
  error: string | null;
}

/** A built-in module the operator uninstalled; it stays uninstalled until it is installed again. */
export interface AvailableModule {
  id: string;
  name: string;
  version: string;
}

/** Active deployment mode (null for none) and the modes that have overlay resources. */
export interface DeploymentModeState {
  mode: string | null;
  modes: string[];
}

/** Public user fields. Password, passwordHash, and passwordHistory are never part of this type. */
export interface UserAccount {
  username: string;
  displayName: string;
  email: string;
  phone: string;
  enabled: boolean;
  roles: string[];
  passwordChangedAt: string;
  version: number;
}

export interface RoleAccount {
  name: string;
  description: string;
  permissions: string[];
  version: number;
}

/** Public key fields. The secret and keyHash are never part of this type. */
export interface ApiKeyAccount {
  name: string;
  description: string;
  enabled: boolean;
  keyId: string;
  scopes: string[];
  secureConnectionRequired: boolean;
  createdAt: string;
  version: number;
}

export interface IssuedApiKey {
  account: ApiKeyAccount;
  secret: string;
}

export interface AuditEntry {
  seq: number;
  timestamp: string;
  actor: string;
  action: string;
  target: string;
  detail: string;
  status: string;
}

export interface AuditPageResult {
  items: AuditEntry[];
  total: number;
  limit: number;
  offset: number;
}

export interface AuditQuery {
  actor?: string;
  action?: string;
  target?: string;
  from?: number | null;
  to?: number | null;
  limit: number;
  offset: number;
}

export interface BackupItem {
  name: string;
  bytes: number;
  /** False only for a plaintext .zip stored by an older version, which the gateway never serves. */
  encrypted: boolean;
}

/** Shortest passphrase the gateway accepts for a new backup (review-1 F3). */
export const MIN_BACKUP_PASSPHRASE = 12;

/** Public certificate fields. The private key is never part of this type. */
export interface HttpsCertificate {
  subject: string;
  issuer: string;
  notBefore: string;
  notAfter: string;
  sha256Fingerprint: string;
  sans: string[];
  source: string;
}

export interface HttpsStatus {
  enabled: boolean;
  listening: boolean;
  httpPort: number;
  httpsPort: number;
  httpsListenPort: number | null;
  publicAddress: string | null;
  publicHttpsPort: number | null;
  forceHttpsRedirect: boolean;
  protocols: string[];
  certificate: HttpsCertificate | null;
}

export interface GatewayPeer {
  name: string;
  host: string;
  port: number | null;
  direction: string;
  status: string;
  fingerprint: string;
  subject: string;
  certificateState: string;
  state: string;
  gatewayName: string;
  protocolVersion: number | null;
  pingMillis: number | null;
  lastCommMillis: number | null;
}

export interface GatewayTask {
  id: string;
  name: string;
  state: string;
  detail: string;
  direction: string;
  peer: string;
}

export interface ServicePolicyRow {
  peer: string;
  service: string;
  access: string;
  roles: string[];
}

export interface GatewayNetworkStatus {
  enabled: boolean;
  listening: boolean;
  port: number | null;
  /** Why the listener is not running, for example its port is taken. */
  listenError?: string | null;
  gatewayName: string;
  protocolVersion: number | null;
  fingerprint: string;
  incoming: GatewayPeer[];
  outgoing: GatewayPeer[];
  tasks: GatewayTask[];
  policies: ServicePolicyRow[];
}

export interface IdpDiagnosis {
  code: string;
  summary: string;
  hint: string;
  facts: Record<string, string>;
}

export interface IdpTestResult {
  testId: string;
  status: string;
  reason: string;
  diagnosis: IdpDiagnosis | null;
  username: string;
  displayName: string;
  email: string;
  roles: string[];
}

export interface ArchitectureCheck {
  id: string;
  label: string;
  met: boolean;
  detail: string;
}

export interface ArchitectureRow {
  id: string;
  feature: string;
  title: string;
  summary: string;
  fits: boolean;
  checks: ArchitectureCheck[];
}

export interface SubsystemRow {
  id: string;
  title: string;
  redundancy: string;
  database: string;
  detail: string;
}

export interface ArchitectureCatalog {
  node: {
    profile: string;
    bindHost: string;
    redundancyMode: string;
    historyMode: string;
  };
  architectures: ArchitectureRow[];
  subsystems: SubsystemRow[];
}

export interface HttpsPemUpload {
  format: "pem";
  certificatePem: string;
  privateKeyPem: string;
}

export interface HttpsPkcs12Upload {
  format: "pkcs12";
  pkcs12Base64: string;
  password?: string;
}

export interface UdtParameterInfo {
  name: string;
  dataType: string;
  /** Null when every instance must supply a value. */
  default: unknown;
}

export interface UdtTypeInfo {
  path: string;
  parameters: UdtParameterInfo[];
  /** Members after parent inheritance. Absent when an older gateway omitted it. */
  memberCount?: number;
  error?: string;
}

export interface UdtInstancePlanRow {
  index: number;
  requestedName: string | null;
  name: string | null;
  path: string | null;
  resource: string | null;
  status: "create" | "rename" | "skip" | "error";
  errors: string[];
  note?: string;
  parameters: Record<string, unknown>;
}

/** Response of POST /api/v1/tags/udt-instances. [ok] is false for a 422: nothing was created. */
/** Collision policy of a tag configuration import (TAG-005). */
export type TagImportPolicy = "overwrite" | "merge" | "ignore";

/** What a tag configuration import did. A non-empty `failed` means nothing was written. */
export interface TagImportReport {
  ok: boolean;
  created: string[];
  updated: string[];
  skipped: string[];
  failed: { resource: string; message: string }[];
}

function parseTagImport(raw: unknown, ok: boolean): TagImportReport {
  const body = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const failed = Array.isArray(body.failed)
    ? body.failed.map((f) => {
        const row = f && typeof f === "object" ? (f as Record<string, unknown>) : {};
        return { resource: String(row.resource ?? ""), message: String(row.message ?? "") };
      })
    : [];
  return { ok: ok && failed.length === 0, created: stringList(body.created), updated: stringList(body.updated), skipped: stringList(body.skipped), failed };
}

export interface UdtInstancePlan {
  ok: boolean;
  dryRun: boolean;
  applied: boolean;
  created: number;
  skipped: number;
  requestErrors: string[];
  rows: UdtInstancePlanRow[];
}

/** One OPC UA client application certificate. Public fields only. */
export interface OpcUaClientCertificate {
  thumbprint: string;
  subject: string;
  commonName: string | null;
  issuer: string;
  applicationUri: string | null;
  notBefore: string;
  notAfter: string;
  expired: boolean;
}

/** C-17: a database connection as the gateway lists it. The password is never part of it. */
export interface DbConnection {
  name: string;
  driver: string;
  url: string;
  username: string;
  description: string;
  enabled: boolean;
  defaultConnection: boolean;
  passwordSet: boolean;
  status: string;
  error: string | null;
  activeConnections: number;
  maxConnections: number;
  queriesPerSecond: number;
  validationQuery: string;
  allowBrowserWrites: boolean;
  /** What uses this connection (DAT-015): the alarm journal, history providers, reports, named queries. */
  usedBy: DbConnectionUse[];
}

/** The editable fields of a database connection. [password] undefined keeps the stored one. */
export interface DbConnectionFields {
  driver: string;
  url: string;
  username: string;
  description: string;
  enabled: boolean;
  defaultConnection: boolean;
  allowBrowserWrites: boolean;
  validationQuery?: string;
  password?: string;
}

/** An uploaded JDBC driver JAR (DAT-016). */
export interface JdbcDriverInfo {
  id: string;
  driverClass: string;
  size: number;
  sha256: string;
  uploadedAt: string;
  uploadedBy: string;
  usedBy: string[];
}

export interface DbConnectionUse {
  kind: string;
  name: string;
}

export interface NamedQueryParam {
  name: string;
  dataType: string;
  mode: "in" | "out" | "inout";
}

export interface NamedQueryInfo {
  name: string;
  description: string;
  enabled: boolean;
  connection: string;
  queryType: string;
  sql?: string;
  procedure?: string;
  parameters: NamedQueryParam[];
  roles: string[];
}

/** A named-query run or a query-browser statement. Rows carry columns; others carry a value or a count. */
export type DbRunResult =
  | { type: "query"; columns: string[]; rows: unknown[][]; truncated: boolean }
  | { type: "scalar"; value: unknown }
  | { type: "update"; rowsAffected: number }
  | { type: "procedure"; returnValue: unknown; outputs: Record<string, unknown>; columns: string[]; rows: unknown[][]; truncated: boolean };

export interface ReportCatalogProperty {
  name: string;
  label: string;
  kind: string;
  default?: unknown;
  options?: string[];
  help?: string;
}

export interface ReportCatalogComponent {
  type: string;
  label: string;
  description: string;
  needsDataset: boolean;
  defaults: Record<string, unknown>;
  properties: ReportCatalogProperty[];
}

/** `GET /api/v1/reports/components`: what a report layout may hold (FEATURES REP-013). */
export interface ReportCatalog {
  components: ReportCatalogComponent[];
  report: ReportCatalogProperty[];
}

export interface ReportStatusInfo {
  report: string;
  scheduled: boolean;
  cron: string | null;
  timezone: string | null;
  nextFireAt: number | null;
  lastRunAt: number | null;
  lastStatus: string | null;
  lastError: string | null;
  consecutiveFailures: number;
}

export interface ReportRunInfo {
  runId: string;
  trigger: string;
  user: string;
  startedAt: number;
  durationMillis: number;
  status: string;
  error: string | null;
  rows: number;
}

/** SEC-022: a server certificate of the gateway's outbound TLS trust store. Public fields only. */
export interface OutboundCertificate {
  /** SHA-256 of the DER encoding, lowercase hex. */
  thumbprint: string;
  subject: string;
  commonName: string | null;
  issuer: string;
  notBefore: string;
  notAfter: string;
  expired: boolean;
  /** For a rejected certificate: where it was presented (`host:port`), by which client, and when. */
  seenAt: string | null;
  client: string | null;
  lastSeen: string | null;
}

export interface OutboundTrustList {
  trusted: OutboundCertificate[];
  rejected: OutboundCertificate[];
}

/** SEC-016: facts for a trial run of the security level rules (no one is signed in). */
export interface LevelTrial {
  source?: string;
  username?: string;
  roles?: string[];
  attributes?: Record<string, string[]>;
  address?: string;
  at?: string;
  secure?: boolean;
}

export interface LevelTrialResult {
  levels: string[];
  rules: string[];
  zone: string;
}

export interface OpcUaTrustList {
  serverEnabled: boolean;
  serverRunning: boolean;
  endpointUrl: string | null;
  trusted: OpcUaClientCertificate[];
  quarantined: OpcUaClientCertificate[];
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export class ApiClient {
  private csrf: string | null = null;
  private readonly fetchImpl: FetchLike;
  private readonly onUnauthorized?: () => void;

  constructor(opts: { fetch?: FetchLike; onUnauthorized?: () => void } = {}) {
    this.fetchImpl = opts.fetch ?? ((input, init) => fetch(input, init));
    this.onUnauthorized = opts.onUnauthorized;
  }

  csrfToken(): string | null {
    return this.csrf;
  }

  async login(username: string, password: string): Promise<SessionState> {
    const res = await this.send("POST", "/api/v1/auth/login", { username, password }, "none");
    if (!res.ok) throw new ApiError(await problemDetail(res), res.status);
    const body = (await res.json()) as { username?: unknown; roles?: unknown; csrfToken?: unknown };
    const csrf = typeof body.csrfToken === "string" ? body.csrfToken : "";
    const name = typeof body.username === "string" ? body.username : "";
    if (!csrf || !name) throw new ApiError("Login response was incomplete", 500);
    this.csrf = csrf;
    return {
      username: name,
      roles: stringList(body.roles),
      permissions: [],
      csrfToken: csrf,
    };
  }

  /** Ends the session. Returns the provider's end-session URL for an identity-provider login that has one. */
  async logout(): Promise<string | null> {
    const res = await this.send("POST", "/api/v1/auth/logout", undefined, "session");
    this.csrf = null;
    if (res.status === 204) return null;
    if (res.ok) {
      const body = (await res.json().catch(() => ({}))) as { endSessionUrl?: unknown };
      return typeof body.endSessionUrl === "string" ? body.endSessionUrl : null;
    }
    throw new ApiError(await problemDetail(res), res.status);
  }

  /** Enabled identity providers for the sign-in page. Needs no session. */
  /** First-run status (0.1.1): true when the gateway has no users yet and this browser may create the first one. */
  async setupStatus(): Promise<{ needsAdmin: boolean; fromThisMachine: boolean }> {
    const res = await this.send("GET", "/api/v1/setup", undefined, "none");
    if (!res.ok) return { needsAdmin: false, fromThisMachine: false };
    const body = (await res.json()) as { needsAdmin?: unknown; fromThisMachine?: unknown };
    return { needsAdmin: body.needsAdmin === true, fromThisMachine: body.fromThisMachine === true };
  }

  /** Creates the first administrator and signs in as it. Refused once any user exists. */
  async createFirstAdmin(username: string, password: string): Promise<SessionState> {
    const res = await this.send("POST", "/api/v1/setup/admin", { username, password }, "none");
    if (!res.ok) throw new ApiError(await problemDetail(res), res.status);
    const body = (await res.json()) as { username?: unknown; roles?: unknown; csrfToken?: unknown };
    const csrf = typeof body.csrfToken === "string" ? body.csrfToken : "";
    const name = typeof body.username === "string" ? body.username : "";
    if (!csrf || !name) throw new ApiError("Setup response was incomplete", 500);
    this.csrf = csrf;
    return { username: name, roles: stringList(body.roles), permissions: [], csrfToken: csrf };
  }

  async loginProviders(): Promise<LoginProvider[]> {
    const res = await this.send("GET", "/api/v1/auth/providers", undefined, "none");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map((raw) => {
      const item = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
      const name = typeof item.name === "string" ? item.name : "";
      return {
        name,
        displayName: typeof item.displayName === "string" && item.displayName ? item.displayName : name,
        type: typeof item.type === "string" ? item.type : "",
        loginPath: typeof item.loginPath === "string" ? item.loginPath : "",
      };
    });
  }

  async listIdentityProviders(): Promise<IdentityProviderRecord[]> {
    const res = await this.send("GET", IDP_BASE, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseIdentityProvider);
  }

  /** Create (version null) or replace a provider. `clientSecret` undefined keeps the stored secret. */
  async saveIdentityProvider(
    name: string,
    version: number | null,
    fields: Record<string, unknown>,
    clientSecret?: string,
  ): Promise<IdentityProviderRecord> {
    const body: Record<string, unknown> = { ...fields };
    if (clientSecret !== undefined) body.clientSecret = clientSecret;
    const res =
      version === null
        ? await this.send("POST", IDP_BASE, { name, ...body }, "session")
        : await this.send("PUT", `${IDP_BASE}/${encodeURIComponent(name)}`, body, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseIdentityProvider(await res.json());
  }

  async deleteIdentityProvider(name: string, version: number): Promise<void> {
    const res = await this.send("DELETE", `${IDP_BASE}/${encodeURIComponent(name)}`, undefined, "session", { "If-Match": String(version) });
    if (res.status === 204) return;
    if (!res.ok) throw await problemError(res);
  }

  /** What a test login has shown so far, including the setup check when the login failed (SEC-032). */
  async identityProviderTest(name: string, testId: string): Promise<IdpTestResult> {
    const res = await this.send("GET", `${IDP_BASE}/${encodeURIComponent(name)}/test/${encodeURIComponent(testId)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseIdpTest(await res.json());
  }

  /** Starts a test login against a provider (SEC-018). The returned path is opened in a browser window; no session results. */
  async startIdentityProviderTest(name: string): Promise<{ testId: string; loginPath: string }> {
    const res = await this.send("POST", `${IDP_BASE}/${encodeURIComponent(name)}/test`, {}, "session");
    if (!res.ok) throw await problemError(res);
    const body = (await res.json()) as { testId?: unknown; loginPath?: unknown };
    if (typeof body.testId !== "string" || typeof body.loginPath !== "string") throw new Error("Unexpected test response");
    return { testId: body.testId, loginPath: body.loginPath };
  }

  /** Discovery (OIDC issuer) or metadata (SAML XML) import. Returns the fields it fills; nothing is saved. */
  async importIdentityProvider(input: { type: "oidc"; issuer: string } | { type: "saml"; metadataXml: string }): Promise<Record<string, unknown>> {
    const res = await this.send("POST", `${IDP_BASE}/import`, input, "session");
    if (!res.ok) throw await problemError(res);
    const body = (await res.json()) as { fields?: unknown };
    return body.fields && typeof body.fields === "object" ? (body.fields as Record<string, unknown>) : {};
  }

  /** User sources and the one gateway logins use (GatewaySecurity.systemUserSource). */
  async listUserSources(): Promise<{ items: UserSourceRecord[]; systemUserSource: string }> {
    const res = await this.send("GET", USER_SOURCE_BASE, undefined, "session");
    if (!res.ok) throw await problemError(res);
    const body = (await res.json()) as Record<string, unknown>;
    return {
      items: objectList(body, "items").map(parseUserSource),
      systemUserSource: typeof body.systemUserSource === "string" ? body.systemUserSource : "internal",
    };
  }

  /** Create (version null) or replace a user source. `bindPassword` undefined keeps the stored one; "" removes it. */
  async saveUserSource(name: string, version: number | null, fields: Record<string, unknown>, bindPassword?: string): Promise<UserSourceRecord> {
    const body: Record<string, unknown> = { ...fields };
    if (bindPassword !== undefined) body.bindPassword = bindPassword;
    const res =
      version === null
        ? await this.send("POST", USER_SOURCE_BASE, { name, ...body }, "session")
        : await this.send("PUT", `${USER_SOURCE_BASE}/${encodeURIComponent(name)}`, body, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseUserSource(await res.json());
  }

  async deleteUserSource(name: string, version: number): Promise<void> {
    const res = await this.send("DELETE", `${USER_SOURCE_BASE}/${encodeURIComponent(name)}`, undefined, "session", { "If-Match": String(version) });
    if (res.status === 204) return;
    if (!res.ok) throw await problemError(res);
  }

  /** Verify a user on a user source. The password, when given, is sent once and never kept. */
  async checkUser(source: string, username: string, password?: string): Promise<UserCheckResult> {
    const body: Record<string, unknown> = { username };
    if (password !== undefined) body.password = password;
    const res = await this.send("POST", `/api/v1/security/user-sources/${encodeURIComponent(source)}/check`, body, "session");
    if (!res.ok) throw await problemError(res);
    const r = (await res.json()) as Record<string, unknown>;
    return {
      source: String(r.source ?? source),
      username: String(r.username ?? username),
      exists: r.exists === true,
      enabled: typeof r.enabled === "boolean" ? r.enabled : null,
      roles: stringList(r.roles),
      passwordChecked: r.passwordChecked === true,
      passwordValid: typeof r.passwordValid === "boolean" ? r.passwordValid : null,
      ...(Array.isArray(r.groups) ? { groups: stringList(r.groups) } : {}),
      ...(typeof r.dn === "string" ? { dn: r.dn } : {}),
      ...(typeof r.unavailable === "string" ? { unavailable: r.unavailable } : {}),
    };
  }

  async session(): Promise<SessionState> {
    const res = await this.send("GET", "/api/v1/auth/session", undefined, "session");
    if (!res.ok) throw new ApiError(await problemDetail(res), res.status);
    const body = (await res.json()) as {
      principal?: unknown;
      roles?: unknown;
      permissions?: unknown;
      csrfToken?: unknown;
    };
    const csrf = typeof body.csrfToken === "string" ? body.csrfToken : "";
    const name = typeof body.principal === "string" ? body.principal : "";
    if (!csrf || !name) throw new ApiError("Session response was incomplete", 500);
    this.csrf = csrf;
    return {
      username: name,
      roles: stringList(body.roles),
      permissions: stringList(body.permissions),
      csrfToken: csrf,
    };
  }

  async health(): Promise<HealthReport> {
    const res = await this.send("GET", "/api/v1/health", undefined, "none");
    if (!res.ok) throw new ApiError(await problemDetail(res), res.status);
    return parseHealth(await res.json());
  }

  async configResources(): Promise<ConfigHit[]> {
    const res = await this.send("GET", "/api/v1/config", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseConfigHits(await res.json());
  }

  async getSchema(moduleName: string, kind: string): Promise<JsonSchema> {
    const res = await this.send("GET", schemaPath(moduleName, kind), undefined, "session");
    if (!res.ok) throw await problemError(res);
    const body = (await res.json()) as { schema?: unknown };
    if (!body.schema || typeof body.schema !== "object" || Array.isArray(body.schema)) {
      throw new ApiError("Schema response was incomplete", 500);
    }
    return body.schema as JsonSchema;
  }

  async getResource(moduleName: string, scope: string, kind: string, name: string): Promise<ResourceDocument> {
    const res = await this.send("GET", configItemPath(moduleName, scope, kind, name), undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseResource(await res.json());
  }

  /**
   * Replace a resource. `If-Match` is the loaded `metadata.version`. The body version is the same
   * value: the config store rejects a stale version with 409.
   */
  async putResource(moduleName: string, scope: string, kind: string, name: string, resource: ResourceDocument): Promise<ResourceDocument> {
    const version = resource.metadata.version;
    const res = await this.send(
      "PUT",
      configItemPath(moduleName, scope, kind, name),
      {
        apiVersion: resource.apiVersion,
        kind: resource.kind,
        metadata: resource.metadata,
        spec: resource.spec,
      },
      "session",
      { "If-Match": String(version) },
    );
    if (!res.ok) throw await problemError(res);
    return parseResource(await res.json());
  }

  /** Resources of one kind in one scope, as stored (security resources come back without hashes). */
  async listResources(moduleName: string, scope: string, kind: string): Promise<ResourceDocument[]> {
    const scopePath = scope.split("/").map(encodeURIComponent).join("/");
    const res = await this.send("GET", `/api/v1/config/${encodeURIComponent(moduleName)}/${scopePath}/${encodeURIComponent(kind)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseResource);
  }

  /** Create a resource. 409 when it already exists. */
  async createResource(moduleName: string, scope: string, kind: string, name: string, spec: Record<string, unknown>): Promise<ResourceDocument> {
    const scopePath = scope.split("/").map(encodeURIComponent).join("/");
    const res = await this.send(
      "POST",
      `/api/v1/config/${encodeURIComponent(moduleName)}/${scopePath}/${encodeURIComponent(kind)}`,
      { apiVersion: "oip/v1", kind, metadata: { name, module: moduleName, version: 0 }, spec },
      "session",
    );
    if (!res.ok) throw await problemError(res);
    return parseResource(await res.json());
  }

  /** Live counters for every event stream, including a stream the compiler rejected. */
  async eventStreamStatus(): Promise<EventStreamView[]> {
    const res = await this.send("GET", "/api/v1/event-streams", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseStreamView).filter((item) => item.name !== "");
  }

  /** Inject one event into a saved stream so its handlers can be tried from the screen. */
  async sendTestEvent(name: string, body: unknown): Promise<void> {
    const res = await this.send("POST", `/api/v1/event-streams/${encodeURIComponent(name)}/test`, body, "session");
    if (!res.ok) throw await problemError(res);
  }

  /** Delete a resource at the loaded version. 409 when someone changed it since. */
  async deleteResource(moduleName: string, scope: string, kind: string, name: string, version: number): Promise<void> {
    const res = await this.send("DELETE", configItemPath(moduleName, scope, kind, name), undefined, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
  }

  async gatewaySettings(): Promise<GatewaySettingsState> {
    const res = await this.send("GET", GATEWAY_SETTINGS_PATH, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewaySettings(await res.json());
  }

  async saveGatewaySettings(input: GatewaySettingsInput): Promise<GatewaySettingsState> {
    const res = await this.send("PUT", GATEWAY_SETTINGS_PATH, input, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewaySettings(await res.json());
  }

  async platform(): Promise<{ os: string; windowsPlc: boolean; banner: string }> {
    const res = await this.send("GET", "/api/v1/platform", undefined, "session");
    if (!res.ok) throw new Error(`platform ${res.status}`);
    return (await res.json()) as { os: string; windowsPlc: boolean; banner: string };
  }

  async plcResources(): Promise<import("./plcResourcesPage").PlcResourceStatus> {
    const res = await this.send("GET", "/api/v1/plc/resources", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as import("./plcResourcesPage").PlcResourceStatus;
  }

  async plcResourcesPreview(body: { plcCores: number; plcRamGb: number; disableSmt: boolean }): Promise<import("./plcResourcesPage").PlcPreview> {
    const res = await this.send("POST", "/api/v1/plc/resources/preview", body, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as import("./plcResourcesPage").PlcPreview;
  }

  async plcResourcesApply(body: { plcCores: number; plcRamGb: number; disableSmt: boolean }): Promise<import("./plcResourcesPage").PlcPreview> {
    const res = await this.send("POST", "/api/v1/plc/resources/apply", body, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as import("./plcResourcesPage").PlcPreview;
  }

  async plcResourcesRevert(): Promise<import("./plcResourcesPage").PlcPreview> {
    const res = await this.send("POST", "/api/v1/plc/resources/revert", {}, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as import("./plcResourcesPage").PlcPreview;
  }

  async listModules(): Promise<ModuleInfo[]> {
    const res = await this.send("GET", "/api/v1/modules", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseModule);
  }

  /** Built-in modules that were uninstalled and can be installed again. */
  async listAvailableModules(): Promise<AvailableModule[]> {
    const res = await this.send("GET", "/api/v1/modules", undefined, "session");
    if (!res.ok) throw await problemError(res);
    const body = record(await res.json());
    return (Array.isArray(body.available) ? body.available : []).map((value: unknown) => {
      const item = record(value);
      const id = requiredText(item, "id");
      return {
        id,
        name: typeof item.name === "string" ? item.name : id,
        version: typeof item.version === "string" ? item.version : "",
      };
    });
  }

  async installBuiltInModule(id: string): Promise<ModuleInfo> {
    const res = await this.send("POST", `/api/v1/modules/${encodeURIComponent(id)}/install`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseModule(await res.json());
  }

  async deploymentMode(): Promise<DeploymentModeState> {
    const res = await this.send("GET", "/api/v1/deployment-mode", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseDeploymentMode(await res.json());
  }

  async setDeploymentMode(mode: string | null): Promise<DeploymentModeState> {
    const res = await this.send("PUT", "/api/v1/deployment-mode", { mode }, "session");
    if (!res.ok) throw await problemError(res);
    return parseDeploymentMode(await res.json());
  }

  async installModule(bytes: ArrayBuffer): Promise<ModuleInfo> {
    const res = await this.sendBytes("POST", "/api/v1/modules", bytes);
    if (!res.ok) throw await problemError(res);
    return parseModule(await res.json());
  }

  async upgradeModule(id: string, bytes: ArrayBuffer): Promise<ModuleInfo> {
    const res = await this.sendBytes("PUT", `/api/v1/modules/${encodeURIComponent(id)}`, bytes);
    if (!res.ok) throw await problemError(res);
    return parseModule(await res.json());
  }

  async uninstallModule(id: string): Promise<void> {
    const res = await this.send("DELETE", `/api/v1/modules/${encodeURIComponent(id)}`, undefined, "session");
    if (res.status === 204) return;
    if (!res.ok) throw await problemError(res);
  }

  async listUsers(): Promise<UserAccount[]> {
    const res = await this.send("GET", "/api/v1/security/users", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseUser);
  }

  async createUser(input: { username: string; password: string; displayName: string; roles: string[] }): Promise<UserAccount> {
    const res = await this.send("POST", "/api/v1/security/users", input, "session");
    if (!res.ok) throw await problemError(res);
    return parseUser(await res.json());
  }

  async updateUser(
    username: string,
    version: number,
    input: { displayName: string; email: string; phone: string; enabled: boolean; roles: string[] },
  ): Promise<UserAccount> {
    const res = await this.send("PUT", userPath(username), input, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseUser(await res.json());
  }

  async setPassword(username: string, version: number, password: string): Promise<UserAccount> {
    const res = await this.send("POST", `${userPath(username)}/password`, { password }, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseUser(await res.json());
  }

  async deleteUser(username: string, version: number): Promise<void> {
    const res = await this.send("DELETE", userPath(username), undefined, "session", { "If-Match": String(version) });
    if (res.status === 204) return;
    if (!res.ok) throw await problemError(res);
  }

  async listRoles(): Promise<RoleAccount[]> {
    const res = await this.send("GET", "/api/v1/security/roles", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseRole);
  }

  async createRole(input: { name: string; description: string; permissions: string[] }): Promise<RoleAccount> {
    const res = await this.send("POST", "/api/v1/security/roles", input, "session");
    if (!res.ok) throw await problemError(res);
    return parseRole(await res.json());
  }

  async updateRole(name: string, version: number, input: { description: string; permissions: string[] }): Promise<RoleAccount> {
    const res = await this.send("PUT", rolePath(name), input, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseRole(await res.json());
  }

  async deleteRole(name: string, version: number): Promise<void> {
    const res = await this.send("DELETE", rolePath(name), undefined, "session", { "If-Match": String(version) });
    if (res.status === 204) return;
    if (!res.ok) throw await problemError(res);
  }

  async listApiKeys(): Promise<ApiKeyAccount[]> {
    const res = await this.send("GET", "/api/v1/security/api-keys", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseApiKey);
  }

  async createApiKey(input: { name: string; description: string; scopes: string[]; secureConnectionRequired: boolean }): Promise<IssuedApiKey> {
    const res = await this.send("POST", "/api/v1/security/api-keys", input, "session");
    if (!res.ok) throw await problemError(res);
    return parseIssuedKey(await res.json());
  }

  async rotateApiKey(name: string, version: number): Promise<IssuedApiKey> {
    const res = await this.send("POST", `${apiKeyPath(name)}/rotate`, undefined, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseIssuedKey(await res.json());
  }

  async revokeApiKey(name: string, version: number): Promise<ApiKeyAccount> {
    const res = await this.send("DELETE", apiKeyPath(name), undefined, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return parseApiKey(await res.json());
  }

  async queryAudit(query: AuditQuery): Promise<AuditPageResult> {
    const params = new URLSearchParams();
    if (query.actor) params.set("actor", query.actor);
    if (query.action) params.set("action", query.action);
    if (query.target) params.set("target", query.target);
    if (query.from != null) params.set("from", String(query.from));
    if (query.to != null) params.set("to", String(query.to));
    params.set("limit", String(query.limit));
    params.set("offset", String(query.offset));
    const res = await this.send("GET", `/api/v1/audit?${params.toString()}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseAuditPage(await res.json());
  }

  async listBackups(): Promise<BackupItem[]> {
    const res = await this.send("GET", "/api/v1/backups", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "backups").map(parseBackup);
  }

  /** The gateway seals the archive under [passphrase] and does not keep the passphrase. */
  async createBackup(passphrase: string): Promise<BackupItem> {
    const res = await this.send("POST", "/api/v1/backups", { passphrase }, "session");
    if (!res.ok) throw await problemError(res);
    const name = res.headers.get("X-Backup-Name");
    if (!name) throw new ApiError("Backup response was incomplete", 500);
    const bytes = await res.arrayBuffer();
    return { name, bytes: bytes.byteLength, encrypted: true };
  }

  async downloadBackup(name: string): Promise<ArrayBuffer> {
    const res = await this.send("GET", backupPath(name), undefined, "session");
    if (!res.ok) throw await problemError(res);
    return res.arrayBuffer();
  }

  /** An encrypted backup needs its passphrase; a wrong one is refused and changes nothing. */
  async restoreBackup(name: string, passphrase?: string): Promise<void> {
    const body = passphrase === undefined ? undefined : { passphrase };
    const res = await this.send("POST", `${backupPath(name)}/restore`, body, "session");
    if (!res.ok) throw await problemError(res);
  }

  /**
   * Restore an archive the user picked from disk (a backup downloaded from this or another gateway), for example onto a
   * fresh install. Sent as base64 JSON so the passphrase travels in the same request body.
   */
  async restoreBackupFile(archive: ArrayBuffer, passphrase?: string): Promise<void> {
    const bytes = new Uint8Array(archive);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    const body: Record<string, string> = { archive: btoa(binary) };
    if (passphrase) body.passphrase = passphrase;
    const res = await this.send("POST", "/api/v1/backups/restore", body, "session");
    if (!res.ok) throw await problemError(res);
  }

  async secrets(): Promise<Array<{ name: string; description: string; reference: string }>> {
    const res = await this.send("GET", "/api/v1/secrets", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items") as Array<{ name: string; description: string; reference: string }>;
  }

  async saveSecret(name: string, value: string, description: string): Promise<{ name: string; description: string; reference: string }> {
    const res = await this.send("PUT", `/api/v1/secrets/${encodeURIComponent(name)}`, { value, description }, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as { name: string; description: string; reference: string };
  }

  async deleteSecret(name: string): Promise<void> {
    const res = await this.send("DELETE", `/api/v1/secrets/${encodeURIComponent(name)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
  }

  async redundancy(): Promise<Record<string, unknown>> {
    const res = await this.send("GET", "/api/v1/redundancy", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as Record<string, unknown>;
  }

  async saveRedundancy(body: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.send("PUT", "/api/v1/redundancy", body, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as Record<string, unknown>;
  }

  async redundancyAction(action: "failover" | "assume" | "resync"): Promise<Record<string, unknown>> {
    const res = await this.send("POST", `/api/v1/redundancy/${action}`, {}, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as Record<string, unknown>;
  }

  async httpsStatus(): Promise<HttpsStatus> {
    const res = await this.send("GET", "/api/v1/https", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseHttpsStatus(await res.json());
  }

  async replaceHttpsCertificate(body: HttpsPemUpload | HttpsPkcs12Upload): Promise<HttpsStatus> {
    const res = await this.send("PUT", "/api/v1/https/certificate", body, "session");
    if (!res.ok) throw await problemError(res);
    return parseHttpsStatus(await res.json());
  }

  async saveServicePolicy(input: { peer: string; service: string; access: string; roles: string[] }): Promise<GatewayNetworkStatus> {
    const res = await this.send("PUT", "/api/v1/gateway-network/policies", input, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewayNetwork(await res.json());
  }

  async deleteServicePolicy(peer: string, service: string): Promise<GatewayNetworkStatus> {
    const query = `peer=${encodeURIComponent(peer)}&service=${encodeURIComponent(service)}`;
    const res = await this.send("DELETE", `/api/v1/gateway-network/policies?${query}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewayNetwork(await res.json());
  }

  async gatewayNetwork(): Promise<GatewayNetworkStatus> {
    const res = await this.send("GET", "/api/v1/gateway-network", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewayNetwork(await res.json());
  }

  async architectures(): Promise<ArchitectureCatalog> {
    const res = await this.send("GET", "/api/v1/architectures", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseArchitectureCatalog(await res.json());
  }

  async approveIncomingGateway(fingerprint: string): Promise<GatewayNetworkStatus> {
    const res = await this.send("POST", `/api/v1/gateway-network/incoming/${fingerprint}/approve`, {}, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewayNetwork(await res.json());
  }

  async denyIncomingGateway(fingerprint: string): Promise<GatewayNetworkStatus> {
    const res = await this.send("POST", `/api/v1/gateway-network/incoming/${fingerprint}/deny`, {}, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewayNetwork(await res.json());
  }

  async regenerateGatewayCertificate(): Promise<GatewayNetworkStatus> {
    const res = await this.send("POST", "/api/v1/gateway-network/certificate/regenerate", {}, "session");
    if (!res.ok) throw await problemError(res);
    return parseGatewayNetwork(await res.json());
  }

  async listUdtTypes(provider = "default"): Promise<UdtTypeInfo[]> {
    const res = await this.send("GET", `/api/v1/tags/udts?provider=${encodeURIComponent(provider)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "types").map(parseUdtType);
  }

  /**
   * Plan (dryRun) or create UDT instances. A 422 that carries the per-row plan resolves with ok=false so the
   * wizard can show every row; other failures (403, malformed request) throw.
   */
  async createUdtInstances(body: UdtInstanceRequestBody): Promise<UdtInstancePlan> {
    const res = await this.send("POST", "/api/v1/tags/udt-instances", body, "session");
    if (res.ok) return parseUdtPlan(await res.json(), true);
    if (res.status === 422) {
      const text = await res.text();
      let parsed: unknown = null;
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = null;
      }
      if (parsed && typeof parsed === "object" && Array.isArray((parsed as Record<string, unknown>).rows)) {
        return parseUdtPlan(parsed, false);
      }
      throw await problemError(new Response(text, { status: res.status }));
    }
    throw await problemError(res);
  }

  /** The tag configuration export document of a provider, as text (TAG-005). Needs gateway.read. */
  async exportTags(provider = "default"): Promise<string> {
    const res = await this.send("GET", `/api/v1/tags/export?provider=${encodeURIComponent(provider)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return res.text();
  }

  /**
   * Import an export document (sent as is). A 422 that lists per-resource problems resolves with ok=false, since
   * nothing was written; other failures throw. Needs gateway.write.
   */
  async importTags(document: string, policy: TagImportPolicy): Promise<TagImportReport> {
    const res = await this.dispatch("POST", `/api/v1/tags/import?policy=${policy}`, document, "session", {
      "Content-Type": "application/json",
    });
    if (res.ok) return parseTagImport(await res.json(), true);
    if (res.status === 422) {
      const text = await res.text();
      let parsed: unknown = null;
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = null;
      }
      if (parsed && typeof parsed === "object" && Array.isArray((parsed as Record<string, unknown>).failed)) {
        return parseTagImport(parsed, false);
      }
      throw await problemError(new Response(text, { status: res.status }));
    }
    throw await problemError(res);
  }

  async opcUaTrust(): Promise<OpcUaTrustList> {
    const res = await this.send("GET", "/api/v1/opcua/certificates", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseOpcUaTrust(await res.json());
  }

  async trustOpcUaCertificate(thumbprint: string): Promise<OpcUaClientCertificate> {
    const res = await this.send("POST", `${opcUaCertPath("quarantined", thumbprint)}/trust`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseOpcUaCertificate(await res.json());
  }

  async importOpcUaCertificate(certificate: string): Promise<OpcUaClientCertificate> {
    const res = await this.send("POST", "/api/v1/opcua/certificates/trusted", { certificate }, "session");
    if (!res.ok) throw await problemError(res);
    return parseOpcUaCertificate(await res.json());
  }

  async removeOpcUaCertificate(list: "trusted" | "quarantined", thumbprint: string): Promise<void> {
    const res = await this.send("DELETE", opcUaCertPath(list, thumbprint), undefined, "session");
    if (!res.ok) throw await problemError(res);
  }

  async outboundTrust(): Promise<OutboundTrustList> {
    const res = await this.send("GET", "/api/v1/tls/outbound", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseOutboundTrust(await res.json());
  }

  async trustOutboundCertificate(thumbprint: string): Promise<OutboundCertificate> {
    const res = await this.send("POST", `${outboundCertPath("rejected", thumbprint)}/trust`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseOutboundCertificate(await res.json());
  }

  async importOutboundCertificate(certificate: string): Promise<OutboundCertificate> {
    const res = await this.send("POST", "/api/v1/tls/outbound/trusted", { certificate }, "session");
    if (!res.ok) throw await problemError(res);
    return parseOutboundCertificate(await res.json());
  }

  async removeOutboundCertificate(list: "trusted" | "rejected", thumbprint: string): Promise<void> {
    const res = await this.send("DELETE", outboundCertPath(list, thumbprint), undefined, "session");
    if (!res.ok) throw await problemError(res);
  }

  /** Run the security level rules over [trial] without signing anyone in (needs users.admin). */
  async evaluateSecurityLevels(trial: LevelTrial): Promise<LevelTrialResult> {
    const res = await this.send("POST", "/api/v1/security/levels/evaluate", trial, "session");
    if (!res.ok) throw await problemError(res);
    const body = record(await res.json());
    const texts = (key: string) => (Array.isArray(body[key]) ? (body[key] as unknown[]).filter((v): v is string => typeof v === "string") : []);
    return { levels: texts("levels"), rules: texts("rules"), zone: typeof body.zone === "string" ? body.zone : "" };
  }

  /**
   * A session-authenticated JSON request for feature clients that keep their own parsing
   * (C-16 tags/tagClient.ts). Sends X-CSRF-Token on unsafe methods like every other call.
   */
  async request(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Response> {
    return this.send(method, path, body, "session", headers);
  }

  /** POST a file body without JSON-encoding it. Used for quarantine import and disk-cache load. */
  async postRaw(path: string, body: string, contentType: string): Promise<Response> {
    return this.dispatch("POST", path, body, "session", { "Content-Type": contentType });
  }

  async dbConnections(): Promise<DbConnection[]> {
    const res = await this.send("GET", "/api/v1/db/connections", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseDbConnection);
  }

  /**
   * Creates (create = true) or updates a database connection. The password is sent only when the form has one:
   * leaving it out keeps the stored password, an empty string clears it. The gateway never returns it.
   */
  async saveDbConnection(name: string, fields: DbConnectionFields, create: boolean): Promise<DbConnection> {
    const body: Record<string, unknown> = { ...fields };
    if (body.password === undefined) delete body.password;
    const res = create
      ? await this.send("POST", "/api/v1/db/connections", { name, ...body }, "session")
      : await this.send("PUT", dbConnectionPath(name), { name, ...body }, "session");
    if (!res.ok) throw await problemError(res);
    return parseDbConnection(await res.json());
  }

  async deleteDbConnection(name: string): Promise<void> {
    const res = await this.send("DELETE", dbConnectionPath(name), undefined, "session");
    if (!res.ok) throw await problemError(res);
  }

  /** Runs the connection's validation query now and returns the new status. */
  async testDbConnection(name: string): Promise<DbConnection> {
    const res = await this.send("POST", `${dbConnectionPath(name)}/refresh`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return parseDbConnection(await res.json());
  }

  async jdbcDrivers(): Promise<JdbcDriverInfo[]> {
    const res = await this.send("GET", "/api/v1/db/drivers", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseJdbcDriver);
  }

  /** Uploads a driver JAR as the request body. Needs modules.admin; the gateway checks the JAR. */
  async uploadJdbcDriver(id: string, bytes: ArrayBuffer, driverClass?: string): Promise<JdbcDriverInfo> {
    const query = new URLSearchParams({ id });
    if (driverClass) query.set("driverClass", driverClass);
    const res = await this.dispatch("POST", `/api/v1/db/drivers?${query.toString()}`, bytes, "session", { "Content-Type": "application/java-archive" });
    if (!res.ok) throw await problemError(res);
    return parseJdbcDriver(await res.json());
  }

  async removeJdbcDriver(id: string): Promise<void> {
    const res = await this.send("DELETE", `/api/v1/db/drivers/${encodeURIComponent(id)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
  }

  async namedQueries(): Promise<NamedQueryInfo[]> {
    const res = await this.send("GET", "/api/v1/db/queries", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items").map(parseNamedQuery);
  }

  /** One named query with every setting the editor shows, and its stored version. */
  async namedQueryDetail(name: string): Promise<Record<string, unknown>> {
    const res = await this.send("GET", `/api/v1/db/queries/${encodeURIComponent(name)}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return record(await res.json());
  }

  /** Creates (POST) or replaces (PUT, `If-Match` the loaded version) a named query. Needs gateway.write. */
  async saveNamedQuery(name: string, fields: Record<string, unknown>, version: number | null): Promise<Record<string, unknown>> {
    const res =
      version === null
        ? await this.send("POST", "/api/v1/db/queries", { name, ...fields }, "session")
        : await this.send("PUT", `/api/v1/db/queries/${encodeURIComponent(name)}`, { name, ...fields }, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
    return record(await res.json());
  }

  async deleteNamedQuery(name: string, version: number): Promise<void> {
    const res = await this.send("DELETE", `/api/v1/db/queries/${encodeURIComponent(name)}`, undefined, "session", { "If-Match": String(version) });
    if (!res.ok) throw await problemError(res);
  }

  async runNamedQuery(name: string, parameters: Record<string, unknown>): Promise<DbRunResult> {
    const res = await this.send("POST", `/api/v1/db/queries/${encodeURIComponent(name)}/execute`, { parameters }, "session");
    if (!res.ok) throw await problemError(res);
    return parseDbRunResult(await res.json());
  }

  async reportCatalog(): Promise<ReportCatalog> {
    const res = await this.send("GET", "/api/v1/reports/components", undefined, "session");
    if (!res.ok) throw await problemError(res);
    const body = (await res.json()) as Partial<ReportCatalog>;
    return { components: body.components ?? [], report: body.report ?? [] };
  }

  /** Runs a draft report spec on the gateway without saving it. Needs gateway.write. */
  async previewReport(spec: Record<string, unknown>, parameters: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.send("POST", "/api/v1/reports/preview", { spec, parameters }, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as Record<string, unknown>;
  }

  /** The draft rendered by the gateway's PDF renderer. */
  async previewReportPdf(spec: Record<string, unknown>, parameters: Record<string, unknown>): Promise<Blob> {
    const res = await this.send("POST", "/api/v1/reports/preview/pdf", { spec, parameters }, "session");
    if (!res.ok) throw await problemError(res);
    return await res.blob();
  }

  async reportStatus(name: string): Promise<ReportStatusInfo> {
    const res = await this.send("GET", `/api/v1/reports/${encodeURIComponent(name)}/status`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as ReportStatusInfo;
  }

  async reportRuns(name: string, limit = 10): Promise<ReportRunInfo[]> {
    const res = await this.send("GET", `/api/v1/reports/${encodeURIComponent(name)}/runs?limit=${limit}`, undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items") as unknown as ReportRunInfo[];
  }

  async runReport(name: string, parameters: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.send("POST", `/api/v1/reports/${encodeURIComponent(name)}/run`, { parameters }, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as Record<string, unknown>;
  }

  async sfcCharts(): Promise<Array<{ name: string; executionMode: string; elements: string[]; valid: boolean }>> {
    const res = await this.send("GET", "/api/v1/sfc/charts", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items") as Array<{ name: string; executionMode: string; elements: string[]; valid: boolean }>;
  }

  async sfcInstances(): Promise<Array<{ id: string; chart: string; state: string; activeSteps: string[] }>> {
    const res = await this.send("GET", "/api/v1/sfc/instances", undefined, "session");
    if (!res.ok) throw await problemError(res);
    return objectList(await res.json(), "items") as Array<{ id: string; chart: string; state: string; activeSteps: string[] }>;
  }

  async sfcStart(name: string): Promise<{ id: string }> {
    const res = await this.send("POST", `/api/v1/sfc/charts/${encodeURIComponent(name)}/start`, {}, "session");
    if (!res.ok) throw await problemError(res);
    return (await res.json()) as { id: string };
  }

  async sfcControl(id: string, action: "pause" | "resume" | "cancel"): Promise<void> {
    const res = await this.send("POST", `/api/v1/sfc/instances/${encodeURIComponent(id)}/${action}`, {}, "session");
    if (!res.ok) throw await problemError(res);
  }

  async browseDatabase(connection: string, sql: string, maxRows: number): Promise<DbRunResult> {
    const res = await this.send("POST", `${dbConnectionPath(connection)}/browse`, { sql, maxRows }, "session");
    if (!res.ok) throw await problemError(res);
    return parseDbRunResult(await res.json());
  }

  private async send(
    method: string,
    path: string,
    body: unknown,
    auth: "none" | "session",
    extra?: Record<string, string>,
  ): Promise<Response> {
    const headers: Record<string, string> = { ...(extra ?? {}) };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const payload = body === undefined ? undefined : JSON.stringify(body);
    return this.dispatch(method, path, payload, auth, headers);
  }

  private async sendBytes(method: string, path: string, bytes: ArrayBuffer): Promise<Response> {
    return this.dispatch(method, path, bytes, "session", { "Content-Type": "application/zip" });
  }

  private async dispatch(
    method: string,
    path: string,
    body: BodyInit | undefined,
    auth: "none" | "session",
    headers: Record<string, string>,
  ): Promise<Response> {
    const req = new Headers(headers);
    if (auth === "session" && UNSAFE.has(method)) {
      if (!this.csrf) throw new ApiError("Missing CSRF token", 403);
      req.set("X-CSRF-Token", this.csrf);
    }
    const res = await this.fetchImpl(path, {
      method,
      headers: req,
      body,
      credentials: "same-origin",
    });
    if (auth === "session" && res.status === 401) {
      this.csrf = null;
      this.onUnauthorized?.();
    }
    return res;
  }
}

const USER_SOURCE_BASE = "/api/v1/security/user-sources";
const USER_SOURCE_META = new Set(["name", "bindPasswordSet", "inUse", "version"]);
const USER_SOURCE_TYPES = new Set(["ldap", "database", "ldap-internal", "ldap-database"]);

export function parseUserSource(body: unknown): UserSourceRecord {
  const r = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  const fields: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(r)) if (!USER_SOURCE_META.has(key)) fields[key] = value;
  const type = typeof r.type === "string" && USER_SOURCE_TYPES.has(r.type) ? (r.type as UserSourceRecord["type"]) : "ldap";
  return {
    name: String(r.name ?? ""),
    type,
    enabled: r.enabled !== false,
    bindPasswordSet: r.bindPasswordSet === true,
    inUse: r.inUse === true,
    version: typeof r.version === "number" ? r.version : 0,
    fields,
  };
}

const IDP_BASE = "/api/v1/security/identity-providers";
const IDP_META = new Set(["name", "clientSecretSet", "callbackPath", "version"]);

export function parseIdentityProvider(body: unknown): IdentityProviderRecord {
  const r = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  const fields: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(r)) if (!IDP_META.has(key)) fields[key] = value;
  return {
    name: String(r.name ?? ""),
    type: r.type === "saml" ? "saml" : "oidc",
    displayName: typeof r.displayName === "string" ? r.displayName : "",
    enabled: r.enabled !== false,
    clientSecretSet: r.clientSecretSet === true,
    callbackPath: typeof r.callbackPath === "string" ? r.callbackPath : "",
    version: typeof r.version === "number" ? r.version : 0,
    fields,
  };
}

export function parseHealth(body: unknown): HealthReport {
  const record = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  const warnings = Array.isArray(record.warnings) ? record.warnings.filter((item): item is string => typeof item === "string") : [];
  return {
    status: typeof record.status === "string" ? record.status : "UNKNOWN",
    profile: typeof record.profile === "string" ? record.profile : undefined,
    version: typeof record.version === "string" ? record.version : undefined,
    modules: typeof record.modules === "number" ? record.modules : undefined,
    warnings,
    startedAt:
      typeof record.startedAt === "string" && !Number.isNaN(Date.parse(record.startedAt)) ? record.startedAt : undefined,
  };
}

export const GATEWAY_SETTINGS_PATH = "/api/v1/gateway/settings";

/** Gateway-wide settings (GAT-035) as `GET /api/v1/gateway/settings` returns them. */
export interface GatewaySettingsState {
  systemName: string;
  effectiveSystemName: string;
  scriptMessageEncoding: string;
  scriptMessageEncodings: string[];
  launch: { defaultProject: string };
  version: number;
}

export interface GatewaySettingsInput {
  systemName: string;
  scriptMessageEncoding: string;
  launch: { defaultProject: string };
}

export function parseGatewaySettings(body: unknown): GatewaySettingsState {
  const record = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  const launch = record.launch && typeof record.launch === "object" ? (record.launch as Record<string, unknown>) : {};
  const encodings = Array.isArray(record.scriptMessageEncodings)
    ? record.scriptMessageEncodings.filter((item): item is string => typeof item === "string")
    : [];
  return {
    systemName: typeof record.systemName === "string" ? record.systemName : "",
    effectiveSystemName: typeof record.effectiveSystemName === "string" ? record.effectiveSystemName : "",
    scriptMessageEncoding: typeof record.scriptMessageEncoding === "string" ? record.scriptMessageEncoding : "json",
    scriptMessageEncodings: encodings.length > 0 ? encodings : ["json"],
    launch: { defaultProject: typeof launch.defaultProject === "string" ? launch.defaultProject : "" },
    version: typeof record.version === "number" ? record.version : 0,
  };
}

export interface ResourceDocument {
  apiVersion: string;
  kind: string;
  metadata: {
    name: string;
    module: string;
    version: number;
    labels?: Record<string, string>;
    modified?: string;
    modifiedBy?: string;
  };
  spec: Record<string, unknown>;
}

export function configItemPath(moduleName: string, scope: string, kind: string, name: string): string {
  const scopePath = scope.split("/").map(encodeURIComponent).join("/");
  return `/api/v1/config/${encodeURIComponent(moduleName)}/${scopePath}/${encodeURIComponent(kind)}/${encodeURIComponent(name)}`;
}

export function schemaPath(moduleName: string, kind: string): string {
  return `/api/v1/schemas/${encodeURIComponent(moduleName)}/${encodeURIComponent(kind)}`;
}

export function parseResource(body: unknown): ResourceDocument {
  if (!body || typeof body !== "object") throw new ApiError("Resource response was incomplete", 500);
  const record = body as Record<string, unknown>;
  const metadata = record.metadata;
  if (!metadata || typeof metadata !== "object") throw new ApiError("Resource response was incomplete", 500);
  const meta = metadata as Record<string, unknown>;
  const spec = record.spec;
  if (!spec || typeof spec !== "object" || Array.isArray(spec)) throw new ApiError("Resource response was incomplete", 500);
  const version = typeof meta.version === "number" ? meta.version : 0;
  const name = typeof meta.name === "string" ? meta.name : "";
  const moduleName = typeof meta.module === "string" ? meta.module : "";
  const kind = typeof record.kind === "string" ? record.kind : "";
  const apiVersion = typeof record.apiVersion === "string" ? record.apiVersion : "";
  if (!name || !moduleName || !kind || !apiVersion) throw new ApiError("Resource response was incomplete", 500);
  return {
    apiVersion,
    kind,
    metadata: {
      name,
      module: moduleName,
      version,
      labels: meta.labels && typeof meta.labels === "object" && !Array.isArray(meta.labels) ? (meta.labels as Record<string, string>) : undefined,
      modified: typeof meta.modified === "string" ? meta.modified : undefined,
      modifiedBy: typeof meta.modifiedBy === "string" ? meta.modifiedBy : undefined,
    },
    spec: spec as Record<string, unknown>,
  };
}

function dbConnectionPath(name: string): string {
  return `/api/v1/db/connections/${encodeURIComponent(name)}`;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function count(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

/** Copies only the fields the page shows, so nothing else the gateway returns reaches the DOM. */
export function parseJdbcDriver(body: unknown): JdbcDriverInfo {
  if (!body || typeof body !== "object") throw new ApiError("Driver response was incomplete", 500);
  const r = body as Record<string, unknown>;
  if (typeof r.id !== "string") throw new ApiError("Driver response was incomplete", 500);
  return {
    id: r.id,
    driverClass: text(r.driverClass),
    size: count(r.size),
    sha256: text(r.sha256),
    uploadedAt: text(r.uploadedAt),
    uploadedBy: text(r.uploadedBy),
    usedBy: Array.isArray(r.usedBy) ? r.usedBy.filter((item): item is string => typeof item === "string") : [],
  };
}

export function parseDbConnection(body: unknown): DbConnection {
  if (!body || typeof body !== "object") throw new ApiError("Connection response was incomplete", 500);
  const r = body as Record<string, unknown>;
  if (typeof r.name !== "string") throw new ApiError("Connection response was incomplete", 500);
  return {
    name: r.name,
    driver: text(r.driver),
    url: text(r.url),
    username: text(r.username),
    description: text(r.description),
    enabled: r.enabled === true,
    defaultConnection: r.defaultConnection === true,
    passwordSet: r.passwordSet === true,
    status: text(r.status) || "Unknown",
    error: typeof r.error === "string" ? r.error : null,
    activeConnections: count(r.activeConnections),
    maxConnections: count(r.maxConnections),
    queriesPerSecond: count(r.queriesPerSecond),
    validationQuery: text(r.validationQuery),
    allowBrowserWrites: r.allowBrowserWrites === true,
    usedBy: Array.isArray(r.usedBy)
      ? r.usedBy.flatMap((item) => {
          if (!item || typeof item !== "object") return [];
          const use = item as Record<string, unknown>;
          return typeof use.kind === "string" && typeof use.name === "string" ? [{ kind: use.kind, name: use.name }] : [];
        })
      : [],
  };
}

export function parseNamedQuery(body: unknown): NamedQueryInfo {
  if (!body || typeof body !== "object") throw new ApiError("Query response was incomplete", 500);
  const r = body as Record<string, unknown>;
  if (typeof r.name !== "string") throw new ApiError("Query response was incomplete", 500);
  const params = Array.isArray(r.parameters) ? r.parameters : [];
  return {
    name: r.name,
    description: text(r.description),
    enabled: r.enabled !== false,
    connection: text(r.connection),
    queryType: text(r.queryType),
    sql: typeof r.sql === "string" ? r.sql : undefined,
    procedure: typeof r.procedure === "string" ? r.procedure : undefined,
    parameters: params
      .filter((p): p is Record<string, unknown> => !!p && typeof p === "object")
      .map((p) => ({
        name: text(p.name),
        dataType: text(p.dataType) || "string",
        mode: p.mode === "out" || p.mode === "inout" ? p.mode : "in",
      })),
    roles: Array.isArray(r.roles) ? r.roles.filter((x): x is string => typeof x === "string") : [],
  };
}

export function parseDbRunResult(body: unknown): DbRunResult {
  if (!body || typeof body !== "object") throw new ApiError("Result was incomplete", 500);
  const r = body as Record<string, unknown>;
  const columns = Array.isArray(r.columns) ? r.columns.map((c) => String(c)) : [];
  const rows = Array.isArray(r.rows) ? r.rows.filter((row): row is unknown[] => Array.isArray(row)) : [];
  switch (r.type) {
    case "query":
      return { type: "query", columns, rows, truncated: r.truncated === true };
    case "scalar":
      return { type: "scalar", value: r.value ?? null };
    case "update":
      return { type: "update", rowsAffected: count(r.rowsAffected) };
    case "procedure": {
      const outputs = r.outputs && typeof r.outputs === "object" && !Array.isArray(r.outputs) ? (r.outputs as Record<string, unknown>) : {};
      return { type: "procedure", returnValue: r.returnValue ?? null, outputs, columns, rows, truncated: r.truncated === true };
    }
    default:
      throw new ApiError("Result was incomplete", 500);
  }
}

function userPath(name: string): string {
  return `/api/v1/security/users/${encodeURIComponent(name)}`;
}

function rolePath(name: string): string {
  return `/api/v1/security/roles/${encodeURIComponent(name)}`;
}

function apiKeyPath(name: string): string {
  return `/api/v1/security/api-keys/${encodeURIComponent(name)}`;
}

function backupPath(name: string): string {
  return `/api/v1/backups/${encodeURIComponent(name)}`;
}

function objectList(body: unknown, key: string): unknown[] {
  if (!body || typeof body !== "object") throw new ApiError("Response was incomplete", 500);
  const value = (body as Record<string, unknown>)[key];
  if (!Array.isArray(value)) throw new ApiError("Response was incomplete", 500);
  return value;
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ApiError("Response was incomplete", 500);
  return value as Record<string, unknown>;
}

function requiredText(body: Record<string, unknown>, key: string): string {
  const value = body[key];
  if (typeof value !== "string" || value.length === 0) throw new ApiError("Response was incomplete", 500);
  return value;
}

function parseDeploymentMode(value: unknown): DeploymentModeState {
  const body = record(value);
  return { mode: typeof body.mode === "string" ? body.mode : null, modes: stringList(body.modes) };
}

function parseModule(value: unknown): ModuleInfo {
  const body = record(value);
  return {
    id: requiredText(body, "id"),
    name: typeof body.name === "string" ? body.name : requiredText(body, "id"),
    version: typeof body.version === "string" ? body.version : "",
    state: typeof body.state === "string" ? body.state : "",
    permissions: stringList(body.permissions),
    error: typeof body.error === "string" ? body.error : null,
  };
}

function parseUser(value: unknown): UserAccount {
  const body = record(value);
  return {
    username: requiredText(body, "username"),
    displayName: typeof body.displayName === "string" ? body.displayName : "",
    email: typeof body.email === "string" ? body.email : "",
    phone: typeof body.phone === "string" ? body.phone : "",
    enabled: body.enabled !== false,
    roles: stringList(body.roles),
    passwordChangedAt: typeof body.passwordChangedAt === "string" ? body.passwordChangedAt : "",
    version: typeof body.version === "number" ? body.version : 0,
  };
}

function parseRole(value: unknown): RoleAccount {
  const body = record(value);
  return {
    name: requiredText(body, "name"),
    description: typeof body.description === "string" ? body.description : "",
    permissions: stringList(body.permissions),
    version: typeof body.version === "number" ? body.version : 0,
  };
}

function parseApiKey(value: unknown): ApiKeyAccount {
  const body = record(value);
  return {
    name: requiredText(body, "name"),
    description: typeof body.description === "string" ? body.description : "",
    enabled: body.enabled !== false,
    keyId: typeof body.keyId === "string" ? body.keyId : "",
    scopes: stringList(body.scopes),
    secureConnectionRequired: body.secureConnectionRequired !== false,
    createdAt: typeof body.createdAt === "string" ? body.createdAt : "",
    version: typeof body.version === "number" ? body.version : 0,
  };
}

function parseIssuedKey(value: unknown): IssuedApiKey {
  const body = record(value);
  const secret = requiredText(body, "secret");
  return { account: parseApiKey(body), secret };
}

function parseAuditPage(value: unknown): AuditPageResult {
  const body = record(value);
  const items = objectList(body, "items").map(parseAuditEntry);
  return {
    items,
    total: typeof body.total === "number" ? body.total : items.length,
    limit: typeof body.limit === "number" ? body.limit : items.length,
    offset: typeof body.offset === "number" ? body.offset : 0,
  };
}

function parseAuditEntry(value: unknown): AuditEntry {
  const body = record(value);
  return {
    seq: typeof body.seq === "number" ? body.seq : 0,
    timestamp: typeof body.timestamp === "string" ? body.timestamp : "",
    actor: typeof body.actor === "string" ? body.actor : "",
    action: typeof body.action === "string" ? body.action : "",
    target: typeof body.target === "string" ? body.target : "",
    detail: typeof body.detail === "string" ? body.detail : "",
    status: typeof body.status === "string" ? body.status : "",
  };
}

function parseHttpsStatus(value: unknown): HttpsStatus {
  const body = record(value);
  const certificate = body.certificate;
  return {
    enabled: body.enabled === true,
    listening: body.listening === true,
    httpPort: typeof body.httpPort === "number" ? body.httpPort : 0,
    httpsPort: typeof body.httpsPort === "number" ? body.httpsPort : 0,
    httpsListenPort: typeof body.httpsListenPort === "number" ? body.httpsListenPort : null,
    publicAddress: typeof body.publicAddress === "string" ? body.publicAddress : null,
    publicHttpsPort: typeof body.publicHttpsPort === "number" ? body.publicHttpsPort : null,
    forceHttpsRedirect: body.forceHttpsRedirect === true,
    protocols: stringList(body.protocols),
    certificate: certificate && typeof certificate === "object" ? parseHttpsCertificate(certificate) : null,
  };
}

function parseArchitectureCatalog(value: unknown): ArchitectureCatalog {
  const body = record(value);
  const node = record(body.node);
  const text = (source: Record<string, unknown>, key: string) => (typeof source[key] === "string" ? source[key] : "");
  return {
    node: {
      profile: text(node, "profile"),
      bindHost: text(node, "bindHost"),
      redundancyMode: text(node, "redundancyMode"),
      historyMode: text(node, "historyMode"),
    },
    architectures: objectList(body, "architectures").map((item) => {
      const row = record(item);
      return {
        id: requiredText(row, "id"),
        feature: requiredText(row, "feature"),
        title: requiredText(row, "title"),
        summary: typeof row.summary === "string" ? row.summary : "",
        fits: row.fits === true,
        checks: Array.isArray(row.checks)
          ? row.checks.map((check) => {
              const entry = record(check);
              return {
                id: requiredText(entry, "id"),
                label: typeof entry.label === "string" ? entry.label : "",
                met: entry.met === true,
                detail: typeof entry.detail === "string" ? entry.detail : "",
              };
            })
          : [],
      };
    }),
    subsystems: objectList(body, "subsystems").map((item) => {
      const row = record(item);
      return {
        id: requiredText(row, "id"),
        title: requiredText(row, "title"),
        redundancy: requiredText(row, "redundancy"),
        database: requiredText(row, "database"),
        detail: typeof row.detail === "string" ? row.detail : "",
      };
    }),
  };
}

function parseGatewayNetwork(value: unknown): GatewayNetworkStatus {
  const body = record(value);
  const certificate = body.certificate && typeof body.certificate === "object" ? record(body.certificate) : {};
  return {
    enabled: body.enabled === true,
    listening: body.listening === true,
    port: typeof body.port === "number" ? body.port : null,
    listenError: typeof body.listenError === "string" ? body.listenError : null,
    gatewayName: typeof body.gatewayName === "string" ? body.gatewayName : "",
    protocolVersion: typeof body.protocolVersion === "number" ? body.protocolVersion : null,
    fingerprint: typeof certificate.sha256Fingerprint === "string" ? certificate.sha256Fingerprint : "",
    incoming: Array.isArray(body.incoming) ? body.incoming.map(parseGatewayPeer) : [],
    outgoing: Array.isArray(body.outgoing) ? body.outgoing.map(parseGatewayPeer) : [],
    tasks: Array.isArray(body.tasks) ? body.tasks.map(parseGatewayTask) : [],
    policies: Array.isArray(body.policies) ? body.policies.map(parseServicePolicy) : [],
  };
}

function parseServicePolicy(value: unknown): ServicePolicyRow {
  const body = record(value);
  return {
    peer: typeof body.peer === "string" ? body.peer : "",
    service: typeof body.service === "string" ? body.service : "",
    access: typeof body.access === "string" ? body.access : "",
    roles: Array.isArray(body.roles) ? body.roles.filter((item): item is string => typeof item === "string") : [],
  };
}

function parseIdpTest(value: unknown): IdpTestResult {
  const body = record(value);
  const raw = body.diagnosis;
  const diagnosis = raw && typeof raw === "object" && !Array.isArray(raw) ? record(raw) : null;
  const facts: Record<string, string> = {};
  if (diagnosis && diagnosis.facts && typeof diagnosis.facts === "object" && !Array.isArray(diagnosis.facts)) {
    for (const [key, item] of Object.entries(diagnosis.facts as Record<string, unknown>)) {
      if (item != null) facts[key] = String(item);
    }
  }
  return {
    testId: typeof body.testId === "string" ? body.testId : "",
    status: typeof body.status === "string" ? body.status : "",
    reason: typeof body.reason === "string" ? body.reason : "",
    diagnosis: diagnosis && typeof diagnosis.code === "string"
      ? {
          code: diagnosis.code,
          summary: typeof diagnosis.summary === "string" ? diagnosis.summary : "",
          hint: typeof diagnosis.hint === "string" ? diagnosis.hint : "",
          facts,
        }
      : null,
    username: typeof body.username === "string" ? body.username : "",
    displayName: typeof body.displayName === "string" ? body.displayName : "",
    email: typeof body.email === "string" ? body.email : "",
    roles: Array.isArray(body.roles) ? body.roles.filter((item): item is string => typeof item === "string") : [],
  };
}

function parseGatewayTask(value: unknown): GatewayTask {
  const body = record(value);
  return {
    id: typeof body.id === "string" ? body.id : "",
    name: typeof body.name === "string" ? body.name : "",
    state: typeof body.state === "string" ? body.state : "",
    detail: typeof body.detail === "string" ? body.detail : "",
    direction: typeof body.direction === "string" ? body.direction : "",
    peer: typeof body.peer === "string" ? body.peer : "",
  };
}

function parseGatewayPeer(value: unknown): GatewayPeer {
  const body = record(value);
  return {
    name: typeof body.name === "string" ? body.name : "",
    host: typeof body.host === "string" ? body.host : "",
    port: typeof body.port === "number" ? body.port : null,
    direction: typeof body.direction === "string" ? body.direction : "",
    status: typeof body.status === "string" ? body.status : "",
    fingerprint: typeof body.fingerprint === "string" ? body.fingerprint : "",
    subject: typeof body.subject === "string" ? body.subject : "",
    certificateState: typeof body.certificateState === "string" ? body.certificateState : "",
    state: typeof body.state === "string" ? body.state : "",
    gatewayName: typeof body.gatewayName === "string" ? body.gatewayName : "",
    protocolVersion: typeof body.protocolVersion === "number" ? body.protocolVersion : null,
    pingMillis: typeof body.pingMillis === "number" ? body.pingMillis : null,
    lastCommMillis: typeof body.lastCommMillis === "number" ? body.lastCommMillis : null,
  };
}

function parseHttpsCertificate(value: unknown): HttpsCertificate {
  const body = record(value);
  return {
    subject: typeof body.subject === "string" ? body.subject : "",
    issuer: typeof body.issuer === "string" ? body.issuer : "",
    notBefore: typeof body.notBefore === "string" ? body.notBefore : "",
    notAfter: typeof body.notAfter === "string" ? body.notAfter : "",
    sha256Fingerprint: typeof body.sha256Fingerprint === "string" ? body.sha256Fingerprint : "",
    sans: stringList(body.sans),
    source: typeof body.source === "string" ? body.source : "",
  };
}

function outboundCertPath(list: "trusted" | "rejected", thumbprint: string): string {
  if (!/^[0-9a-f]{64}$/.test(thumbprint)) throw new ApiError("Invalid certificate thumbprint", 400);
  return `/api/v1/tls/outbound/${list}/${thumbprint}`;
}

export function parseOutboundTrust(value: unknown): OutboundTrustList {
  const body = record(value);
  const list = (key: string) => (Array.isArray(body[key]) ? (body[key] as unknown[]).map(parseOutboundCertificate) : []);
  return { trusted: list("trusted"), rejected: list("rejected") };
}

function parseOutboundCertificate(value: unknown): OutboundCertificate {
  const body = record(value);
  const text = (key: string) => (typeof body[key] === "string" ? (body[key] as string) : null);
  return {
    thumbprint: requiredText(body, "thumbprint"),
    subject: text("subject") ?? "",
    commonName: text("commonName"),
    issuer: text("issuer") ?? "",
    notBefore: text("notBefore") ?? "",
    notAfter: text("notAfter") ?? "",
    expired: body.expired === true,
    seenAt: text("seenAt"),
    client: text("client"),
    lastSeen: text("lastSeen"),
  };
}

function opcUaCertPath(list: "trusted" | "quarantined", thumbprint: string): string {
  if (!/^[0-9a-fA-F]{40}$/.test(thumbprint)) throw new ApiError("Invalid certificate thumbprint", 400);
  return `/api/v1/opcua/certificates/${list}/${thumbprint}`;
}

export function parseOpcUaTrust(value: unknown): OpcUaTrustList {
  const body = record(value);
  const list = (key: string) => (Array.isArray(body[key]) ? (body[key] as unknown[]).map(parseOpcUaCertificate) : []);
  return {
    serverEnabled: body.serverEnabled === true,
    serverRunning: body.serverRunning === true,
    endpointUrl: typeof body.endpointUrl === "string" ? body.endpointUrl : null,
    trusted: list("trusted"),
    quarantined: list("quarantined"),
  };
}

function parseOpcUaCertificate(value: unknown): OpcUaClientCertificate {
  const body = record(value);
  return {
    thumbprint: requiredText(body, "thumbprint"),
    subject: typeof body.subject === "string" ? body.subject : "",
    commonName: typeof body.commonName === "string" ? body.commonName : null,
    issuer: typeof body.issuer === "string" ? body.issuer : "",
    applicationUri: typeof body.applicationUri === "string" ? body.applicationUri : null,
    notBefore: typeof body.notBefore === "string" ? body.notBefore : "",
    notAfter: typeof body.notAfter === "string" ? body.notAfter : "",
    expired: body.expired === true,
  };
}

function parseBackup(value: unknown): BackupItem {
  const body = record(value);
  return {
    name: requiredText(body, "name"),
    bytes: typeof body.bytes === "number" ? body.bytes : 0,
    encrypted: body.encrypted === true,
  };
}

function parseUdtType(value: unknown): UdtTypeInfo {
  const body = record(value);
  const parameters = Array.isArray(body.parameters) ? body.parameters : [];
  const info: UdtTypeInfo = {
    path: requiredText(body, "path"),
    parameters: parameters.map((item) => {
      const p = record(item);
      return {
        name: requiredText(p, "name"),
        dataType: typeof p.dataType === "string" ? p.dataType : "String",
        default: p.default ?? null,
      };
    }),
  };
  if (typeof body.memberCount === "number" && Number.isFinite(body.memberCount)) info.memberCount = body.memberCount;
  if (typeof body.error === "string") info.error = body.error;
  return info;
}

function parseUdtPlan(value: unknown, ok: boolean): UdtInstancePlan {
  const body = record(value);
  const rows = Array.isArray(body.rows) ? body.rows : [];
  const nullableText = (v: unknown) => (typeof v === "string" ? v : null);
  return {
    ok,
    dryRun: body.dryRun === true,
    applied: body.applied === true,
    created: typeof body.created === "number" ? body.created : 0,
    skipped: typeof body.skipped === "number" ? body.skipped : 0,
    requestErrors: stringList(body.requestErrors),
    rows: rows.map((item) => {
      const row = record(item);
      const status = row.status === "create" || row.status === "rename" || row.status === "skip" ? row.status : "error";
      const parsed: UdtInstancePlanRow = {
        index: typeof row.index === "number" ? row.index : 0,
        requestedName: nullableText(row.requestedName),
        name: nullableText(row.name),
        path: nullableText(row.path),
        resource: nullableText(row.resource),
        status,
        errors: stringList(row.errors),
        parameters: row.parameters && typeof row.parameters === "object" ? (row.parameters as Record<string, unknown>) : {},
      };
      if (typeof row.note === "string") parsed.note = row.note;
      return parsed;
    }),
  };
}

async function problemDetail(res: Response): Promise<string> {
  return (await problemError(res)).message;
}

export async function problemError(res: Response): Promise<ApiError> {
  const text = await res.text();
  let detail = `Request failed (${res.status})`;
  let errors: string[] = [];
  try {
    const body = JSON.parse(text) as { detail?: unknown; title?: unknown; errors?: unknown };
    if (typeof body.detail === "string" && body.detail) detail = body.detail;
    else if (typeof body.title === "string" && body.title) detail = body.title;
    if (Array.isArray(body.errors)) errors = body.errors.filter((item): item is string => typeof item === "string");
  } catch {
    // not problem+json
  }
  return new ApiError(detail, res.status, errors);
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === "string");
}
