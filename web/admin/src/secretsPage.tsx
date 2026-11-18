import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import { ApiError } from "./api";
import type { AdminPageProps } from "./pages";

const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

interface SecretRow {
  name: string;
  description: string;
  reference: string;
}

/** SEC-023: save a secret and copy its reference. The value is not shown again. */
export function SecretsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [rows, setRows] = useState<SecretRow[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [value, setValue] = useState("");
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("{}");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function load() {
    if (!client) return;
    setRows(await client.secrets());
  }

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    load().catch((err: unknown) => {
      if (!cancelled) setError(errorMessage(err, "Could not load secrets"));
    });
    return () => {
      cancelled = true;
    };
  }, [client]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!client) return;
    setError(null);
    setNotice(null);
    let nextName = name.trim();
    let nextDescription = description;
    let nextValue = value;
    if (jsonMode) {
      try {
        const parsed = JSON.parse(jsonText) as { name?: string; description?: string; value?: string };
        nextName = (parsed.name ?? nextName).trim();
        nextDescription = parsed.description ?? "";
        nextValue = parsed.value ?? "";
      } catch (err) {
        setError(err instanceof Error ? err.message : "The JSON could not be read");
        return;
      }
    }
    if (!NAME.test(nextName)) {
      setError("Use a short lowercase name. Digits and dashes are allowed.");
      return;
    }
    if (!nextValue) {
      setError("Enter the secret value. It is stored encrypted and is not shown again.");
      return;
    }
    try {
      const saved = await client.saveSecret(nextName, nextValue, nextDescription);
      setValue("");
      setName("");
      setDescription("");
      setJsonMode(false);
      setNotice(`Saved ${saved.reference}. Copy that reference into other settings.`);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? errorMessage(err, "Could not save the secret") : err instanceof Error ? err.message : "Could not save the secret");
    }
  }

  async function remove(secretName: string) {
    if (!client) return;
    setError(null);
    try {
      await client.deleteSecret(secretName);
      setNotice(`Deleted ${secretName}.`);
      await load();
    } catch (err) {
      setError(errorMessage(err, "Could not delete the secret"));
    }
  }

  async function copy(reference: string) {
    try {
      await navigator.clipboard.writeText(reference);
      setNotice(`Copied ${reference}.`);
    } catch {
      setNotice(reference);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>A secret is stored encrypted. Other settings keep the reference, such as secret://db-password, and never the value.</p>
      <p>Rotating the encryption keys is an offline command. Stop the gateway, then run the key rotation command. This page cannot rotate keys while the gateway is running.</p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {canWrite ? (
        <form aria-label="New secret" onSubmit={(event) => void save(event)}>
          <label>
            Name
            <input aria-label="Secret name" value={name} disabled={jsonMode} onChange={(event) => setName(event.target.value)} />
          </label>
          <label>
            Description
            <input aria-label="Description" value={description} disabled={jsonMode} onChange={(event) => setDescription(event.target.value)} />
          </label>
          <label>
            Value
            <input aria-label="Secret value" type="password" autoComplete="new-password" value={value} disabled={jsonMode} onChange={(event) => setValue(event.target.value)} />
          </label>
          <label>
            <input
              type="checkbox"
              checked={jsonMode}
              onChange={(event) => {
                const on = event.target.checked;
                if (on) setJsonText(JSON.stringify({ name, description, value }, null, 2));
                setJsonMode(on);
              }}
            />
            Edit as JSON
          </label>
          {jsonMode ? <textarea aria-label="Secret JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} rows={6} /> : null}
          <button type="submit">Save secret</button>
        </form>
      ) : null}
      <table className="data-table" aria-label="Secrets">
        <thead>
          <tr>
            <th>Name</th>
            <th>Description</th>
            <th>Reference</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.name}>
              <td>{row.name}</td>
              <td>{row.description}</td>
              <td>{row.reference}</td>
              <td>
                <button type="button" onClick={() => void copy(row.reference)}>Copy {row.reference}</button>
                {canWrite ? <button type="button" onClick={() => void remove(row.name)}>Delete {row.name}</button> : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </article>
  );
}
