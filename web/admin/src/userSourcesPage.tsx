import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import type { ApiClient, UserSourceRecord } from "./api";
import { formatMappings, parseMappings } from "./identityProvidersPage";
import type { AdminPageProps } from "./pages";
import { UserCheckForm } from "./usersPage";

/**
 * User sources (SEC-007, SEC-010, SEC-021, SEC-025): an LDAP or Active Directory server, tables in an external
 * database behind a named connection, or a hybrid (directory passwords with roles kept here, or with roles from the
 * database). Gateway logins use the source that gateway security names as its system user source. The directory
 * service account's password goes to the gateway's secret store; this page never receives or shows it.
 * Doc source: FEATURES SEC-007.
 * Doc source: FEATURES SEC-010.
 * Doc source: FEATURES SEC-021.
 * Doc source: FEATURES SEC-025.
 */
export function UserSourcesPage({ section, client, session }: AdminPageProps) {
  const canAdmin = allows(session, "users.admin");
  const [sources, setSources] = useState<UserSourceRecord[] | null>(null);
  const [active, setActive] = useState("internal");
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | "new" | null>(null);
  const [checking, setChecking] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!client || !canAdmin) return;
    let cancelled = false;
    client
      .listUserSources()
      .then((next) => {
        if (!cancelled) {
          setSources(next.items);
          setActive(next.systemUserSource);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not list user sources"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, canAdmin, reload]);

  const selected = sources?.find((s) => s.name === editing) ?? null;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        Gateway logins check the system user source named in gateway security, now <strong data-testid="system-user-source">{active}</strong>. A
        directory&apos;s service account password is kept in the gateway&apos;s secret store and is never shown here.
      </p>
      {!canAdmin ? <p role="alert">Managing user sources needs the users.admin permission.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {canAdmin && client ? (
        <>
          {!sources && !error ? <p>Loading user sources.</p> : null}
          {sources ? (
            <table className="data-table" aria-label="User sources">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Enabled</th>
                  <th>Used for login</th>
                  <th>Bind password</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {sources.length === 0 ? (
                  <tr>
                    <td colSpan={6}>No user sources yet. Logins use the internal user source.</td>
                  </tr>
                ) : null}
                {sources.map((s) => (
                  <tr key={s.name}>
                    <td>{s.name}</td>
                    <td>{TYPE_LABELS[s.type]}</td>
                    <td>{s.enabled ? "Yes" : "No"}</td>
                    <td>{s.inUse ? "Yes" : "No"}</td>
                    <td>{s.type === "database" ? "—" : s.bindPasswordSet ? "Set" : "Not set"}</td>
                    <td className="row-actions">
                      <button type="button" onClick={() => setEditing(s.name)}>
                        Edit {s.name}
                      </button>
                      <button type="button" onClick={() => setChecking(checking === s.name ? null : s.name)}>
                        Check user on {s.name}
                      </button>
                      <button
                        type="button"
                        disabled={pending || s.inUse}
                        title={s.inUse ? "Choose another system user source in gateway security first" : undefined}
                        onClick={() => {
                          setPending(true);
                          setError(null);
                          void client
                            .deleteUserSource(s.name, s.version)
                            .then(() => {
                              if (editing === s.name) setEditing(null);
                              setReload((n) => n + 1);
                            })
                            .catch((err: unknown) => setError(errorMessage(err, "Could not delete the user source")))
                            .finally(() => setPending(false));
                        }}
                      >
                        Delete {s.name}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          {checking ? <UserCheckForm key={checking} client={client} source={checking} /> : null}
          {editing === null ? (
            <button type="button" onClick={() => setEditing("new")}>
              Add user source
            </button>
          ) : (
            <SourceForm
              key={selected ? `${selected.name}:${selected.version}` : "new"}
              client={client}
              source={selected}
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

const TYPE_LABELS: Record<UserSourceRecord["type"], string> = {
  ldap: "LDAP / Active Directory",
  database: "Database",
  "ldap-internal": "LDAP with internal roles",
  "ldap-database": "LDAP with database roles",
};

const text = (fields: Record<string, unknown>, key: string): string => (typeof fields[key] === "string" ? (fields[key] as string) : "");
const list = (fields: Record<string, unknown>, key: string): string[] =>
  Array.isArray(fields[key]) ? (fields[key] as unknown[]).filter((v): v is string => typeof v === "string") : [];

function splitList(raw: string): string[] {
  return raw
    .split(/[,\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

/** `username = role, role` per line, for the users an `ldap-internal` source keeps roles for. */
export function formatUsers(raw: unknown): string {
  if (!Array.isArray(raw)) return "";
  return raw
    .map((u) => (u && typeof u === "object" ? (u as { username?: unknown; roles?: unknown }) : {}))
    .filter((u) => typeof u.username === "string")
    .map((u) => `${String(u.username)} = ${Array.isArray(u.roles) ? u.roles.join(", ") : ""}`)
    .join("\n");
}

export function parseUsers(value: string): { username: string; roles: string[] }[] {
  return parseMappings(value).map((m) => ({ username: m.value, roles: m.roles }));
}

const LDAP_TEXT: [string, string, string][] = [
  ["bindDn", "Service account DN", "cn=gateway,ou=system,dc=example,dc=test"],
  ["userBaseDn", "User search base", "ou=people,dc=example,dc=test"],
  ["userFilter", "User filter", "(uid={username}); Active Directory: (sAMAccountName={username})"],
  ["userBindPattern", "Bind pattern without a service account", "uid={username},ou=people,... or {username}@example.test"],
  ["usernameAttribute", "Username attribute", "uid"],
  ["displayNameAttribute", "Display name attribute", "cn"],
  ["emailAttribute", "Email attribute", "mail"],
  ["groupAttribute", "Group membership attribute", "memberOf"],
  ["groupBaseDn", "Group search base", "ou=groups,dc=example,dc=test"],
  ["groupFilter", "Group filter", "(member={dn})"],
  ["groupNameAttribute", "Group name attribute", "cn"],
];

const DB_TEXT: [string, string, string][] = [
  ["usersTable", "Users table", "users or schema.users"],
  ["usernameColumn", "Username column", "username"],
  ["passwordColumn", "Password hash column", "password_hash"],
  ["enabledColumn", "Enabled column (optional)", ""],
  ["displayNameColumn", "Display name column (optional)", ""],
  ["emailColumn", "Email column (optional)", ""],
];

const ROLE_TEXT: [string, string, string][] = [
  ["rolesTable", "Roles table", "user_roles"],
  ["rolesUsernameColumn", "Roles table username column", "username"],
  ["roleColumn", "Role column", "role"],
];

function SourceForm({
  client,
  source,
  onCancel,
  onSaved,
}: {
  client: ApiClient;
  source: UserSourceRecord | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(source?.name ?? "");
  const [fields, setFields] = useState<Record<string, unknown>>(source?.fields ?? { type: "ldap", enabled: true });
  const [mappings, setMappings] = useState(formatMappings(source?.fields.roleMappings));
  const [users, setUsers] = useState(formatUsers(source?.fields.users));
  const [secret, setSecret] = useState("");
  const [clearSecret, setClearSecret] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [pending, setPending] = useState(false);
  const type = typeof fields.type === "string" ? (fields.type as UserSourceRecord["type"]) : "ldap";
  const usesLdap = type !== "database";
  const usesDb = type === "database" || type === "ldap-database";
  const set = (key: string, value: unknown) => setFields((current) => ({ ...current, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || (!source && !RESOURCE_NAME.test(name))) return;
    setPending(true);
    setError(null);
    setProblems([]);
    try {
      const body: Record<string, unknown> = { ...fields, roleMappings: parseMappings(mappings) };
      if (type === "ldap-internal") body.users = parseUsers(users);
      else delete body.users;
      const bindPassword = !usesLdap ? undefined : secret.length > 0 ? secret : clearSecret ? "" : undefined;
      await client.saveUserSource(source?.name ?? name, source ? source.version : null, body, bindPassword);
      setSecret("");
      onSaved();
    } catch (err) {
      setError(errorMessage(err, "Could not save the user source"));
      const extra = err && typeof err === "object" && "errors" in err ? (err as { errors?: unknown }).errors : undefined;
      setProblems(Array.isArray(extra) ? extra.filter((e): e is string => typeof e === "string") : []);
    } finally {
      setPending(false);
    }
  }

  const input = ([key, label, hint]: [string, string, string]) => (
    <label key={key}>
      {label}
      <input value={text(fields, key)} placeholder={hint} autoComplete="off" onChange={(e) => set(key, e.target.value)} />
    </label>
  );
  const check = (key: string, label: string) => (
    <label className="schema-check" key={key}>
      <input type="checkbox" checked={fields[key] === true} onChange={(e) => set(key, e.target.checked)} />
      <span>{label}</span>
    </label>
  );

  return (
    <form className="stack-form" aria-label={source ? `Edit user source ${source.name}` : "New user source"} onSubmit={(e) => void submit(e)}>
      <h2>{source ? `Edit ${source.name}` : "New user source"}</h2>
      {source ? null : (
        <label>
          Source name
          <input value={name} autoComplete="off" onChange={(e) => setName(e.target.value)} required />
        </label>
      )}
      <label>
        Type
        <select value={type} onChange={(e) => set("type", e.target.value)}>
          {(Object.keys(TYPE_LABELS) as UserSourceRecord["type"][]).map((t) => (
            <option key={t} value={t}>
              {TYPE_LABELS[t]}
            </option>
          ))}
        </select>
      </label>
      <label>
        Description
        <input value={text(fields, "description")} onChange={(e) => set("description", e.target.value)} />
      </label>
      {check("enabled", "Enabled")}
      {usesLdap ? (
        <fieldset>
          <legend>Directory</legend>
          <label>
            Server URLs (ldaps:// or ldap://, tried in order)
            <input
              value={list(fields, "ldapUrls").join(" ")}
              placeholder="ldaps://dc1.example.test ldaps://dc2.example.test"
              onChange={(e) => set("ldapUrls", splitList(e.target.value))}
            />
          </label>
          {check("startTls", "Use StartTLS on ldap:// URLs")}
          {check("allowPlaintext", "Allow ldap:// without TLS (passwords cross the network in clear text)")}
          {LDAP_TEXT.slice(0, 1).map(input)}
          <label>
            Service account password
            <input
              type="password"
              autoComplete="new-password"
              value={secret}
              placeholder={source?.bindPasswordSet ? "Stored. Leave blank to keep it." : "None stored"}
              onChange={(e) => setSecret(e.target.value)}
            />
          </label>
          {source?.bindPasswordSet ? removeSecretBox(clearSecret, setClearSecret) : null}
          {LDAP_TEXT.slice(1).map(input)}
          {check("nestedGroups", "Follow nested groups")}
          <label>
            Nested group depth
            <input
              type="number"
              min={1}
              max={10}
              value={typeof fields.nestedGroupDepth === "number" ? fields.nestedGroupDepth : 5}
              onChange={(e) => set("nestedGroupDepth", Number(e.target.value))}
            />
          </label>
        </fieldset>
      ) : null}
      {usesDb ? (
        <fieldset>
          <legend>Database</legend>
          <label>
            Database connection
            <input value={text(fields, "connection")} autoComplete="off" onChange={(e) => set("connection", e.target.value)} />
          </label>
          {type === "database" ? DB_TEXT.map(input) : null}
          {type === "database" ? (
            <label>
              Password hash scheme
              <select value={text(fields, "hashScheme") || "argon2id"} onChange={(e) => set("hashScheme", e.target.value)}>
                <option value="argon2id">Argon2id</option>
                <option value="bcrypt">bcrypt</option>
                <option value="pbkdf2-sha256">PBKDF2 with SHA-256</option>
                <option value="pbkdf2-sha512">PBKDF2 with SHA-512</option>
              </select>
            </label>
          ) : null}
          {ROLE_TEXT.map(input)}
        </fieldset>
      ) : null}
      <fieldset>
        <legend>Roles</legend>
        {type === "ldap-internal" ? (
          <>
            <label>
              Users and their roles (one per line: username = role, role)
              <textarea rows={4} value={users} onChange={(e) => setUsers(e.target.value)} />
            </label>
            {check("allowUnlistedUsers", "Let directory users not listed here log in with the default roles")}
          </>
        ) : (
          <label>
            Role mappings (one per line: {usesDb ? "role value" : "group name or DN"} = role, role)
            <textarea rows={4} value={mappings} onChange={(e) => setMappings(e.target.value)} />
          </label>
        )}
        {type === "ldap" ? check("mapRolesByName", "Give a role whose name matches a group name") : null}
        <label>
          Default roles
          <input value={list(fields, "defaultRoles").join(", ")} onChange={(e) => set("defaultRoles", splitList(e.target.value))} />
        </label>
      </fieldset>
      {error ? <p role="alert">{error}</p> : null}
      {problems.length > 0 ? (
        <ul aria-label="Problems">
          {problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      ) : null}
      <div className="inline-form">
        <button type="submit" disabled={pending || (!source && !RESOURCE_NAME.test(name))}>
          {source ? "Save user source" : "Create user source"}
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function removeSecretBox(checked: boolean, onChange: (value: boolean) => void) {
  return (
    <label className="schema-check">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>Remove the stored service account password</span>
    </label>
  );
}
