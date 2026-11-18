import type { Action, ComponentEvent, ViewDocument, ViewNode } from "@oip/hmi-runtime";
import { walkNodes } from "@oip/hmi-runtime";

/**
 * Event handlers as the Designer edits them (D-5). A component or view keeps `events: [{event, actions}]`; the
 * runtime runs the actions of an event from top to bottom (hmi-runtime `planActions`). The Designer keeps one entry
 * per event name and drops an entry once its last action is removed.
 */

/** Events a component fires (hmi-runtime `ComponentView`). */
export const COMPONENT_EVENTS = ["onClick", "onChange"] as const;
/** Events a view fires when it opens and closes. */
export const VIEW_EVENTS = ["onStartup", "onShutdown"] as const;

export type ActionKind = Action["kind"];

export const ACTION_KINDS: readonly { kind: ActionKind; label: string }[] = [
  { kind: "script", label: "Script" },
  { kind: "navigation", label: "Navigation" },
  { kind: "popup", label: "Popup" },
  { kind: "tag-write", label: "Tag write" },
  { kind: "message", label: "Message" },
  { kind: "call-method", label: "Call method" },
  { kind: "logout", label: "Logout" },
];

/** Message types: a letter, then letters, digits, `_`, `-` and `.` (hmi-runtime parses them the same way). */
const MESSAGE_TYPE = /^[A-Za-z][A-Za-z0-9_.-]{0,99}$/;

export function messageTypeProblem(type: string): string | null {
  if (!type) return "Enter a message type.";
  if (!MESSAGE_TYPE.test(type)) return "Start with a letter; use letters, digits, '_', '-' and '.'.";
  return null;
}

/** Method names: a letter, then letters, digits and `_` (hmi-runtime parses them the same way). */
const METHOD_NAME = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const METHOD_PARAM = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

export function methodNameProblem(name: string): string | null {
  if (!name) return "Enter a method name.";
  return METHOD_NAME.test(name) ? null : "Start with a letter; use letters, digits and '_'.";
}

export function methodParamProblem(name: string): string | null {
  if (!name) return "Enter a parameter name.";
  return METHOD_PARAM.test(name) ? null : "Use letters, digits and '_'.";
}

export function actionLabel(kind: ActionKind): string {
  return ACTION_KINDS.find((entry) => entry.kind === kind)?.label ?? kind;
}

/** Hook names the gateway accepts (its `ScriptHooks` resource uses the same rule). */
const HOOK_NAME = /^[A-Za-z][A-Za-z0-9_.-]*$/;
export const HOOK_NAME_MAX = 100;

export function hookNameProblem(name: string): string | null {
  if (!name) return "Enter a hook name.";
  if (name.length > HOOK_NAME_MAX) return `Hook names are at most ${HOOK_NAME_MAX} characters.`;
  if (!HOOK_NAME.test(name)) return "Use letters, digits, '_', '-' and '.', starting with a letter.";
  return null;
}

/** The actions for [eventName] (empty when there is no entry). */
export function actionsFor(events: readonly ComponentEvent[] | undefined, eventName: string): Action[] {
  return (events ?? []).filter((entry) => entry.event === eventName).flatMap((entry) => entry.actions);
}

/** [events] with the actions of [eventName] replaced; other events keep their order. */
export function withActions(events: readonly ComponentEvent[] | undefined, eventName: string, actions: Action[]): ComponentEvent[] {
  const next: ComponentEvent[] = [];
  let placed = false;
  for (const entry of events ?? []) {
    if (entry.event !== eventName) {
      next.push(entry);
      continue;
    }
    // Several entries for one event are merged into the first.
    if (!placed && actions.length > 0) next.push({ event: eventName, actions });
    placed = true;
  }
  if (!placed && actions.length > 0) next.push({ event: eventName, actions });
  return next;
}

/** The view with component [nodeId]'s events replaced (removed when empty). */
export function withNodeEvents(view: ViewDocument, nodeId: string, events: ComponentEvent[]): ViewDocument {
  const next = structuredClone(view);
  let found = false;
  walkNodes(next.spec.root, (node: ViewNode) => {
    if (node.id !== nodeId || node.kind !== "component") return;
    found = true;
    if (events.length > 0) node.events = events;
    else delete node.events;
  });
  if (!found) throw new Error(`component ${nodeId} not found`);
  return next;
}

