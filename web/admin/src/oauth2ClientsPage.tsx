import { useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { AdminPageProps } from "./pages";
import type { OAuth2Client, OAuth2ClientInput, OAuth2TokenCheck } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * OAuth2 client registrations (SEC-029): how outbound services such as an OAuth2 email profile obtain access tokens.
 * The client secret and refresh token are typed into write-only fields that go to the gateway secret store (or the
 * secret is picked as a reference to one already stored); the page only shows whether each is set. Verify Token makes
 * one token request and reports the answer without the token; Verify Authorization runs the authorization code flow
 * in a new window and stores the refresh token it returns.
 * Doc source: FEATURES SEC-029.
 */

const NAME = /^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$/;

export interface Draft {
  name: string;
  description: string;
  enabled: boolean;
  clientId: string;
  secretSource: "embedded" | "referenced";
  clientSecret: string;
  clientSecretRef: string;
  authorizationUrl: string;
  tokenUrl: string;
  scope: string;
  grantType: "client_credentials" | "refresh_token";
  refreshToken: string;
  redirectBaseUrl: string;
  httpVersion: "HTTP_2" | "HTTP_1_1";
  connectTimeoutMillis: string;
  requestTimeoutMillis: string;
}

const EMPTY: Draft = {
  name: "",
  description: "",
  enabled: true,
  clientId: "",
  secretSource: "embedded",
  clientSecret: "",
  clientSecretRef: "",
  authorizationUrl: "",
  tokenUrl: "",
  scope: "",
  grantType: "client_credentials",
  refreshToken: "",
  redirectBaseUrl: "",
  httpVersion: "HTTP_2",
  connectTimeoutMillis: "30000",
  requestTimeoutMillis: "120000",
};

function draftOf(client: OAuth2Client): Draft {
  return {
    ...EMPTY,
    name: client.name,
    description: client.description,
    enabled: client.enabled,
    clientId: client.clientId,
    secretSource: client.clientSecretRef ? "referenced" : "embedded",
    clientSecretRef: client.clientSecretRef ?? "",
    authorizationUrl: client.authorizationUrl,
    tokenUrl: client.tokenUrl,
    scope: client.scope,
    grantType: client.grantType,
    redirectBaseUrl: client.redirectBaseUrl,
    httpVersion: client.httpVersion,
    connectTimeoutMillis: String(client.connectTimeoutMillis),
    requestTimeoutMillis: String(client.requestTimeoutMillis),
  };
}

/** Secrets typed in the form are sent; an untouched secret field is left out so the stored one stays. */
export function inputOf(draft: Draft): OAuth2ClientInput {
  const input: OAuth2ClientInput = {
    description: draft.description.trim(),
    enabled: draft.enabled,
    clientId: draft.clientId.trim(),
    authorizationUrl: draft.authorizationUrl.trim(),
    tokenUrl: draft.tokenUrl.trim(),
    scope: draft.scope.trim(),
    grantType: draft.grantType,
    redirectBaseUrl: draft.redirectBaseUrl.trim(),
    httpVersion: draft.httpVersion,
    connectTimeoutMillis: Number(draft.connectTimeoutMillis),
    requestTimeoutMillis: Number(draft.requestTimeoutMillis),
  };
  if (draft.secretSource === "referenced") input.clientSecretRef = draft.clientSecretRef.trim();
  else if (draft.clientSecret) input.clientSecret = draft.clientSecret;
  if (draft.refreshToken) input.refreshToken = draft.refreshToken;
  return input;
}

const GRANT_LABEL: Record<string, string> = {
  client_credentials: "Client credentials",
  refresh_token: "Refresh token",
  authorization_code: "Authorization code",
};

export function OAuth2ClientsPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  const canWrite = session ? allows(session, "gateway.write") : true;
  const api = useStatusApi(client);
  const loaded = useLoad(() => api.oauth2Clients(), [api], { enabled: Boolean(client) && canRead, fallback: "Could not read OAuth2 clients" });
  const [editing, setEditing] = useState<{ draft: Draft; existing: OAuth2Client | null } | null>(null);
  const [verifying, setVerifying] = useState<OAuth2Client | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  async function run(action: () => Promise<void>, done: string) {
    setFailure(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
      loaded.reload();
    } catch (err) {
      setFailure(errorMessage(err, "The request failed"));
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    const { draft, existing } = editing;
    if (!existing && !NAME.test(draft.name)) {
      setFailure("The name is lowercase letters, digits and hyphens, 1 to 40 characters.");
      return;
    }
    await run(async () => {
      await api.saveOAuth2Client(draft.name, inputOf(draft));
      setEditing(null);
    }, `Saved ${draft.name}.`);
  }

  async function authorize(item: OAuth2Client) {
    // Opened before the request, so the browser treats it as the user's own window rather than a pop-up.
    const opened = window.open("", "_blank");
    await run(async () => {
      try {
        const url = await api.authorizeOAuth2Client(item.name, {});
        if (opened) opened.location.href = url;
        else window.location.assign(url);
      } catch (err) {
        opened?.close();
        throw err;
      }
    }, `Authorization for ${item.name} opened in a new window. Its refresh token is stored when the provider sends you back.`);
  }

  const items = loaded.data ?? [];
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>
        OAuth2 clients let outbound services, such as an email profile that logs in with OAuth2, get access tokens from an
        authorization server. One client can serve several email profiles. Client secrets and refresh tokens are kept in the
        gateway secret store and are never shown.
      </p>
      {!canRead ? (
        <p role="status" data-testid="oauth2-no-read">
          Your roles do not include gateway.read, so OAuth2 clients are hidden.
        </p>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
      {failure ? <p role="alert">{failure}</p> : null}
      {canRead && loaded.error ? <p role="alert">{loaded.error}</p> : null}
      {canRead && loaded.data ? (
        <table className="data-table" aria-label="OAuth2 clients" data-testid="oauth2-clients">
          <thead>
            <tr>
              <th>Name</th>
              <th>Client id</th>
              <th>Token URL</th>
              <th>Grant</th>
              <th>Secrets</th>
              <th>Used by</th>
              <th>State</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={8} data-testid="oauth2-empty">
                  No OAuth2 client is configured.
                </td>
              </tr>
            ) : null}
            {items.map((item) => (
              <tr key={item.name} data-testid={`oauth2-client-${item.name}`}>
                <td>
                  {item.name}
                  {item.description ? <div className="muted">{item.description}</div> : null}
                </td>
                <td>{item.clientId}</td>
                <td>{item.tokenUrl}</td>
                <td>{GRANT_LABEL[item.grantType]}</td>
                <td>
                  {item.clientSecretSet ? (item.clientSecretRef ? `Client secret (${item.clientSecretRef})` : "Client secret set") : "No client secret"}
                  {item.refreshTokenSet ? ", refresh token set" : ""}
                </td>
                <td>{item.usedBy.length > 0 ? item.usedBy.join(", ") : "None"}</td>
                <td>
                  {item.enabled ? "Enabled" : "Disabled"}
                  {item.problems.length > 0 ? <div className="muted">{item.problems.join("; ")}</div> : null}
                </td>
                <td className="row-actions">
                  {canWrite ? (
                    <>
                      <button type="button" className="quiet" onClick={() => setEditing({ draft: draftOf(item), existing: item })}>
                        {`Edit ${item.name}`}
                      </button>
                      <button type="button" className="quiet" onClick={() => setVerifying(item)}>
                        {`Verify token for ${item.name}`}
                      </button>
                      {item.authorizationUrl ? (
                        <button type="button" className="quiet" onClick={() => void authorize(item)}>
                          {`Verify authorization for ${item.name}`}
                        </button>
                      ) : null}
                      <button
                        type="button"
                        className="quiet"
                        onClick={() => void run(() => api.deleteOAuth2Client(item.name), `Deleted ${item.name}.`)}
                      >
                        {`Delete ${item.name}`}
                      </button>
                    </>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {canWrite && canRead && !editing ? (
        <p>
          <button type="button" onClick={() => setEditing({ draft: EMPTY, existing: null })}>
            Create OAuth2 client
          </button>
        </p>
      ) : null}
      {editing ? (
        <ClientForm
          draft={editing.draft}
          existing={editing.existing}
          onChange={(draft) => setEditing({ ...editing, draft })}
          onCancel={() => setEditing(null)}
          onSubmit={(event) => void submit(event)}
        />
      ) : null}
      {verifying ? <VerifyToken item={verifying} api={api} onClose={() => setVerifying(null)} /> : null}
    </article>
  );
}

function VerifyToken({ item, api, onClose }: { item: OAuth2Client; api: ReturnType<typeof useStatusApi>; onClose: () => void }) {
  const [grantType, setGrantType] = useState<string>(item.grantType);
  const [scope, setScope] = useState(item.scope);
  const [code, setCode] = useState("");
  const [redirectUri, setRedirectUri] = useState(item.redirectUri ?? "");
  const [refreshToken, setRefreshToken] = useState("");
  const [result, setResult] = useState<OAuth2TokenCheck | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  async function verify(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    setResult(null);
    try {
      const body: Parameters<typeof api.verifyOAuth2Token>[1] = { grantType, scope: scope.trim() };
      if (grantType === "authorization_code") {
        body.code = code.trim();
        body.redirectUri = redirectUri.trim();
      }
      if (grantType === "refresh_token" && refreshToken) body.refreshToken = refreshToken;
      setResult(await api.verifyOAuth2Token(item.name, body));
    } catch (err) {
      setFailure(errorMessage(err, "The token request failed"));
    }
  }

  return (
    <form className="stack-form" aria-label={`Verify token for ${item.name}`} onSubmit={(event) => void verify(event)}>
      <h2>{`Verify token for ${item.name}`}</h2>
      <label>
        Grant type
        <select aria-label="Grant type" value={grantType} onChange={(event) => setGrantType(event.target.value)}>
          {Object.entries(GRANT_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Scope
        <input aria-label="Verify scope" value={scope} onChange={(event) => setScope(event.target.value)} />
      </label>
      {grantType === "authorization_code" ? (
        <>
          <label>
            Authorization code
            <input value={code} onChange={(event) => setCode(event.target.value)} required />
          </label>
          <label>
            Redirect URL
            <input value={redirectUri} onChange={(event) => setRedirectUri(event.target.value)} required />
          </label>
        </>
      ) : null}
      {grantType === "refresh_token" ? (
        <label>
          Refresh token
          <input
            type="password"
            aria-label="Verify refresh token"
            value={refreshToken}
            autoComplete="off"
            placeholder={item.refreshTokenSet ? "Empty uses the stored refresh token." : ""}
            onChange={(event) => setRefreshToken(event.target.value)}
          />
        </label>
      ) : null}
      <div className="row-actions">
        <button type="submit">Request token</button>
        <button type="button" className="quiet" onClick={onClose}>
          Close
        </button>
      </div>
      {failure ? <p role="alert">{failure}</p> : null}
      {result ? (
        <dl data-testid="oauth2-token-result">
          <dt>Result</dt>
          <dd>Token received</dd>
          <dt>Token type</dt>
          <dd>{result.tokenType || "not given"}</dd>
          <dt>Expires in</dt>
          <dd>{`${result.expiresInSeconds} s (${result.expiresAt})`}</dd>
          <dt>Scope</dt>
          <dd>{result.scope || "not given"}</dd>
          <dt>Refresh token</dt>
          <dd>{result.refreshTokenReceived ? "Received" : "Not received"}</dd>
          <dt>Received at</dt>
          <dd>{result.receivedAt}</dd>
        </dl>
      ) : null}
    </form>
  );
}

function ClientForm({
  draft,
  existing,
  onChange,
  onCancel,
  onSubmit,
}: {
  draft: Draft;
  existing: OAuth2Client | null;
  onChange: (draft: Draft) => void;
  onCancel: () => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => onChange({ ...draft, [key]: value });
  const title = existing ? `Edit ${existing.name}` : "Create OAuth2 client";
  return (
    <form className="stack-form" aria-label={title} onSubmit={onSubmit}>
      <h2>{title}</h2>
      <label>
        Name
        <input value={draft.name} disabled={Boolean(existing)} onChange={(event) => set("name", event.target.value)} required />
      </label>
      <label>
        Description
        <input value={draft.description} onChange={(event) => set("description", event.target.value)} />
      </label>
      <label>
        <input type="checkbox" checked={draft.enabled} onChange={(event) => set("enabled", event.target.checked)} />
        Enabled
      </label>
      <label>
        Client ID
        <input value={draft.clientId} autoComplete="off" onChange={(event) => set("clientId", event.target.value)} />
      </label>
      <label>
        Client secret source
        <select
          aria-label="Client secret source"
          value={draft.secretSource}
          onChange={(event) => set("secretSource", event.target.value as Draft["secretSource"])}
        >
          <option value="embedded">Embedded</option>
          <option value="referenced">Referenced</option>
        </select>
      </label>
      {draft.secretSource === "embedded" ? (
        <label>
          Client secret
          <input
            type="password"
            value={draft.clientSecret}
            autoComplete="new-password"
            placeholder={existing?.clientSecretSet && !existing.clientSecretRef ? "Set. Leave empty to keep it." : ""}
            onChange={(event) => set("clientSecret", event.target.value)}
          />
        </label>
      ) : (
        <label>
          Secret reference
          <input value={draft.clientSecretRef} placeholder="secret://name" onChange={(event) => set("clientSecretRef", event.target.value)} />
        </label>
      )}
      <label>
        Authorization URL
        <input value={draft.authorizationUrl} onChange={(event) => set("authorizationUrl", event.target.value)} />
      </label>
      <label>
        Token URL
        <input value={draft.tokenUrl} onChange={(event) => set("tokenUrl", event.target.value)} />
      </label>
      <label>
        Scope
        <input value={draft.scope} onChange={(event) => set("scope", event.target.value)} />
      </label>
      <label>
        Grant type
        <select aria-label="Token grant" value={draft.grantType} onChange={(event) => set("grantType", event.target.value as Draft["grantType"])}>
          <option value="client_credentials">Client credentials</option>
          <option value="refresh_token">Refresh token (from authorization)</option>
        </select>
      </label>
      <label>
        Refresh token
        <input
          type="password"
          value={draft.refreshToken}
          autoComplete="new-password"
          placeholder={existing?.refreshTokenSet ? "Set. Leave empty to keep it." : "Stored by Verify authorization, or enter one."}
          onChange={(event) => set("refreshToken", event.target.value)}
        />
      </label>
      <label>
        Redirect base URL
        <input value={draft.redirectBaseUrl} placeholder="This gateway's address" onChange={(event) => set("redirectBaseUrl", event.target.value)} />
      </label>
      {existing?.redirectUri ? (
        <p data-testid="oauth2-redirect">
          Redirect URI to register with the identity provider: <code>{existing.redirectUri}</code>
        </p>
      ) : null}
      <fieldset>
        <legend>HTTP client settings</legend>
        <label>
          HTTP version
          <select aria-label="HTTP version" value={draft.httpVersion} onChange={(event) => set("httpVersion", event.target.value as Draft["httpVersion"])}>
            <option value="HTTP_2">HTTP/2</option>
            <option value="HTTP_1_1">HTTP/1.1</option>
          </select>
        </label>
        <label>
          HTTP connect timeout (ms)
          <input type="number" value={draft.connectTimeoutMillis} onChange={(event) => set("connectTimeoutMillis", event.target.value)} />
        </label>
        <label>
          HTTP request timeout (ms)
          <input type="number" value={draft.requestTimeoutMillis} onChange={(event) => set("requestTimeoutMillis", event.target.value)} />
        </label>
      </fieldset>
      <div className="row-actions">
        <button type="submit">{existing ? "Save OAuth2 client" : "Create OAuth2 client"}</button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
