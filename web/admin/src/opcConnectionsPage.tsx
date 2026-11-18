import { allows } from "./access";
import type { AdminPageProps } from "./pages";
import type { OpcUaConnectionStatus } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * OPC UA client connection status: server state, session, and per-subscription counts.
 *
 * Monitored item counts are items the server created. A monitored item the server rejected
 * is not included. Doc source: FEATURES DEV-016.
 */

export const OPC_CONNECTIONS_REFRESH_MS = 3_000;

export function OpcConnectionsPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  const api = useStatusApi(client);
  const loaded = useLoad(() => api.opcUaConnections(), [api], {
    intervalMs: OPC_CONNECTIONS_REFRESH_MS,
    enabled: Boolean(client) && canRead,
    fallback: "Could not read OPC UA connections",
  });

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>
        Server state, session, and subscription counts for each OPC UA client connection. Monitored items are only
        those the server created.
      </p>
      {!canRead ? (
        <p role="status" data-testid="opc-connections-no-read">
          Your roles do not include gateway.read, so connection status is hidden.
        </p>
      ) : null}
      {canRead && loaded.error ? <p role="alert">{loaded.error}</p> : null}
      {canRead && !loaded.data && !loaded.error ? <p>Loading OPC UA connections.</p> : null}
      {canRead && loaded.data ? (
        <ConnectionsTable connections={loaded.data} updatedAt={loaded.updatedAt} />
      ) : null}
    </article>
  );
}

function ConnectionsTable({ connections, updatedAt }: { connections: OpcUaConnectionStatus[]; updatedAt: Date | null }) {
  if (connections.length === 0) {
    return <p data-testid="opc-connections-empty">No OPC UA connections are configured.</p>;
  }
  return (
    <>
      <p data-testid="opc-connections-updated">
        Refreshes every {OPC_CONNECTIONS_REFRESH_MS / 1000} seconds.
        {updatedAt ? ` Last refresh ${updatedAt.toLocaleTimeString()}.` : ""}
      </p>
      <table className="data-table" aria-label="OPC UA connections" data-testid="opc-connections">
        <thead>
          <tr>
            <th>Name</th>
            <th>Status</th>
            <th>Server state</th>
            <th>Session</th>
            <th>Subscriptions</th>
            <th>Monitored items</th>
            <th>Sampling</th>
            <th>Publishing</th>
            <th>Requests</th>
            <th>Errors</th>
            <th>Timeouts</th>
            <th>Avg response</th>
          </tr>
        </thead>
        <tbody>
          {connections.map((row) => (
            <ConnectionRows key={row.name} row={row} />
          ))}
        </tbody>
      </table>
    </>
  );
}

function ConnectionRows({ row }: { row: OpcUaConnectionStatus }) {
  return (
    <>
      <tr data-testid={`opc-connection-${row.name}`} data-connected={row.connected ? "true" : "false"}>
        <td>{row.name}</td>
        <td>{row.enabled ? row.state : "Disabled"}</td>
        <td data-testid={`opc-server-state-${row.name}`}>{row.serverState ?? "—"}</td>
        <td data-testid={`opc-session-${row.name}`}>{sessionLabel(row)}</td>
        <td data-testid={`opc-subscriptions-${row.name}`}>{row.subscriptionCount}</td>
        <td data-testid={`opc-monitored-items-${row.name}`}>{row.monitoredItemCount}</td>
        <td>{millis(row.samplingIntervalMs)}</td>
        <td>{millis(row.publishIntervalMs)}</td>
        <td>{row.requestCount}</td>
        <td>{row.errorCount}</td>
        <td>{row.timeoutCount}</td>
        <td>{millis(row.averageResponseTimeMs)}</td>
      </tr>
      <tr>
        <td colSpan={12}>
          <details>
            <summary>Subscriptions for {row.name}</summary>
            {row.subscriptions.length === 0 ? (
              <p>No subscriptions.</p>
            ) : (
              <table className="data-table" aria-label={`Subscriptions for ${row.name}`}>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Sampling</th>
                    <th>Requested publishing</th>
                    <th>Revised publishing</th>
                    <th>Monitored items</th>
                  </tr>
                </thead>
                <tbody>
                  {row.subscriptions.map((sub) => (
                    <tr key={sub.name}>
                      <td>{sub.name}</td>
                      <td>{millis(sub.samplingIntervalMs)}</td>
                      <td>{millis(sub.requestedPublishingIntervalMs)}</td>
                      <td>{millis(sub.revisedPublishingIntervalMs)}</td>
                      <td>{sub.monitoredItemCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </details>
        </td>
      </tr>
    </>
  );
}

function sessionLabel(row: OpcUaConnectionStatus): string {
  if (!row.connected) return "—";
  if (row.sessionName && row.sessionId) return `${row.sessionName} (${row.sessionId})`;
  return row.sessionName ?? row.sessionId ?? "—";
}

function millis(value: number | null): string {
  if (value == null) return "—";
  return `${trim(value)} ms`;
}

function trim(value: number): string {
  if (Number.isInteger(value)) return String(value);
  return value.toFixed(1);
}
