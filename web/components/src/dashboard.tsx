import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from "react";
import { EmbeddedView } from "@oip/hmi-runtime";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { readList } from "./containers";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Dashboard (HMP-019): a grid of widgets that session users arrange themselves. Written from the public component
 * page; the code and wording are our own.
 * doc source: FEATURES HMP-019
 *
 * - `available` is the designer's list of widgets a user may add: `{id, title, view, params, w, h}`. A widget shows
 *   the project view at `view` with its `params`, through the runtime's embedded view (so it shares the session's
 *   tags and actions); `w` and `h` are its starting size in grid cells.
 * - `layout` is the designer's default arrangement: `{key, id, x, y, w, h, params}` per placed widget. `id` names an
 *   entry of `available`; unknown ids, repeated keys and malformed entries are dropped.
 * - The grid has `columns` columns of equal width and rows of `rowHeight` pixels with `gap` between cells. Widgets
 *   never overlap: placing or moving one pushes the widgets under it down.
 * - A user opens "Edit layout" to add widgets, drag a widget by its handle, drag its corner to resize, or use the
 *   keyboard on the same handles (arrow keys move, Shift plus arrow keys resize), and remove widgets. The result is
 *   kept per user in this browser's local storage under `storageKey` and `userKey` (bind `userKey` to the session
 *   user name) and is restored on the next visit. "Reset layout" forgets it and returns to the designer's default.
 * - `locked` freezes the dashboard: no editing controls, and the designer's layout is shown whatever a user saved.
 */

export const dashboardSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Dashboard",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Dashboard" },
    available: { type: "string", title: "Available widgets (JSON array of {id, title, view, params, w, h})", default: "[]" },
    layout: { type: "string", title: "Default layout (JSON array of {key, id, x, y, w, h, params})", default: "[]" },
    columns: { type: "integer", title: "Columns", default: 12, minimum: 1, maximum: 48 },
    rowHeight: { type: "number", title: "Row height (px)", default: 60, minimum: 16, maximum: 400 },
    gap: { type: "number", title: "Gap (px)", default: 8, minimum: 0, maximum: 64 },
    locked: { type: "boolean", title: "Locked (users cannot change the layout)", default: false },
    storageKey: { type: "string", title: "Storage key (names this dashboard in the saved layouts)", default: "dashboard" },
    userKey: { type: "string", title: "User key (bind to the session user name)", default: "" },
  },
};

export interface DashboardWidget {
  id: string;
  title: string;
  view: string;
  params: Record<string, unknown>;
  w: number;
  h: number;
}

