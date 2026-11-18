import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import type { ApiClient, RoleAccount, UserAccount, UserCheckResult } from "./api";
import type { AdminPageProps } from "./pages";
import { mergeFieldErrors, validateAgainstSchema, type JsonSchema } from "./schema";
import { SchemaForm } from "./schemaForm";

/**
 * Create, edit, and delete users and roles, and assign roles to users.
 * The page renders only the public account fields from the security API.
 * A password hash is not among those fields and is never drawn.
 * (FEATURES SEC-011).
 */
export function UsersPage({ section, client, session }: AdminPageProps) {
  const canUsers = allows(session, "users.admin");
  const canRoles = allows(session, "roles.admin");
  const [users, setUsers] = useState<UserAccount[] | null>(null);
  const [roles, setRoles] = useState<RoleAccount[] | null>(null);
  const [roleSchema, setRoleSchema] = useState<JsonSchema | null>(null);
  const [userError, setUserError] = useState<string | null>(null);
  const [roleError, setRoleError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [editingUser, setEditingUser] = useState<string | null>(null);
  const [editingRole, setEditingRole] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .listUsers()
      .then((next) => {
        if (cancelled) return;
        setUsers(next);
        setUserError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setUsers(null);
        setUserError(errorMessage(err, "Could not list users"));
      });
    client
      .listRoles()
      .then((next) => {
        if (cancelled) return;
        setRoles(next);
        setRoleError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setRoles(null);
        setRoleError(errorMessage(err, "Could not list roles"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  useEffect(() => {
    if (!client || !canRoles) return;
    let cancelled = false;
    client
      .getSchema("org.oip.security", "Role")
      .then((schema) => {
        if (!cancelled) setRoleSchema(schema);
      })
      .catch((err: unknown) => {
        if (!cancelled) setRoleError(errorMessage(err, "Could not load the role schema"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, canRoles]);

  const selectedUser = users?.find((user) => user.username === editingUser) ?? null;
  const selectedRole = roles?.find((role) => role.name === editingRole) ?? null;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">Users and roles are stored by the gateway. This page never shows a password or a password hash.</p>
      {userError ? <p role="alert">{userError}</p> : null}
      {roleError ? <p role="alert">{roleError}</p> : null}
      {!client ? <p role="alert">Users and roles are unavailable.</p> : null}
      {canUsers && client ? (
        <CreateUserForm
          roles={roles ?? []}
          pending={pending}
          onSubmit={async (input) => {
            setPending(true);
            setUserError(null);
            try {
              await client.createUser(input);
              setReloadToken((token) => token + 1);
            } catch (err) {
              setUserError(errorMessage(err, "Could not create the user"));
              throw err;
            } finally {
              setPending(false);
            }
          }}
        />
      ) : null}
      {!users && !userError ? <p>Loading users.</p> : null}
      {users ? (
        <table className="data-table" aria-label="Users">
          <thead>
            <tr>
              <th>Username</th>
              <th>Display name</th>
              <th>Roles</th>
              <th>Enabled</th>
              {canUsers ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.username}>
                <td>{user.username}</td>
                <td>{user.displayName}</td>
                <td>{user.roles.join(", ")}</td>
                <td>{user.enabled ? "Yes" : "No"}</td>
                {canUsers ? (
                  <td className="row-actions">
                    <button type="button" onClick={() => setEditingUser(user.username)}>
                      Edit {user.username}
                    </button>
                    {session?.username === user.username ? null : (
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() => {
                          if (!client) return;
                          setPending(true);
                          setUserError(null);
                          void client
                            .deleteUser(user.username, user.version)
                            .then(() => {
                              if (editingUser === user.username) setEditingUser(null);
                              setReloadToken((token) => token + 1);
                            })
                            .catch((err: unknown) => setUserError(errorMessage(err, "Could not delete the user")))
                            .finally(() => setPending(false));
                        }}
                      >
                        Delete {user.username}
                      </button>
                    )}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {canUsers && client && selectedUser ? (
        <EditUserForm
          user={selectedUser}
          roles={roles ?? []}
          pending={pending}
          onCancel={() => setEditingUser(null)}
          onSave={async (input) => {
            setPending(true);
            setUserError(null);
            try {
              const saved = await client.updateUser(selectedUser.username, selectedUser.version, input);
              setUsers((current) => current?.map((user) => (user.username === saved.username ? saved : user)) ?? current);
              setEditingUser(saved.username);
              setReloadToken((token) => token + 1);
            } catch (err) {
              setUserError(errorMessage(err, "Could not update the user"));
            } finally {
              setPending(false);
            }
          }}
          onResetPassword={async (password) => {
            setPending(true);
            setUserError(null);
            try {
              const saved = await client.setPassword(selectedUser.username, selectedUser.version, password);
              setUsers((current) => current?.map((user) => (user.username === saved.username ? saved : user)) ?? current);
              setReloadToken((token) => token + 1);
              return true;
            } catch (err) {
              setUserError(errorMessage(err, "Could not reset the password"));
              return false;
            } finally {
              setPending(false);
            }
          }}
        />
      ) : null}

      {canUsers && client ? <UserCheckForm client={client} /> : null}

      <h2>Roles</h2>
      {!roles && !roleError ? <p>Loading roles.</p> : null}
      {roles ? (
        <table className="data-table" aria-label="Roles">
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Permissions</th>
              {canRoles ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {roles.map((role) => (
              <tr key={role.name}>
                <td>{role.name}</td>
                <td>{role.description}</td>
                <td>{role.permissions.join(", ")}</td>
                {canRoles ? (
                  <td className="row-actions">
                    <button type="button" onClick={() => setEditingRole(role.name)}>
                      Edit role {role.name}
                    </button>
                    <button
                      type="button"
                      disabled={pending}
                      onClick={() => {
                        if (!client) return;
                        setPending(true);
                        setRoleError(null);
                        void client
                          .deleteRole(role.name, role.version)
                          .then(() => {
                            if (editingRole === role.name) setEditingRole(null);
                            setReloadToken((token) => token + 1);
                          })
                          .catch((err: unknown) => setRoleError(errorMessage(err, "Could not delete the role")))
                          .finally(() => setPending(false));
                      }}
                    >
                      Delete role {role.name}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {canRoles && client && roleSchema ? (
        <RoleEditor
          key={selectedRole ? `edit:${selectedRole.name}:${selectedRole.version}` : "create"}
          client={client}
          schema={roleSchema}
          role={selectedRole}
          pending={pending}
          onCancel={() => setEditingRole(null)}
          onDone={() => {
            setEditingRole(null);
            setReloadToken((token) => token + 1);
          }}
          onError={setRoleError}
          onPending={setPending}
        />
      ) : null}
    </article>
  );
}

/**
 * Verify a user on the user source (SEC-027): whether it exists, its roles, and, when a password is entered, whether
 * it matches. The password is sent once and cleared from the form; the gateway never records it.
 * Doc source: FEATURES SEC-027.
 */
/** [source] is the user source to check: the internal one by default, or an LDAP, database or hybrid source (SEC-007). */
export function UserCheckForm({ client, source = "internal" }: { client: ApiClient; source?: string }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [result, setResult] = useState<UserCheckResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || username.length === 0) return;
    setPending(true);
    setError(null);
    setResult(null);
    try {
      setResult(await client.checkUser(source, username, password.length > 0 ? password : undefined));
    } catch (err) {
      setError(errorMessage(err, "Could not check the user"));
    } finally {
      setPassword("");
      setPending(false);
    }
  }

  return (
    <form className="stack-form" aria-label={source === "internal" ? "Check user" : `Check user on ${source}`} onSubmit={(event) => void submit(event)}>
      <h2>{source === "internal" ? "Check a user" : `Check a user on ${source}`}</h2>
      <p className="lede">
        {source === "internal"
          ? "Verify that a user exists in the internal user source, list its roles, and optionally test a password."
          : "Verify a user on this source, list the groups or role values it reads and the roles they map to, and optionally test a password."}
      </p>
      <label>
        Username to check
        <input value={username} autoComplete="off" onChange={(event) => setUsername(event.target.value)} required />
      </label>
      <label>
        Password to test (optional)
        <input type="password" autoComplete="off" value={password} onChange={(event) => setPassword(event.target.value)} />
      </label>
      <button type="submit" disabled={pending || username.length === 0}>
        Check user
      </button>
      {error ? <p role="alert">{error}</p> : null}
      {result ? (
        <dl aria-label="Check result">
          <dt>User</dt>
          <dd>{result.exists ? `${result.username} exists${result.enabled === false ? " (disabled)" : ""}` : `${result.username} was not found`}</dd>
          {result.exists ? (
            <>
              <dt>Roles</dt>
              <dd>{result.roles.length > 0 ? result.roles.join(", ") : "None"}</dd>
            </>
          ) : null}
          <dt>Password</dt>
          <dd>{!result.passwordChecked ? "Not tested" : result.passwordValid ? "Correct" : "Incorrect"}</dd>
          {result.dn ? (
            <>
              <dt>Directory entry</dt>
              <dd>{result.dn}</dd>
            </>
          ) : null}
          {result.groups ? (
            <>
              <dt>Groups or role values read</dt>
              <dd>{result.groups.length > 0 ? result.groups.join(", ") : "None"}</dd>
            </>
          ) : null}
          {result.unavailable ? (
            <>
              <dt>Source unavailable</dt>
              <dd>{result.unavailable}</dd>
            </>
          ) : null}
        </dl>
      ) : null}
    </form>
  );
}

function CreateUserForm({
  roles,
  pending,
  onSubmit,
}: {
  roles: RoleAccount[];
  pending: boolean;
  onSubmit: (input: { username: string; password: string; displayName: string; roles: string[] }) => Promise<void>;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const nameError = username.length > 0 && !RESOURCE_NAME.test(username) ? "Use lowercase letters, digits, and hyphens." : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || !RESOURCE_NAME.test(username) || password.length === 0) return;
    try {
      await onSubmit({ username, password, displayName, roles: selected });
    } catch {
      return;
    }
    setUsername("");
    setPassword("");
    setDisplayName("");
    setSelected([]);
  }

  return (
    <form className="stack-form" aria-label="Create user" onSubmit={(event) => void submit(event)}>
      <h2>New user</h2>
      <label>
        Username
        <input value={username} autoComplete="off" onChange={(event) => setUsername(event.target.value)} required />
      </label>
      {nameError ? <p role="alert">{nameError}</p> : null}
      <label>
        Password
        <input
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
      </label>
      <label>
        Display name
        <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
      </label>
      <RoleChecks roles={roles} selected={selected} onChange={setSelected} />
      <button type="submit" disabled={pending || !RESOURCE_NAME.test(username) || password.length === 0}>
        Create user
      </button>
    </form>
  );
}

function EditUserForm({
  user,
  roles,
  pending,
  onCancel,
  onSave,
  onResetPassword,
}: {
  user: UserAccount;
  roles: RoleAccount[];
  pending: boolean;
  onCancel: () => void;
  onSave: (input: { displayName: string; email: string; phone: string; enabled: boolean; roles: string[] }) => Promise<void>;
  onResetPassword: (password: string) => Promise<boolean>;
}) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [email, setEmail] = useState(user.email);
  const [phone, setPhone] = useState(user.phone);
  const [enabled, setEnabled] = useState(user.enabled);
  const [selected, setSelected] = useState<string[]>(user.roles);
  const [password, setPassword] = useState("");
  const [resetNote, setResetNote] = useState<string | null>(null);

  async function save(event: FormEvent) {
    event.preventDefault();
    await onSave({ displayName, email, phone, enabled, roles: selected });
  }

  return (
    <form className="stack-form" aria-label={`Edit ${user.username}`} onSubmit={(event) => void save(event)}>
      <h2>Edit {user.username}</h2>
      <label>
        Display name
        <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
      </label>
      <label>
        Email
        <input value={email} onChange={(event) => setEmail(event.target.value)} />
      </label>
      <label>
        Phone
        <input value={phone} onChange={(event) => setPhone(event.target.value)} />
      </label>
      <label className="schema-check">
        <input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
        <span>Enabled</span>
      </label>
      <RoleChecks roles={roles} selected={selected} onChange={setSelected} />
      <div className="inline-form">
        <button type="submit" disabled={pending}>
          Save user
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
      <label>
        New password
        <input
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => {
            setPassword(event.target.value);
            setResetNote(null);
          }}
        />
      </label>
      <button
        type="button"
        disabled={pending || password.length === 0}
        onClick={() => {
          void onResetPassword(password).then((ok) => {
            if (!ok) return;
            setPassword("");
            setResetNote("Password updated.");
          });
        }}
      >
        Reset password
      </button>
      {resetNote ? <p role="status">{resetNote}</p> : null}
    </form>
  );
}

function RoleChecks({
  roles,
  selected,
  onChange,
}: {
  roles: RoleAccount[];
  selected: string[];
  onChange: (roles: string[]) => void;
}) {
  return (
    <fieldset>
      <legend>Roles</legend>
      {roles.map((role) => (
        <label className="schema-check" key={role.name}>
          <input
            type="checkbox"
            checked={selected.includes(role.name)}
            onChange={(event) =>
              onChange(event.target.checked ? [...selected, role.name] : selected.filter((item) => item !== role.name))
            }
          />
          <span>{role.name}</span>
        </label>
      ))}
    </fieldset>
  );
}

function RoleEditor({
  client,
  schema,
  role,
  pending,
  onCancel,
  onDone,
  onError,
  onPending,
}: {
  client: ApiClient;
  schema: JsonSchema;
  role: RoleAccount | null;
  pending: boolean;
  onCancel: () => void;
  onDone: () => void;
  onError: (message: string | null) => void;
  onPending: (pending: boolean) => void;
}) {
  const [name, setName] = useState(role?.name ?? "");
  const [spec, setSpec] = useState<Record<string, unknown>>({
    description: role?.description ?? "",
    permissions: role?.permissions ?? [],
  });
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const structural = validateAgainstSchema(schema, spec);
  const errors = mergeFieldErrors(structural, drafts, {});
  const blocked = structural.length > 0 || Object.keys(drafts).length > 0;
  const nameError = !role && name.length > 0 && !RESOURCE_NAME.test(name) ? "Use lowercase letters, digits, and hyphens." : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || blocked) return;
    const description = typeof spec.description === "string" ? spec.description : "";
    const permissions = Array.isArray(spec.permissions)
      ? [...new Set(spec.permissions.filter((item): item is string => typeof item === "string"))]
      : [];
    onPending(true);
    onError(null);
    try {
      if (role) {
        await client.updateRole(role.name, role.version, { description, permissions });
      } else {
        if (!RESOURCE_NAME.test(name)) return;
        await client.createRole({ name, description, permissions });
      }
      onDone();
    } catch (err) {
      onError(errorMessage(err, role ? "Could not update the role" : "Could not create the role"));
    } finally {
      onPending(false);
    }
  }

  return (
    <form className="stack-form" aria-label={role ? "Edit role" : "Create role"} onSubmit={(event) => void submit(event)}>
      <h2>{role ? `Edit role ${role.name}` : "New role"}</h2>
      {role ? null : (
        <label>
          Role name
          <input value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} required />
        </label>
      )}
      {nameError ? <p role="alert">{nameError}</p> : null}
      {errors[""] ? <p role="alert">{errors[""]}</p> : null}
      <SchemaForm
        schema={schema}
        value={spec}
        errors={errors}
        onDrafts={setDrafts}
        onChange={(next) => {
          setSpec(next && typeof next === "object" && !Array.isArray(next) ? (next as Record<string, unknown>) : {});
        }}
      />
      <div className="inline-form">
        <button type="submit" disabled={pending || blocked || (!role && !RESOURCE_NAME.test(name))}>
          {role ? "Save role" : "Create role"}
        </button>
        {role ? (
          <button type="button" onClick={onCancel}>
            Cancel
          </button>
        ) : null}
      </div>
    </form>
  );
}
