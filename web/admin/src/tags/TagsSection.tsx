import { useEffect, useState, type ReactNode } from "react";
import { allows, errorMessage } from "../access";
import type { AdminPageProps } from "../pages";
import type { JsonSchema } from "../schema";
import { sectionPath } from "../sections";
import { TagsPage } from "../tagsPage";
import { CreateTagForm, DeviceBrowseForm } from "./CreateTag";
import { listProviders, tagSchema, type NodeKind } from "./tagClient";
import { TagDiagnosticsPanel } from "./TagDiagnostics";
import { LiveValue, TagEditor } from "./TagEditor";
import { DEFAULT_PROVIDER, parentPath, parseTagPath } from "./tagModel";
import { TagTree } from "./TagTree";

interface Selection {
  path: string;
  /** null for a provider root. */
  kind: NodeKind | null;
  resource?: string;
  /** A tag row listed without its value: the caller's read permissions do not admit it. */
  denied?: boolean;
}

type Panel = "view" | "create" | "device";

export function tagsTab(search: string | undefined): { tab: "browser" | "udt"; type: string } {
  const params = new URLSearchParams(search ?? "");
  return { tab: params.get("tab") === "udt" ? "udt" : "browser", type: params.get("type") ?? "" };
}

/**
 * Admin Tags section (C-16): the tag browser and editor, and the UDT instance wizard (C-8) on its own tab.
 */
export function TagsSection(props: AdminPageProps) {
  const { section, search, onVisit } = props;
  const { tab } = tagsTab(search);
  const go = (to: string) => {
    if (onVisit) onVisit(to);
    else window.history.pushState(null, "", to);
  };
  const tabs = (
    <nav className="tag-section-tabs" aria-label="Tags views">
      <a
        href={sectionPath("tags")}
        aria-current={tab === "browser" ? "page" : undefined}
        onClick={(event) => {
          event.preventDefault();
          go(sectionPath("tags"));
        }}
      >
        Tag browser
      </a>
      <a
        href={`${sectionPath("tags")}?tab=udt`}
        aria-current={tab === "udt" ? "page" : undefined}
        onClick={(event) => {
          event.preventDefault();
          go(`${sectionPath("tags")}?tab=udt`);
        }}
      >
        UDT instances
      </a>
      <a
        href={sectionPath("udt-types")}
        onClick={(event) => {
          event.preventDefault();
          go(sectionPath("udt-types"));
        }}
      >
        UDT definitions
      </a>
    </nav>
  );
  if (tab === "udt") {
    return (
      <>
        {tabs}
        <TagsPage {...props} />
      </>
    );
  }
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {tabs}
      <TagBrowserView
        {...props}
        onOpenUdt={(typeId) => go(`${sectionPath("tags")}?tab=udt&type=${encodeURIComponent(typeId)}`)}
        onEditUdt={(typeId) => go(`${sectionPath("udt-types")}?type=${encodeURIComponent(typeId)}`)}
      />
    </article>
  );
}

