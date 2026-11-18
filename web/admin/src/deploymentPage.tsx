import { useState } from "react";
import type { AdminPageProps } from "./pages";

const VALUES: Array<[string, string]> = [
  ["ERIC_PROFILE", "edge, standard, or hub"],
  ["ERIC_DATA_DIR", "directory that holds config, history, and the key file"],
  ["ERIC_HTTP_HOST", "address the plain HTTP port binds"],
  ["ERIC_HTTP_PORT", "plain HTTP port"],
  ["ERIC_HTTP_HTTPS", "true turns on HTTPS"],
  ["ERIC_HTTP_HTTPS_PORT", "HTTPS listen port"],
  ["ERIC_HTTP_PUBLIC_ADDRESS", "name clients use"],
  ["ERIC_HTTP_FORCE_REDIRECT", "true sends plain HTTP to HTTPS"],
  ["ERIC_FEATURES", "comma-separated feature list"],
  ["ERIC_ADMIN_PASSWORD", "seeds the admin user only while no users exist"],
  ["ERIC_SECRETS_KEY_FILE", "path of the secrets key file; leave the key itself out of this page"],
  ["ERIC_ADMIN_DIST", "built admin files"],
  ["ERIC_WEB_DIST", "built session and Control Canvas files"],
];

const CHART = `profile: standard
httpPort: 8088
gatewayNetwork:
  enabled: false
  port: 8060
persistence:
  enabled: true
  size: 10Gi
admin:
  existingSecret: oip-admin
  secretKey: password
`;

function block(): string {
  return VALUES.map(([name, meaning]) => `# ${meaning}\n${name}=`).join("\n");
}

/** GAT-009 through GAT-012 and HMP-088: install values and the proxy note, read-only, with copy. */
export function DeploymentPage({ section }: AdminPageProps) {
  const [notice, setNotice] = useState<string | null>(null);
  const env = block();

  async function copy(label: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setNotice(`Copied the ${label}.`);
    } catch {
      setNotice(`Select the ${label} and copy them.`);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>
        These are the settings a container, a Helm install, and a first boot read. This page does not change the running process. A persistent volume keeps the data directory. On a first boot with no users, ERIC_ADMIN_PASSWORD seeds the administrator, and a backup restore is the Backups page. Older OIP_* names still work and print a warning.
      </p>
      <h2>Environment</h2>
      <textarea aria-label="Install values" readOnly rows={16} value={env} />
      <p>
        <button type="button" onClick={() => void copy("environment names", env)}>Copy environment names</button>
      </p>
      <h2>Helm values</h2>
      <p>The chart uses these names. Put the administrator password in a secret the cluster already has. The chart does not create that password.</p>
      <textarea aria-label="Helm values" readOnly rows={12} value={CHART} />
      <p>
        <button type="button" onClick={() => void copy("Helm values", CHART)}>Copy Helm values</button>
      </p>
      <h2>Reverse proxy</h2>
      <p>
        A proxy in front of this gateway terminates TLS and forwards the websocket upgrade used by sessions. Put the proxy address on Network under trusted proxies so the gateway records the client address the proxy sends.
      </p>
      {notice ? <p role="status">{notice}</p> : null}
    </article>
  );
}
