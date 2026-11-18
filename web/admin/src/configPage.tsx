import { useEffect, useState, type FormEvent } from "react";
import { ApiClient, ApiError, type ResourceDocument } from "./api";
import type { AdminPageProps } from "./pages";
import { indexProblemErrors, mergeFieldErrors, validateAgainstSchema, type JsonSchema } from "./schema";
import { SchemaForm } from "./schemaForm";
import { resourceHref, type ConfigHit } from "./sections";

/**
 * Config area of the gateway admin UI.
 * The public web interface is a single place to reach every configuration area.
 * This page lists those resources and edits one from its JSON Schema.
 * (FEATURES GAT-016, SPEC §4).
 */
export function ConfigPage({ section, client, search = "", onVisit }: AdminPageProps) {
  const params = new URLSearchParams(search.startsWith("?") ? search.slice(1) : search);
  const moduleName = params.get("module") ?? "";
  const scope = params.get("scope") ?? "";
  const kind = params.get("kind") ?? "";
  const name = params.get("name") ?? "";
  const selected = Boolean(moduleName && scope && kind && name && client);

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {selected && client ? (
        <ResourceEditor
          client={client}
          moduleName={moduleName}
          scope={scope}
          kind={kind}
          name={name}
          onBack={() => onVisit?.("/admin/config")}
        />
      ) : (
        <ResourceList client={client} onVisit={onVisit} />
      )}
    </article>
  );
}

/**
 * Config resources, optionally only those of [moduleName]. Each links to the shared editor on the config page.
 * The service pages (historian, alarming) use it to group their resources under Services (GAT-036).
 */
export function ResourceList({ client, onVisit, moduleName }: { client?: ApiClient; onVisit?: (to: string) => void; moduleName?: string }) {
  const [hits, setHits] = useState<ConfigHit[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .configResources()
      .then((next) => {
        if (!cancelled) setHits(moduleName ? next.filter((hit) => hit.module === moduleName) : next);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : "Could not list resources");
      });
    return () => {
      cancelled = true;
    };
  }, [client, moduleName]);

  if (!client) return <p role="alert">Config is unavailable.</p>;
  if (error) return <p role="alert">{error}</p>;
  if (!hits) return <p>Loading resources.</p>;

  return (
    <ul className="config-list" data-testid="config-resources">
      {hits.length === 0 ? <li className="empty">No config resources.</li> : null}
      {hits.map((hit) => (
        <li key={`${hit.module}/${hit.scope}/${hit.kind}/${hit.name}`}>
          <a
            aria-label={`${hit.kind} ${hit.name} ${hit.module}`}
            href={resourceHref(hit)}
            onClick={(event) => {
              event.preventDefault();
              onVisit?.(resourceHref(hit));
            }}
          >
            <span>
              {hit.kind} {hit.name}
            </span>
            <small>
              {hit.module} · {hit.scope}
            </small>
          </a>
        </li>
      ))}
    </ul>
  );
}

/**
 * Schema-driven editor for one config resource. Shared by the config page and the security pages (GAT-034).
 * Without [canWrite] the form is shown read-only and has no Save button; the gateway refuses the write anyway.
 */
export function ResourceEditor({
  client,
  moduleName,
  scope,
  kind,
  name,
  onBack,
  canWrite = true,
  onSaved,
}: {
  client: ApiClient;
  moduleName: string;
  scope: string;
  kind: string;
  name: string;
  onBack?: () => void;
  canWrite?: boolean;
  onSaved?: (resource: ResourceDocument) => void;
}) {
  const [schema, setSchema] = useState<JsonSchema | null>(null);
  const [resource, setResource] = useState<ResourceDocument | null>(null);
  const [spec, setSpec] = useState<Record<string, unknown>>({});
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setConflict(false);
    setDrafts({});
    Promise.all([client.getSchema(moduleName, kind), client.getResource(moduleName, scope, kind, name)])
      .then(([nextSchema, nextResource]) => {
        if (cancelled) return;
        setSchema(nextSchema);
        setResource(nextResource);
        setSpec(nextResource.spec);
        setDrafts({});
        setServerErrors({});
        setSaved(false);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? err.message : "Could not load the resource");
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [client, moduleName, scope, kind, name, reloadToken]);

  const structural = schema ? validateAgainstSchema(schema, spec) : [];
  const errors = mergeFieldErrors(structural, drafts, serverErrors);
  const blocked = structural.length > 0 || Object.keys(drafts).length > 0;

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!resource || !schema || blocked || pending || !canWrite) return;
    setPending(true);
    setSaved(false);
    try {
      const next = await client.putResource(moduleName, scope, kind, name, { ...resource, spec });
      setResource(next);
      setSpec(next.spec);
      setServerErrors({});
      setConflict(false);
      setSaved(true);
      onSaved?.(next);
    } catch (err) {
      setSaved(false);
      if (err instanceof ApiError && err.status === 409) {
        setConflict(true);
      } else if (err instanceof ApiError && err.status === 422) {
        setServerErrors(indexProblemErrors(err.errors.length > 0 ? err.errors : [err.message]));
      } else {
        setError(err instanceof ApiError ? err.message : "Save failed");
      }
    } finally {
      setPending(false);
    }
  }

  return (
    <div data-testid="resource-editor">
      <p className="schema-identity">
        {onBack ? (
          <button type="button" onClick={onBack}>
            All resources
          </button>
        ) : null}
        <span>
          {kind} {name}
        </span>
        <span>
          {moduleName} · {scope}
          {resource ? ` · version ${resource.metadata.version}` : ""}
        </span>
      </p>
      {loading ? <p>Loading resource.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {conflict ? (
        <div role="alert" data-testid="version-conflict" className="banner banner-warn">
          <p>This resource was changed somewhere else. Reload the current version. Your edit was not saved.</p>
          <button type="button" onClick={() => setReloadToken((token) => token + 1)}>
            Reload
          </button>
        </div>
      ) : null}
      {schema && resource && !loading ? (
        <form onSubmit={(event) => void save(event)}>
          {errors[""] ? <p role="alert">{errors[""]}</p> : null}
          <SchemaForm
            key={`${resource.metadata.version}:${reloadToken}`}
            schema={schema}
            value={spec}
            errors={errors}
            onDrafts={setDrafts}
            onChange={(next) => {
              setSpec(next && typeof next === "object" && !Array.isArray(next) ? (next as Record<string, unknown>) : {});
              setServerErrors({});
              setSaved(false);
            }}
          />
          {canWrite ? (
            <div className="schema-actions">
              <button type="submit" disabled={pending || blocked}>
                Save
              </button>
              {saved ? <p role="status">Saved.</p> : null}
            </div>
          ) : (
            <p className="read-only-note" data-testid="read-only-note">
              Read only. Changing this needs gateway.write, users.admin, roles.admin and apikeys.admin.
            </p>
          )}
        </form>
      ) : null}
    </div>
  );
}
