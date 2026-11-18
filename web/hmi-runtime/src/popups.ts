import type { PopupAction } from "./types";

/** Popup identity, parameters, and title-bar rules.
 * Defaults match `system.screen.openPopup`: showCloseIcon and draggable are true;
 * resizable, modal, overlayDismiss, and viewportBound are false.
 */

export interface PopupModel {
  id: string;
  view: string;
  params: Record<string, unknown>;
  title?: string;
  showClose: boolean;
  draggable: boolean;
  resizable: boolean;
  modal: boolean;
  backgroundDismissible: boolean;
  viewportBound: boolean;
  x: number;
  y: number;
  width: number;
  height: number;
  positioned: boolean;
}

export function reducePopups(current: readonly PopupModel[], action: PopupAction, viewport: { width: number; height: number }): PopupModel[] {
  if (action.mode === "close") return current.filter((popup) => popup.id !== action.id);
  if (action.mode === "toggle" && current.some((popup) => popup.id === action.id)) {
    return current.filter((popup) => popup.id !== action.id);
  }
  const next = openPopup(action, viewport);
  return [...current.filter((popup) => popup.id !== action.id), next];
}

export function clampPopup(popup: PopupModel, viewport: { width: number; height: number }): PopupModel {
  if (!popup.viewportBound) return popup;
  const width = Math.min(popup.width, viewport.width);
  const height = Math.min(popup.height, viewport.height);
  const x = Math.min(Math.max(0, popup.x), Math.max(0, viewport.width - width));
  const y = Math.min(Math.max(0, popup.y), Math.max(0, viewport.height - height));
  return { ...popup, width, height, x, y };
}

function openPopup(action: PopupAction, viewport: { width: number; height: number }): PopupModel {
  const width = action.position?.width ?? 420;
  const height = action.position?.height ?? 280;
  const positioned = action.position?.left !== undefined || action.position?.top !== undefined;
  const x = action.position?.left ?? Math.max(0, (viewport.width - width) / 2);
  const y = action.position?.top ?? Math.max(0, (viewport.height - height) / 2);
  const popup: PopupModel = {
    id: action.id,
    view: action.view ?? "",
    params: { ...(action.params ?? {}) },
    title: action.title,
    showClose: action.showClose !== false,
    draggable: action.draggable !== false,
    resizable: action.resizable === true,
    modal: action.modal === true,
    backgroundDismissible: action.backgroundDismissible === true,
    viewportBound: action.viewportBound === true,
    x,
    y,
    width,
    height,
    positioned,
  };
  return clampPopup(popup, viewport);
}

/** Fill `{param}` in a popup id, title, and string params from the view that opened it. */
export function withViewParams(action: PopupAction, params: Record<string, unknown>): PopupAction {
  const fill = (value: string) => value.replace(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (match, name: string) => {
    const found = params[name];
    return typeof found === "string" || typeof found === "number" ? String(found) : match;
  });
  const next: PopupAction = { ...action, id: fill(action.id) };
  if (action.title) next.title = fill(action.title);
  if (action.view) next.view = fill(action.view);
  if (action.params) {
    const filled: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(action.params)) filled[key] = typeof value === "string" ? fill(value) : value;
    next.params = filled;
  }
  return next;
}

export function popupShowsTitleBar(popup: Pick<PopupModel, "title" | "showClose" | "draggable">): boolean {
  return Boolean(popup.title) || popup.showClose || popup.draggable;
}
