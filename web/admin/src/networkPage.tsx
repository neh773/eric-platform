import { useEffect, useState } from "react";
import { allows, errorMessage } from "./access";
import type { GatewayNetworkStatus, HttpsStatus } from "./api";
import type { AdminPageProps } from "./pages";
import { RedundancyPanel } from "./redundancyPanel";

/**
 * HTTPS listener status, web-server settings, TLS policy, certificate signing requests, and certificate upload.
 * No private key is ever shown or sent back by these routes. Port and redirect edits are saved to
 * gateway.yaml and take effect on the next start; the TLS choice and a new certificate take effect at once.
 * (FEATURES GAT-031, GAT-032).
 */
export function NetworkPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [status, setStatus] = useState<HttpsStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .httpsStatus()
      .then((next) => {
        if (cancelled) return;
        setStatus(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setStatus(null);
        setError(errorMessage(err, "Could not load HTTPS status"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function uploadPem(certificate: File, key: File) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const next = await client.replaceHttpsCertificate({
        format: "pem",
        certificatePem: await certificate.text(),
        privateKeyPem: await key.text(),
      });
      setStatus(next);
      setNotice("Certificate replaced.");
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not replace the certificate"));
    } finally {
      setPending(false);
    }
  }

  async function uploadPkcs12(file: File, password: string) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      let binary = "";
      for (const byte of bytes) binary += String.fromCharCode(byte);
      const next = await client.replaceHttpsCertificate({
        format: "pkcs12",
        pkcs12Base64: btoa(binary),
        password,
      });
      setStatus(next);
      setNotice("Certificate replaced.");
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not replace the certificate"));
    } finally {
      setPending(false);
    }
  }

  const certificate = status?.certificate;
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <RedundancyPanel client={client} canWrite={canWrite} />
      <p data-testid="https-status">
        {status
          ? status.listening
            ? "HTTPS is on. Plain HTTP stays available unless redirect is enabled."
            : "HTTPS is off. Plain HTTP stays available."
          : "Loading HTTPS status."}
      </p>
      {status && !status.listening ? (
        <p className="muted" data-testid="plain-http-note">
          Plain HTTP is fine for a browser on this computer. Before people sign in from other computers, turn on HTTPS
          below so passwords and session cookies are encrypted on the network.
        </p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {status ? (
        <dl className="meta" data-testid="https-details">
          <dt>HTTP port</dt>
          <dd>{status.httpPort}</dd>
          <dt>HTTPS port</dt>
          <dd>{status.listening ? status.httpsListenPort : status.httpsPort}</dd>
          <dt>Public address</dt>
          <dd>{status.publicAddress ?? "Not set"}</dd>
          <dt>Redirect HTTP to HTTPS</dt>
          <dd>{status.forceHttpsRedirect ? "On" : "Off"}</dd>
          <dt>Protocols</dt>
          <dd>{status.protocols.join(", ") || "TLS 1.2 and 1.3"}</dd>
        </dl>
      ) : null}
      {certificate ? (
        <dl className="meta" data-testid="https-certificate">
          <dt>Subject</dt>
          <dd>{certificate.subject}</dd>
          <dt>Fingerprint</dt>
          <dd>
            <code data-testid="https-fingerprint">{certificate.sha256Fingerprint}</code>
          </dd>
          <dt>Names</dt>
          <dd>{certificate.sans.join(", ") || "None"}</dd>
          <dt>Not after</dt>
          <dd>{certificate.notAfter}</dd>
          <dt>Source</dt>
          <dd>{certificate.source}</dd>
        </dl>
      ) : status ? (
        <p>No certificate is stored. The private key is not shown here.</p>
      ) : null}
      {canWrite ? (
        <div data-testid="https-upload">
          <PemForm pending={pending} onSubmit={uploadPem} />
          <Pkcs12Form pending={pending} onSubmit={uploadPkcs12} />
        </div>
      ) : null}
      {client ? (
        <HttpsAdmin
          canWrite={canWrite}
          csrfToken={() => client.csrfToken() ?? session?.csrfToken ?? null}
          reloadToken={reloadToken}
          onChanged={() => setReloadToken((token) => token + 1)}
        />
      ) : null}
      <GatewayNetworkSection client={client} canWrite={canWrite} />
    </article>
  );
}

/** Fields of GET /api/v1/https that the shared admin client does not parse yet. */
interface HttpsExtras {
  httpPort: number;
  enabled: boolean;
  httpsPort: number;
  publicAddress: string | null;
  publicHttpsPort: number | null;
  forceHttpsRedirect: boolean;
  configuredProtocols: string[];
  cipherSuites: string[] | null;
  configuredCipherSuites: string[] | null;
  availableCipherSuites: string[];
  csr: { subject: string; sans: string[]; publicKeySha256: string } | null;
  savedSettings: Record<string, unknown> | null;
}

class RouteError extends Error {}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function parseExtras(value: unknown): HttpsExtras {
  const body = value && typeof value === "object" ? (value as Record<string, unknown>) : {};
  const csr = body.csr && typeof body.csr === "object" ? (body.csr as Record<string, unknown>) : null;
  return {
    httpPort: typeof body.httpPort === "number" ? body.httpPort : 0,
    enabled: body.enabled === true,
    httpsPort: typeof body.httpsPort === "number" ? body.httpsPort : 0,
    publicAddress: typeof body.publicAddress === "string" ? body.publicAddress : null,
    publicHttpsPort: typeof body.publicHttpsPort === "number" ? body.publicHttpsPort : null,
    forceHttpsRedirect: body.forceHttpsRedirect === true,
    configuredProtocols: strings(body.configuredProtocols),
    cipherSuites: Array.isArray(body.cipherSuites) ? strings(body.cipherSuites) : null,
    configuredCipherSuites: Array.isArray(body.configuredCipherSuites) ? strings(body.configuredCipherSuites) : null,
    availableCipherSuites: strings(body.availableCipherSuites),
    csr: csr
      ? {
          subject: typeof csr.subject === "string" ? csr.subject : "",
          sans: strings(csr.sans),
          publicKeySha256: typeof csr.publicKeySha256 === "string" ? csr.publicKeySha256 : "",
        }
      : null,
    savedSettings:
      body.savedSettings && typeof body.savedSettings === "object" ? (body.savedSettings as Record<string, unknown>) : null,
  };
}

/** Same-origin call with the session cookie and, for writes, the CSRF header. Problem details become the message. */
async function call(method: string, path: string, csrfToken: string | null, body?: unknown): Promise<unknown> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    if (!csrfToken) throw new RouteError("Sign in again to make changes");
    headers["X-CSRF-Token"] = csrfToken;
  }
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  const text = await res.text();
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = null;
  }
  if (!res.ok) {
    const detail =
      parsed && typeof parsed === "object" && typeof (parsed as Record<string, unknown>).detail === "string"
        ? ((parsed as Record<string, unknown>).detail as string)
        : `Request failed (${res.status})`;
    throw new RouteError(detail);
  }
  return parsed;
}

