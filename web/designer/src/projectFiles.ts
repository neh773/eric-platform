import type { CustomThemeSet, StyleClassSet, ViewDocument } from "@oip/hmi-runtime";
import { GatewayError, type GatewayClient } from "./gatewayClient";
import {
  DESIGNER_MODULE,
  VIEW_KIND,
  loadPageConfig,
  loadScriptHooks,
  loadStyleClasses,
  projectScope,
  savePageConfig,
  saveScriptHooks,
  saveStyleClasses,
  saveView,
  validProjectName,
  type PageConfigSpec,
  type ScriptHookSet,
  type StoredRef,
} from "./persistence";

/**
 * Save As and the Open/Create project window.
 *
 * Save As shows a name that starts with "Copy_Of_" and saves the open project under the name the user confirms
 * (doc source: FEATURES DES-009). Project names here follow
 * the gateway's existing rule (lowercase letters, digits, and '-'), so the suggested prefix is "copy-of-". A name
 * that is already a project is refused: the copy route answers 409 and Save As does not overwrite it.
 *
 * The Open/Create window lists projects and creates one from a name, title, description and optional parent
 * (doc source: FEATURES DES-011).
 */

export const SAVE_AS_PREFIX = "copy-of-";
export const RECENT_PROJECTS_KEY = "oip.studio.recentProjects";
export const RECENT_PROJECTS_LIMIT = 8;

/** A project name the gateway will accept, or a sentence saying why not. */
export function projectNameProblem(name: string): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Enter a project name.";
  if (!validProjectName(trimmed)) {
    return "A project name is lowercase letters, digits, and '-', 1 to 63 characters, starting and ending with a letter or digit.";
  }
  return null;
}

/**
 * The name Save As offers: "copy-of-" plus the current name, shortened so it stays a project name.
 * the reference product prefixes "Copy_Of_"; that spelling is not a project name on this gateway.
 */
export function suggestedSaveAsName(current: string): string {
  const room = 63 - SAVE_AS_PREFIX.length;
  let base = current
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, room)
    .replace(/-+$/g, "");
  if (!base) base = "project";
  const name = `${SAVE_AS_PREFIX}${base}`;
  return validProjectName(name) ? name : "copy-of-project";
}

/** The open project already uses [name]. Save As does not replace it. */
export class ProjectNameConflict extends Error {
  readonly project: string;

  constructor(project: string) {
    super(`A project named "${project}" already exists.`);
    this.name = "ProjectNameConflict";
    this.project = project;
  }
}

export interface SnapshotView {
  view: ViewDocument;
  ref: StoredRef;
  dirty: boolean;
  /** Stored in an ancestor. Save As leaves it there unless this project has overridden it. */
  inherited: boolean;
}

export interface ProjectSnapshot {
  views: SnapshotView[];
  styleClasses: { classes: StyleClassSet; themes: CustomThemeSet; ref: StoredRef; dirty: boolean };
  scriptHooks: { hooks: ScriptHookSet; ref: StoredRef; dirty: boolean };
  pageConfig: { spec: PageConfigSpec; ref: StoredRef; dirty: boolean } | null;
}

/**
 * Save the open project under [rawName]. The original project's stored resources are not written.
 * Unsaved edits are written only onto the new project. A name that fails [projectNameProblem], matches
 * [from], or is already a project is refused before anything is copied.
 */
export async function saveProjectAs(client: GatewayClient, from: string, rawName: string, snapshot: ProjectSnapshot): Promise<string> {
  const to = rawName.trim();
  const problem = projectNameProblem(to);
  if (problem) throw new Error(problem);
  if (to === from) throw new Error("Choose a different name from the open project.");
  if (await client.projectInfo(to)) throw new ProjectNameConflict(to);
  const source = await client.projectInfo(from);
  try {
    if (source) await client.copyProject(from, to);
    else await client.createProject({ name: to });
  } catch (error) {
    if (error instanceof GatewayError && error.status === 409) throw new ProjectNameConflict(to);
    throw error;
  }
  await writeEditorState(client, to, snapshot, source !== null);
  return to;
}

async function writeEditorState(client: GatewayClient, project: string, snapshot: ProjectSnapshot, copied: boolean): Promise<void> {
  for (const entry of snapshot.views) {
    if (entry.inherited && !entry.dirty) continue;
    if (copied && !entry.dirty) continue;
    const current = await client.getResource(DESIGNER_MODULE, projectScope(project), VIEW_KIND, entry.ref.name);
    await saveView(client, project, entry.view, { name: entry.ref.name, version: current?.metadata.version ?? 0 });
  }
  const styles = snapshot.styleClasses;
  const stylesMatter = styles.dirty || Object.keys(styles.classes).length > 0 || Object.keys(styles.themes).length > 0;
  if ((!copied && stylesMatter) || (copied && styles.dirty)) {
    const current = await loadStyleClasses(client, project);
    await saveStyleClasses(client, project, styles.classes, current?.ref ?? { name: styles.ref.name, version: 0 }, styles.themes);
  }
  const hooks = snapshot.scriptHooks;
  if ((!copied && (hooks.dirty || Object.keys(hooks.hooks).length > 0)) || (copied && hooks.dirty)) {
    const current = await loadScriptHooks(client, project);
    await saveScriptHooks(client, project, hooks.hooks, current?.ref ?? { name: hooks.ref.name, version: 0 });
  }
  const pages = snapshot.pageConfig;
  if (pages && ((!copied && (pages.dirty || pages.spec.pages.length > 0)) || (copied && pages.dirty))) {
    const current = await loadPageConfig(client, project);
    await savePageConfig(client, project, pages.spec, current?.ref ?? { name: pages.ref.name, version: 0 });
  }
}

type Store = Pick<Storage, "getItem" | "setItem">;

/** Projects opened most recently, newest first, ignoring anything that is not a project name. */
export function readRecentProjects(storage: Store): string[] {
  try {
    const raw = storage.getItem(RECENT_PROJECTS_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((name): name is string => typeof name === "string" && validProjectName(name)).slice(0, RECENT_PROJECTS_LIMIT);
  } catch {
    return [];
  }
}

/** Remember [name] as the most recently opened project. */
export function rememberProject(storage: Store, name: string): string[] {
  if (!validProjectName(name)) return readRecentProjects(storage);
  const next = [name, ...readRecentProjects(storage).filter((other) => other !== name)].slice(0, RECENT_PROJECTS_LIMIT);
  storage.setItem(RECENT_PROJECTS_KEY, JSON.stringify(next));
  return next;
}

/**
 * Address that opens a project in this Studio. It keeps the current path, so the Studio served at /studio/ on a
 * gateway stays there instead of landing on the gateway's home page.
 */
export function projectUrl(name: string, pathname: string = window.location.pathname): string {
  return `${pathname}?project=${encodeURIComponent(name)}`;
}
