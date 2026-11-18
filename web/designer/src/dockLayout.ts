/** Studio panel layout (doc source: FEATURES DES-010). A panel sits in one dock, or is hidden; the layout is kept per browser. */

export type DockId = "left" | "right" | "bottom";
export const DOCKS: readonly DockId[] = ["left", "right", "bottom"];

export const PANEL_IDS = ["project", "outline", "inspector", "tools"] as const;
export type PanelId = (typeof PANEL_IDS)[number];

export const PANEL_TITLES: Record<PanelId, string> = {
  project: "Project browser",
  outline: "Outline",
  inspector: "Property editor",
  tools: "Palette and tools",
};

export interface DockLayout {
  left: PanelId[];
  right: PanelId[];
  bottom: PanelId[];
  hidden: PanelId[];
  collapsed: PanelId[];
}

export const STORAGE_KEY = "oip.designer.dock-layout.v2";

export function defaultLayout(): DockLayout {
  return { left: ["project", "outline", "tools"], right: ["inspector"], bottom: [], hidden: [], collapsed: [] };
}

export function isDefaultLayout(layout: DockLayout): boolean {
  return JSON.stringify(layout) === JSON.stringify(defaultLayout());
}

/** Moves [id] to the end of [dock], showing it if it was hidden. */
export function moveToDock(layout: DockLayout, id: PanelId, dock: DockId): DockLayout {
  const next = withoutPanel(layout, id);
  return { ...next, [dock]: [...next[dock], id] };
}

export function hidePanel(layout: DockLayout, id: PanelId): DockLayout {
  const next = withoutPanel(layout, id);
  return { ...next, hidden: [...next.hidden, id] };
}

/** Shows a hidden panel in the dock it was last in, falling back to the right dock. */
export function showPanel(layout: DockLayout, id: PanelId): DockLayout {
  return layout.hidden.includes(id) ? moveToDock(layout, id, id === "inspector" ? "right" : "left") : layout;
}

export function toggleCollapsed(layout: DockLayout, id: PanelId): DockLayout {
  return { ...layout, collapsed: layout.collapsed.includes(id) ? layout.collapsed.filter((p) => p !== id) : [...layout.collapsed, id] };
}

function withoutPanel(layout: DockLayout, id: PanelId): DockLayout {
  const drop = (list: PanelId[]) => list.filter((p) => p !== id);
  return { ...layout, left: drop(layout.left), right: drop(layout.right), bottom: drop(layout.bottom), hidden: drop(layout.hidden) };
}

/** Reads a saved layout. Anything malformed gives the default; panels the saved layout lacks are added to the right dock. */
export function parseLayout(raw: string | null): DockLayout {
  if (!raw) return defaultLayout();
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) return defaultLayout();
    const record = value as Record<string, unknown>;
    const seen = new Set<PanelId>();
    const list = (key: string): PanelId[] => {
      const items = record[key];
      if (!Array.isArray(items)) return [];
      const out: PanelId[] = [];
      for (const item of items) {
        if (typeof item === "string" && (PANEL_IDS as readonly string[]).includes(item) && !seen.has(item as PanelId)) {
          seen.add(item as PanelId);
          out.push(item as PanelId);
        }
      }
      return out;
    };
    const layout: DockLayout = { left: list("left"), right: list("right"), bottom: list("bottom"), hidden: list("hidden"), collapsed: [] };
    for (const id of PANEL_IDS) if (!seen.has(id)) layout.right.push(id);
    const collapsed = record.collapsed;
    if (Array.isArray(collapsed)) layout.collapsed = collapsed.filter((id): id is PanelId => (PANEL_IDS as readonly string[]).includes(id as string));
    return layout;
  } catch {
    return defaultLayout();
  }
}

export function loadLayout(): DockLayout {
  try {
    return parseLayout(window.localStorage.getItem(STORAGE_KEY));
  } catch {
    return defaultLayout();
  }
}

export function saveLayout(layout: DockLayout): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(layout));
  } catch {
    // Private windows and blocked storage: the layout just is not remembered.
  }
}
