import { useEffect, useState, type ReactNode } from "react";
import { allows, errorMessage } from "./access";
import type { OutboundCertificate, OutboundTrustList } from "./api";
import { certificateText } from "./opcUaPage";
import type { AdminPageProps } from "./pages";

/**
 * Server certificates for the gateway's own outbound TLS connections (SEC-022): databases, MQTT brokers, identity
 * providers, mail servers, and HTTP calls from scripts and event streams.
 *
 * The gateway accepts a server certificate the JVM's public authorities accept, or one whose chain ends at a
 * certificate trusted here (a server's self-signed certificate or a private authority). A certificate neither accepts
 * is refused and listed under Rejected with the client and host that met it. Trusting it lets the next connection
 * through. Reading needs gateway.read; every change needs gateway.write and is audited.
 * Doc source: FEATURES SEC-022.
 */
export function OutboundTlsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [trust, setTrust] = useState<OutboundTrustList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState<OutboundCertificate | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .outboundTrust()
      .then((next) => {
        if (cancelled) return;
        setTrust(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setTrust(null);
        setError(errorMessage(err, "Could not load the outbound trust store"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function act(run: () => Promise<unknown>, done: string, failed: string) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await run();
      setNotice(done);
      setConfirming(null);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, failed));
    } finally {
      setPending(false);
    }
  }

  const label = (cert: OutboundCertificate) => cert.commonName ?? cert.subject;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        Certificates the gateway trusts when it connects out over TLS, beside the public certificate authorities. A server
        whose certificate is not trusted is refused and listed under Rejected.
      </p>
      {!client ? <p role="alert">The outbound trust store is unavailable.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {client && !trust && !error ? <p>Loading the trust store.</p> : null}

      {trust ? (
        <>
          <h2>Rejected</h2>
          <p>Servers the gateway refused because their certificate is not trusted, newest first.</p>
          <CertificateTable
            label="Rejected server certificates"
            items={trust.rejected}
            rejected
            empty="No rejected certificates."
            actions={
              canWrite
                ? (cert) => (
                    <>
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() =>
                          void act(() => client!.trustOutboundCertificate(cert.thumbprint), `Trusted ${label(cert)}.`, "Could not trust the certificate")
                        }
                      >
                        Trust {label(cert)}
                      </button>
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() =>
                          void act(
                            () => client!.removeOutboundCertificate("rejected", cert.thumbprint),
                            `Deleted ${label(cert)} from the rejected list.`,
                            "Could not delete the certificate",
                          )
                        }
                      >
                        Delete {label(cert)}
                      </button>
                    </>
                  )
                : undefined
            }
          />
          <h2>Trusted</h2>
          <CertificateTable
            label="Trusted server certificates"
            items={trust.trusted}
            empty="No certificates trusted here. Servers with publicly trusted certificates still connect."
            actions={
              canWrite
                ? (cert) => (
                    <button type="button" disabled={pending} onClick={() => setConfirming(cert)}>
                      Remove {label(cert)}
                    </button>
                  )
                : undefined
            }
          />
        </>
      ) : null}

      {confirming ? (
        <div role="dialog" aria-labelledby="outbound-untrust-title" className="confirm-panel">
          <h2 id="outbound-untrust-title">Remove trust</h2>
          <p>
            New connections to servers that present {label(confirming)} will be refused. Connections already open are not cut.
            Confirm to continue.
          </p>
          <div className="inline-form">
            <button
              type="button"
              disabled={pending}
              onClick={() =>
                void act(
                  () => client!.removeOutboundCertificate("trusted", confirming.thumbprint),
                  `Removed trust for ${label(confirming)}.`,
                  "Could not remove the certificate",
                )
              }
            >
              Confirm removal
            </button>
            <button type="button" disabled={pending} onClick={() => setConfirming(null)}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      {canWrite && client ? (
        <ImportForm
          pending={pending}
          onSubmit={(text) => act(() => client.importOutboundCertificate(text), "Certificate imported and trusted.", "Could not import the certificate")}
        />
      ) : null}
    </article>
  );
}

function CertificateTable({
  label,
  items,
  empty,
  rejected = false,
  actions,
}: {
  label: string;
  items: OutboundCertificate[];
  empty: string;
  rejected?: boolean;
  actions?: (cert: OutboundCertificate) => ReactNode;
}) {
  const columns = 4 + (rejected ? 2 : 0) + (actions ? 1 : 0);
  return (
    <table className="data-table" aria-label={label}>
      <thead>
        <tr>
          <th>Subject</th>
          <th>Issuer</th>
          {rejected ? <th>Seen at</th> : null}
          {rejected ? <th>Client</th> : null}
          <th>SHA-256 thumbprint</th>
          <th>Valid until</th>
          {actions ? <th>Actions</th> : null}
        </tr>
      </thead>
      <tbody>
        {items.length === 0 ? (
          <tr>
            <td colSpan={columns}>{empty}</td>
          </tr>
        ) : null}
        {items.map((cert) => (
          <tr key={cert.thumbprint}>
            <td title={cert.subject}>{cert.commonName ?? cert.subject}</td>
            <td>{cert.issuer === cert.subject ? "Self-signed" : cert.issuer}</td>
            {rejected ? <td title={cert.lastSeen ?? ""}>{cert.seenAt ?? ""}</td> : null}
            {rejected ? <td>{cert.client ?? ""}</td> : null}
            <td>
              <code className="thumbprint">{cert.thumbprint}</code>
            </td>
            <td>
              {cert.notAfter}
              {cert.expired ? " (expired)" : ""}
            </td>
            {actions ? <td className="row-actions">{actions(cert)}</td> : null}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ImportForm({ pending, onSubmit }: { pending: boolean; onSubmit: (text: string) => Promise<void> }) {
  const [file, setFile] = useState<File | null>(null);
  return (
    <form
      className="stack-form"
      aria-label="Import a server certificate"
      onSubmit={(event) => {
        event.preventDefault();
        if (!file) return;
        const form = event.currentTarget;
        void file
          .arrayBuffer()
          .then((buffer) => onSubmit(certificateText(new Uint8Array(buffer))))
          .then(() => {
            setFile(null);
            form.reset();
          });
      }}
    >
      <h2>Import a server or authority certificate</h2>
      <label>
        Certificate file (PEM or DER)
        <input type="file" accept=".pem,.crt,.cer,.der" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
      </label>
      <button type="submit" disabled={pending || !file}>
        Import and trust
      </button>
    </form>
  );
}
