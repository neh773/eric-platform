import { useState, type FormEvent } from "react";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import type { AdminPageProps } from "./pages";
import type { EmailProfile, EmailProfileInput } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * SMTP email profiles, configured once and reused by alarm notification, scheduled reports and gateway scripts.
 * The password, OAuth2 client secret and refresh token are typed into write-only fields: they go to the gateway's
 * secret store and are never shown again, only whether each one is set.
 * Doc source: FEATURES GAT-030.
 */

export interface Draft {
  name: string;
  hostname: string;
  port: string;
  security: "none" | "starttls" | "ssl";
  authType: "password" | "oauth2";
  username: string;
  password: string;
  fromAddress: string;
  enabled: boolean;
  oauthTokenUrl: string;
  oauthClientId: string;
  oauthClientSecret: string;
  oauthRefreshToken: string;
  oauthScope: string;
  /** SEC-029: an OAuth2 client registration that supplies the token. */
  oauthClient: string;
}

const EMPTY: Draft = {
  name: "",
  hostname: "",
  port: "587",
  security: "starttls",
  authType: "password",
  username: "",
  password: "",
  fromAddress: "",
  enabled: true,
  oauthTokenUrl: "",
  oauthClientId: "",
  oauthClientSecret: "",
  oauthRefreshToken: "",
  oauthScope: "",
  oauthClient: "",
};

function draftOf(profile: EmailProfile): Draft {
  return {
    name: profile.name,
    hostname: profile.hostname,
    port: String(profile.port),
    security: profile.useSsl ? "ssl" : profile.startTls ? "starttls" : "none",
    authType: profile.authType,
    username: profile.username,
    password: "",
    fromAddress: profile.fromAddress,
    enabled: profile.enabled,
    oauthTokenUrl: profile.oauthTokenUrl,
    oauthClientId: profile.oauthClientId,
    oauthClientSecret: "",
    oauthRefreshToken: "",
    oauthScope: profile.oauthScope,
    oauthClient: profile.oauthClient,
  };
}

/** Secrets typed in the form are sent; an untouched secret field is left out so the stored one stays. */
export function inputOf(draft: Draft): EmailProfileInput {
  const input: EmailProfileInput = {
    enabled: draft.enabled,
    hostname: draft.hostname.trim(),
    port: Number(draft.port),
    useSsl: draft.security === "ssl",
    startTls: draft.security === "starttls",
    username: draft.username.trim(),
    fromAddress: draft.fromAddress.trim(),
    authType: draft.authType,
  };
  if (draft.password) input.password = draft.password;
  if (draft.authType === "oauth2") {
    input.oauthTokenUrl = draft.oauthTokenUrl.trim();
    input.oauthClientId = draft.oauthClientId.trim();
    input.oauthScope = draft.oauthScope.trim();
    input.oauthClient = draft.oauthClient.trim();
    if (draft.oauthClientSecret) input.oauthClientSecret = draft.oauthClientSecret;
    if (draft.oauthRefreshToken) input.oauthRefreshToken = draft.oauthRefreshToken;
  }
  return input;
}

