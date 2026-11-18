import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import type { ApiClient, IdentityProviderRecord, IdpTestResult } from "./api";
import type { AdminPageProps } from "./pages";

/**
 * Identity providers (SEC-014): OIDC and SAML providers users can sign in with. A provider is filled from its
 * discovery document (OIDC issuer) or metadata (SAML), then completed with the client id, the client secret, and role
 * mappings. The client secret goes to the gateway's secret store; this page never receives or shows it, only whether
 * one is set.
 * Doc source: FEATURES SEC-014.
 */
export function IdentityProvidersPage({ section, client, session }: AdminPageProps) {
  const canAdmin = allows(session, "users.admin");
  const [providers, setProviders] = useState<IdentityProviderRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | "new" | null>(null);
  const [pending, setPending] = useState(false);
  const [reload, setReload] = useState(0);
  const [testCheck, setTestCheck] = useState<{ name: string; testId: string; result: IdpTestResult | null } | null>(null);

  useEffect(() => {
    if (!client || !canAdmin) return;
    let cancelled = false;
    client
      .listIdentityProviders()
      .then((next) => {
        if (!cancelled) {
          setProviders(next);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not list identity providers"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, canAdmin, reload]);

  const selected = providers?.find((p) => p.name === editing) ?? null;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        Users can sign in through an OIDC or SAML identity provider. Roles come from the provider&apos;s claims through the role
        mappings below. A client secret is kept in the gateway&apos;s secret store and is never shown here.
      </p>
      {!canAdmin ? <p role="alert">Managing identity providers needs the users.admin permission.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {canAdmin && client ? (
        <>
          {!providers && !error ? <p>Loading identity providers.</p> : null}
          {providers ? (
            <table className="data-table" aria-label="Identity providers">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Display name</th>
                  <th>Type</th>
                  <th>Enabled</th>
                  <th>Client secret</th>
                  <th>Callback path</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {providers.length === 0 ? (
                  <tr>
                    <td colSpan={7}>No identity providers yet.</td>
                  </tr>
                ) : null}
                {providers.map((p) => (
                  <tr key={p.name}>
                    <td>{p.name}</td>
                    <td>{p.displayName}</td>
                    <td>{p.type.toUpperCase()}</td>
                    <td>{p.enabled ? "Yes" : "No"}</td>
                    <td>{p.type === "oidc" ? (p.clientSecretSet ? "Set" : "Not set") : "—"}</td>
                    <td>
                      <code>{p.callbackPath}</code>
                    </td>
                    <td className="row-actions">
                      <button type="button" onClick={() => setEditing(p.name)}>
                        Edit {p.name}
                      </button>
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() => {
                          setError(null);
                          void client
                            .startIdentityProviderTest(p.name)
                            .then((test) => {
                              setTestCheck({ name: p.name, testId: test.testId, result: null });
                              window.open(test.loginPath, "_blank", "noopener");
                            })
                            .catch((err: unknown) => setError(errorMessage(err, "Could not start the test")));
                        }}
                      >
                        Test login {p.name}
                      </button>
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() => {
                          setPending(true);
                          setError(null);
                          void client
                            .deleteIdentityProvider(p.name, p.version)
                            .then(() => {
                              if (editing === p.name) setEditing(null);
                              setReload((n) => n + 1);
                            })
                            .catch((err: unknown) => setError(errorMessage(err, "Could not delete the identity provider")))
                            .finally(() => setPending(false));
                        }}
                      >
                        Delete {p.name}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          {testCheck ? (
            <section aria-label="Setup check" className="stack-form">
              <h2>Setup check for {testCheck.name}</h2>
              <p>Finish the sign-in in the other window, then check what the gateway learned. A failed or stalled login names the setting to fix.</p>
              <button
                type="button"
                disabled={pending}
                onClick={() => {
                  if (!client) return;
                  setPending(true);
                  setError(null);
                  void client
                    .identityProviderTest(testCheck.name, testCheck.testId)
                    .then((result) => setTestCheck({ ...testCheck, result }))
                    .catch((err: unknown) => setError(errorMessage(err, "Could not read the setup check")))
                    .finally(() => setPending(false));
                }}
              >
                Check setup
              </button>
              {testCheck.result ? (
                <div data-testid="idp-setup-check">
                  <p>Status: {testCheck.result.status}{testCheck.result.username ? ` as ${testCheck.result.username}` : ""}</p>
                  {testCheck.result.diagnosis ? (
                    <>
                      <p><strong>{testCheck.result.diagnosis.summary}</strong></p>
                      <p>{testCheck.result.diagnosis.hint}</p>
                    </>
                  ) : testCheck.result.status === "success" ? (
                    <p>The login succeeded. Name {testCheck.result.displayName || "—"}, email {testCheck.result.email || "—"}, roles {testCheck.result.roles.join(", ") || "none"}.</p>
                  ) : (
                    <p>No setup problem to report yet.</p>
                  )}
                </div>
              ) : null}
            </section>
          ) : null}
          {editing === null ? (
            <button type="button" onClick={() => setEditing("new")}>
              Add identity provider
            </button>
          ) : (
            <ProviderForm
              key={selected ? `${selected.name}:${selected.version}` : "new"}
              client={client}
              provider={selected}
              onCancel={() => setEditing(null)}
              onSaved={() => {
                setEditing(null);
                setReload((n) => n + 1);
              }}
            />
          )}
        </>
      ) : null}
    </article>
  );
}

const text = (fields: Record<string, unknown>, key: string): string => (typeof fields[key] === "string" ? (fields[key] as string) : "");
const list = (fields: Record<string, unknown>, key: string): string[] =>
  Array.isArray(fields[key]) ? (fields[key] as unknown[]).filter((v): v is string => typeof v === "string") : [];

/** `value = role-a, role-b` per line. */
export function formatMappings(raw: unknown): string {
  if (!Array.isArray(raw)) return "";
  return raw
    .map((m) => (m && typeof m === "object" ? (m as { value?: unknown; roles?: unknown }) : {}))
    .filter((m) => typeof m.value === "string")
    .map((m) => `${String(m.value)} = ${Array.isArray(m.roles) ? m.roles.join(", ") : ""}`)
    .join("\n");
}

export function parseMappings(textValue: string): { value: string; roles: string[] }[] {
  return textValue
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const at = line.lastIndexOf("=");
      const value = (at < 0 ? line : line.slice(0, at)).trim();
      const roles = at < 0 ? [] : splitList(line.slice(at + 1));
      return { value, roles };
    });
}

function splitList(raw: string): string[] {
  return raw
    .split(/[,\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

const OIDC_URLS: [string, string][] = [
  ["authorizationEndpoint", "Authorization endpoint"],
  ["tokenEndpoint", "Token endpoint"],
  ["jwksUri", "JWK set URL"],
  ["endSessionEndpoint", "End-session endpoint"],
];

function ProviderForm({
  client,
  provider,
  onCancel,
  onSaved,
}: {
  client: ApiClient;
  provider: IdentityProviderRecord | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(provider?.name ?? "");
  const [fields, setFields] = useState<Record<string, unknown>>(provider?.fields ?? { type: "oidc", enabled: true });
  const [mappings, setMappings] = useState(formatMappings(provider?.fields.roleMappings));
  const [secret, setSecret] = useState("");
  const [clearSecret, setClearSecret] = useState(false);
  const [metadata, setMetadata] = useState("");
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [pending, setPending] = useState(false);
  const [mode, setMode] = useState<"form" | "json">("form");
  const [jsonText, setJsonText] = useState("");
  const type = fields.type === "saml" ? "saml" : "oidc";
  const set = (key: string, value: unknown) => setFields((current) => ({ ...current, [key]: value }));

  async function importMetadata() {
    setPending(true);
    setError(null);
    setNote(null);
    try {
      const imported =
        type === "oidc"
          ? await client.importIdentityProvider({ type: "oidc", issuer: text(fields, "issuer") })
          : await client.importIdentityProvider({ type: "saml", metadataXml: metadata });
      setFields((current) => ({ ...current, ...imported }));
      setNote(type === "oidc" ? "Discovery document imported." : "Metadata imported.");
    } catch (err) {
      setError(errorMessage(err, "Could not import the metadata"));
    } finally {
      setPending(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || (!provider && !RESOURCE_NAME.test(name))) return;
    setPending(true);
    setError(null);
    setProblems([]);
    try {
      const body = mode === "json" ? parseProviderJson(jsonText) : { ...fields, roleMappings: parseMappings(mappings) };
      const clientSecret = type !== "oidc" ? undefined : secret.length > 0 ? secret : clearSecret ? "" : undefined;
      await client.saveIdentityProvider(provider?.name ?? name, provider ? provider.version : null, body, clientSecret);
      setSecret("");
      onSaved();
    } catch (err) {
      setError(errorMessage(err, "Could not save the identity provider"));
      const extra = err && typeof err === "object" && "errors" in err ? (err as { errors?: unknown }).errors : undefined;
      setProblems(Array.isArray(extra) ? extra.filter((e): e is string => typeof e === "string") : []);
    } finally {
      setPending(false);
    }
  }

  const certificates = list(fields, "signingCertificates");

  return (
    <form className="stack-form" aria-label={provider ? `Edit identity provider ${provider.name}` : "New identity provider"} onSubmit={(e) => void submit(e)}>
      <h2>{provider ? `Edit ${provider.name}` : "New identity provider"}</h2>
      <div className="row-actions">
        <button
          type="button"
          className="quiet"
          onClick={() => {
            if (mode === "json") {
              try {
                const parsed = parseProviderJson(jsonText);
                setFields(parsed);
                setMappings(formatMappings(parsed.roleMappings));
                setMode("form");
                setError(null);
              } catch (err) {
                setError(err instanceof Error ? err.message : "The JSON could not be read.");
              }
              return;
            }
            setJsonText(JSON.stringify({ ...fields, roleMappings: parseMappings(mappings) }, null, 2));
            setMode("json");
          }}
        >
          {mode === "json" ? "Edit as form" : "Edit as JSON"}
        </button>
      </div>
      {mode === "json" ? (
        <label>
          Provider JSON
          <textarea className="json-editor" aria-label="Provider JSON" value={jsonText} onChange={(e) => setJsonText(e.target.value)} />
        </label>
      ) : null}
      {mode === "form" ? (
      <>
      {provider ? null : (
        <>
          <label>
            Provider name
            <input value={name} autoComplete="off" onChange={(e) => setName(e.target.value)} required />
          </label>
          <label>
            Type
            <select value={type} onChange={(e) => set("type", e.target.value)}>
              <option value="oidc">OpenID Connect</option>
              <option value="saml">SAML 2.0</option>
            </select>
          </label>
        </>
      )}
      <label>
        Display name
        <input value={text(fields, "displayName")} onChange={(e) => set("displayName", e.target.value)} />
      </label>
      <label className="schema-check">
        <input type="checkbox" checked={fields.enabled !== false} onChange={(e) => set("enabled", e.target.checked)} />
        <span>Enabled</span>
      </label>
      {type === "oidc" ? (
        <>
          <label>
            Issuer
            <input value={text(fields, "issuer")} onChange={(e) => set("issuer", e.target.value)} />
          </label>
          <button type="button" disabled={pending || text(fields, "issuer").length === 0} onClick={() => void importMetadata()}>
            Import discovery document
          </button>
          {OIDC_URLS.map(([key, label]) => (
            <label key={key}>
              {label}
              <input value={text(fields, key)} onChange={(e) => set(key, e.target.value)} />
            </label>
          ))}
          <label>
            Client ID
            <input value={text(fields, "clientId")} autoComplete="off" onChange={(e) => set("clientId", e.target.value)} />
          </label>
          <label>
            Client secret
            <input
              type="password"
              autoComplete="new-password"
              value={secret}
              placeholder={provider?.clientSecretSet ? "Stored. Leave blank to keep it." : "None stored"}
              onChange={(e) => setSecret(e.target.value)}
            />
          </label>
          {provider?.clientSecretSet ? (
            <label className="schema-check">
              <input type="checkbox" checked={clearSecret} onChange={(e) => setClearSecret(e.target.checked)} />
              <span>Remove the stored client secret</span>
            </label>
          ) : null}
          <label>
            Scopes
            <input
              value={(Array.isArray(fields.scopes) ? list(fields, "scopes") : ["openid", "profile", "email"]).join(" ")}
              onChange={(e) => set("scopes", splitList(e.target.value))}
            />
          </label>
        </>
      ) : (
        <>
          <label>
            Metadata XML
            <textarea rows={6} value={metadata} onChange={(e) => setMetadata(e.target.value)} />
          </label>
          <button type="button" disabled={pending || metadata.trim().length === 0} onClick={() => void importMetadata()}>
            Import metadata
          </button>
          <label>
            Provider entity ID
            <input value={text(fields, "idpEntityId")} onChange={(e) => set("idpEntityId", e.target.value)} />
          </label>
          <label>
            Single sign-on URL
            <input value={text(fields, "ssoUrl")} onChange={(e) => set("ssoUrl", e.target.value)} />
          </label>
          <label>
            Gateway entity ID
            <input value={text(fields, "spEntityId")} onChange={(e) => set("spEntityId", e.target.value)} />
          </label>
          <p>{certificates.length === 1 ? "1 signing certificate." : `${certificates.length} signing certificates.`}</p>
        </>
      )}
      <label>
        Username claim
        <input
          value={text(fields, "usernameClaim")}
          placeholder={type === "oidc" ? "preferred_username, then sub" : "NameID"}
          onChange={(e) => set("usernameClaim", e.target.value)}
        />
      </label>
      <label>
        Name claim
        <input value={text(fields, "nameClaim")} placeholder="name" onChange={(e) => set("nameClaim", e.target.value)} />
      </label>
      <label>
        Email claim
        <input value={text(fields, "emailClaim")} placeholder="email" onChange={(e) => set("emailClaim", e.target.value)} />
      </label>
      <label>
        Groups claim
        <input value={text(fields, "rolesClaim")} placeholder="groups" onChange={(e) => set("rolesClaim", e.target.value)} />
      </label>
      <p>The groups claim is the list of groups from the provider. Each value is turned into gateway roles by the mappings below. Leave name and email blank to use the provider's usual name and email.</p>
      <label>
        Redirect base URL
        <input value={text(fields, "redirectBaseUrl")} placeholder="https://gateway.example" onChange={(e) => set("redirectBaseUrl", e.target.value)} />
      </label>
      <label>
        Clock skew (seconds)
        <input
          inputMode="numeric"
          value={typeof fields.clockSkewSeconds === "number" ? String(fields.clockSkewSeconds) : ""}
          placeholder="60"
          onChange={(e) => {
            const raw = e.target.value.trim();
            setFields((current) => {
              const next = { ...current };
              if (raw === "") delete next.clockSkewSeconds;
              else if (/^\d+$/.test(raw)) next.clockSkewSeconds = Number(raw);
              return next;
            });
          }}
        />
      </label>
      <label>
        Role mappings (one per line: claim value = role, role)
        <textarea rows={4} value={mappings} onChange={(e) => setMappings(e.target.value)} />
      </label>
      <label>
        Default roles
        <input value={list(fields, "defaultRoles").join(", ")} onChange={(e) => set("defaultRoles", splitList(e.target.value))} />
      </label>
      </>
      ) : null}
      {note ? <p role="status">{note}</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {problems.length > 0 ? (
        <ul aria-label="Problems">
          {problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      ) : null}
      <div className="inline-form">
        <button type="submit" disabled={pending || (!provider && !RESOURCE_NAME.test(name))}>
          {provider ? "Save identity provider" : "Create identity provider"}
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function parseProviderJson(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("The JSON could not be read.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The provider JSON must be an object.");
  return value as Record<string, unknown>;
}
