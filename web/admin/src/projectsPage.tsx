import { useState, type FormEvent } from "react";
import { RESOURCE_NAME, allows, errorMessage } from "./access";
import type { AdminPageProps } from "./pages";
import { projectExportPath, type ConflictPolicy, type ImportOutcome, type ProjectItem, type StatusApi } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * Projects: the list with each project's settings, create, edit (title, description, parent, inheritable, runnable),
 * enable and disable, rename, copy, delete, export download, and import with a conflict policy (skip or overwrite).
 *
 * Concepts from the public project pages. The archive is our own resource-tree zip (DIFFERENCES.md entry 10), not a
 * vendor project file. Text and layout are our own.
 * - (PRO-003)
 * - (PRO-004)
 * - (PRO-002)
 */

const NAME_RULE = "Project names use lowercase letters, digits, and dashes, and start and end with a letter or digit.";

/** `project-plant.zip` (the export file name) suggests `plant` as the import target. */
export function projectNameFromFile(fileName: string): string {
  const base = fileName.replace(/\.zip$/i, "").replace(/^project-/, "");
  return RESOURCE_NAME.test(base) ? base : "";
}

/** Projects [project] may name as its parent: inheritable ones that are not [project] and do not inherit from it. */
export function parentChoices(items: readonly ProjectItem[], project: string | null): string[] {
  return items
    .filter((item) => item.inheritable && item.name !== project && (project === null || !item.inheritanceChain.includes(project)))
    .map((item) => item.name);
}

export function projectState(item: ProjectItem): string {
  const parts = [item.enabled ? "Enabled" : "Disabled"];
  if (item.inheritable) parts.push("inheritable");
  if (!item.runnable) parts.push("not runnable");
  return parts.join(", ");
}