/** The view with its own startup and shutdown events replaced (removed when empty). */
export function withViewEvents(view: ViewDocument, events: ComponentEvent[]): ViewDocument {
  const next = structuredClone(view);
  if (events.length > 0) next.spec.events = events;
  else delete next.spec.events;
  return next;
}

/** Every script hook name the view's event actions and script transforms use. */
export function viewHooks(view: ViewDocument): string[] {
  const names: string[] = [];
  const collect = (value: unknown) => {
    if (Array.isArray(value)) {
      for (const item of value) collect(item);
      return;
    }
    if (!value || typeof value !== "object") return;
    const record = value as Record<string, unknown>;
    if (record.kind === "script" && typeof record.hook === "string") names.push(record.hook);
    for (const child of Object.values(record)) collect(child);
  };
  collect(view.spec);
  return names;
}

/**
 * A hook name for a new script action, from the node and event (`pump-start.onClick`), not used in the view yet.
 * The gateway finds a view's script source by hook name and the first entry wins, so names stay unique per view.
 */
export function suggestHook(view: ViewDocument, nodeId: string, eventName: string): string {
  const base = `${nodeId.replace(/[^A-Za-z0-9_.-]+/g, "-").replace(/^[^A-Za-z]+/, "") || "script"}.${eventName}`.slice(0, HOOK_NAME_MAX - 4);
  const used = new Set(viewHooks(view));
  if (!used.has(base)) return base;
  for (let index = 2; ; index += 1) {
    const candidate = `${base}-${index}`;
    if (!used.has(candidate)) return candidate;
  }
}

/** The provider prefix a new tag write starts with; the author completes the path. */
export const NEW_TAG_PATH = "[default]";

/**
 * A new action of [kind]. Every required field gets a value, so the view still opens (hmi-runtime `parseView`
 * rejects empty required fields) while the author fills it in: [hook] for a script, [view] (the view being edited)
 * for a popup.
 */
export function newAction(kind: ActionKind, defaults: { hook: string; view: string }): Action {
  // No `source` until one is typed: the gateway takes any saved source, even an empty one, over the project hook.
  if (kind === "script") return { kind: "script", hook: defaults.hook };
  if (kind === "navigation") return { kind: "navigation", mode: "page", page: "/" };
  if (kind === "popup") return { kind: "popup", mode: "open", id: "popup", view: defaults.view };
  if (kind === "message") return { kind: "message", messageType: "refresh", scope: "page" };
  if (kind === "logout") return { kind: "logout" };
  if (kind === "call-method") return { kind: "call-method", method: "run" };
  return { kind: "tag-write", path: NEW_TAG_PATH, value: true };
}

/** A navigation action switched to [mode], with that mode's required field filled in when it was empty. */
export function withNavigationMode(action: Extract<Action, { kind: "navigation" }>, mode: Extract<Action, { kind: "navigation" }>["mode"], view: string) {
  const next = { ...action, mode };
  if (mode === "page" && !next.page) next.page = "/";
  if (mode === "view" && !next.view) next.view = view;
  if (mode === "url" && !next.url) next.url = "/";
  if (mode === "browser" && !next.direction) next.direction = "back";
  return next;
}

/** What still blocks [action] from running as authored, or null. Shown under the action, not enforced on save. */
export function actionProblem(action: Action): string | null {
  if (action.kind === "script") return hookNameProblem(action.hook);
  if (action.kind === "navigation") {
    if (action.mode === "page" && !action.page) return "Enter a page URL.";
    if (action.mode === "view" && !action.view) return "Enter a view path.";
    if (action.mode === "url" && !action.url) return "Enter a URL.";
    return null;
  }
  if (action.kind === "popup") {
    if (!action.id) return "Enter a popup id.";
    if (action.mode !== "close" && !action.view) return "Enter the view the popup shows.";
    return null;
  }
  if (action.kind === "message") return messageTypeProblem(action.messageType);
  if (action.kind === "logout") return null;
  if (action.kind === "call-method") return methodNameProblem(action.method);
  return action.path && !/^\[[^\]]*\]$/.test(action.path) ? null : "Enter a tag path.";
}