export function EmailProfilesPage({ section, client, session }: AdminPageProps) {
  const canRead = session ? allows(session, "gateway.read") : true;
  const canWrite = session ? allows(session, "gateway.write") : true;
  const api = useStatusApi(client);
  const loaded = useLoad(() => api.emailProfiles(), [api], {
    enabled: Boolean(client) && canRead,
    fallback: "Could not read email profiles",
  });
  const [editing, setEditing] = useState<{ draft: Draft; existing: EmailProfile | null } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [testTo, setTestTo] = useState("");

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
    if (!existing && !RESOURCE_NAME.test(draft.name)) {
      setFailure("The name is lowercase letters, digits and hyphens, 1 to 63 characters.");
      return;
    }
    await run(async () => {
      await api.saveEmailProfile(draft.name, inputOf(draft));
      setEditing(null);
    }, `Saved ${draft.name}.`);
  }

  const profiles = loaded.data ?? [];

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p>
        SMTP profiles are set up here once. Alarm notification pipelines, scheduled reports and gateway scripts pick a
        profile by name. Passwords and OAuth2 secrets are kept in the gateway secret store and are never shown.
      </p>
      {!canRead ? (
        <p role="status" data-testid="email-no-read">
          Your roles do not include gateway.read, so email profiles are hidden.
        </p>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
      {failure ? <p role="alert">{failure}</p> : null}
      {canRead && loaded.error ? <p role="alert">{loaded.error}</p> : null}
      {canRead && loaded.data ? (
        <table className="data-table" aria-label="Email profiles" data-testid="email-profiles">
          <thead>
            <tr>
              <th>Name</th>
              <th>Server</th>
              <th>Security</th>
              <th>Login</th>
              <th>Sender</th>
              <th>State</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {profiles.length === 0 ? (
              <tr>
                <td colSpan={7} data-testid="email-empty">
                  No email profile is configured.
                </td>
              </tr>
            ) : null}
            {profiles.map((profile) => (
              <tr key={profile.name} data-testid={`email-profile-${profile.name}`}>
                <td>{profile.name}</td>
                <td>
                  {profile.hostname}:{profile.port}
                </td>
                <td>{profile.useSsl ? "SSL" : profile.startTls ? "STARTTLS" : "none"}</td>
                <td>
                  {profile.authType === "oauth2" ? "OAuth2" : profile.username ? "Password" : "None"}
                  {profile.passwordSet || profile.clientSecretSet ? " (secret set)" : ""}
                </td>
                <td>{profile.fromAddress}</td>
                <td>{profile.enabled ? "Enabled" : "Disabled"}</td>
                <td className="row-actions">
                  {canWrite ? (
                    <>
                      <button type="button" className="quiet" onClick={() => setEditing({ draft: draftOf(profile), existing: profile })}>
                        {`Edit ${profile.name}`}
                      </button>
                      <button
                        type="button"
                        className="quiet"
                        onClick={() => void run(() => api.deleteEmailProfile(profile.name), `Deleted ${profile.name}.`)}
                      >
                        {`Delete ${profile.name}`}
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
            New email profile
          </button>
        </p>
      ) : null}
      {canWrite && canRead && profiles.length > 0 ? (
        <div role="group" className="inline-form" aria-label="Send a test message">
          <label>
            Test recipient
            <input type="email" value={testTo} onChange={(event) => setTestTo(event.target.value)} />
          </label>
          <TestProfilePicker
            profiles={profiles}
            disabled={!testTo.trim()}
            onSend={(name) => void run(() => api.testEmailProfile(name, testTo.trim()), `Test message sent through ${name}.`)}
          />
        </div>
      ) : null}
      {editing ? (
        <ProfileForm
          draft={editing.draft}
          existing={editing.existing}
          onChange={(draft) => setEditing({ ...editing, draft })}
          onCancel={() => setEditing(null)}
          onSubmit={(event) => void submit(event)}
        />
      ) : null}
    </article>
  );
}

function TestProfilePicker({ profiles, onSend, disabled }: { profiles: EmailProfile[]; onSend: (name: string) => void; disabled: boolean }) {
  const [name, setName] = useState(profiles[0]?.name ?? "");
  const chosen = profiles.some((item) => item.name === name) ? name : (profiles[0]?.name ?? "");
  return (
    <>
      <label>
        Test profile
        <select aria-label="Test profile" value={chosen} onChange={(event) => setName(event.target.value)}>
          {profiles.map((item) => (
            <option key={item.name} value={item.name}>
              {item.name}
            </option>
          ))}
        </select>
      </label>
      <button type="button" disabled={disabled} onClick={() => onSend(chosen)}>
        Send test message
      </button>
    </>
  );
}

function ProfileForm({
  draft,
  existing,
  onChange,
  onCancel,
  onSubmit,
}: {
  draft: Draft;
  existing: EmailProfile | null;
  onChange: (draft: Draft) => void;
  onCancel: () => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => onChange({ ...draft, [key]: value });
  const oauth = draft.authType === "oauth2";
  return (
    <form className="stack-form" aria-label={existing ? `Edit ${existing.name}` : "New email profile"} onSubmit={onSubmit}>
      <h2>{existing ? `Edit ${existing.name}` : "New email profile"}</h2>
      <label>
        Profile name
        <input value={draft.name} disabled={Boolean(existing)} onChange={(event) => set("name", event.target.value)} required />
      </label>
      <label>
        SMTP host
        <input value={draft.hostname} onChange={(event) => set("hostname", event.target.value)} required />
      </label>
      <label>
        Port
        <input type="number" min={1} max={65535} value={draft.port} onChange={(event) => set("port", event.target.value)} required />
      </label>
      <label>
        Connection security
        <select aria-label="Connection security" value={draft.security} onChange={(event) => set("security", event.target.value as Draft["security"])}>
          <option value="none">None</option>
          <option value="starttls">STARTTLS</option>
          <option value="ssl">SSL</option>
        </select>
      </label>
      <label>
        Login method
        <select aria-label="Login method" value={draft.authType} onChange={(event) => set("authType", event.target.value as Draft["authType"])}>
          <option value="password">Username and password</option>
          <option value="oauth2">OAuth2 access token</option>
        </select>
      </label>
      <label>
        Username
        <input value={draft.username} autoComplete="off" onChange={(event) => set("username", event.target.value)} />
      </label>
      {oauth ? null : (
        <label>
          Password
          <input
            type="password"
            value={draft.password}
            autoComplete="new-password"
            placeholder={existing?.passwordSet ? "Set. Leave empty to keep it." : ""}
            onChange={(event) => set("password", event.target.value)}
          />
        </label>
      )}
      {oauth ? (
        <label>
          OAuth2 client
          <input
            value={draft.oauthClient}
            placeholder="Name of an OAuth2 client, or empty to set the token endpoint here"
            onChange={(event) => set("oauthClient", event.target.value)}
          />
        </label>
      ) : null}
      {oauth && !draft.oauthClient.trim() ? (
        <>
          <label>
            Token URL
            <input value={draft.oauthTokenUrl} onChange={(event) => set("oauthTokenUrl", event.target.value)} required />
          </label>
          <label>
            Client id
            <input value={draft.oauthClientId} onChange={(event) => set("oauthClientId", event.target.value)} required />
          </label>
          <label>
            Client secret
            <input
              type="password"
              value={draft.oauthClientSecret}
              autoComplete="new-password"
              placeholder={existing?.clientSecretSet ? "Set. Leave empty to keep it." : ""}
              onChange={(event) => set("oauthClientSecret", event.target.value)}
            />
          </label>
          <label>
            Refresh token
            <input
              type="password"
              value={draft.oauthRefreshToken}
              autoComplete="new-password"
              placeholder={existing?.refreshTokenSet ? "Set. Leave empty to keep it." : "Empty uses the client credentials grant."}
              onChange={(event) => set("oauthRefreshToken", event.target.value)}
            />
          </label>
        </>
      ) : null}
      {oauth ? (
        <label>
          Scope
          <input value={draft.oauthScope} onChange={(event) => set("oauthScope", event.target.value)} />
        </label>
      ) : null}
      <label>
        Sender address
        <input type="email" value={draft.fromAddress} onChange={(event) => set("fromAddress", event.target.value)} />
      </label>
      <label>
        <input type="checkbox" checked={draft.enabled} onChange={(event) => set("enabled", event.target.checked)} />
        Enabled
      </label>
      <div className="row-actions">
        <button type="submit">Save profile</button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
