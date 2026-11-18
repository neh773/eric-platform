import { useEffect, useState, type ReactNode } from "react";
import { allows, errorMessage } from "./access";
import type { OpcUaClientCertificate, OpcUaTrustList } from "./api";
import type { AdminPageProps } from "./pages";

/**
 * Client certificates for the gateway OPC UA server.
 *
 * A client that opens a secured endpoint with a certificate the server does not trust is refused, and
 * its certificate waits in quarantine. Administrators trust or delete it, import a certificate before a
 * client first connects, and remove trust. Reading needs gateway.read; every change needs gateway.write.
 */
export function OpcUaPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [trust, setTrust] = useState<OpcUaTrustList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState<OpcUaClientCertificate | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .opcUaTrust()
      .then((next) => {
        if (cancelled) return;
        setTrust(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setTrust(null);
        setError(errorMessage(err, "Could not load the OPC UA trust list"));
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

  const label = (cert: OpcUaClientCertificate) => cert.commonName ?? cert.subject;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p data-testid="opcua-server-status">
        {trust
          ? trust.serverRunning
            ? `The OPC UA server is running${trust.endpointUrl ? ` at ${trust.endpointUrl}` : ""}.`
            : trust.serverEnabled
              ? "The OPC UA server is enabled but not running."
              : "The OPC UA server is off. Trust changes apply when it starts."
          : "Loading the trust list."}
      </p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}

      {trust ? (
        <>
          <h2>Quarantined</h2>
          <p>Clients refused on a secured endpoint because their certificate is not trusted.</p>
          <CertificateTable
            label="Quarantined certificates"
            items={trust.quarantined}
            empty="No quarantined certificates."
            actions={
              canWrite
                ? (cert) => (
                    <>
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() =>
                          void act(() => client!.trustOpcUaCertificate(cert.thumbprint), `Trusted ${label(cert)}.`, "Could not trust the certificate")
                        }
                      >
                        Trust {label(cert)}
                      </button>
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() =>
                          void act(
                            () => client!.removeOpcUaCertificate("quarantined", cert.thumbprint),
                            `Deleted ${label(cert)} from quarantine.`,
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
            label="Trusted certificates"
            items={trust.trusted}
            empty="No trusted client certificates."
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
        <div role="dialog" aria-labelledby="untrust-title" className="confirm-panel">
          <h2 id="untrust-title">Remove trust</h2>
          <p>
            {label(confirming)} will be refused on its next secured connection and will appear in quarantine again. Confirm to
            continue.
          </p>
          <div className="inline-form">
            <button
              type="button"
              disabled={pending}
              onClick={() =>
                void act(
                  () => client!.removeOpcUaCertificate("trusted", confirming.thumbprint),
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
          onSubmit={(text) => act(() => client.importOpcUaCertificate(text), "Certificate imported and trusted.", "Could not import the certificate")}
        />
      ) : null}
    </article>
  );
}

function CertificateTable({
  label,
  items,
  empty,
  actions,
}: {
  label: string;
  items: OpcUaClientCertificate[];
  empty: string;
  actions?: (cert: OpcUaClientCertificate) => ReactNode;
}) {
  const columns = actions ? 5 : 4;
  return (
    <table className="data-table" aria-label={label}>
      <thead>
        <tr>
          <th>Subject</th>
          <th>Application URI</th>
          <th>SHA-1 thumbprint</th>
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
            <td>{cert.applicationUri ?? ""}</td>
            <td>
              <code>{cert.thumbprint}</code>
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
      <h2>Import a client certificate</h2>
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

/** PEM files are sent as text. Anything else is treated as DER and sent as base64. */
export function certificateText(bytes: Uint8Array): string {
  const text = new TextDecoder().decode(bytes);
  if (text.includes("-----BEGIN CERTIFICATE-----")) return text;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
