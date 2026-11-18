import { useCallback, useEffect, useRef, useState } from "react";
import type { CustomThemeSet, StyleClassSet, ViewDocument } from "@oip/hmi-runtime";
import type { StudioProject } from "./project";
import type { GatewayClient } from "./gatewayClient";
import { saveProjectAs, type ProjectSnapshot } from "./projectFiles";
import {
  PAGE_CONFIG_RESOURCE,
  SCRIPT_HOOKS_RESOURCE,
  STYLE_CLASSES_RESOURCE,
  PageConfigConflict,
  SaveConflict,
  ScriptHooksConflict,
  StyleClassesConflict,
  blankView,
  resourceNameFor,
  savePageConfig,
  saveScriptHooks,
  saveStyleClasses,
  saveView,
  viewNameProblem,
  type LoadedPageConfig,
  type LoadedScriptHooks,
  type LoadedStyleClasses,
  type LoadedView,
  type PageConfigSpec,
  type ScriptHookSet,
  type StoredRef,
} from "./persistence";

/** Project state for a Studio whose views and style classes are stored on the gateway. */

/**
 * Conflict key for the project's style classes. View paths are runtime slugs (letters, digits, '-'),
 * so this key cannot name a view.
 */
export const STYLE_CLASSES_KEY = "#style-classes";
/** Conflict key for the project's script hooks (D-5); like [STYLE_CLASSES_KEY], it cannot name a view. */
export const SCRIPT_HOOKS_KEY = "#script-hooks";
/** Conflict key for the project's page configuration. Like the others, it cannot name a view. */
export const PAGE_CONFIG_KEY = "#page-config";

export type Conflict =
  | { kind: "view"; path: string; theirs: LoadedView | null }
  | { kind: "styleClasses"; path: typeof STYLE_CLASSES_KEY; theirs: LoadedStyleClasses | null }
  | { kind: "scriptHooks"; path: typeof SCRIPT_HOOKS_KEY; theirs: LoadedScriptHooks | null }
  | { kind: "pageConfig"; path: typeof PAGE_CONFIG_KEY; theirs: LoadedPageConfig | null };

/** One resource with edits not yet on the gateway. [id] is a view path or one of the resource keys above. */
export interface DirtyResource {
  id: string;
  label: string;
  kind: "view" | "styleClasses" | "scriptHooks" | "pageConfig";
}

export type SaveState =
  | { kind: "idle" }
  | { kind: "saving" }
  | { kind: "saved"; count: number }
  | { kind: "error"; message: string }
  /** [errors]: other resources in the same save that failed for another reason. */
  | { kind: "conflict"; conflicts: Conflict[]; errors?: string[] };

export interface SavedProject {
  project: StudioProject;
  /** Paths with edits not yet on the gateway. */
  dirty: ReadonlySet<string>;
  /**
   * Views the project inherits (PRO-002), by path, with the ancestor project that stores each. They are read-only:
   * [replaceView] ignores them until [overrideView] makes the project's own copy.
   */
  inherited: ReadonlyMap<string, string>;
  /** Make an inherited view the project's own: it becomes editable and is saved into this project on the next save. */
  overrideView: (path: string) => void;
  /** The project's style classes have edits not yet on the gateway. */
  styleClassesDirty: boolean;
  /** The project's script hooks (`ScriptHooks` resource). */
  scriptHooks: ScriptHookSet;
  /** The project's script hooks have edits not yet on the gateway. */
  scriptHooksDirty: boolean;
  /** The project's page configuration, or null when none is stored and the author has not edited one. */
  pageConfig: PageConfigSpec | null;
  /** The project's page configuration has edits not yet on the gateway. */
  pageConfigDirty: boolean;
  /** Resources with edits not yet on the gateway, one entry each. */
  dirtyResources: readonly DirtyResource[];
  saveState: SaveState;
  persistent: boolean;
  replaceView: (view: ViewDocument) => void;
  replaceStyleClasses: (styleClasses: StyleClassSet) => void;
  replaceThemes: (themes: CustomThemeSet) => void;
  replaceScriptHooks: (hooks: ScriptHookSet) => void;
  replacePageConfig: (spec: PageConfigSpec) => void;
  /** Adds an empty view named [name]. Returns an error message, or null and the new view's path. */
  createView: (path: string) => { error: string | null; path: string };
  /** Save every unsaved resource. */
  save: () => Promise<void>;
  /** Save the unsaved resources whose ids are in [ids]. Other unsaved resources stay unsaved. */
  saveSome: (ids: ReadonlySet<string>) => Promise<void>;
  /**
   * Save As: copy the project to [name] and write unsaved edits only there. The open project's stored resources
   * stay as they were. Returns the new name.
   */
  saveAs: (name: string) => Promise<string>;
  /** The next navigation should not ask about unsaved edits (Save As, or the author confirmed leaving). */
  allowLeave: () => void;
  /** Keep this Designer's copy: save again over the stored version. [path] is a view path or a resource key. */
  keepMine: (path: string) => Promise<void>;
  /** Discard local edits to [path] and take the stored copy (no-op when it was deleted). */
  takeTheirs: (path: string) => void;
  /** Leave the conflict unresolved; the view stays unsaved. */
  cancelConflict: () => void;
  /** The gateway resource name of the stored view at [path], or undefined while it is not stored yet (SEC-020 locks). */
  storedName: (path: string) => string | undefined;
}