function message(err: unknown, fallback: string): string {
  return err instanceof RouteError ? err.message : fallback;
}

function HttpsAdmin({
  canWrite,
  csrfToken,
  reloadToken,
  onChanged,
}: {
  canWrite: boolean;
  csrfToken: () => string | null;
  reloadToken: number;
  onChanged: () => void;
}) {
  const [extras, setExtras] = useState<HttpsExtras | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    call("GET", "/api/v1/https", null)
      .then((body) => {
        if (!cancelled) setExtras(parseExtras(body));
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(message(err, "Could not load HTTPS settings"));
      });
    return () => {
      cancelled = true;
    };
  }, [reloadToken]);

  if (error) return <p role="alert">{error}</p>;
  if (!extras) return null;
  const send = async (method: string, path: string, body?: unknown) => {
    const result = await call(method, path, csrfToken(), body);
    onChanged();
    return result;
  };
  return (
    <>
      <WebServerSettings extras={extras} canWrite={canWrite} send={send} />
      <TlsPolicyForm extras={extras} canWrite={canWrite} send={send} />
      <CsrSection extras={extras} canWrite={canWrite} send={send} />
    </>
  );
}

type Send = (method: string, path: string, body?: unknown) => Promise<unknown>;

function numberOrNull(text: string): number | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  return Number.isInteger(value) ? value : Number.NaN;
}

