import { useEffect, useState, type FormEvent } from "react";
import type { SavedProject } from "./useSavedProject";

/** Save control, save status, and the save-conflict prompt for a gateway-backed Studio. */
export function SaveControls({
  saved,
  offlineReason,
  blockedReason = null,
}: {
  saved: SavedProject;
  offlineReason: string | null;
  /** Why the user may not save this project (SEC-020); the gateway would refuse the save. */
  blockedReason?: string | null;
}) {
  const { dirty, saveState } = saved;
  const unsaved = unsavedLabel(dirty.size, saved.styleClassesDirty, saved.scriptHooksDirty, saved.pageConfigDirty);

  useEffect(() => {
    if (!saved.persistent) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        void saved.save();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [saved]);

  if (!saved.persistent) {
    return (
      <span className="oip-save-status" data-save-state="offline" title={offlineReason ?? undefined}>
        Gateway not reachable: edits stay in this tab
      </span>
    );
  }
  const status =
    saveState.kind === "saving"
      ? "Saving…"
      : saveState.kind === "error"
        ? `Save failed: ${saveState.message}`
        : saveState.kind === "conflict"
          ? "Save conflict"
          : unsaved ?? "All changes saved";
  return (
    <>
      <span className="oip-save-status" role="status" data-save-state={saveState.kind === "idle" || saveState.kind === "saved" ? (unsaved ? "dirty" : "clean") : saveState.kind}>
        {status}
      </span>
      <button type="button" onClick={() => void saved.save()} disabled={saveState.kind === "saving" || blockedReason !== null} title={blockedReason ?? undefined}>
        Save
      </button>
      {saveState.kind === "conflict" ? <ConflictDialog saved={saved} /> : null}
    </>
  );
}

function ConflictDialog({ saved }: { saved: SavedProject }) {
  if (saved.saveState.kind !== "conflict") return null;
  const conflict = saved.saveState.conflicts[0]!;
  const what = conflict.theirs ? "changed" : "deleted";
  const name =
    conflict.kind === "view" ? (saved.project.views.find((view) => view.metadata.path === conflict.path)?.metadata.name ?? conflict.path) : null;
  return (
    <div className="oip-conflict" role="alertdialog" aria-modal="true" aria-labelledby="oip-conflict-title">
      <h2 id="oip-conflict-title">Save conflict</h2>
      {name !== null ? (
        <p>
          View <strong>{name}</strong> was {what} on the gateway after you opened it.
        </p>
      ) : (
        <p>
          The project&apos;s <strong>{resourceLabel(conflict.kind)}</strong> were {what} on the gateway after you opened them.
        </p>
      )}
      {saved.saveState.errors?.length ? (
        <p role="alert">Also not saved: {saved.saveState.errors.join("; ")}</p>
      ) : null}
      <div className="oip-conflict-actions">
        <button type="button" onClick={() => void saved.keepMine(conflict.path)}>
          Keep my changes
        </button>
        {conflict.theirs ? (
          <button type="button" onClick={() => saved.takeTheirs(conflict.path)}>
            Use gateway copy
          </button>
        ) : null}
        <button type="button" onClick={() => saved.cancelConflict()}>
          Cancel
        </button>
      </div>
    </div>
  );
}

export function NewViewForm({ onCreate }: { onCreate: (path: string) => string | null }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = onCreate(name);
    setError(problem);
    if (!problem) setName("");
  }
  return (
    <form className="oip-new-view" aria-label="New view" onSubmit={submit}>
      <input aria-label="New view name" placeholder="New view name" value={name} onChange={(event) => setName(event.target.value)} />
      <button type="submit">Add view</button>
      {error ? <p role="alert">{error}</p> : null}
    </form>
  );
}

function resourceLabel(kind: string): string {
  if (kind === "scriptHooks") return "script hooks";
  if (kind === "pageConfig") return "page configuration";
  return "style classes";
}

/**
 * "2 unsaved views", "Unsaved style classes", "1 unsaved view and style classes", "Unsaved script hooks",
 * "1 unsaved view, style classes and script hooks", or null when all is saved.
 */
export function unsavedLabel(views: number, styleClasses: boolean, scriptHooks = false, pageConfig = false): string | null {
  const parts = [
    ...(views > 0 ? [`${views} unsaved ${views === 1 ? "view" : "views"}`] : []),
    ...(styleClasses ? ["style classes"] : []),
    ...(scriptHooks ? ["script hooks"] : []),
    ...(pageConfig ? ["page configuration"] : []),
  ];
  if (parts.length === 0) return null;
  const text = parts.length === 1 ? parts[0]! : `${parts.slice(0, -1).join(", ")} and ${parts.at(-1)!}`;
  return views > 0 ? text : `Unsaved ${text}`;
}

/** Stored resources the Studio could not open. They are left untouched on the gateway. */
export function LoadErrorsNotice({ errors }: { errors: readonly string[] }) {
  const [dismissed, setDismissed] = useState(false);
  if (errors.length === 0 || dismissed) return null;
  return (
    <div className="oip-load-errors" role="alert" aria-label="Could not open">
      <p>
        {errors.length === 1 ? "One stored item" : `${errors.length} stored items`} could not be opened and{" "}
        {errors.length === 1 ? "is" : "are"} not shown. {errors.length === 1 ? "It is" : "They are"} left unchanged on the gateway.
      </p>
      <ul>
        {errors.map((error) => (
          <li key={error}>{error}</li>
        ))}
      </ul>
      <button type="button" onClick={() => setDismissed(true)}>
        Dismiss
      </button>
    </div>
  );
}