export function ProjectsPage({ section, client, session }: AdminPageProps) {
  const api = useStatusApi(client);
  const canRead = session ? allows(session, "gateway.read") : true;
  const canWrite = allows(session, "gateway.write");
  const projects = useLoad(() => api.projectItems(), [api], { enabled: Boolean(client), fallback: "Could not list projects" });
  const examples = useLoad(() => api.listExamples(), [api], { enabled: Boolean(client), fallback: "Could not list examples" });
  const [editing, setEditing] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function act(run: () => Promise<string>) {
    setNotice(null);
    setActionError(null);
    try {
      setNotice(await run());
      projects.reload();
    } catch (err) {
      setActionError(errorMessage(err, "The project change failed"));
    }
  }

  const items = projects.data ?? [];
  const current = editing ? items.find((item) => item.name === editing) ?? null : null;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {canWrite && examples.data && examples.data.length > 0 ? (
        <section aria-label="Example projects">
          <h2>Import example project</h2>
          <p>Example projects are not installed until you import one. A new project has no public HMI.</p>
          {examples.data.map((example) => (
            <button
              key={example.id}
              type="button"
              onClick={() =>
                void act(async () => {
                  const installed = await api.installExample(example.id);
                  return `Imported ${installed.project}.`;
                })
              }
            >
              Import {example.title}
            </button>
          ))}
        </section>
      ) : null}
      {!canRead ? (
        <p role="status" data-testid="projects-no-read">
          Your roles do not include gateway.read, so projects are hidden.
        </p>
      ) : null}
      {projects.error ? <p role="alert">{projects.error}</p> : null}
      {!projects.data && !projects.error ? <p>Loading projects.</p> : null}
      {projects.data ? (
        <table className="data-table" aria-label="Projects">
          <thead>
            <tr>
              <th>Project</th>
              <th>Title</th>
              <th>Parent</th>
              <th>State</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={5}>No projects yet.</td>
              </tr>
            ) : null}
            {items.map((item) => (
              <tr key={item.name} data-project={item.name}>
                <td>{item.name}</td>
                <td>
                  {item.title}
                  {item.description ? <span className="meta"> {item.description}</span> : null}
                </td>
                <td>{item.parent ?? "None"}</td>
                <td data-testid={`state-${item.name}`}>{projectState(item)}</td>
                <td className="row-actions">
                  <a href={projectExportPath(item.name)} download={`project-${item.name}.zip`}>
                    Export {item.name}
                  </a>
                  {canWrite ? (
                    <>
                      <button
                        type="button"
                        onClick={() =>
                          void act(async () => {
                            await api.updateProject(item.name, { enabled: !item.enabled });
                            return `${item.name} ${item.enabled ? "disabled" : "enabled"}.`;
                          })
                        }
                      >
                        {item.enabled ? "Disable" : "Enable"} {item.name}
                      </button>
                      <button type="button" onClick={() => setEditing(editing === item.name ? null : item.name)}>
                        Manage {item.name}
                      </button>
                    </>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {notice ? (
        <p role="status" data-testid="project-notice">
          {notice}
        </p>
      ) : null}
      {actionError ? <p role="alert">{actionError}</p> : null}

      {canWrite && current ? (
        <ManageProject
          key={current.name}
          api={api}
          item={current}
          parents={parentChoices(items, current.name)}
          onDone={(message, next) => {
            setEditing(next);
            setNotice(message);
            setActionError(null);
            projects.reload();
          }}
          onError={(message) => setActionError(message)}
        />
      ) : null}
      {canWrite && projects.data ? (
        <CreateProject api={api} parents={parentChoices(items, null)} onCreated={(name) => void act(async () => `Created ${name}.`)} />
      ) : null}
      {canWrite ? <ImportProject api={api} onImported={() => projects.reload()} /> : null}
    </article>
  );
}

function CreateProject({ api, parents, onCreated }: { api: StatusApi; parents: string[]; onCreated: (name: string) => void }) {
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [parent, setParent] = useState("");
  const [inheritable, setInheritable] = useState(false);
  const [runnable, setRunnable] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (!RESOURCE_NAME.test(trimmed)) {
      setError(NAME_RULE);
      return;
    }
    setPending(true);
    setError(null);
    try {
      await api.createProject({ name: trimmed, title, description, parent, inheritable, runnable });
      setName("");
      setTitle("");
      setDescription("");
      setParent("");
      setInheritable(false);
      setRunnable(true);
      onCreated(trimmed);
    } catch (err) {
      setError(errorMessage(err, "Could not create the project"));
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="stack-form" aria-label="Create project" onSubmit={(event) => void submit(event)}>
      <h2>Create a project</h2>
      <label>
        New project name
        <input type="text" value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <label>
        New project title
        <input type="text" value={title} onChange={(event) => setTitle(event.target.value)} />
      </label>
      <label>
        New project description
        <input type="text" value={description} onChange={(event) => setDescription(event.target.value)} />
      </label>
      <label>
        New project parent
        <select value={parent} onChange={(event) => setParent(event.target.value)}>
          <option value="">None</option>
          {parents.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </label>
      <label>
        <input type="checkbox" checked={inheritable} onChange={(event) => setInheritable(event.target.checked)} /> Other projects may
        inherit from it (inheritable)
      </label>
      <label>
        <input type="checkbox" checked={runnable} onChange={(event) => setRunnable(event.target.checked)} /> Sessions can run it
        (runnable)
      </label>
      <div className="inline-form">
        <button type="submit" disabled={pending}>
          Create project
        </button>
      </div>
      {error ? <p role="alert">{error}</p> : null}
    </form>
  );
}

function ManageProject({
  api,
  item,
  parents,
  onDone,
  onError,
}: {
  api: StatusApi;
  item: ProjectItem;
  parents: string[];
  /** [next] is the project to keep managing (the new name after a rename), or null to close. */
  onDone: (message: string, next: string | null) => void;
  onError: (message: string) => void;
}) {
  const [title, setTitle] = useState(item.title);
  const [description, setDescription] = useState(item.description);
  const [parent, setParent] = useState(item.parent ?? "");
  const [inheritable, setInheritable] = useState(item.inheritable);
  const [runnable, setRunnable] = useState(item.runnable);
  const [publicHmi, setPublicHmi] = useState(item.publicHmi);
  const [renameTo, setRenameTo] = useState("");
  const [copyTo, setCopyTo] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [pending, setPending] = useState(false);
  // A parent that is no longer a valid choice stays listed so the form shows what is stored.
  const parentOptions = item.parent && !parents.includes(item.parent) ? [item.parent, ...parents] : parents;

  async function run(work: () => Promise<[string, string | null]>) {
    if (pending) return;
    setPending(true);
    try {
      const [message, next] = await work();
      onDone(message, next);
    } catch (err) {
      onError(errorMessage(err, "The project change failed"));
    } finally {
      setPending(false);
    }
  }

  function save(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await api.updateProject(item.name, { title, description, parent, inheritable, runnable, publicHmi });
      return [`Saved ${item.name}.`, item.name];
    });
  }

  function newName(raw: string): string | null {
    const trimmed = raw.trim();
    if (RESOURCE_NAME.test(trimmed)) return trimmed;
    onError(NAME_RULE);
    return null;
  }

  return (
    <section aria-label={`Manage ${item.name}`} className="stack-form">
      <h2>Manage {item.name}</h2>
      <p className="meta">
        {item.resources} resources.
        {item.inheritanceChain.length > 1 ? ` Inherits through ${item.inheritanceChain.slice(1).join(" → ")}.` : ""}
        {item.children.length > 0 ? ` Inherited by ${item.children.join(", ")}.` : ""}
      </p>
      <form className="stack-form" aria-label={`Settings of ${item.name}`} onSubmit={save}>
        <label>
          Title
          <input type="text" value={title} onChange={(event) => setTitle(event.target.value)} />
        </label>
        <label>
          Description
          <input type="text" value={description} onChange={(event) => setDescription(event.target.value)} />
        </label>
        <label>
          Parent project
          <select value={parent} onChange={(event) => setParent(event.target.value)}>
            <option value="">None</option>
            {parentOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input type="checkbox" checked={inheritable} onChange={(event) => setInheritable(event.target.checked)} /> Inheritable
        </label>
        <label>
          <input type="checkbox" checked={runnable} onChange={(event) => setRunnable(event.target.checked)} /> Runnable
        </label>
        <label>
          <input type="checkbox" checked={publicHmi} onChange={(event) => setPublicHmi(event.target.checked)} /> Public HMI (anonymous read and write, off by default)
        </label>
        <div className="inline-form">
          <button type="submit" disabled={pending}>
            Save settings
          </button>
        </div>
      </form>
      <form
        className="inline-form"
        aria-label={`Rename ${item.name}`}
        onSubmit={(event) => {
          event.preventDefault();
          const to = newName(renameTo);
          if (to) void run(async () => [`Renamed ${item.name} to ${(await api.renameProject(item.name, to)).name}.`, to]);
        }}
      >
        <label>
          Rename to
          <input type="text" value={renameTo} onChange={(event) => setRenameTo(event.target.value)} />
        </label>
        <button type="submit" disabled={pending}>
          Rename
        </button>
      </form>
      <form
        className="inline-form"
        aria-label={`Copy ${item.name}`}
        onSubmit={(event) => {
          event.preventDefault();
          const to = newName(copyTo);
          if (to) void run(async () => [`Copied ${item.name} to ${(await api.copyProject(item.name, to)).name}.`, item.name]);
        }}
      >
        <label>
          Copy to
          <input type="text" value={copyTo} onChange={(event) => setCopyTo(event.target.value)} />
        </label>
        <button type="submit" disabled={pending}>
          Copy
        </button>
      </form>
      <div className="inline-form">
        {confirmDelete ? (
          <>
            <span>Delete {item.name} and every resource in it?</span>
            <button
              type="button"
              disabled={pending}
              onClick={() =>
                void run(async () => {
                  await api.deleteProject(item.name);
                  return [`Deleted ${item.name}.`, null];
                })
              }
            >
              Confirm delete
            </button>
            <button type="button" onClick={() => setConfirmDelete(false)}>
              Keep it
            </button>
          </>
        ) : (
          <button type="button" onClick={() => setConfirmDelete(true)}>
            Delete {item.name}
          </button>
        )}
      </div>
    </section>
  );
}

function ImportProject({ api, onImported }: { api: StatusApi; onImported: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [target, setTarget] = useState("");
  const [policy, setPolicy] = useState<ConflictPolicy>("skip");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<ImportOutcome | null>(null);
  const [importError, setImportError] = useState<string | null>(null);

  function pick(next: File | null) {
    setFile(next);
    setResult(null);
    setImportError(null);
    if (next && !target) setTarget(projectNameFromFile(next.name));
  }

  async function runImport(event: FormEvent) {
    event.preventDefault();
    if (!file || pending) return;
    const name = target.trim();
    if (!RESOURCE_NAME.test(name)) {
      setImportError(NAME_RULE);
      return;
    }
    setPending(true);
    setResult(null);
    setImportError(null);
    try {
      const bytes = await file.arrayBuffer();
      setResult(await api.importProject(name, bytes, policy));
      onImported();
    } catch (err) {
      setImportError(errorMessage(err, "Could not import the project"));
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <form className="stack-form" aria-label="Import project" onSubmit={(event) => void runImport(event)}>
        <h2>Import a project</h2>
        <label>
          Project archive
          <input type="file" accept=".zip,application/zip" onChange={(event) => pick(event.target.files?.[0] ?? null)} />
        </label>
        <label>
          Import into project
          <input type="text" value={target} onChange={(event) => setTarget(event.target.value)} />
        </label>
        <fieldset>
          <legend>When a resource already exists</legend>
          <label>
            <input type="radio" name="policy" value="skip" checked={policy === "skip"} onChange={() => setPolicy("skip")} /> Keep the
            existing resource (skip)
          </label>
          <label>
            <input
              type="radio"
              name="policy"
              value="overwrite"
              checked={policy === "overwrite"}
              onChange={() => setPolicy("overwrite")}
            />{" "}
            Replace it with the archive copy (overwrite)
          </label>
        </fieldset>
        <div className="inline-form">
          <button type="submit" disabled={pending || !file}>
            Import
          </button>
        </div>
      </form>
      {result ? (
        <p role="status" data-testid="import-result">
          Imported into {result.project} ({result.policy}): {result.created} created, {result.overwritten} overwritten,{" "}
          {result.skipped} skipped.
        </p>
      ) : null}
      {importError ? <p role="alert">{importError}</p> : null}
    </>
  );
}
