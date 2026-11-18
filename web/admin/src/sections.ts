/**
 * Configuration areas in the gateway admin shell.
 *
 * The public gateway web interface describes navigation across every configuration
 * area, a global search, and status banners. This list is that map in our own words.
 * (FEATURES GAT-016).
 *
 * `installAdminPages` registers a page for every id below. Do not fork this list's ids.
 *
 * The nav groups the areas the way the public interface does (GAT-034, GAT-036): Platform for the gateway itself,
 * Security for accounts, keys, zones, audit and certificates, Services for tags, history and other service
 * configuration, and Connections for databases and the network.
 * Doc source: FEATURES GAT-034.
 * Doc source: FEATURES GAT-036.
 */

export const ADMIN_BASE = "/admin";

export type AdminGroupId = "platform" | "security" | "services" | "connections";

export interface AdminGroup {
  id: AdminGroupId;
  label: string;
}

export const ADMIN_GROUPS: readonly AdminGroup[] = [
  { id: "platform", label: "Platform" },
  { id: "security", label: "Security" },
  { id: "services", label: "Services" },
  { id: "connections", label: "Connections" },
];

export interface AdminSection {
  id: string;
  label: string;
  group: AdminGroupId;
  /** Extra words the global search matches, in addition to the label and id. */
  keywords: readonly string[];
}

export const ADMIN_SECTIONS: readonly AdminSection[] = [
  { id: "home", label: "Home", group: "platform", keywords: ["launch", "first run", "setup", "designer", "hmi"] },
  { id: "overview", label: "Overview", group: "platform", keywords: ["status", "home", "health"] },
  { id: "gateway-settings", label: "Gateway Settings", group: "platform", keywords: ["system name", "launch", "message encoding", "default project"] },
  { id: "plc-resources", label: "PLC / ERIC resource split", group: "platform", keywords: ["cores", "memory", "isolcpus", "scan engine", "reboot"] },
  { id: "modules", label: "Modules", group: "platform", keywords: ["packages"] },
  { id: "backups", label: "Backups", group: "platform", keywords: ["restore", "archive"] },
  // Designers connected now, with the Designer launch link and shortcut download (GAT-028).
  { id: "designer-sessions", label: "Canvas sessions", group: "platform", keywords: ["designer", "launch", "download", "studio", "control canvas", "connected designers"] },
  { id: "projects", label: "Projects", group: "platform", keywords: ["import", "export"] },
  { id: "logs", label: "Logs", group: "platform", keywords: ["logging", "levels"] },
  { id: "diagnostics", label: "Diagnostics", group: "platform", keywords: ["performance", "threads", "memory", "architecture", "environment", "connection status"] },
  // User-built gauge and time-series dashboards over gateway metrics (GAT-024).
  { id: "metrics", label: "Metrics Dashboards", group: "platform", keywords: ["gauge", "chart", "cpu", "memory", "heap", "throughput", "sessions", "queue", "performance", "tiles"] },
  // Store-and-forward status, quarantine, and alarm-journal counters (HIS-025, HIS-026).
  { id: "store-and-forward", label: "Store and Forward", group: "platform", keywords: ["quarantine", "buffer", "throughput", "journal", "historian"] },
  { id: "users", label: "Users & Roles", group: "security", keywords: ["security", "roles", "accounts"] },
  { id: "api-keys", label: "API Keys", group: "security", keywords: ["tokens", "bearer"] },
  { id: "identity-providers", label: "Identity Providers", group: "security", keywords: ["oidc", "saml", "sso", "single sign-on", "login"] },
  { id: "user-sources", label: "User Sources", group: "security", keywords: ["ldap", "active directory", "directory", "database accounts", "hybrid", "login"] },
  { id: "zones", label: "Security Zones", group: "security", keywords: ["zone", "ip address", "host name"] },
  { id: "security-levels", label: "Security Levels", group: "security", keywords: ["levels", "rules", "hmi", "attributes", "identity provider"] },
  { id: "security-settings", label: "Security Settings", group: "security", keywords: ["password policy", "retention", "inactivity", "profile"] },
  { id: "secrets", label: "Secrets", group: "security", keywords: ["secret", "password", "reference", "encrypted"] },
  { id: "audit", label: "Audit", group: "security", keywords: ["log", "history"] },
  { id: "opcua", label: "OPC UA Certificates", group: "security", keywords: ["opc", "trust", "quarantine", "client certificates", "certificates"] },
  { id: "outbound-tls", label: "Outbound TLS Certificates", group: "security", keywords: ["tls", "trust", "certificates", "rejected", "database", "mqtt", "outbound"] },
  // OAuth2 client registrations used by outbound services such as email profiles (SEC-029).
  { id: "oauth2-clients", label: "OAuth2 Clients", group: "security", keywords: ["oauth", "oauth2", "token", "client credentials", "refresh token", "xoauth2", "smtp"] },
  { id: "tags", label: "Tags", group: "services", keywords: ["udt", "instances", "wizard"] },
  { id: "historian", label: "Historian", group: "services", keywords: ["tag history", "providers", "storage"] },
  { id: "alarming", label: "Alarming", group: "services", keywords: ["alarm", "notification", "roster", "pipeline", "schedule"] },
  { id: "event-streams", label: "Event Streams", group: "services", keywords: ["event", "stream", "kafka", "handler", "source", "pipeline"] },
  // SMTP email profiles shared by alarm notification, reports and scripts (GAT-030).
  { id: "email-profiles", label: "Email Profiles", group: "services", keywords: ["smtp", "mail", "email settings", "oauth", "oauth2", "notification"] },
  { id: "charts", label: "Charts", group: "services", keywords: ["sfc", "sequence", "step", "transition"] },
  { id: "reports", label: "Reports", group: "services", keywords: ["report", "canvas", "layout", "pdf"] },
  { id: "http-endpoints", label: "HTTP Endpoints", group: "services", keywords: ["webdev", "http", "get", "post", "python", "folder"] },
  { id: "deployment", label: "Deployment", group: "platform", keywords: ["docker", "helm", "container", "environment", "proxy", "install"] },
  { id: "config", label: "Config", group: "services", keywords: ["resources", "configuration", "services"] },
  // One list of device, database, service and OPC connections with live status (GAT-022).
  { id: "connections", label: "Connections", group: "connections", keywords: ["devices", "status", "database", "opc", "service", "faulted"] },
  { id: "databases", label: "Databases", group: "connections", keywords: ["database", "sql", "connections", "named queries", "query browser"] },
  { id: "network", label: "Network", group: "connections", keywords: ["https", "tls", "ports"] },
  // Enterprise administration: controller and agents over the peer network (EAM-001, EAM-014).
  {
    id: "enterprise-admin",
    label: "Enterprise Admin",
    group: "connections",
    keywords: ["eam", "agents", "controller", "fleet", "remote backup", "tasks", "inventory"],
  },
  {
    id: "opc-connections",
    label: "OPC UA Connections",
    group: "connections",
    keywords: ["opc", "ua", "session", "subscription", "monitored items", "server state"],
  },
  { id: "architectures", label: "Architectures", group: "connections", keywords: ["topology", "redundancy", "edge", "scale-out", "segmentation"] },
];

