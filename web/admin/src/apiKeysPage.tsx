import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import type { ApiClient, ApiKeyAccount } from "./api";
import type { AdminPageProps } from "./pages";
import { enumList } from "./schema";

/**
 * Create, rotate, and revoke API keys. The secret is returned once by create and rotate
 * and is not stored in this page after it is dismissed.
 * (FEATURES SEC-005).
 */
export function ApiKeysPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "apikeys.admin");
  const [items, setItems] = useState<ApiKeyAccount[] | null>(null);
  const [scopes, setScopes] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [revealed, setRevealed] = useState<{ name: string; secret: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .listApiKeys()
      .then((next) => {
        if (cancelled) return;
        setItems(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setItems(null);
        setError(errorMessage(err, "Could not list API keys"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  useEffect(() => {
    if (!client || !canWrite) return;
    let cancelled = false;
    client
      .getSchema("org.oip.security", "ApiKey")
      .then((schema) => {
        if (!cancelled) setScopes(enumList(schema, "scopes"));
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load API key scopes"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, canWrite]);

  function showSecret(account: ApiKeyAccount, secret: string) {
    setRevealed({ name: account.name, secret });
    setCopied(false);
    setItems((current) => mergeKey(current, account));
    setReloadToken((token) => token + 1);
  }

  async function rotate(account: ApiKeyAccount) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    try {
      const issued = await client.rotateApiKey(account.name, account.version);
      showSecret(issued.account, issued.secret);
    } catch (err) {
      setError(errorMessage(err, "Could not rotate the API key"));
    } finally {
      setPending(false);
    }
  }

  async function revoke(account: ApiKeyAccount) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    try {
      await client.revokeApiKey(account.name, account.version);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not revoke the API key"));
    } finally {
      setPending(false);
    }
  }

  async function copySecret() {
    if (!revealed) return;
    try {
      await writeClipboard(revealed.secret);
      setCopied(true);
    } catch (err) {
      setCopied(false);
      setError(errorMessage(err, "Could not copy the secret"));
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">A key secret is shown once, when the key is created or rotated. Later reads do not include it.</p>
      {error ? <p role="alert">{error}</p> : null}
      {revealed ? (
        <section className="secret-once" data-testid="key-secret" aria-label="API key secret">
          <h2>Secret for {revealed.name}</h2>
          <p>Copy this secret now. Closing it removes it from this page, and the gateway will not show it again.</p>
          <p data-testid="key-secret-value">{revealed.secret}</p>
          <div className="inline-form">
            <button type="button" onClick={() => void copySecret()}>
              Copy secret
            </button>
            {copied ? <p role="status">Copied.</p> : null}
            <button type="button" onClick={() => setRevealed(null)}>
              Done
            </button>
          </div>
        </section>
      ) : null}
      {canWrite && client ? (
        <CreateKeyForm
          client={client}
          scopes={scopes}
          pending={pending}
          onPending={setPending}
          onError={setError}
          onCreated={(account, secret) => showSecret(account, secret)}
        />
      ) : null}
      {!items && !error ? <p>Loading API keys.</p> : null}
      {items ? (
        <table className="data-table" aria-label="API keys">
          <thead>
            <tr>
              <th>Name</th>
              <th>Scopes</th>
              <th>State</th>
              <th>Key id</th>
              {canWrite ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={canWrite ? 5 : 4}>No API keys.</td>
              </tr>
            ) : null}
            {items.map((account) => (
              <tr key={account.name}>
                <td>{account.name}</td>
                <td>{account.scopes.join(", ") || "none"}</td>
                <td>{account.enabled ? "Enabled" : "Disabled"}</td>
                <td>{account.keyId}</td>
                {canWrite ? (
                  <td className="row-actions">
                    <button type="button" disabled={pending} onClick={() => void rotate(account)}>
                      Rotate {account.name}
                    </button>
                    <button type="button" disabled={pending || !account.enabled} onClick={() => void revoke(account)}>
                      Revoke {account.name}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </article>
  );
}

function CreateKeyForm({
  client,
  scopes,
  pending,
  onPending,
  onError,
  onCreated,
}: {
  client: ApiClient;
  scopes: string[];
  pending: boolean;
  onPending: (pending: boolean) => void;
  onError: (message: string | null) => void;
  onCreated: (account: ApiKeyAccount, secret: string) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [secure, setSecure] = useState(true);
  const nameError = name.length > 0 && !RESOURCE_NAME.test(name) ? "Use lowercase letters, digits, and hyphens." : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || !RESOURCE_NAME.test(name)) return;
    onPending(true);
    onError(null);
    try {
      const issued = await client.createApiKey({
        name,
        description,
        scopes: selected,
        secureConnectionRequired: secure,
      });
      setName("");
      setDescription("");
      setSelected([]);
      onCreated(issued.account, issued.secret);
    } catch (err) {
      onError(errorMessage(err, "Could not create the API key"));
    } finally {
      onPending(false);
    }
  }

  return (
    <form className="stack-form" aria-label="Create API key" onSubmit={(event) => void submit(event)}>
      <label>
        Name
        <input value={name} onChange={(event) => setName(event.target.value)} autoComplete="off" required />
      </label>
      {nameError ? <p role="alert">{nameError}</p> : null}
      <label>
        Description
        <input value={description} onChange={(event) => setDescription(event.target.value)} />
      </label>
      <fieldset>
        <legend>Scopes</legend>
        {scopes.length === 0 ? <p>Loading scopes from the API key schema.</p> : null}
        {scopes.map((scope) => (
          <label className="schema-check" key={scope}>
            <input
              type="checkbox"
              checked={selected.includes(scope)}
              onChange={(event) =>
                setSelected((current) =>
                  event.target.checked ? [...current, scope] : current.filter((item) => item !== scope),
                )
              }
            />
            <span>{scope}</span>
          </label>
        ))}
      </fieldset>
      <label className="schema-check">
        <input type="checkbox" checked={secure} onChange={(event) => setSecure(event.target.checked)} />
        <span>Require a secure connection</span>
      </label>
      <button type="submit" disabled={pending || !RESOURCE_NAME.test(name)}>
        Create API key
      </button>
    </form>
  );
}

function mergeKey(current: ApiKeyAccount[] | null, account: ApiKeyAccount): ApiKeyAccount[] {
  if (!current) return [account];
  const exists = current.some((item) => item.name === account.name);
  if (!exists) return [...current, account].sort((a, b) => a.name.localeCompare(b.name));
  return current.map((item) => (item.name === account.name ? account : item));
}

async function writeClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Some browsers reject the async clipboard on a non-secure origin. Fall through.
    }
  }
  const area = document.createElement("textarea");
  area.value = text;
  document.body.appendChild(area);
  area.select();
  const ok = document.execCommand("copy");
  area.remove();
  if (!ok) throw new Error("Could not copy the secret");
}