function TagBrowserView({ client, session, onOpenUdt, onEditUdt }: AdminPageProps & { onOpenUdt: (typeId: string) => void; onEditUdt: (typeId: string) => void }) {
  const canReadConfig = allows(session, "gateway.read");
  const canCreate = allows(session, "gateway.write");
  const [providers, setProviders] = useState<string[]>([]);
  const [schema, setSchema] = useState<JsonSchema | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [panel, setPanel] = useState<Panel>("view");
  const [reloadToken, setReloadToken] = useState(0);
  const [reveal, setReveal] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [diagnosticsPath, setDiagnosticsPath] = useState<string | null>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    listProviders(client)
      .then((next) => {
        if (!cancelled) setProviders(next.length > 0 ? next.map((p) => p.name) : [DEFAULT_PROVIDER]);
      })
      .catch(() => {
        if (!cancelled) setProviders([DEFAULT_PROVIDER]);
      });
    if (canReadConfig) {
      tagSchema(client)
        .then((next) => {
          if (!cancelled) setSchema(next);
        })
        .catch((err: unknown) => {
          if (!cancelled) setSchemaError(errorMessage(err, "Could not load the tag schema"));
        });
    }
    return () => {
      cancelled = true;
    };
  }, [client, canReadConfig]);

  if (!client) return null;

  const folder = !selection
    ? `[${providers[0] ?? DEFAULT_PROVIDER}]`
    : selection.kind === null || selection.kind === "folder"
      ? selection.path
      : parentPath(selection.path);

  const refresh = (path?: string) => {
    setReloadToken((token) => token + 1);
    if (path) setReveal(path);
  };

  let detail: ReactNode = <p className="muted">Select a tag to see its value and edit its properties, or a folder to create tags in it.</p>;
  if (diagnosticsPath) {
    detail = <TagDiagnosticsPanel client={client} path={diagnosticsPath} onClose={() => setDiagnosticsPath(null)} />;
  } else if (panel === "create" && schema) {
    detail = (
      <CreateTagForm
        client={client}
        schema={schema}
        folder={folder}
        onCancel={() => setPanel("view")}
        onCreated={(path, resource) => {
          setPanel("view");
          setSelection({ path, kind: "tag", resource });
          setNotice(`Created ${path}.`);
          refresh(path);
        }}
      />
    );
  } else if (panel === "device" && schema) {
    detail = (
      <DeviceBrowseForm
        client={client}
        schema={schema}
        folder={folder}
        onCancel={() => setPanel("view")}
        onCreated={(paths) => {
          setPanel("view");
          setNotice(`Created ${paths.length} tag${paths.length === 1 ? "" : "s"} from the device.`);
          refresh(paths[0]);
        }}
      />
    );
  } else if (selection && selection.kind === "udt_definition") {
    const typeId = parseTagPath(selection.path).path;
    detail = (
      <section className="stack-form" aria-label="UDT definition">
        <h2>{selection.path}</h2>
        <p>Change its parameters, members, alarms and history on the UDT Definitions page.</p>
        <p className="row-actions">
          <button type="button" onClick={() => onEditUdt(typeId)}>
            Edit this UDT definition
          </button>
          <button type="button" onClick={() => onOpenUdt(typeId)}>
            Create instances of this UDT
          </button>
        </p>
      </section>
    );
  } else if (selection && selection.kind !== null && selection.kind !== "folder" && selection.resource && schema) {
    detail = (
      <TagEditor
        key={selection.resource}
        client={client}
        session={session}
        schema={schema}
        resourceName={selection.resource}
        onOpenUdt={onOpenUdt}
        onChanged={() => refresh()}
        onMoved={(path) => {
          setSelection({ ...selection, path });
          setNotice(`Moved to ${path}.`);
          refresh(path);
        }}
        onDeleted={() => {
          setNotice(`Deleted ${selection.path}.`);
          setSelection({ path: parentPath(selection.path), kind: "folder" });
          refresh();
        }}
      />
    );
  } else if (selection && selection.kind === "tag") {
    detail = (
      <section aria-label="Tag value">
        <h2>{selection.path}</h2>
        <LiveValue client={client} session={session} path={selection.path} dataType="" />
        <p className="muted">
          {selection.denied
            ? "This tag's read permissions do not admit you, so its value and configuration are hidden."
            : !canReadConfig
            ? "Viewing tag configuration requires the gateway.read permission."
            : "This tag has no stored configuration of its own (a UDT member or a system tag). Edit its UDT instance or definition instead."}
        </p>
      </section>
    );
  } else if (selection) {
    detail = (
      <section aria-label="Folder">
        <h2>{selection.path}</h2>
        <p className="muted">Folder. New tags are created here.</p>
      </section>
    );
  }

  return (
    <div className="tag-browser" data-testid="tag-browser">
      <div className="inline-form tag-toolbar">
        {canCreate ? (
          <>
            <button type="button" disabled={!schema} onClick={() => { setDiagnosticsPath(null); setPanel("create"); }}>
              New tag
            </button>
            <button type="button" disabled={!schema} onClick={() => { setDiagnosticsPath(null); setPanel("device"); }}>
              Create from device
            </button>
          </>
        ) : null}
        <button type="button" className="quiet" onClick={() => refresh()}>
          Refresh
        </button>
        <span className="muted">Folder: {folder}</span>
      </div>
      {!canCreate ? (
        <p role="status" data-testid="tag-browser-readonly">
          Creating, editing, moving and deleting tags requires the gateway.write permission.
        </p>
      ) : null}
      {schemaError ? <p role="alert">{schemaError}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      <div className="tag-browser-grid">
        <TagTree
          client={client}
          providers={providers}
          selected={selection?.path ?? null}
          reloadToken={reloadToken}
          reveal={reveal}
          onDiagnose={(path) => {
            setPanel("view");
            setNotice(null);
            setDiagnosticsPath(path);
          }}
          onSelect={({ path, row }) => {
            setPanel("view");
            setNotice(null);
            setDiagnosticsPath(null);
            setSelection({ path, kind: row ? row.kind : null, resource: row?.resource, denied: row?.kind === "tag" && row.quality === undefined });
          }}
        />
        <div className="tag-detail">{detail}</div>
      </div>
    </div>
  );
}
