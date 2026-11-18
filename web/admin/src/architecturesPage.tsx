import { useEffect, useState } from "react";
import type { ArchitectureCatalog } from "./api";
import { allows, errorMessage } from "./access";
import type { AdminPageProps } from "./pages";

/**
 * Reference architectures and the redundancy storage table, both read from this gateway.
 * GAN-006, GAN-007, GAN-008, GAN-011, GAN-012, GAN-013, RED-001, RED-004.
 */
export function ArchitecturesPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <ArchitecturesBody client={client} canRead={canRead} />
    </article>
  );
}

/** The catalog without the page heading, so Diagnostics can show it as its Architecture group (GAT-021). */
export function ArchitecturesBody({ client, canRead }: { client?: AdminPageProps["client"]; canRead: boolean }) {
  const [catalog, setCatalog] = useState<ArchitectureCatalog | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!client || !canRead) return;
    let cancelled = false;
    client
      .architectures()
      .then((next) => {
        if (cancelled) return;
        setCatalog(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setCatalog(null);
        setError(errorMessage(err, "Could not load architectures"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, canRead]);

  return (
    <>
      {!canRead ? (
        <p role="status" data-testid="architectures-no-read">
          Your roles do not include gateway.read, so the architecture catalog is hidden.
        </p>
      ) : null}
      <p>
        Each row is checked against this gateway. A match is the live profile, bind address, peers, redundancy, edge
        sync, zones, and service policies, not a drawing of a deployment this process is not running.
      </p>
      {error ? <p role="alert">{error}</p> : null}
      {catalog ? (
        <>
          <dl data-testid="architecture-node">
            <div>
              <dt>Profile</dt>
              <dd>{catalog.node.profile}</dd>
            </div>
            <div>
              <dt>Bind</dt>
              <dd>{catalog.node.bindHost}</dd>
            </div>
            <div>
              <dt>Redundancy</dt>
              <dd>{catalog.node.redundancyMode}</dd>
            </div>
            <div>
              <dt>History mode</dt>
              <dd>{catalog.node.historyMode}</dd>
            </div>
          </dl>
          <ul>
            {catalog.architectures.map((row) => (
              <li key={row.id} data-testid={`architecture-${row.id}`}>
                <h2>{row.title}</h2>
                <p>{row.summary}</p>
                <p>{row.fits ? "Matches this gateway" : "Does not match this gateway"}</p>
                <ul>
                  {row.checks.map((check) => (
                    <li key={check.id}>
                      {check.met ? "Met" : "Not met"}: {check.label}. {check.detail}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
          <h2>Redundancy storage</h2>
          <table data-testid="redundancy-subsystems">
            <thead>
              <tr>
                <th>Subsystem</th>
                <th>Redundancy</th>
                <th>Database</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {catalog.subsystems.map((row) => (
                <tr key={row.id}>
                  <td>{row.title}</td>
                  <td>{row.redundancy}</td>
                  <td>{row.database}</td>
                  <td>{row.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : null}
    </>
  );
}