/** [ADMIN_SECTIONS] by group, in [ADMIN_GROUPS] order. Every section is in exactly one group. */
export function sectionsByGroup(sections: readonly AdminSection[] = ADMIN_SECTIONS): { group: AdminGroup; sections: AdminSection[] }[] {
  return ADMIN_GROUPS.map((group) => ({ group, sections: sections.filter((section) => section.group === group.id) })).filter(
    (entry) => entry.sections.length > 0,
  );
}

export type AdminRoute =
  | { kind: "login" }
  | { kind: "section"; section: AdminSection };

export function sectionPath(id: string): string {
  return `${ADMIN_BASE}/${id}`;
}

export function routeFromPath(pathname: string): AdminRoute {
  const trimmed = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
  if (trimmed === `${ADMIN_BASE}/login`) return { kind: "login" };
  // `/admin` and unknown paths open the Overview; the gateway root (`/`) redirects to the Home page (GAT-023).
  const overview = ADMIN_SECTIONS.find((item) => item.id === "overview")!;
  if (trimmed === ADMIN_BASE || trimmed === `${ADMIN_BASE}/overview`) {
    return { kind: "section", section: overview };
  }
  const rest = trimmed.startsWith(`${ADMIN_BASE}/`) ? trimmed.slice(ADMIN_BASE.length + 1) : "";
  const id = rest.split("/")[0] ?? "";
  const section = ADMIN_SECTIONS.find((item) => item.id === id);
  return { kind: "section", section: section ?? overview };
}

/** Query string P1-24 can read when search opens a config resource. Spec fields are not included. */
export function resourceHref(hit: ConfigHit): string {
  const params = new URLSearchParams({
    module: hit.module,
    scope: hit.scope,
    kind: hit.kind,
    name: hit.name,
  });
  return `${sectionPath("config")}?${params.toString()}`;
}

export interface ConfigHit {
  kind: string;
  name: string;
  module: string;
  scope: string;
}
