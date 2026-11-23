import type { ComponentType } from "react";
import type { ApiClient, SessionState } from "./api";
import { ApiKeysPage } from "./apiKeysPage";
import { AuditPage } from "./auditPage";
import { BackupsPage } from "./backupsPage";
import { ConfigPage } from "./configPage";
import { DatabasesPage } from "./databasesPage";
import { ModulesPage } from "./modulesPage";
import { NetworkPage } from "./networkPage";
import { EamPage } from "./eamPage";
import { OpcUaPage } from "./opcUaPage";
import { OutboundTlsPage } from "./outboundTlsPage";
import { OpcConnectionsPage } from "./opcConnectionsPage";
import { ConnectionsPage } from "./connectionsPage";
import { DesignerSessionsPage } from "./designerSessionsPage";
import { EmailProfilesPage } from "./emailProfilesPage";
import { OAuth2ClientsPage } from "./oauth2ClientsPage";
import type { AdminSection } from "./sections";
import { TagsSection } from "./tags/TagsSection";
import { UdtTypesPage } from "./udt/udtTypesPage";
import { UsersPage } from "./usersPage";
import { DiagnosticsPage } from "./diagnosticsPage";
import { MetricsPage } from "./metricsPage";
import { LogsPage } from "./logsPage";
import { OverviewPage } from "./overviewPage";
import { ProjectsPage } from "./projectsPage";
import { HomePage } from "./homePage";
import { GatewaySettingsPage } from "./gatewaySettingsPage";
import { PlcResourcesPage } from "./plcResourcesPage";
import { SecuritySettingsPage } from "./securitySettingsPage";
import { ZonesPage } from "./zonesPage";
import { SecurityLevelsPage } from "./securityLevelsPage";
import { IdentityProvidersPage } from "./identityProvidersPage";
import { UserSourcesPage } from "./userSourcesPage";
import { AlarmEditorPage } from "./alarmEditor";
import { HistoryPage } from "./historyPage";
import { StoreAndForwardPage } from "./storeForwardPage";
import { ChartsPage } from "./chartsPage";
import { ReportsPage } from "./reportsPage";
import { ArchitecturesPage } from "./architecturesPage";
import { EventStreamsPage } from "./eventStreamsPage";
import { SecretsPage } from "./secretsPage";
import { HttpEndpointsPage } from "./httpEndpointsPage";
import { DeploymentPage } from "./deploymentPage";

/**
 * Page slots for admin areas.
 *
 * `installAdminPages` registers the schema-driven config editor (P1-24), the
 * modules, users, API keys, audit, and backups pages (P1-25), and the network
 * HTTPS page (P1-28), the OPC UA client-certificate trust page (C-7), and the
 * tags section: the tag browser and editor (C-16) and the UDT instance wizard (TAG-024).
 * The shell calls `installAdminPages` before the first render.
 */
export interface AdminPageProps {
  section: AdminSection;
  client?: ApiClient;
  session?: SessionState;
  search?: string;
  onVisit?: (to: string) => void;
}

const pages = new Map<string, ComponentType<AdminPageProps>>();

export function registerAdminPage(id: string, page: ComponentType<AdminPageProps>): void {
  pages.set(id, page);
}

export function resetAdminPages(): void {
  pages.clear();
}

export function installAdminPages(): void {
  registerAdminPage("config", ConfigPage);
  registerAdminPage("tags", TagsSection);
  registerAdminPage("udt-types", UdtTypesPage);
  registerAdminPage("modules", ModulesPage);
  registerAdminPage("users", UsersPage);
  registerAdminPage("api-keys", ApiKeysPage);
  registerAdminPage("audit", AuditPage);
  registerAdminPage("backups", BackupsPage);
  registerAdminPage("network", NetworkPage);
  // Enterprise administration (EAM-001..EAM-017): role, agents, tasks, runs.
  registerAdminPage("enterprise-admin", EamPage);
  registerAdminPage("architectures", ArchitecturesPage);
  registerAdminPage("opcua", OpcUaPage);
  // SEC-022: the outbound TLS trust store, beside the OPC UA client certificates.
  registerAdminPage("outbound-tls", OutboundTlsPage);
  registerAdminPage("opc-connections", OpcConnectionsPage);
  // GAT-022: the Connections list.
  registerAdminPage("connections", ConnectionsPage);
  // GAT-028: connected Designers and the launch link.
  registerAdminPage("designer-sessions", DesignerSessionsPage);
  // GAT-030: SMTP email profiles.
  registerAdminPage("email-profiles", EmailProfilesPage);
  registerAdminPage("oauth2-clients", OAuth2ClientsPage);
  // C-12: status pages (GAT-016, GAT-017, PRO-003).
  registerAdminPage("overview", OverviewPage);
  registerAdminPage("logs", LogsPage);
  registerAdminPage("diagnostics", DiagnosticsPage);
  // GAT-024: user-built metrics dashboards.
  registerAdminPage("metrics", MetricsPage);
  // P3-05: store-and-forward status, quarantine, and alarm-journal counters (HIS-025, HIS-026).
  registerAdminPage("store-and-forward", StoreAndForwardPage);
  registerAdminPage("projects", ProjectsPage);
  // P1-35: the gateway home page (GAT-023).
  registerAdminPage("home", HomePage);
  // C-17: database connections, named-query test runs, and the query browser.
  registerAdminPage("databases", DatabasesPage);
  // R1-F13: gateway settings (GAT-035), security zones and security settings (GAT-034).
  registerAdminPage("gateway-settings", GatewaySettingsPage);
  registerAdminPage("plc-resources", PlcResourcesPage);
  registerAdminPage("zones", ZonesPage);
  // SEC-016, SEC-017: the security level tree and the rules that grant levels at login.
  registerAdminPage("security-levels", SecurityLevelsPage);
  registerAdminPage("security-settings", SecuritySettingsPage);
  registerAdminPage("secrets", SecretsPage);
  registerAdminPage("http-endpoints", HttpEndpointsPage);
  registerAdminPage("deployment", DeploymentPage);
  // P1-33: identity providers (SEC-014).
  registerAdminPage("identity-providers", IdentityProvidersPage);
  // SEC-007, SEC-010, SEC-021, SEC-025: LDAP, database and hybrid user sources.
  registerAdminPage("user-sources", UserSourcesPage);
  // Services group (GAT-036): historian and alarming resources.
  registerAdminPage("historian", HistoryPage);
  registerAdminPage("alarming", AlarmEditorPage);
  // MES-002..MES-009, SCR-011: event streams and Kafka connections.
  registerAdminPage("event-streams", EventStreamsPage);
  registerAdminPage("charts", ChartsPage);
  registerAdminPage("reports", ReportsPage);
}

export function AdminPage(props: AdminPageProps) {
  const Page = pages.get(props.section.id) ?? PlaceholderPage;
  return <Page {...props} />;
}

export function PlaceholderPage({ section }: { section: AdminSection }) {
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p data-testid="section-placeholder">No page is registered for this area yet.</p>
    </article>
  );
}
