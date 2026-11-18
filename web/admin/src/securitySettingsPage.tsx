import { ResourceEditor } from "./configPage";
import type { AdminPageProps } from "./pages";
import { canWriteSecuritySettings, SECURITY_MODULE } from "./zonesPage";

/** The singleton security resources the gateway seeds, in the order the page shows them. */
export const SECURITY_SETTINGS = [
  { kind: "GatewaySecurity", name: "gateway", label: "Gateway security", testId: "gateway-security" },
  { kind: "PasswordPolicy", name: "default", label: "Password policy", testId: "password-policy" },
  { kind: "AuditProfile", name: "internal", label: "Audit profile", testId: "audit-profile" },
] as const;

/**
 * Gateway-wide security settings (GAT-034): general security (inactivity timeout, designer and gateway-area roles),
 * the internal password policy, and the internal audit profile (enabled, retention days). Each is a config resource
 * edited with its schema. None holds a credential, so gateway.read shows them; a change needs the security
 * administration permissions as well as gateway.write. Rows older than the audit profile's retention are removed by
 * the gateway (SEC-001).
 * Doc source: FEATURES GAT-034.
 * Doc source: FEATURES SEC-001.
 */
export function SecuritySettingsPage({ section, client, session }: AdminPageProps) {
  const canWrite = canWriteSecuritySettings(session);
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        These settings apply to every account. Audit records older than the audit profile's retention days are removed;
        0 keeps them forever.
      </p>
      {!client ? <p role="alert">Security settings are unavailable.</p> : null}
      {client
        ? SECURITY_SETTINGS.map((item) => (
            <section key={item.kind} aria-label={item.label} data-testid={item.testId}>
              <h2>{item.label}</h2>
              <ResourceEditor client={client} moduleName={SECURITY_MODULE} scope="gateway" kind={item.kind} name={item.name} canWrite={canWrite} />
            </section>
          ))
        : null}
    </article>
  );
}