export interface SavedProjectInit {
  name: string;
  views: LoadedView[];
  /** Views shown but not stored yet (a starter for an empty project). */
  drafts?: ViewDocument[];
  /** Stored style classes, or null when the project has none stored yet. */
  styleClasses?: LoadedStyleClasses | null;
  /** Style classes shown but not stored yet (the starter set for an empty project); marks them unsaved. */
  styleClassesDraft?: StyleClassSet | null;
  /** Stored script hooks, or null when the project has none stored yet. */
  scriptHooks?: LoadedScriptHooks | null;
  /** Stored page configuration, or null when the project has none stored yet. */
  pageConfig?: LoadedPageConfig | null;
}

export function useSavedProject(init: SavedProjectInit, client: GatewayClient | null, project: string): SavedProject {
  const [views, setViews] = useState<ViewDocument[]>(() => [...init.views.map((entry) => entry.view), ...(init.drafts ?? [])]);
  const refs = useRef<Map<string, StoredRef>>(seedRefs(init));
  const [dirty, setDirty] = useState<Set<string>>(() => new Set((init.drafts ?? []).map((view) => view.metadata.path)));
  const [inherited, setInherited] = useState<Map<string, string>>(
    () => new Map(init.views.flatMap((entry) => (entry.inheritedFrom ? [[entry.view.metadata.path, entry.inheritedFrom] as const] : []))),
  );
  const inheritedRef = useRef(inherited);
  inheritedRef.current = inherited;
  const [saveState, setSaveState] = useState<SaveState>({ kind: "idle" });
  const [styleClasses, setStyleClasses] = useState<StyleClassSet>(
    () => init.styleClasses?.styleClasses ?? init.styleClassesDraft ?? {},
  );
  const [styleClassesDirty, setStyleClassesDirty] = useState(() => !init.styleClasses && !!init.styleClassesDraft);
  const [themes, setThemes] = useState<CustomThemeSet>(() => init.styleClasses?.themes ?? {});
  const styleRef = useRef<StoredRef>(init.styleClasses?.ref ?? { name: STYLE_CLASSES_RESOURCE, version: 0 });
  const styleClassesRef = useRef(styleClasses);
  styleClassesRef.current = styleClasses;
  const styleClassesDirtyRef = useRef(styleClassesDirty);
  styleClassesDirtyRef.current = styleClassesDirty;
  const themesRef = useRef(themes);
  themesRef.current = themes;
  const [scriptHooks, setScriptHooks] = useState<ScriptHookSet>(() => init.scriptHooks?.hooks ?? {});
  const [scriptHooksDirty, setScriptHooksDirty] = useState(false);
  const hooksRef = useRef<StoredRef>(init.scriptHooks?.ref ?? { name: SCRIPT_HOOKS_RESOURCE, version: 0 });
  const scriptHooksRef = useRef(scriptHooks);
  scriptHooksRef.current = scriptHooks;
  const scriptHooksDirtyRef = useRef(scriptHooksDirty);
  scriptHooksDirtyRef.current = scriptHooksDirty;
  const [pageConfig, setPageConfig] = useState<PageConfigSpec | null>(() => init.pageConfig?.spec ?? null);
  const [pageConfigDirty, setPageConfigDirty] = useState(false);
  const pageRef = useRef<StoredRef>(init.pageConfig?.ref ?? { name: PAGE_CONFIG_RESOURCE, version: 0 });
  const pageConfigRef = useRef(pageConfig);
  pageConfigRef.current = pageConfig;
  const pageConfigDirtyRef = useRef(pageConfigDirty);
  pageConfigDirtyRef.current = pageConfigDirty;
  /** Set when the author confirmed leaving, so the browser does not ask a second time. */
  const suppressUnload = useRef(false);
  /** True while a save (or a keep-mine save) is running; a second save waits its turn instead of racing it. */
  const savingRef = useRef(false);
  const viewsRef = useRef(views);
  viewsRef.current = views;
  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;

  const markDirty = useCallback((path: string, on: boolean) => {
    setDirty((current) => {
      if (current.has(path) === on) return current;
      const next = new Set(current);
      if (on) next.add(path);
      else next.delete(path);
      return next;
    });
  }, []);

  const replaceView = useCallback(
    (view: ViewDocument) => {
      // An inherited view is read-only until it is overridden.
      if (inheritedRef.current.has(view.metadata.path)) return;
      setViews((current) => current.map((candidate) => (candidate.metadata.path === view.metadata.path ? view : candidate)));
      markDirty(view.metadata.path, true);
    },
    [markDirty],
  );

  const overrideView = useCallback(
    (path: string) => {
      if (!inheritedRef.current.has(path)) return;
      setInherited((current) => {
        const next = new Map(current);
        next.delete(path);
        return next;
      });
      // The ref already holds version 0 under the inherited resource name, so the save creates the project's copy.
      markDirty(path, true);
    },
    [markDirty],
  );

  const replaceStyleClasses = useCallback((next: StyleClassSet) => {
    setStyleClasses(next);
    setStyleClassesDirty(true);
  }, []);

  const replaceThemes = useCallback((next: CustomThemeSet) => {
    setThemes(next);
    setStyleClassesDirty(true);
  }, []);

  const replaceScriptHooks = useCallback((next: ScriptHookSet) => {
    setScriptHooks(next);
    setScriptHooksDirty(true);
  }, []);

  const replacePageConfig = useCallback((next: PageConfigSpec) => {
    setPageConfig(next);
    setPageConfigDirty(true);
  }, []);

  const createView = useCallback(
    (raw: string) => {
      const name = raw.trim();
      const error = viewNameProblem(name, viewsRef.current.map((view) => view.metadata.name));
      if (error) return { error, path: "" };
      const taken = [...viewsRef.current.map((view) => view.metadata.path), ...[...refs.current.values()].map((ref) => ref.name)];
      const path = resourceNameFor(name, taken);
      refs.current.set(path, { name: path, version: 0 });
      setViews((current) => [...current, blankView(path, name)]);
      markDirty(path, true);
      return { error: null, path };
    },
    [markDirty],
  );

  const saveOne = useCallback(
    async (view: ViewDocument): Promise<void> => {
      if (!client) return;
      const path = view.metadata.path;
      const ref = refs.current.get(path) ?? { name: resourceNameFor(path, [...refs.current.values()].map((r) => r.name)), version: 0 };
      refs.current.set(path, ref);
      const stored = await saveView(client, project, view, ref);
      refs.current.set(path, stored);
      // An edit made while the request was in flight stays dirty.
      if (viewsRef.current.find((candidate) => candidate.metadata.path === path) === view) markDirty(path, false);
    },
    [client, project, markDirty],
  );

  const saveStyles = useCallback(async (): Promise<void> => {
    if (!client) return;
    const sent = styleClassesRef.current;
    styleRef.current = await saveStyleClasses(client, project, sent, styleRef.current, themesRef.current);
    // An edit made while the request was in flight stays dirty.
    if (styleClassesRef.current === sent) setStyleClassesDirty(false);
  }, [client, project]);

  const saveHooks = useCallback(async (): Promise<void> => {
    if (!client) return;
    const sent = scriptHooksRef.current;
    hooksRef.current = await saveScriptHooks(client, project, sent, hooksRef.current);
    // An edit made while the request was in flight stays dirty.
    if (scriptHooksRef.current === sent) setScriptHooksDirty(false);
  }, [client, project]);

  const savePages = useCallback(async (): Promise<void> => {
    if (!client || !pageConfigRef.current) return;
    const sent = pageConfigRef.current;
    pageRef.current = await savePageConfig(client, project, sent, pageRef.current);
    if (pageConfigRef.current === sent) setPageConfigDirty(false);
  }, [client, project]);

  const saveFiltered = useCallback(async (include: (id: string) => boolean) => {
    const pending = viewsRef.current.filter((view) => dirtyRef.current.has(view.metadata.path) && include(view.metadata.path));
    const stylesPending = styleClassesDirtyRef.current && include(STYLE_CLASSES_KEY);
    const hooksPending = scriptHooksDirtyRef.current && include(SCRIPT_HOOKS_KEY);
    const pagesPending = pageConfigDirtyRef.current && include(PAGE_CONFIG_KEY);
    if (pending.length === 0 && !stylesPending && !hooksPending && !pagesPending) {
      setSaveState({ kind: "saved", count: 0 });
      return;
    }
    setSaveState({ kind: "saving" });
    // Every pending resource is tried; conflicts and other failures are reported together at the end.
    const conflicts: Conflict[] = [];
    const errors: string[] = [];
    let saved = 0;
    for (const view of pending) {
      try {
        await saveOne(view);
        saved += 1;
      } catch (error) {
        if (error instanceof SaveConflict) conflicts.push({ kind: "view", path: error.path, theirs: error.theirs });
        else errors.push(`${view.metadata.name}: ${message(error)}`);
      }
    }
    if (stylesPending) {
      try {
        await saveStyles();
        saved += 1;
      } catch (error) {
        if (error instanceof StyleClassesConflict) conflicts.push({ kind: "styleClasses", path: STYLE_CLASSES_KEY, theirs: error.theirs });
        else errors.push(`style classes: ${message(error)}`);
      }
    }
    if (hooksPending) {
      try {
        await saveHooks();
        saved += 1;
      } catch (error) {
        if (error instanceof ScriptHooksConflict) conflicts.push({ kind: "scriptHooks", path: SCRIPT_HOOKS_KEY, theirs: error.theirs });
        else errors.push(`script hooks: ${message(error)}`);
      }
    }
    if (pagesPending) {
      try {
        await savePages();
        saved += 1;
      } catch (error) {
        if (error instanceof PageConfigConflict) conflicts.push({ kind: "pageConfig", path: PAGE_CONFIG_KEY, theirs: error.theirs });
        else errors.push(`page configuration: ${message(error)}`);
      }
    }
    if (conflicts.length > 0) setSaveState(errors.length > 0 ? { kind: "conflict", conflicts, errors } : { kind: "conflict", conflicts });
    else if (errors.length > 0) setSaveState({ kind: "error", message: errors.join("; ") });
    else setSaveState({ kind: "saved", count: saved });
  }, [saveOne, saveStyles, saveHooks, savePages]);

  const runSave = useCallback(
    async (include: (id: string) => boolean) => {
      // Two quick saves (button + Ctrl/Cmd+S) would send the same version twice, and the second would 409
      // against the first. The running save already covers what is dirty, so a second one is ignored.
      if (!client || savingRef.current) return;
      savingRef.current = true;
      try {
        await saveFiltered(include);
      } finally {
        savingRef.current = false;
      }
    },
    [client, saveFiltered],
  );

  const save = useCallback(() => runSave(() => true), [runSave]);

  const storedName = useCallback((path: string) => {
    const ref = refs.current.get(path);
    return ref && ref.version > 0 ? ref.name : undefined;
  }, []);

  const saveSome = useCallback((ids: ReadonlySet<string>) => runSave((id) => ids.has(id)), [runSave]);

  const dropConflict = useCallback((path: string) => {
    setSaveState((current) => {
      if (current.kind !== "conflict") return current;
      const rest = current.conflicts.filter((conflict) => conflict.path !== path);
      if (rest.length > 0) return { ...current, conflicts: rest };
      // Failures from the same save stay visible once the last conflict is resolved.
      return current.errors?.length ? { kind: "error", message: current.errors.join("; ") } : { kind: "idle" };
    });
  }, []);

  const keepMine = useCallback(
    async (path: string) => {
      const state = saveState;
      if (state.kind !== "conflict") return;
      const conflict = state.conflicts.find((entry) => entry.path === path);
      if (!conflict || savingRef.current) return;
      savingRef.current = true;
      try {
        if (conflict.kind === "styleClasses") {
          styleRef.current = conflict.theirs ? conflict.theirs.ref : { name: styleRef.current.name, version: 0 };
          await saveStyles();
        } else if (conflict.kind === "scriptHooks") {
          hooksRef.current = conflict.theirs ? conflict.theirs.ref : { name: hooksRef.current.name, version: 0 };
          await saveHooks();
        } else if (conflict.kind === "pageConfig") {
          pageRef.current = conflict.theirs ? conflict.theirs.ref : { name: pageRef.current.name, version: 0 };
          await savePages();
        } else {
          const view = viewsRef.current.find((candidate) => candidate.metadata.path === path);
          if (!view) return;
          const ref = refs.current.get(path);
          refs.current.set(path, conflict.theirs ? conflict.theirs.ref : { name: ref?.name ?? resourceNameFor(path, []), version: 0 });
          await saveOne(view);
        }
        dropConflict(path);
      } catch (error) {
        if (error instanceof SaveConflict || error instanceof StyleClassesConflict || error instanceof ScriptHooksConflict || error instanceof PageConfigConflict) {
          const again: Conflict =
            error instanceof SaveConflict
              ? { kind: "view", path, theirs: error.theirs }
              : error instanceof StyleClassesConflict
                ? { kind: "styleClasses", path: STYLE_CLASSES_KEY, theirs: error.theirs }
                : error instanceof ScriptHooksConflict
                  ? { kind: "scriptHooks", path: SCRIPT_HOOKS_KEY, theirs: error.theirs }
                  : { kind: "pageConfig", path: PAGE_CONFIG_KEY, theirs: error.theirs };
          // Functional update: the prompt may have changed since this click was handled.
          setSaveState((current) =>
            current.kind === "conflict" ? { ...current, conflicts: current.conflicts.map((entry) => (entry.path === path ? again : entry)) } : current,
          );
          return;
        }
        setSaveState({ kind: "error", message: message(error) });
      } finally {
        savingRef.current = false;
      }
    },
    [saveState, saveOne, saveStyles, saveHooks, savePages, dropConflict],
  );

  const takeTheirs = useCallback(
    (path: string) => {
      if (saveState.kind !== "conflict") return;
      const conflict = saveState.conflicts.find((entry) => entry.path === path);
      // A resource deleted on the gateway has no stored copy to take; keep mine or cancel instead.
      if (!conflict?.theirs) return;
      if (conflict.kind === "styleClasses") {
        styleRef.current = conflict.theirs.ref;
        setStyleClasses(conflict.theirs.styleClasses);
        setThemes(conflict.theirs.themes ?? {});
        setStyleClassesDirty(false);
        dropConflict(path);
        return;
      }
      if (conflict.kind === "scriptHooks") {
        hooksRef.current = conflict.theirs.ref;
        setScriptHooks(conflict.theirs.hooks);
        setScriptHooksDirty(false);
        dropConflict(path);
        return;
      }
      if (conflict.kind === "pageConfig") {
        pageRef.current = conflict.theirs.ref;
        setPageConfig(conflict.theirs.spec);
        setPageConfigDirty(false);
        dropConflict(path);
        return;
      }
      const theirs = conflict.theirs;
      refs.current.set(path, theirs.ref);
      setViews((current) => current.map((candidate) => (candidate.metadata.path === path ? theirs.view : candidate)));
      markDirty(path, false);
      dropConflict(path);
    },
    [saveState, markDirty, dropConflict],
  );

  const cancelConflict = useCallback(() => setSaveState({ kind: "idle" }), []);

  // Leaving the page with unsaved edits asks first. Only for a gateway-backed Studio: offline, nothing can be saved.
  // The listener reads refs so Save As can suppress it before the navigation, without waiting for a render.
  const hasUnsaved = client !== null && (dirty.size > 0 || styleClassesDirty || scriptHooksDirty || pageConfigDirty);
  const hasUnsavedRef = useRef(hasUnsaved);
  hasUnsavedRef.current = hasUnsaved;
  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!hasUnsavedRef.current || suppressUnload.current) return;
      event.preventDefault();
      // Older browsers need returnValue set to show the prompt.
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, []);

  const snapshot = useCallback((): ProjectSnapshot => {
    return {
      views: viewsRef.current.map((view) => ({
        view,
        ref: refs.current.get(view.metadata.path) ?? { name: view.metadata.path, version: 0 },
        dirty: dirtyRef.current.has(view.metadata.path),
        inherited: inheritedRef.current.has(view.metadata.path),
      })),
      styleClasses: {
        classes: styleClassesRef.current,
        themes: themesRef.current,
        ref: styleRef.current,
        dirty: styleClassesDirtyRef.current,
      },
      scriptHooks: { hooks: scriptHooksRef.current, ref: hooksRef.current, dirty: scriptHooksDirtyRef.current },
      pageConfig: pageConfigRef.current
        ? { spec: pageConfigRef.current, ref: pageRef.current, dirty: pageConfigDirtyRef.current }
        : null,
    };
  }, []);

  const saveAs = useCallback(
    async (rawName: string) => {
      if (!client) throw new Error("The gateway is not reachable.");
      if (savingRef.current) throw new Error("A save is already running.");
      savingRef.current = true;
      try {
        const name = await saveProjectAs(client, project, rawName, snapshot());
        // The edits now live on the copy. This tab is about to leave, so do not write them back here.
        suppressUnload.current = true;
        setDirty(new Set());
        setStyleClassesDirty(false);
        setScriptHooksDirty(false);
        setPageConfigDirty(false);
        return name;
      } finally {
        savingRef.current = false;
      }
    },
    [client, project, snapshot],
  );

  const allowLeave = useCallback(() => {
    suppressUnload.current = true;
  }, []);

  const dirtyResources: DirtyResource[] = [
    ...views.filter((view) => dirty.has(view.metadata.path)).map((view) => ({ id: view.metadata.path, label: view.metadata.name, kind: "view" as const })),
    ...(styleClassesDirty ? [{ id: STYLE_CLASSES_KEY, label: "Style classes", kind: "styleClasses" as const }] : []),
    ...(scriptHooksDirty ? [{ id: SCRIPT_HOOKS_KEY, label: "Script hooks", kind: "scriptHooks" as const }] : []),
    ...(pageConfigDirty ? [{ id: PAGE_CONFIG_KEY, label: "Page configuration", kind: "pageConfig" as const }] : []),
  ];

  return {
    project: { name: init.name, views, styleClasses, themes },
    dirty,
    inherited,
    overrideView,
    styleClassesDirty,
    scriptHooks,
    scriptHooksDirty,
    pageConfig,
    pageConfigDirty,
    dirtyResources,
    saveState,
    persistent: client !== null,
    replaceView,
    replaceStyleClasses,
    replaceThemes,
    replaceScriptHooks,
    replacePageConfig,
    createView,
    save,
    saveSome,
    saveAs,
    allowLeave,
    keepMine,
    takeTheirs,
    cancelConflict,
    storedName,
  };
}

function seedRefs(init: SavedProjectInit): Map<string, StoredRef> {
  const refs = new Map<string, StoredRef>();
  for (const entry of init.views) refs.set(entry.view.metadata.path, entry.ref);
  for (const draft of init.drafts ?? []) {
    refs.set(draft.metadata.path, { name: resourceNameFor(draft.metadata.path, [...refs.values()].map((ref) => ref.name)), version: 0 });
  }
  return refs;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
