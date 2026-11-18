import { useState } from "react";
import { allows } from "./access";
import { DeviceWizard } from "./deviceWizard";
import type { AdminPageProps } from "./pages";
import { sectionPath } from "./sections";
import type { ConnectionItem, ConnectionsDocument } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * The Connections section: every device, database, service and OPC connection in one list with its live status.
 * Status comes from the driver, pool or link that already tracks it; this page opens no connection of its own.
 * Doc source: FEATURES GAT-022.
 */

export const CONNECTIONS_REFRESH_MS = 3_000;

const KIND_LABEL: Record<ConnectionItem["kind"], string> = {
  device: "Device",
  database: "Database",
  service: "Service",
  opc: "OPC",
};

const KIND_FILTERS = ["all", "device", "database", "service", "opc"] as const;
type KindFilter = (typeof KIND_FILTERS)[number];

export function ConnectionsPage({ section, client, session, onVisit }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>
        Device, database, service and OPC connections with their live status. Each row links to the page that edits it.
      </p>
      {!canRead ? (
        <p role="status" data-testid="connections-no-read">
          Your roles do not include gateway.read, so connection status is hidden.
        </p>
      ) : null}
      {canRead ? <ConnectionsList client={client} onVisit={onVisit} /> : null}
      {canRead && client ? <DeviceWizard client={client} canWrite={allows(session, "gateway.write")} /> : null}
    </article>
  );
}

/** The list and its summary, also embedded in the Diagnostics Connections group. */
export function ConnectionsList({
  client,
  onVisit,
  compact = false,
}: Pick<AdminPageProps, "client" | "onVisit"> & { compact?: boolean }) {
  const api = useStatusApi(client);
  const loaded = useLoad<ConnectionsDocument>(() => api.connections(), [api], {
    intervalMs: CONNECTIONS_REFRESH_MS,
    enabled: Boolean(client),
    fallback: "Could not read connections",
  });
  const [kind, setKind] = useState<KindFilter>("all");
  const data = loaded.data;
  const rows = (data?.items ?? []).filter((row) => kind === "all" || row.kind === kind);

  if (loaded.error && !data) return <p role="alert">{loaded.error}</p>;
  if (!data) return <p>Loading connections.</p>;
  return (
    <>
      {loaded.error ? <p role="alert">{loaded.error}</p> : null}
      <p data-testid="connections-summary">
        {data.summary.total} connections: {data.summary.ok} connected, {data.summary.pending} connecting, {data.summary.fault} faulted,{" "}
        {data.summary.disabled} disabled.
      </p>
      {compact ? null : (
        <div role="group" aria-label="Connection kind" className="inline-form">
          {KIND_FILTERS.map((item) => (
            <button
              key={item}
              type="button"
              aria-pressed={kind === item}
              className={kind === item ? undefined : "quiet"}
              onClick={() => setKind(item)}
            >
              {item === "all" ? "All" : KIND_LABEL[item]}
            </button>
          ))}
        </div>
      )}
      <table className="data-table" aria-label="Connections list" data-testid="connections-table">
        <thead>
          <tr>
            <th>Kind</th>
            <th>Type</th>
            <th>Name</th>
            <th>Status</th>
            <th>Detail</th>
            <th>Edit</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={6}>No connections of this kind.</td>
            </tr>
          ) : null}
          {rows.map((row) => (
            <tr key={row.id} data-testid={`connection-${row.id}`} data-health={row.health}>
              <td>{KIND_LABEL[row.kind]}</td>
              <td>{row.type}</td>
              <td>{row.name}</td>
              <td>
                <span className={`status-${row.health}`}>{row.status}</span>
              </td>
              <td>{row.detail}</td>
              <td>
                <a
                  href={sectionPath(row.section)}
                  onClick={(event) => {
                    if (!onVisit) return;
                    event.preventDefault();
                    onVisit(sectionPath(row.section));
                  }}
                >
                  Open {row.name}
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
