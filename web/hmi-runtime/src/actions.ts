import type { Action, CallMethodAction, ComponentEvent, LogoutAction, MessageAction, NavigationAction, PopupAction, ScriptAction, TagWriteAction } from "./types";

/** Actions for one event, in list order, skipping disabled ones.
 * Callers invoke them without waiting: a slow script does not block the next action from starting.
 */

export interface ActionPlan {
  actions: Action[];
  stopPropagation: boolean;
}

export function planActions(events: readonly ComponentEvent[] | undefined, eventName: string): ActionPlan {
  const actions: Action[] = [];
  let stopPropagation = false;
  for (const entry of events ?? []) {
    if (entry.event !== eventName) continue;
    for (const action of entry.actions) {
      if (action.enabled === false) continue;
      actions.push(action);
      if (action.stopPropagation) stopPropagation = true;
    }
  }
  return { actions, stopPropagation };
}

export function isScript(action: Action): action is ScriptAction {
  return action.kind === "script";
}

export function isNavigation(action: Action): action is NavigationAction {
  return action.kind === "navigation";
}

export function isPopup(action: Action): action is PopupAction {
  return action.kind === "popup";
}

export function isTagWrite(action: Action): action is TagWriteAction {
  return action.kind === "tag-write";
}

export function isMessage(action: Action): action is MessageAction {
  return action.kind === "message";
}

export function isLogout(action: Action): action is LogoutAction {
  return action.kind === "logout";
}

export function isCallMethod(action: Action): action is CallMethodAction {
  return action.kind === "call-method";
}