export interface DashboardItem {
  key: string;
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
  params: Record<string, unknown>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function cells(value: unknown, fallback: number, low: number, high: number): number {
  return typeof value === "number" && Number.isFinite(value) ? Math.min(high, Math.max(low, Math.round(value))) : fallback;
}

export function readAvailable(value: unknown): DashboardWidget[] {
  const widgets: DashboardWidget[] = [];
  for (const entry of readList(value)) {
    if (!isRecord(entry)) continue;
    const id = typeof entry.id === "string" ? entry.id.trim() : "";
    const view = typeof entry.view === "string" ? entry.view.trim() : "";
    if (!id || !view || widgets.some((widget) => widget.id === id)) continue;
    widgets.push({
      id,
      view,
      title: typeof entry.title === "string" && entry.title.trim() ? entry.title : id,
      params: isRecord(entry.params) ? entry.params : {},
      w: cells(entry.w, 4, 1, 48),
      h: cells(entry.h, 3, 1, 48),
    });
  }
  return widgets;
}

/** Items from a layout value (an array or its JSON text), kept to known widgets and the column count, then made non-overlapping. */
export function readLayout(value: unknown, available: readonly DashboardWidget[], columns: number): DashboardItem[] {
  const items: DashboardItem[] = [];
  const list = Array.isArray(value) ? value : readList(value);
  list.forEach((entry, index) => {
    if (!isRecord(entry)) return;
    const id = typeof entry.id === "string" ? entry.id : "";
    const widget = available.find((candidate) => candidate.id === id);
    if (!widget) return;
    const key = typeof entry.key === "string" && entry.key ? entry.key : `w${index}`;
    if (items.some((item) => item.key === key)) return;
    const w = cells(entry.w, widget.w, 1, columns);
    items.push({
      key,
      id,
      w,
      h: cells(entry.h, widget.h, 1, 48),
      x: cells(entry.x, 0, 0, columns - w),
      y: cells(entry.y, 0, 0, 10_000),
      params: isRecord(entry.params) ? entry.params : {},
    });
  });
  return resolve(items);
}

function overlaps(a: DashboardItem, b: DashboardItem): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

/**
 * Makes [items] non-overlapping by pushing widgets down. [priority] (the widget just placed) keeps its spot; the
 * rest are placed in reading order and move down only as far as they must.
 */
export function resolve(items: readonly DashboardItem[], priority?: string): DashboardItem[] {
  const ordered = [...items].sort((a, b) => (a.key === priority ? -1 : b.key === priority ? 1 : a.y - b.y || a.x - b.x));
  const placed: DashboardItem[] = [];
  for (const item of ordered) {
    const next = { ...item };
    for (let guard = 0; guard < 10_000; guard++) {
      const hit = placed.find((other) => overlaps(next, other));
      if (!hit) break;
      next.y = hit.y + hit.h;
    }
    placed.push(next);
  }
  return items.map((item) => placed.find((p) => p.key === item.key)!);
}

export function moveItem(items: readonly DashboardItem[], key: string, x: number, y: number, columns: number): DashboardItem[] {
  return resolve(
    items.map((item) => (item.key === key ? { ...item, x: Math.min(columns - item.w, Math.max(0, x)), y: Math.max(0, y) } : item)),
    key,
  );
}

export function resizeItem(items: readonly DashboardItem[], key: string, w: number, h: number, columns: number): DashboardItem[] {
  return resolve(
    items.map((item) => (item.key === key ? { ...item, w: Math.min(columns - item.x, Math.max(1, w)), h: Math.min(48, Math.max(1, h)) } : item)),
    key,
  );
}

export function addItem(items: readonly DashboardItem[], widget: DashboardWidget, columns: number): DashboardItem[] {
  let n = items.length;
  while (items.some((item) => item.key === `w${n}`)) n++;
  const w = Math.min(widget.w, columns);
  const bottom = items.reduce((max, item) => Math.max(max, item.y + item.h), 0);
  return [...items, { key: `w${n}`, id: widget.id, x: 0, y: bottom, w, h: widget.h, params: {} }];
}

// ---------------------------------------------------------------- storage

function storageName(storageKey: string, userKey: string): string {
  return `oip.dashboard.${storageKey || "dashboard"}.${userKey || "shared"}`;
}

function loadSaved(name: string): unknown {
  try {
    const raw = window.localStorage.getItem(name);
    return raw === null ? null : (JSON.parse(raw) as unknown);
  } catch {
    return null;
  }
}

function save(name: string, items: readonly DashboardItem[] | null) {
  try {
    if (items === null) window.localStorage.removeItem(name);
    else window.localStorage.setItem(name, JSON.stringify(items));
  } catch {
    // Storage may be blocked or full; the layout then lasts for this visit only.
  }
}

// ---------------------------------------------------------------- component

export function Dashboard({ nodeId, props }: HmiComponentProps) {
  const label = readString(props, "label", "Dashboard");
  const columns = Math.min(48, Math.max(1, Math.round(readNumber(props, "columns", 12))));
  const rowHeight = Math.min(400, Math.max(16, readNumber(props, "rowHeight", 60)));
  const gap = Math.min(64, Math.max(0, readNumber(props, "gap", 8)));
  const locked = readBoolean(props, "locked", false);
  const name = storageName(readString(props, "storageKey", "dashboard"), readString(props, "userKey", ""));

  const available = useMemo(() => readAvailable(props.available), [props.available]);
  const defaults = useMemo(() => readLayout(props.layout, available, columns), [props.layout, available, columns]);

  const [items, setItems] = useState<DashboardItem[]>(defaults);
  const [editing, setEditing] = useState(false);
  const [toAdd, setToAdd] = useState("");
  const [width, setWidth] = useState(0);
  const frame = useRef<HTMLDivElement>(null);
  const gesture = useRef<{ kind: "move" | "resize"; key: string; x: number; y: number; item: DashboardItem; start: DashboardItem[] } | null>(null);

  // The saved layout of this user wins over the designer's default unless the dashboard is locked.
  useEffect(() => {
    const saved = locked ? null : loadSaved(name);
    setItems(saved === null ? defaults : readLayout(saved, available, columns));
  }, [name, locked, defaults, available, columns]);

  useEffect(() => {
    if (locked) setEditing(false);
  }, [locked]);

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    const measure = () => setWidth(element.clientWidth);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const commit = (next: DashboardItem[]) => {
    setItems(next);
    save(name, next);
  };

  const cellWidth = ((width || 960) - gap * (columns - 1)) / columns + gap;
  const cellHeight = rowHeight + gap;
  const rows = items.reduce((max, item) => Math.max(max, item.y + item.h), 1);

  const onKey = (event: ReactKeyboardEvent, item: DashboardItem, kind: "move" | "resize") => {
    const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[event.key];
    if (!step) return;
    event.preventDefault();
    if (kind === "resize" || event.shiftKey) commit(resizeItem(items, item.key, item.w + step[0], item.h + step[1], columns));
    else commit(moveItem(items, item.key, item.x + step[0], item.y + step[1], columns));
  };

  const begin = (event: ReactPointerEvent, item: DashboardItem, kind: "move" | "resize") => {
    gesture.current = { kind, key: item.key, x: event.clientX, y: event.clientY, item, start: items };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };
  const apply = (event: ReactPointerEvent, persist: boolean) => {
    const g = gesture.current;
    if (!g) return;
    const dx = Math.round((event.clientX - g.x) / cellWidth);
    const dy = Math.round((event.clientY - g.y) / cellHeight);
    const next =
      g.kind === "move"
        ? moveItem(g.start, g.key, g.item.x + dx, g.item.y + dy, columns)
        : resizeItem(g.start, g.key, g.item.w + dx, g.item.h + dy, columns);
    setItems(next);
    if (persist) {
      save(name, next);
      gesture.current = null;
    }
  };

  const widgetOf = (item: DashboardItem) => available.find((candidate) => candidate.id === item.id);
  const chosen = available.find((widget) => widget.id === toAdd) ?? available[0];
  const gridStyle: CSSProperties = {
    display: "grid",
    gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
    gridAutoRows: `${rowHeight}px`,
    gap,
    minHeight: rows * rowHeight + (rows - 1) * gap,
  };

  return (
    <div className="oip-dashboard" ref={frame} role="region" aria-label={label} data-locked={locked ? "true" : "false"} data-editing={editing ? "true" : "false"}>
      {!locked ? (
        <div className="oip-dashboard-bar">
          <button type="button" aria-pressed={editing} onClick={() => setEditing(!editing)}>
            {editing ? "Done" : "Edit layout"}
          </button>
          {editing ? (
            <>
              <select aria-label="Widget to add" value={chosen?.id ?? ""} onChange={(event) => setToAdd(event.target.value)} disabled={available.length === 0}>
                {available.map((widget) => (
                  <option key={widget.id} value={widget.id}>
                    {widget.title}
                  </option>
                ))}
              </select>
              <button type="button" disabled={!chosen} onClick={() => chosen && commit(addItem(items, chosen, columns))}>
                Add widget
              </button>
              <button
                type="button"
                onClick={() => {
                  save(name, null);
                  setItems(defaults);
                }}
              >
                Reset layout
              </button>
            </>
          ) : null}
        </div>
      ) : null}
      <div
        className="oip-dashboard-grid"
        style={gridStyle}
        onPointerMove={(event) => apply(event, false)}
        onPointerUp={(event) => apply(event, true)}
        onPointerCancel={() => (gesture.current = null)}
      >
        {items.map((item) => {
          const widget = widgetOf(item);
          if (!widget) return null;
          return (
            <div
              key={item.key}
              className="oip-dashboard-widget"
              role="group"
              aria-label={widget.title}
              data-widget={item.key}
              data-x={item.x}
              data-y={item.y}
              data-w={item.w}
              data-h={item.h}
              style={{ gridColumn: `${item.x + 1} / span ${item.w}`, gridRow: `${item.y + 1} / span ${item.h}` }}
            >
              {editing ? (
                <div className="oip-dashboard-tools">
                  <button type="button" className="oip-dashboard-handle" aria-label={`Move ${widget.title}`} onPointerDown={(event) => begin(event, item, "move")} onKeyDown={(event) => onKey(event, item, "move")}>
                    ⠿
                  </button>
                  <span>{widget.title}</span>
                  <button type="button" aria-label={`Remove ${widget.title}`} onClick={() => commit(items.filter((other) => other.key !== item.key))}>
                    ×
                  </button>
                </div>
              ) : null}
              <div className="oip-dashboard-body">
                <EmbeddedView
                  path={widget.view}
                  params={{ ...widget.params, ...item.params }}
                  instanceKey={`${nodeId}-${item.key}`}
                  useDefaultViewWidth={false}
                  useDefaultViewHeight={false}
                />
              </div>
              {editing ? (
                <button type="button" className="oip-dashboard-resize" aria-label={`Resize ${widget.title}`} onPointerDown={(event) => begin(event, item, "resize")} onKeyDown={(event) => onKey(event, item, "resize")} />
              ) : null}
            </div>
          );
        })}
      </div>
      {items.length === 0 ? <div className="oip-dashboard-empty">{locked || available.length === 0 ? "No widgets" : "No widgets yet. Choose Edit layout to add some."}</div> : null}
    </div>
  );
}
