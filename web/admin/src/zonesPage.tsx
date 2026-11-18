import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage, RESOURCE_NAME } from "./access";
import { ApiError, type ResourceDocument } from "./api";
import { ResourceEditor } from "./configPage";
import type { AdminPageProps } from "./pages";
import { emptyValue, indexProblemErrors, mergeFieldErrors, validateAgainstSchema, type JsonSchema } from "./schema";
import { SchemaForm } from "./schemaForm";

export const SECURITY_MODULE = "org.oip.security";
export const ZONE_KIND = "SecurityZone";

/**
 * Security settings change the checks on every account, so the gateway requires all three security administration
 * permissions on top of gateway.write for a write (SecurityResourceGuard). The page hides its write controls without them.
 */
export function canWriteSecuritySettings(session: AdminPageProps["session"]): boolean {
  return ["gateway.write", "users.admin", "roles.admin", "apikeys.admin"].every((permission) => allows(session, permission));
}

/**
 * List, create, edit and delete security zones (GAT-034). A zone matches connections by IP address pattern, host name
 * or gateway name, and can cap or deny permissions for callers it matches. The built-in Default zone applies when no
 * zone matches and is not a stored resource, so it is listed but cannot be edited or deleted.
 * Doc source: FEATURES GAT-034.
 */
export function ZonesPage({ section, client, session }: AdminPageProps) {
  const canWrite = canWriteSecuritySettings(session);
  const [zones, setZones] = useState<ResourceDocument[] | null>(null);
  const [schema, setSchema] = useState<JsonSchema | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .listResources(SECURITY_MODULE, "gateway", ZONE_KIND)
      .then((next) => {
        if (cancelled) return;
        setZones([...next].sort((a, b) => a.metadata.name.localeCompare(b.metadata.name)));
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setZones(null);
        setError(errorMessage(err, "Could not list security zones"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  useEffect(() => {
    if (!client || !canWrite) return;
    let cancelled = false;
    client
      .getSchema(SECURITY_MODULE, ZONE_KIND)
      .then((next) => {
        if (!cancelled) setSchema(next);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load the zone schema"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, canWrite]);

  async function remove(zone: ResourceDocument) {
    if (!client) return;
    setPending(true);
    setError(null);
    try {
      await client.deleteResource(SECURITY_MODULE, "gateway", ZONE_KIND, zone.metadata.name, zone.metadata.version);
      if (editing === zone.metadata.name) setEditing(null);
      setReloadToken((token) => token + 1);
    } catch (err) {
      setError(errorMessage(err, "Could not delete the zone"));
    } finally {
      setPending(false);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        A zone matches a connection by IP address pattern, host name or gateway name. The highest priority match wins; a
        connection that matches nothing is in the Default zone.
      </p>
      {!client ? <p role="alert">Security zones are unavailable.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {client && canWrite && schema ? (
        <CreateZoneForm
          schema={schema}
          pending={pending}
          onCreate={async (name, spec) => {
            setPending(true);
            setError(null);
            try {
              await client.createResource(SECURITY_MODULE, "gateway", ZONE_KIND, name, spec);
              setReloadToken((token) => token + 1);
            } finally {
              setPending(false);
            }
          }}
        />
      ) : null}
      {!zones && !error ? <p>Loading zones.</p> : null}
      {zones ? (
        <table className="data-table" aria-label="Security zones">
          <thead>
            <tr>
              <th>Name</th>
              <th>Priority</th>
              <th>Enabled</th>
              <th>Identifiers</th>
              {canWrite ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {zones.map((zone) => (
              <tr key={zone.metadata.name}>
                <td>{zone.metadata.name}</td>
                <td>{String(zone.spec.priority ?? "")}</td>
                <td>{zone.spec.enabled === false ? "No" : "Yes"}</td>
                <td>{identifiers(zone.spec)}</td>
                {canWrite ? (
                  <td className="row-actions">
                    <button type="button" onClick={() => setEditing(zone.metadata.name)}>
                      Edit {zone.metadata.name}
                    </button>
                    <button type="button" disabled={pending} onClick={() => void remove(zone)}>
                      Delete {zone.metadata.name}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
            <tr data-testid="default-zone">
              <td>Default</td>
              <td>lowest</td>
              <td>Yes</td>
              <td>Any connection no other zone matches (built in, not editable)</td>
              {canWrite ? <td /> : null}
            </tr>
          </tbody>
        </table>
      ) : null}
      {client && editing ? (
        <section aria-label={`Edit zone ${editing}`}>
          <h2>Edit {editing}</h2>
          <ResourceEditor
            key={editing}
            client={client}
            moduleName={SECURITY_MODULE}
            scope="gateway"
            kind={ZONE_KIND}
            name={editing}
            canWrite={canWrite}
            onSaved={() => setReloadToken((token) => token + 1)}
          />
          <button type="button" className="quiet" onClick={() => setEditing(null)}>
            Close
          </button>
        </section>
      ) : null}
    </article>
  );
}

function identifiers(spec: Record<string, unknown>): string {
  const list = (key: string) => (Array.isArray(spec[key]) ? (spec[key] as unknown[]).map(String) : []);
  return [...list("ipAddresses"), ...list("hostNames"), ...list("gatewayNames")].join(", ");
}

function CreateZoneForm({
  schema,
  pending,
  onCreate,
}: {
  schema: JsonSchema;
  pending: boolean;
  onCreate: (name: string, spec: Record<string, unknown>) => Promise<void>;
}) {
  const initial = () => ({ ...(emptyValue(schema) as Record<string, unknown>), priority: 1 });
  const [name, setName] = useState("");
  const [spec, setSpec] = useState<Record<string, unknown>>(initial);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [formKey, setFormKey] = useState(0);

  const nameOk = RESOURCE_NAME.test(name) && name.toLowerCase() !== "default";
  const structural = validateAgainstSchema(schema, spec);
  const errors = mergeFieldErrors(structural, drafts, serverErrors);
  const blocked = !nameOk || structural.length > 0 || Object.keys(drafts).length > 0;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || pending) return;
    setError(null);
    try {
      await onCreate(name, spec);
      setName("");
      setSpec(initial());
      setServerErrors({});
      setFormKey((key) => key + 1);
    } catch (err) {
      if (err instanceof ApiError && err.status === 422) {
        setServerErrors(indexProblemErrors(err.errors.length > 0 ? err.errors : [err.message]));
      }
      setError(errorMessage(err, "Could not create the zone"));
    }
  }

  return (
    <form aria-label="Create security zone" className="stack-form" onSubmit={(event) => void submit(event)}>
      <h2>New zone</h2>
      <label>
        Name
        <input value={name} onChange={(event) => setName(event.target.value.trim())} required />
      </label>
      {name && !nameOk ? <p role="alert">Use lowercase letters, digits and '-'. "default" is reserved.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      <SchemaForm
        key={formKey}
        schema={schema}
        value={spec}
        errors={errors}
        onDrafts={setDrafts}
        onChange={(next) => {
          setSpec(next && typeof next === "object" && !Array.isArray(next) ? (next as Record<string, unknown>) : {});
          setServerErrors({});
        }}
      />
      <button type="submit" disabled={pending || blocked}>
        Create zone
      </button>
    </form>
  );
}