function WebServerSettings({ extras, canWrite, send }: { extras: HttpsExtras; canWrite: boolean; send: Send }) {
  const saved = extras.savedSettings;
  const pick = <T,>(key: string, live: T): T => (saved && key in saved ? (saved[key] as T) : live);
  const [httpPort, setHttpPort] = useState(String(pick("httpPort", extras.httpPort) ?? ""));
  const [httpsEnabled, setHttpsEnabled] = useState(Boolean(pick("httpsEnabled", extras.enabled)));
  const [httpsPort, setHttpsPort] = useState(String(pick("httpsPort", extras.httpsPort) ?? ""));
  const [publicAddress, setPublicAddress] = useState(String(pick("publicAddress", extras.publicAddress) ?? ""));
  const [publicHttpsPort, setPublicHttpsPort] = useState(String(pick("publicHttpsPort", extras.publicHttpsPort) ?? ""));
  const [redirect, setRedirect] = useState(Boolean(pick("forceHttpsRedirect", extras.forceHttpsRedirect)));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const overridden = saved && Array.isArray(saved.overriddenByEnvironment) ? strings(saved.overriddenByEnvironment) : [];

  async function save() {
    const body = {
      httpPort: numberOrNull(httpPort),
      httpsEnabled,
      httpsPort: numberOrNull(httpsPort),
      publicAddress: publicAddress.trim() || null,
      publicHttpsPort: numberOrNull(publicHttpsPort),
      forceHttpsRedirect: redirect,
    };
    if ([body.httpPort, body.httpsPort, body.publicHttpsPort].some((value) => Number.isNaN(value))) {
      setError("Ports must be whole numbers.");
      return;
    }
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const result = (await send("PUT", "/api/v1/https/settings", body)) as { savedSettings?: { appliedNow?: boolean } } | undefined;
      if (result?.savedSettings?.appliedNow) {
        setNotice(httpsEnabled ? `Saved. HTTPS is on now at port ${body.httpsPort ?? 8043}.` : "Saved. HTTPS is off now.");
      } else {
        setNotice("Saved to gateway.yaml. Restart the gateway to use the new ports, address, and redirect.");
      }
    } catch (err) {
      setError(message(err, "Could not save the web server settings"));
    } finally {
      setPending(false);
    }
  }

  return (
    <section data-testid="web-server-settings">
      <h2>Web server</h2>
      {saved && saved.restartRequired !== false ? <p data-testid="settings-restart">Saved changes are waiting for a gateway restart.</p> : null}
      {overridden.length > 0 ? (
        <p data-testid="settings-overridden">Environment variables override: {overridden.join(", ")}.</p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      <form
        className="stack-form"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset disabled={!canWrite || pending}>
          <label>
            HTTP port
            <input inputMode="numeric" value={httpPort} onChange={(event) => setHttpPort(event.target.value)} />
          </label>
          <label>
            <input type="checkbox" checked={httpsEnabled} onChange={(event) => setHttpsEnabled(event.target.checked)} />
            Serve HTTPS
          </label>
          <label>
            HTTPS port
            <input inputMode="numeric" value={httpsPort} onChange={(event) => setHttpsPort(event.target.value)} />
          </label>
          <label>
            Public address
            <input
              value={publicAddress}
              placeholder="gateway.example.com"
              onChange={(event) => setPublicAddress(event.target.value)}
            />
          </label>
          <label>
            Public HTTPS port
            <input
              inputMode="numeric"
              value={publicHttpsPort}
              placeholder="Same as the HTTPS port"
              onChange={(event) => setPublicHttpsPort(event.target.value)}
            />
          </label>
          <label>
            <input type="checkbox" checked={redirect} onChange={(event) => setRedirect(event.target.checked)} />
            Send plain HTTP requests to HTTPS
          </label>
          {canWrite ? <button type="submit">Save web server settings</button> : null}
        </fieldset>
      </form>
    </section>
  );
}

const PROTOCOLS: { id: string; label: string }[] = [
  { id: "TLSv1.2", label: "TLS 1.2" },
  { id: "TLSv1.3", label: "TLS 1.3" },
];

function TlsPolicyForm({ extras, canWrite, send }: { extras: HttpsExtras; canWrite: boolean; send: Send }) {
  const [protocols, setProtocols] = useState<string[]>(
    extras.configuredProtocols.length > 0 ? extras.configuredProtocols : PROTOCOLS.map((item) => item.id),
  );
  const [custom, setCustom] = useState(extras.configuredCipherSuites !== null);
  const [suites, setSuites] = useState<string[]>(extras.configuredCipherSuites ?? []);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function toggle(list: string[], value: string, on: boolean): string[] {
    return on ? [...list.filter((item) => item !== value), value] : list.filter((item) => item !== value);
  }

  async function save() {
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await send("PUT", "/api/v1/https/tls", {
        protocols,
        cipherSuites: custom ? extras.availableCipherSuites.filter((suite) => suites.includes(suite)) : null,
      });
      setNotice("TLS settings applied to the HTTPS listener.");
    } catch (err) {
      setError(message(err, "Could not apply the TLS settings"));
    } finally {
      setPending(false);
    }
  }

  return (
    <section data-testid="tls-policy">
      <h2>TLS protocols and cipher suites</h2>
      <p>TLS 1.2 is the oldest protocol offered. Suites known to be weak are never offered and are not listed here.</p>
      {extras.cipherSuites ? (
        <p data-testid="tls-active-suites">In use: {extras.cipherSuites.join(", ")}</p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      <form
        className="stack-form"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset disabled={!canWrite || pending}>
          <legend>Protocols</legend>
          {PROTOCOLS.map((item) => (
            <label key={item.id}>
              <input
                type="checkbox"
                checked={protocols.includes(item.id)}
                onChange={(event) => setProtocols(toggle(protocols, item.id, event.target.checked))}
              />
              {item.label}
            </label>
          ))}
        </fieldset>
        <fieldset disabled={!canWrite || pending}>
          <legend>Cipher suites</legend>
          <label>
            <input type="radio" name="suite-mode" checked={!custom} onChange={() => setCustom(false)} />
            Use the default list
          </label>
          <label>
            <input type="radio" name="suite-mode" checked={custom} onChange={() => setCustom(true)} />
            Only the suites checked below
          </label>
          {custom ? (
            <div data-testid="tls-suite-list">
              {extras.availableCipherSuites.map((suite) => (
                <label key={suite}>
                  <input
                    type="checkbox"
                    checked={suites.includes(suite)}
                    onChange={(event) => setSuites(toggle(suites, suite, event.target.checked))}
                  />
                  <code>{suite}</code>
                </label>
              ))}
            </div>
          ) : null}
        </fieldset>
        {canWrite ? (
          <button type="submit" disabled={pending || protocols.length === 0 || (custom && suites.length === 0)}>
            Apply TLS settings
          </button>
        ) : null}
      </form>
    </section>
  );
}

const KEY_CHOICES: { id: string; label: string; keyType: string; keySize?: number }[] = [
  { id: "rsa2048", label: "RSA 2048", keyType: "RSA", keySize: 2048 },
  { id: "rsa3072", label: "RSA 3072", keyType: "RSA", keySize: 3072 },
  { id: "rsa4096", label: "RSA 4096", keyType: "RSA", keySize: 4096 },
  { id: "ec256", label: "EC P-256", keyType: "EC" },
];

function CsrSection({ extras, canWrite, send }: { extras: HttpsExtras; canWrite: boolean; send: Send }) {
  const [form, setForm] = useState({
    commonName: "",
    organization: "",
    organizationalUnit: "",
    locality: "",
    state: "",
    country: "",
    sans: "",
    key: "rsa2048",
  });
  const [reply, setReply] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const csr = extras.csr;

  async function run(action: () => Promise<unknown>, done: string, failed: string) {
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
    } catch (err) {
      setError(message(err, failed));
    } finally {
      setPending(false);
    }
  }

  function field(key: keyof typeof form, label: string, placeholder?: string) {
    return (
      <label>
        {label}
        <input
          value={form[key]}
          placeholder={placeholder}
          onChange={(event) => setForm({ ...form, [key]: event.target.value })}
        />
      </label>
    );
  }

  const choice = KEY_CHOICES.find((item) => item.id === form.key) ?? KEY_CHOICES[0];
  return (
    <section data-testid="https-csr">
      <h2>Certificate signing request</h2>
      <p>The gateway makes the key pair and keeps the private key. Send the request to your certificate authority, then install its reply here.</p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {csr ? (
        <div data-testid="csr-pending">
          <dl className="meta">
            <dt>Subject</dt>
            <dd>{csr.subject}</dd>
            <dt>Names</dt>
            <dd>{csr.sans.join(", ")}</dd>
            <dt>Public key SHA-256</dt>
            <dd>
              <code>{csr.publicKeySha256}</code>
            </dd>
          </dl>
          <a href="/api/v1/https/csr?download=true" download="gateway.csr" data-testid="csr-download">
            Download request
          </a>
          {canWrite ? (
            <>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  void run(
                    async () => {
                      await send("POST", "/api/v1/https/csr/install", { certificatePem: reply });
                      setReply("");
                    },
                    "Signed certificate installed.",
                    "Could not install the signed certificate",
                  );
                }}
              >
                <label>
                  Signed certificate and chain (PEM)
                  <textarea rows={6} value={reply} onChange={(event) => setReply(event.target.value)} />
                </label>
                <label>
                  Or choose a file
                  <input
                    type="file"
                    accept=".pem,.crt,.cer"
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      if (file) void file.text().then(setReply);
                    }}
                  />
                </label>
                <button type="submit" disabled={pending || !reply.trim()}>
                  Install signed certificate
                </button>
              </form>
              <button
                type="button"
                disabled={pending}
                onClick={() =>
                  void run(() => send("DELETE", "/api/v1/https/csr"), "Request discarded.", "Could not discard the request")
                }
              >
                Discard request
              </button>
            </>
          ) : null}
        </div>
      ) : (
        <p data-testid="csr-none">No request is waiting for a CA reply.</p>
      )}
      {canWrite ? (
        <form
          className="stack-form"
          data-testid="csr-form"
          onSubmit={(event) => {
            event.preventDefault();
            const sans = form.sans
              .split(/[\n,]/)
              .map((item) => item.trim())
              .filter(Boolean);
            void run(
              () =>
                send("POST", "/api/v1/https/csr", {
                  commonName: form.commonName,
                  organization: form.organization,
                  organizationalUnit: form.organizationalUnit,
                  locality: form.locality,
                  state: form.state,
                  country: form.country,
                  sans,
                  keyType: choice.keyType,
                  keySize: choice.keySize,
                }),
              "Request created. Download it and send it to your certificate authority.",
              "Could not create the request",
            );
          }}
        >
          <h3>{csr ? "Start a new request" : "New request"}</h3>
          {field("commonName", "Common name", "gateway.example.com")}
          {field("organization", "Organization")}
          {field("organizationalUnit", "Department")}
          {field("locality", "City")}
          {field("state", "State or province")}
          {field("country", "Country code", "US")}
          <label>
            Other names (DNS names or IP addresses, one per line)
            <textarea rows={3} value={form.sans} onChange={(event) => setForm({ ...form, sans: event.target.value })} />
          </label>
          <label>
            Key
            <select value={form.key} onChange={(event) => setForm({ ...form, key: event.target.value })}>
              {KEY_CHOICES.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={pending || !form.commonName.trim()}>
            Create request
          </button>
        </form>
      ) : null}
    </section>
  );
}

