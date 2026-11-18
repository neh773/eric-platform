import { allows } from "./access";
import type { AdminPageProps } from "./pages";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * Designers connected to this gateway now, the Designer launch link, and a shortcut download.
 * A session drops out of the list when its Designer stops sending heartbeats.
 * Doc source: FEATURES GAT-028.
 */

export const DESIGNER_SESSIONS_REFRESH_MS = 5_000;

export function DesignerSessionsPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  const canLaunch = session ? allows(session, "gateway.write") : true;
  const api = useStatusApi(client);
  const loaded = useLoad(() => api.designerSessions(), [api], {
    intervalMs: DESIGNER_SESSIONS_REFRESH_MS,
    enabled: Boolean(client) && canRead,
    fallback: "Could not read Control Canvas sessions",
  });
  const data = loaded.data;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>
        Control Canvas sessions connected to this gateway now. A session that stops sending heartbeats drops out of the list after{" "}
        {data?.ttlSeconds ?? 45} seconds.
      </p>
      {!canRead ? (
        <p role="status" data-testid="designer-sessions-no-read">
          Your roles do not include gateway.read, so Control Canvas sessions are hidden.
        </p>
      ) : null}
      {canLaunch && data ? (
        <p className="inline-form">
          <a href={data.launch.href} target="_blank" rel="noreferrer" data-testid="designer-launch">
            {data.launch.label}
          </a>
          <a href={data.download.href} download={data.download.filename} data-testid="designer-download">
            {data.download.label}
          </a>
        </p>
      ) : null}
      {canRead && loaded.error ? <p role="alert">{loaded.error}</p> : null}
      {canRead && !data && !loaded.error ? <p>Loading Control Canvas sessions.</p> : null}
      {canRead && data ? (
        <table className="data-table" aria-label="Control Canvas sessions" data-testid="designer-sessions">
          <thead>
            <tr>
              <th>User</th>
              <th>Project</th>
              <th>Address</th>
              <th>Opened</th>
              <th>Last heartbeat</th>
            </tr>
          </thead>
          <tbody>
            {data.sessions.length === 0 ? (
              <tr>
                <td colSpan={5} data-testid="designer-sessions-empty">
                  No Control Canvas session is connected.
                </td>
              </tr>
            ) : null}
            {data.sessions.map((row) => (
              <tr key={row.id} data-testid={`designer-session-${row.id}`}>
                <td>{row.user}</td>
                <td>{row.project ?? "none"}</td>
                <td>{row.address}</td>
                <td>{new Date(row.openedAt).toLocaleString()}</td>
                <td>{new Date(row.lastSeen).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </article>
  );
}
