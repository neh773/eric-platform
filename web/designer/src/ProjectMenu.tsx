import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { PROJECT_TEMPLATES, applyProjectTemplate, isProjectTemplateId } from "./projectTemplates";
import type { GatewayClient, ProjectInfo } from "./gatewayClient";
import { projectNameProblem, readRecentProjects, suggestedSaveAsName } from "./projectFiles";
import type { SavedProject } from "./useSavedProject";
import "./persistence.css";
import { BETA_NOTICE } from "./betaNotice";

/**
 * File menu: open or create a project, save one resource, save all, or save as.
 * doc source: FEATURES DES-011
 * doc source: FEATURES DES-009
 */
export function StudioFileMenu({
  saved,
  client,
  project,
  onOpenProject,
}: {
  saved: SavedProject;
  client: GatewayClient | null;
  project: string;
  onOpenProject: (name: string) => void;
}) {
  const [menu, setMenu] = useState(false);
  const [dialog, setDialog] = useState<"open" | "create" | "save" | "saveAs" | "example" | "about" | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  function choose(name: string) {
    setDialog(null);
    if (name === project) return;
    const unsaved = saved.dirty.size > 0 || saved.styleClassesDirty || saved.scriptHooksDirty || saved.pageConfigDirty;
    if (unsaved) setPending(name);
    else onOpenProject(name);
  }

  return (
    <div className="oip-file">
      <button type="button" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((open) => !open)}>
        File
      </button>
      {menu ? (
        <div role="menu" aria-label="File">
          <button type="button" role="menuitem" onClick={() => { setMenu(false); setDialog("open"); }}>
            Open…
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); setDialog("create"); }} disabled={!saved.persistent}>
            New project…
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); setDialog("example"); }} disabled={!client}>
            New from example…
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); setDialog("save"); }} disabled={!saved.persistent}>
            Save…
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); void saved.save(); }} disabled={!saved.persistent}>
            Save all
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); setDialog("saveAs"); }} disabled={!saved.persistent}>
            Save as…
          </button>
          <button type="button" role="menuitem" onClick={() => { setMenu(false); setDialog("about"); }}>
            About
          </button>
        </div>
      ) : null}
      {dialog === "open" && client ? <OpenDialog client={client} current={project} onOpen={choose} onCreate={() => setDialog("create")} onClose={() => setDialog(null)} /> : null}
      {dialog === "create" && client ? <CreateDialog client={client} onOpen={choose} onClose={() => setDialog(null)} /> : null}
      {dialog === "example" && client ? <ExampleDialog client={client} onOpen={choose} onClose={() => setDialog(null)} /> : null}
      {dialog === "save" ? <SaveDialog saved={saved} onClose={() => setDialog(null)} /> : null}
      {dialog === "saveAs" ? <SaveAsDialog project={project} saved={saved} onOpen={onOpenProject} onClose={() => setDialog(null)} /> : null}
      {dialog === "about" ? <AboutDialog onClose={() => setDialog(null)} /> : null}
      {pending ? (
        <div className="oip-dialog-backdrop">
          <div className="oip-dialog" role="alertdialog" aria-modal="true" aria-labelledby="oip-leave-title">
            <h2 id="oip-leave-title">Unsaved changes</h2>
            <p>
              {project} has unsaved changes. Open {pending} without saving them here?
            </p>
            <div className="oip-dialog-actions">
              <button type="button" onClick={() => setPending(null)}>
                Stay
              </button>
              <button
                type="button"
                onClick={() => {
                  const name = pending;
                  setPending(null);
                  saved.allowLeave();
                  onOpenProject(name);
                }}
              >
                Open anyway
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function OpenDialog({
  client,
  current,
  onOpen,
  onCreate,
  onClose,
}: {
  client: GatewayClient;
  current: string;
  onOpen: (name: string) => void;
  onCreate: () => void;
  onClose: () => void;
}) {
  const [items, setItems] = useState<ProjectInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const recent = readRecentProjects(window.localStorage).filter((name) => name !== current);

  useEffect(() => {
    let cancelled = false;
    void client.listProjectItems().then(
      (next) => {
        if (!cancelled) setItems(next);
      },
      (failure: unknown) => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  return (
    <Dialog title="Open project" onClose={onClose}>
      {recent.length > 0 ? (
        <>
          <h3>Recent</h3>
          <ul aria-label="Recent projects">
            {recent.map((name) => (
              <li key={name}>
                <button type="button" aria-label={name} onClick={() => onOpen(name)}>
                  {name}
                </button>
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {items === null && !error ? <p role="status">Loading projects…</p> : null}
      {items ? (
        <ul aria-label="Projects">
          {items.map((item) => (
            <li key={item.name}>
              <button type="button" aria-label={item.name} aria-current={item.name === current ? "true" : undefined} onClick={() => onOpen(item.name)}>
                <span>{item.title || item.name}</span>
                {item.description ? <small>{item.description}</small> : null}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="oip-dialog-actions">
        <button type="button" onClick={onCreate}>
          New project…
        </button>
        <button type="button" onClick={onClose}>
          Cancel
        </button>
      </div>
    </Dialog>
  );
}

function CreateDialog({ client, onOpen, onClose }: { client: GatewayClient; onOpen: (name: string) => void; onClose: () => void }) {
  const [parents, setParents] = useState<ProjectInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void client.listProjectItems().then(
      (items) => {
        if (!cancelled) setParents(items.filter((item) => item.inheritable));
      },
      () => {
        if (!cancelled) setParents([]);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const name = String(form.get("name") ?? "");
    const problem = projectNameProblem(name);
    if (problem) {
      setError(problem);
      return;
    }
    const parent = String(form.get("parent") ?? "");
    setBusy(true);
    setError(null);
    try {
      await client.createProject({
        name: name.trim(),
        title: String(form.get("title") ?? "").trim(),
        description: String(form.get("description") ?? "").trim(),
        ...(parent ? { parent } : {}),
        inheritable: form.get("inheritable") === "on",
      });
      const template = String(form.get("template") ?? "blank");
      if (isProjectTemplateId(template)) await applyProjectTemplate(client, name.trim(), template);
      onOpen(name.trim());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setBusy(false);
    }
  }

  return (
    <Dialog title="New project" onClose={onClose}>
      <form aria-label="New project" onSubmit={(event) => void submit(event)}>
        <label>
          Project name
          <input name="name" aria-label="Project name" required autoFocus />
        </label>
        <label>
          Title
          <input name="title" aria-label="Title" />
        </label>
        <label>
          Description
          <textarea name="description" aria-label="Description" rows={3} />
        </label>
        <label>
          Parent project
          <select name="parent" aria-label="Parent project" defaultValue="">
            <option value="">None</option>
            {parents.map((item) => (
              <option key={item.name} value={item.name}>
                {item.title || item.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Template
          <select name="template" aria-label="Template" defaultValue="blank">
            {PROJECT_TEMPLATES.map((template) => (
              <option key={template.id} value={template.id} title={template.description}>
                {template.label}
              </option>
            ))}
          </select>
        </label>
        <label className="oip-check">
          <input name="inheritable" type="checkbox" aria-label="Inheritable" />
          Inheritable
        </label>
        {error ? <p role="alert">{error}</p> : null}
        <div className="oip-dialog-actions">
          <button type="submit" disabled={busy}>
            Create project
          </button>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function ExampleDialog({ client, onOpen, onClose }: { client: GatewayClient; onOpen: (name: string) => void; onClose: () => void }) {
  const [examples, setExamples] = useState<Array<{ id: string; title: string; description: string; project: string }>>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void client.listExamples().then(
      (items) => { if (!cancelled) setExamples(items); },
      (failure: unknown) => { if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure)); },
    );
    return () => { cancelled = true; };
  }, [client]);
  return (
    <Dialog title="New from example" onClose={onClose}>
      <div aria-label="Example projects">
        {examples.length === 0 && !error ? <p>No example projects are installed on this gateway.</p> : null}
        {examples.map((example) => (
          <button
            key={example.id}
            type="button"
            disabled={busy}
            onClick={() => {
              setBusy(true);
              setError(null);
              void client.installExample(example.id).then(
                (result) => onOpen(result.project),
                (failure: unknown) => {
                  setBusy(false);
                  setError(failure instanceof Error ? failure.message : String(failure));
                },
              );
            }}
          >
            {example.title}
          </button>
        ))}
        {error ? <p role="alert">{error}</p> : null}
        <div className="oip-dialog-actions">
          <button type="button" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </Dialog>
  );
}

function SaveDialog({ saved, onClose }: { saved: SavedProject; onClose: () => void }) {
  const resources = saved.dirtyResources;
  const [picked, setPicked] = useState<Set<string>>(() => new Set(resources.map((item) => item.id)));

  function toggle(id: string) {
    setPicked((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <Dialog title="Save" onClose={onClose}>
      <p>Choose the resources to save. The others stay unsaved.</p>
      {resources.length === 0 ? <p>Nothing to save.</p> : null}
      <ul aria-label="Unsaved resources">
        {resources.map((item) => (
          <li key={item.id}>
            <label>
              <input type="checkbox" checked={picked.has(item.id)} onChange={() => toggle(item.id)} />
              {item.label}
            </label>
          </li>
        ))}
      </ul>
      <div className="oip-dialog-actions">
        <button type="button" onClick={() => setPicked(new Set(resources.map((item) => item.id)))}>
          All
        </button>
        <button type="button" onClick={() => setPicked(new Set())}>
          None
        </button>
        <button
          type="button"
          disabled={picked.size === 0 || saved.saveState.kind === "saving"}
          onClick={() => {
            void saved.saveSome(picked).then(() => {
              if (saved.saveState.kind !== "conflict") onClose();
            });
          }}
        >
          Save
        </button>
        <button type="button" onClick={onClose}>
          Cancel
        </button>
      </div>
    </Dialog>
  );
}

function SaveAsDialog({
  project,
  saved,
  onOpen,
  onClose,
}: {
  project: string;
  saved: SavedProject;
  onOpen: (name: string) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(() => suggestedSaveAsName(project));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = projectNameProblem(name);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await saved.saveAs(name);
      onOpen(created);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setBusy(false);
    }
  }

  return (
    <Dialog title="Save as" onClose={onClose}>
      <form aria-label="Save as" onSubmit={(event) => void submit(event)}>
        <p>Save a copy of {project}. The original project is left as it is.</p>
        <label>
          New project name
          <input aria-label="New project name" value={name} onChange={(event) => setName(event.target.value)} autoFocus />
        </label>
        {error ? <p role="alert">{error}</p> : null}
        <div className="oip-dialog-actions">
          <button type="submit" disabled={busy}>
            Save copy
          </button>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function AboutDialog({ onClose }: { onClose: () => void }) {
  return (
    <Dialog title="About Control Canvas" onClose={onClose}>
      <p>Control Canvas is the designer for ERIC (Engineered Runtime Interface & Control), version 0.1.0-beta.</p>
      <p data-testid="about-beta">{BETA_NOTICE}</p>
      <p>Copyright (C) 2026 Nick Hepburn. Licensed under the GNU Affero General Public License v3.0 or later. No warranty.</p>
      <div className="oip-dialog-actions">
        <button type="button" onClick={onClose}>
          Close
        </button>
      </div>
    </Dialog>
  );
}

function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="oip-dialog-backdrop">
      <div className="oip-dialog" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}