/**
 * Incoming gateway connections stay pending until an administrator approves them.
 */
function GatewayNetworkSection({
  client,
  canWrite,
}: {
  client?: AdminPageProps["client"];
  canWrite: boolean;
}) {
  const [status, setStatus] = useState<GatewayNetworkStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .gatewayNetwork()
      .then((next) => {
        if (cancelled) return;
        setStatus(next);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(errorMessage(err, "Could not load peer network status"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function decide(fingerprint: string, approve: boolean) {
    if (!client || pending || !fingerprint) return;
    setPending(true);
    setError(null);
    try {
      const next = approve
        ? await client.approveIncomingGateway(fingerprint)
        : await client.denyIncomingGateway(fingerprint);
      setStatus(next);
    } catch (err) {
      setError(errorMessage(err, approve ? "Could not approve the connection" : "Could not deny the connection"));
    } finally {
      setPending(false);
      setReloadToken((token) => token + 1);
    }
  }

  const incoming = status?.incoming ?? [];
  const peers = [...incoming, ...(status?.outgoing ?? [])];
  async function regenerate() {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    try {
      setStatus(await client.regenerateGatewayCertificate());
    } catch (err) {
      setError(errorMessage(err, "Could not regenerate the certificate"));
    } finally {
      setPending(false);
      setReloadToken((token) => token + 1);
    }
  }
  return (
    <section data-testid="gateway-network">
      <h2>Gateway network</h2>
      <svg data-testid="gateway-network-diagram" viewBox="0 0 360 160" role="img" width="360" height="160">
        <title>Gateway network diagram</title>
        <rect x="130" y="50" width="100" height="48" rx="6" fill="none" stroke="currentColor" />
        <text data-testid="gateway-network-node" x="180" y="78" textAnchor="middle">
          {status?.gatewayName || "This gateway"}
        </text>
        {peers.map((peer, index) => {
          const label = peer.gatewayName || peer.name || peer.subject || "Peer";
          const y = 24 + index * 28;
          return (
            <g key={`${peer.direction}-${peer.fingerprint || peer.name || index}`} data-testid="gateway-network-peer">
              <line x1={index % 2 === 0 ? 130 : 230} y1="74" x2={index % 2 === 0 ? 40 : 320} y2={y} stroke="currentColor" />
              <text x={index % 2 === 0 ? 8 : 248} y={y + 4}>
                {label}
              </text>
            </g>
          );
        })}
      </svg>
      <section data-testid="gateway-network-diagnostics">
        <h3>Connection diagnostics</h3>
        <p>
          Protocol {status?.protocolVersion ?? "unknown"}.
          {status?.fingerprint ? (
            <>
              {" "}
              Certificate <code data-testid="gateway-network-fingerprint">{status.fingerprint}</code>
            </>
          ) : null}
        </p>
        {peers.length === 0 ? <p>No peer diagnostics yet.</p> : null}
        {peers.map((peer) => (
          <p key={`diag-${peer.direction}-${peer.fingerprint || peer.name}`}>
            {(peer.gatewayName || peer.name || "Peer") +
              `: ${peer.status || peer.state || "unknown"}` +
              (peer.pingMillis != null ? `, ping ${peer.pingMillis} ms` : "") +
              (peer.protocolVersion != null ? `, protocol ${peer.protocolVersion}` : "")}
          </p>
        ))}
        {(status?.tasks.length ?? 0) === 0 ? <p>No long-running tasks.</p> : null}
        {status?.tasks.map((task) => (
          <p key={task.id} data-testid="gateway-network-task">
            {task.name} {task.state}
            {task.detail ? `: ${task.detail}` : ""}
          </p>
        ))}
        {canWrite ? (
          <button type="button" disabled={pending} onClick={() => void regenerate()}>
            Regenerate certificate
          </button>
        ) : null}
      </section>
      <p data-testid="gateway-network-status">
        {status
          ? status.listening
            ? `Listening on port ${status.port ?? ""}. Incoming connections stay pending until approved.`
            : "Gateway network is off."
          : "Loading peer network status."}
      </p>
      {error ? <p role="alert">{error}</p> : null}
      {incoming.length === 0 ? <p>No incoming connections.</p> : null}
      {incoming.map((peer) => {
        const fingerprint = peer.fingerprint;
        const waiting = peer.status === "pending" || peer.state === "pending";
        return (
          <div key={fingerprint || peer.subject} data-testid="gateway-incoming">
            <dl className="meta">
              <dt>Gateway</dt>
              <dd>{peer.gatewayName || peer.subject || "Unknown"}</dd>
              <dt>Status</dt>
              <dd>{peer.status || peer.state || "unknown"}</dd>
              <dt>Fingerprint</dt>
              <dd>
                <code>{fingerprint || "none"}</code>
              </dd>
            </dl>
            {canWrite && waiting && fingerprint ? (
              <div>
                <button type="button" disabled={pending} onClick={() => void decide(fingerprint, true)}>
                  Approve
                </button>
                <button type="button" disabled={pending} onClick={() => void decide(fingerprint, false)}>
                  Deny
                </button>
              </div>
            ) : null}
          </div>
        );
      })}
      <ServicePolicies
        policies={status?.policies ?? []}
        canWrite={canWrite}
        pending={pending}
        onSave={async (input) => {
          if (!client) return;
          setPending(true);
          setError(null);
          try {
            setStatus(await client.saveServicePolicy(input));
          } catch (err) {
            setError(errorMessage(err, "Could not save the service policy"));
          } finally {
            setPending(false);
          }
        }}
        onDelete={async (peer, service) => {
          if (!client) return;
          setPending(true);
          setError(null);
          try {
            setStatus(await client.deleteServicePolicy(peer, service));
          } catch (err) {
            setError(errorMessage(err, "Could not delete the service policy"));
          } finally {
            setPending(false);
          }
        }}
      />
    </section>
  );
}

const POLICY_SERVICES = ["tags", "alarms", "history", "audit", "alarm-journal", "tasks", "notifications"] as const;

function ServicePolicies({
  policies,
  canWrite,
  pending,
  onSave,
  onDelete,
}: {
  policies: { peer: string; service: string; access: string; roles: string[] }[];
  canWrite: boolean;
  pending: boolean;
  onSave: (input: { peer: string; service: string; access: string; roles: string[] }) => Promise<void>;
  onDelete: (peer: string, service: string) => Promise<void>;
}) {
  const [peer, setPeer] = useState("*");
  const [service, setService] = useState<string>("tags");
  const [access, setAccess] = useState("read");
  const [roles, setRoles] = useState("");
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("");
  const [jsonError, setJsonError] = useState<string | null>(null);
  return (
    <section aria-label="Service policies">
      <h3>Service policies</h3>
      <div className="row-actions">
        <button
          type="button"
          className="quiet"
          onClick={() => {
            if (jsonMode) {
              setJsonMode(false);
              setJsonError(null);
              return;
            }
            setJsonText(JSON.stringify(policies, null, 2));
            setJsonMode(true);
          }}
        >
          {jsonMode ? "Edit as form" : "Edit as JSON"}
        </button>
      </div>
      {jsonMode ? (
        <form
          className="stack-form"
          aria-label="Service policies JSON"
          onSubmit={(event) => {
            event.preventDefault();
            let parsed: unknown;
            try {
              parsed = JSON.parse(jsonText) as unknown;
            } catch {
              setJsonError("The JSON could not be read.");
              return;
            }
            if (!Array.isArray(parsed)) {
              setJsonError("The policies JSON must be a list.");
              return;
            }
            const next = parsed.map(readPolicyJson);
            if (next.some((item) => item == null)) {
              setJsonError("Each policy needs a peer, a service, and access of allow, read, or deny.");
              return;
            }
            const saved = next.filter((item): item is { peer: string; service: string; access: string; roles: string[] } => item != null);
            setJsonError(null);
            void (async () => {
              for (const policy of saved) await onSave(policy);
              for (const policy of policies) {
                if (!saved.some((item) => item.peer === policy.peer && item.service === policy.service)) {
                  await onDelete(policy.peer, policy.service);
                }
              }
              setJsonMode(false);
            })();
          }}
        >
          <label>
            Policies JSON
            <textarea className="json-editor" aria-label="Policies JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} />
          </label>
          {jsonError ? <p role="alert">{jsonError}</p> : null}
          {canWrite ? <button type="submit" disabled={pending}>Save policies JSON</button> : null}
        </form>
      ) : null}
      {jsonMode ? null : (
      <>
      <p>Each rule says whether a remote gateway may use one service. The peer is a certificate fingerprint, a gateway name, or * for every approved peer. Allow includes changes. Read is query only. Deny blocks the service.</p>
      {policies.length === 0 ? <p>No service policies. Remote tag reads stay allowed, and the other services stay closed, until you add a rule.</p> : (
        <table className="data-table" aria-label="Service policy list">
          <thead>
            <tr><th>Peer</th><th>Service</th><th>Access</th><th>Roles</th><th></th></tr>
          </thead>
          <tbody>
            {policies.map((policy) => (
              <tr key={`${policy.peer}:${policy.service}`}>
                <td>{policy.peer}</td>
                <td>{policy.service}</td>
                <td>{policy.access}</td>
                <td>{policy.roles.join(", ")}</td>
                <td>
                  {canWrite ? (
                    <button type="button" className="quiet" disabled={pending} onClick={() => void onDelete(policy.peer, policy.service)}>
                      Delete {policy.service} for {policy.peer}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite ? (
        <form
          className="inline-form"
          aria-label="New service policy"
          onSubmit={(event) => {
            event.preventDefault();
            const next = peer.trim();
            if (!next) return;
            void onSave({
              peer: next,
              service,
              access,
              roles: roles.split(",").map((item) => item.trim()).filter(Boolean),
            });
          }}
        >
          <label>
            Peer
            <input aria-label="Policy peer" value={peer} onChange={(event) => setPeer(event.target.value)} />
          </label>
          <label>
            Service
            <select aria-label="Policy service" value={service} onChange={(event) => setService(event.target.value)}>
              {POLICY_SERVICES.map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </label>
          <label>
            Access
            <select aria-label="Policy access" value={access} onChange={(event) => setAccess(event.target.value)}>
              <option value="allow">Allow</option>
              <option value="read">Read</option>
              <option value="deny">Deny</option>
            </select>
          </label>
          <label>
            Roles
            <input aria-label="Policy roles" value={roles} placeholder="operator, viewer" onChange={(event) => setRoles(event.target.value)} />
          </label>
          <button type="submit" disabled={pending || peer.trim() === ""}>Save policy</button>
        </form>
      ) : null}
      </>
      )}
    </section>
  );
}

function readPolicyJson(value: unknown): { peer: string; service: string; access: string; roles: string[] } | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  const peer = typeof body.peer === "string" ? body.peer.trim() : "";
  const service = typeof body.service === "string" ? body.service : "";
  const access = typeof body.access === "string" ? body.access : "";
  if (!peer || !POLICY_SERVICES.includes(service as (typeof POLICY_SERVICES)[number]) || !["allow", "read", "deny"].includes(access)) return null;
  const roles = Array.isArray(body.roles) ? body.roles.filter((item): item is string => typeof item === "string") : [];
  return { peer, service, access, roles };
}

function PemForm({
  pending,
  onSubmit,
}: {
  pending: boolean;
  onSubmit: (certificate: File, key: File) => Promise<void>;
}) {
  const [certificate, setCertificate] = useState<File | null>(null);
  const [key, setKey] = useState<File | null>(null);
  return (
    <form
      className="stack-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (!certificate || !key) return;
        const form = event.currentTarget;
        void onSubmit(certificate, key).then(() => {
          setCertificate(null);
          setKey(null);
          form.reset();
        });
      }}
    >
      <h2>Upload PEM</h2>
      <label>
        Certificate chain
        <input
          type="file"
          accept=".pem,.crt,.cer"
          onChange={(event) => setCertificate(event.target.files?.[0] ?? null)}
        />
      </label>
      <label>
        Private key
        <input type="file" accept=".pem,.key" onChange={(event) => setKey(event.target.files?.[0] ?? null)} />
      </label>
      <button type="submit" disabled={pending || !certificate || !key}>
        Upload PEM
      </button>
    </form>
  );
}

function Pkcs12Form({
  pending,
  onSubmit,
}: {
  pending: boolean;
  onSubmit: (file: File, password: string) => Promise<void>;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  return (
    <form
      className="stack-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (!file) return;
        const form = event.currentTarget;
        const current = password;
        void onSubmit(file, current).then(() => {
          setFile(null);
          setPassword("");
          form.reset();
        });
      }}
    >
      <h2>Upload PKCS#12</h2>
      <label>
        PKCS#12 file
        <input
          type="file"
          accept=".p12,.pfx"
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
        />
      </label>
      <label>
        Password
        <input type="password" value={password} autoComplete="off" onChange={(event) => setPassword(event.target.value)} />
      </label>
      <button type="submit" disabled={pending || !file}>
        Upload PKCS#12
      </button>
    </form>
  );
}
