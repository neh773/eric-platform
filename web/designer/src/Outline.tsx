import { memo, useEffect, useMemo, useRef, useState } from "react";
import type { DragEvent as ReactDragEvent, KeyboardEvent as ReactKeyboardEvent } from "react";
import type { ContainerNode, LayoutKind, ViewDocument, ViewNode } from "@oip/hmi-runtime";
import { componentModules } from "@oip/components";
import { ancestorsOf, moveProblem, removeProblem, shownTab, type DesignTabs } from "./project";

/**
 * The outline: the active view's node tree beside the canvas, in step with the canvas selection. It is an ARIA tree,
 * so it is also the keyboard way to reach every node (the live canvas itself is inert). Arrow keys move, Left and
 * Right collapse and expand, Enter or Space selects, Delete removes after a confirmation, and Alt with an arrow key
 * moves the selected node up, down, out of its container or into the container above it. Rows can be dragged to
 * reorder or reparent.
 */
export interface OutlineProps {
  view: ViewDocument;
  selectedId: string | null;
  designTabs: DesignTabs;
  onSelect: (id: string) => void;
  /** Move node [id] into container [parentId] before the child now at [index]. */
  onMove: (id: string, parentId: string, index: number) => void;
  onDelete: (id: string) => void;
}

/** The custom drag type for outline rows, so tag drops and outline drops never mix. */
export const OUTLINE_DRAG_TYPE = "application/x-oip-outline-node";

type Zone = "before" | "inside" | "after";

interface Row {
  node: ViewNode;
  parent: ContainerNode | null;
  depth: number;
  index: number;
  siblings: number;
  /** On a tab panel the canvas does not show right now (its own or an ancestor's). Selecting it shows it. */
  hiddenTab: boolean;
}

const LAYOUT_LABEL: Record<LayoutKind, string> = {
  coordinate: "Coordinate container",
  flex: "Flex container",
  column: "Column container",
  tab: "Tab container",
  breakpoint: "Breakpoint container",
  split: "Split container",
};

const LAYOUT_GLYPH: Record<LayoutKind, string> = {
  coordinate: "XY",
  flex: "FX",
  column: "CL",
  tab: "TB",
  breakpoint: "BP",
  split: "SP",
};

const MOVE_KEYS: Partial<Record<string, "up" | "down" | "out" | "in">> = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "out", ArrowRight: "in" };

const titles = new Map(componentModules.map((module) => [module.type, module.title]));

/** A readable type for a node: its container layout or its component's title. */
export function typeLabel(node: ViewNode): string {
  if (node.kind === "container") return LAYOUT_LABEL[node.layout] ?? `${node.layout} container`;
  return titles.get(node.type) ?? node.type;
}

function glyph(node: ViewNode): string {
  if (node.kind === "container") return LAYOUT_GLYPH[node.layout] ?? "C";
  const title = titles.get(node.type) ?? node.type;
  return title.slice(0, 2).toUpperCase();
}

/** The rows a reader can reach: every node whose ancestors are all expanded, in document order. */
export function visibleRows(root: ContainerNode, collapsed: ReadonlySet<string>, designTabs: DesignTabs = {}): Row[] {
  const rows: Row[] = [];
  const visit = (node: ViewNode, parent: ContainerNode | null, depth: number, index: number, siblings: number, hidden: boolean) => {
    const hiddenTab = hidden || (parent?.layout === "tab" && shownTab(parent, designTabs) !== index);
    rows.push({ node, parent, depth, index, siblings, hiddenTab });
    if (node.kind !== "container" || collapsed.has(node.id)) return;
    node.children.forEach((child, at) => visit(child, node, depth + 1, at, node.children.length, hiddenTab));
  };
  visit(root, null, 1, 0, 1, false);
  return rows;
}

/** Row events, as one object that keeps its identity across renders so memoised rows skip re-rendering. */
interface RowHandlers {
  click(id: string): void;
  toggle(id: string): void;
  focus(id: string): void;
  dragStart(id: string, event: ReactDragEvent<HTMLElement>): void;
  dragOver(id: string, event: ReactDragEvent<HTMLElement>): void;
  dragLeave(id: string, event: ReactDragEvent<HTMLElement>): void;
  drop(id: string, event: ReactDragEvent<HTMLElement>): void;
  dragEnd(): void;
}

interface RowProps {
  id: string;
  name: string;
  kind: ViewNode["kind"];
  type: string;
  glyph: string;
  depth: number;
  posinset: number;
  setsize: number;
  expanded: boolean | undefined;
  selected: boolean;
  tabbable: boolean;
  hiddenTab: boolean;
  drop: Zone | undefined;
  draggable: boolean;
  handlers: RowHandlers;
}

/**
 * One outline row. Rows are a flat list with `aria-level`, `aria-setsize` and `aria-posinset`, so each is memoised on
 * plain values: a nudge on the canvas re-renders only the rows whose name, type, place or state changed.
 */
const OutlineRow = memo(function OutlineRow(props: RowProps) {
  const { id, handlers } = props;
  return (
    <li
      role="treeitem"
      aria-label={props.name}
      aria-description={`${props.type}${props.hiddenTab ? ", on a hidden tab" : ""}`}
      aria-level={props.depth}
      aria-setsize={props.setsize}
      aria-posinset={props.posinset}
      aria-selected={props.selected}
      aria-expanded={props.expanded}
      tabIndex={props.tabbable ? 0 : -1}
      data-outline-id={id}
      onFocus={(event) => {
        if (event.target === event.currentTarget) handlers.focus(id);
      }}
    >
      <div
        className="oip-outline-row"
        data-outline-row={id}
        data-selected={props.selected ? "true" : undefined}
        data-hidden-tab={props.hiddenTab ? "true" : undefined}
        data-drop={props.drop}
        style={{ paddingLeft: 4 + (props.depth - 1) * 14 }}
        draggable={props.draggable}
        onClick={() => handlers.click(id)}
        onDragStart={(event) => handlers.dragStart(id, event)}
        onDragEnd={() => handlers.dragEnd()}
        onDragOver={(event) => handlers.dragOver(id, event)}
        onDragLeave={(event) => handlers.dragLeave(id, event)}
        onDrop={(event) => handlers.drop(id, event)}
      >
        {props.expanded !== undefined ? (
          <span
            className="oip-outline-twisty"
            aria-hidden="true"
            data-expanded={props.expanded ? "true" : "false"}
            onClick={(event) => {
              event.stopPropagation();
              handlers.toggle(id);
            }}
          >
            ▸
          </span>
        ) : (
          <span className="oip-outline-twisty" aria-hidden="true" />
        )}
        <span className="oip-outline-glyph" aria-hidden="true" data-kind={props.kind}>
          {props.glyph}
        </span>
        <span className="oip-outline-name">{props.name}</span>
        <span className="oip-outline-type" aria-hidden="true">
          {props.type}
        </span>
      </div>
    </li>
  );
});

/** Memoised: with stable callbacks it re-renders only when the view, the selection or the tab choices change. */
export const Outline = memo(function Outline({ view, selectedId, designTabs, onSelect, onMove, onDelete }: OutlineProps) {
  const root = view.spec.root;
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [drop, setDrop] = useState<{ id: string; zone: Zone } | null>(null);
  const [notice, setNotice] = useState("");
  const treeRef = useRef<HTMLUListElement>(null);
  const dragged = useRef<string | null>(null);
  const rows = useMemo(() => visibleRows(root, collapsed, designTabs), [root, collapsed, designTabs]);
  const byId = useMemo(() => new Map(rows.map((row) => [row.node.id, row])), [rows]);

  // A new selection (from the canvas, the breadcrumb or here) opens the containers above it and scrolls its row into
  // view. Only a change of selection does this: a container the author collapsed stays collapsed through other edits.
  const shownSelection = useRef<string | null>(null);
  const scrollTo = useRef<string | null>(null);
  useEffect(() => {
    if (selectedId === shownSelection.current) return;
    shownSelection.current = selectedId;
    if (!selectedId) return;
    expand(ancestorsOf(root, selectedId).map((node) => node.id));
    setFocusedId(selectedId);
    scrollTo.current = selectedId;
    // `root` is read for the selection's ancestors at the moment it changes; later edits must not re-run this.
  }, [selectedId]);
  useEffect(() => {
    const id = scrollTo.current;
    if (!id) return;
    const row = treeRef.current?.querySelector<HTMLElement>(`[data-outline-row="${cssEscape(id)}"]`);
    if (!row) return;
    scrollTo.current = null;
    row.scrollIntoView?.({ block: "nearest" });
  });

  // The focusable row: the one last focused while it is still visible, else the selection, else the root.
  const current = focusedId && byId.has(focusedId) ? focusedId : selectedId && byId.has(selectedId) ? selectedId : root.id;

  function focusRow(id: string) {
    setFocusedId(id);
    treeRef.current?.querySelector<HTMLElement>(`[data-outline-id="${cssEscape(id)}"]`)?.focus();
  }

  function expand(ids: string[]) {
    setCollapsed((set) => (ids.some((id) => set.has(id)) ? withoutAll(set, ids) : set));
  }

  function toggle(id: string, open: boolean) {
    setCollapsed((set) => {
      if (open === !set.has(id)) return set;
      const next = new Set(set);
      if (open) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  /** Moves [id] into [parentId] at [index], opening the target so the moved row stays in sight. */
  function move(id: string, parentId: string, index: number) {
    expand([parentId, ...ancestorsOf(root, parentId).map((node) => node.id)]);
    scrollTo.current = id;
    onMove(id, parentId, index);
  }

  /** Where a keyboard move command sends [id]: its parent and index, or null when there is nowhere to go. */
  function moveCommand(id: string, command: "up" | "down" | "out" | "in"): [string, number] | null {
    const parent = ancestorsOf(root, id).at(-1);
    if (!parent) return null;
    const index = parent.children.findIndex((child) => child.id === id);
    if (command === "up") return index > 0 ? [parent.id, index - 1] : null;
    if (command === "down") return index < parent.children.length - 1 ? [parent.id, index + 2] : null;
    if (command === "out") {
      const grand = ancestorsOf(root, parent.id).at(-1);
      if (!grand) return null;
      return [grand.id, grand.children.findIndex((child) => child.id === parent.id) + 1];
    }
    const above = parent.children[index - 1];
    if (!above || above.kind !== "container") return null;
    return [above.id, above.children.length];
  }

  function runMove(id: string, command: "up" | "down" | "out" | "in") {
    const target = moveCommand(id, command);
    if (!target) return;
    const problem = moveProblem(view, id, target[0]);
    if (problem) {
      setNotice(problem);
      return;
    }
    setNotice("");
    move(id, target[0], target[1]);
    // The row is rebuilt under its new parent; keep keyboard focus on it.
    requestAnimationFrame(() => focusRow(id));
  }

  function askDelete(id: string) {
    const problem = removeProblem(view, id);
    if (problem) {
      setNotice(problem);
      return;
    }
    setNotice("");
    setConfirming(id);
  }

  function onKeyDown(event: ReactKeyboardEvent<HTMLUListElement>) {
    // The key acts on the row that has focus (focus can move before its focus event has re-rendered the tree).
    const target = (event.target as HTMLElement).closest?.("[data-outline-id]")?.getAttribute("data-outline-id");
    const row = byId.get(target ?? current);
    if (!row) return;
    const at = rows.indexOf(row);
    const node = row.node;
    const open = node.kind === "container" && node.children.length > 0 && !collapsed.has(node.id);
    if (event.altKey) {
      const command = MOVE_KEYS[event.key];
      if (!command) return;
      event.preventDefault();
      runMove(node.id, command);
      return;
    }
    switch (event.key) {
      case "ArrowDown":
        if (at < rows.length - 1) focusRow(rows[at + 1]!.node.id);
        break;
      case "ArrowUp":
        if (at > 0) focusRow(rows[at - 1]!.node.id);
        break;
      case "Home":
        focusRow(rows[0]!.node.id);
        break;
      case "End":
        focusRow(rows.at(-1)!.node.id);
        break;
      case "ArrowRight":
        if (node.kind !== "container" || node.children.length === 0) break;
        if (!open) toggle(node.id, true);
        else focusRow(node.children[0]!.id);
        break;
      case "ArrowLeft":
        if (open) toggle(node.id, false);
        else if (row.parent) focusRow(row.parent.id);
        break;
      case "Enter":
      case " ":
        onSelect(node.id);
        break;
      case "Delete":
      case "Backspace":
        askDelete(node.id);
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  function zoneFor(event: ReactDragEvent<HTMLElement>, node: ViewNode): Zone {
    const rect = event.currentTarget.getBoundingClientRect();
    const offset = rect.height > 0 ? (event.clientY - rect.top) / rect.height : 0.5;
    if (node.id === root.id) return "inside";
    if (node.kind === "container" && offset > 0.25 && offset < 0.75) return "inside";
    return offset < 0.5 ? "before" : "after";
  }

  /** The parent and index a drop on [row] in [zone] means. */
  function dropTarget(row: Row, zone: Zone): [string, number] | null {
    if (zone === "inside") return row.node.kind === "container" ? [row.node.id, row.node.children.length] : null;
    if (!row.parent) return null;
    return [row.parent.id, zone === "before" ? row.index : row.index + 1];
  }

  // Row events go through a ref to this render's handlers, so the object rows receive never changes.
  const impl = {
    click(id: string) {
      onSelect(id);
      focusRow(id);
    },
    toggle(id: string) {
      toggle(id, collapsed.has(id));
    },
    focus(id: string) {
      setFocusedId(id);
    },
    dragStart(id: string, event: ReactDragEvent<HTMLElement>) {
      dragged.current = id;
      event.dataTransfer.setData(OUTLINE_DRAG_TYPE, id);
      event.dataTransfer.effectAllowed = "move";
    },
    dragOver(id: string, event: ReactDragEvent<HTMLElement>) {
      const row = byId.get(id);
      const moving = dragged.current;
      if (!row || !moving || !event.dataTransfer.types.includes(OUTLINE_DRAG_TYPE)) return;
      const zone = zoneFor(event, row.node);
      const target = dropTarget(row, zone);
      if (!target || moveProblem(view, moving, target[0])) {
        event.dataTransfer.dropEffect = "none";
        setDrop(null);
        return;
      }
      event.preventDefault();
      event.dataTransfer.dropEffect = "move";
      setDrop((current) => (current?.id === id && current.zone === zone ? current : { id, zone }));
    },
    dragLeave(id: string, event: ReactDragEvent<HTMLElement>) {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDrop((current) => (current?.id === id ? null : current));
    },
    drop(id: string, event: ReactDragEvent<HTMLElement>) {
      const moving = dragged.current ?? event.dataTransfer.getData(OUTLINE_DRAG_TYPE);
      const row = byId.get(id);
      setDrop(null);
      dragged.current = null;
      if (!moving || !row) return;
      event.preventDefault();
      const target = dropTarget(row, zoneFor(event, row.node));
      if (!target || moveProblem(view, moving, target[0])) return;
      move(moving, target[0], target[1]);
    },
    dragEnd() {
      dragged.current = null;
      setDrop(null);
    },
  };
  const latest = useRef(impl);
  latest.current = impl;
  const handlers = useMemo<RowHandlers>(
    () => ({
      click: (id) => latest.current.click(id),
      toggle: (id) => latest.current.toggle(id),
      focus: (id) => latest.current.focus(id),
      dragStart: (id, event) => latest.current.dragStart(id, event),
      dragOver: (id, event) => latest.current.dragOver(id, event),
      dragLeave: (id, event) => latest.current.dragLeave(id, event),
      drop: (id, event) => latest.current.drop(id, event),
      dragEnd: () => latest.current.dragEnd(),
    }),
    [],
  );

  const selected = selectedId ? findInRows(root, selectedId) : undefined;
  const confirmNode = confirming ? findInRows(root, confirming) : undefined;
  const can = (command: "up" | "down" | "out" | "in") => {
    if (!selected) return false;
    const target = moveCommand(selected.id, command);
    return Boolean(target) && !moveProblem(view, selected.id, target![0]);
  };
  return (
    <section className="oip-outline" aria-label="Outline">
      <div className="oip-outline-head">
        <p>Outline</p>
        <div className="oip-outline-tools" role="toolbar" aria-label="Outline actions">
          <button type="button" aria-label="Move up" title="Move up (Alt+Up)" disabled={!can("up")} onClick={() => selected && runMove(selected.id, "up")}>
            ↑
          </button>
          <button type="button" aria-label="Move down" title="Move down (Alt+Down)" disabled={!can("down")} onClick={() => selected && runMove(selected.id, "down")}>
            ↓
          </button>
          <button
            type="button"
            aria-label="Move out of container"
            title="Move out of its container (Alt+Left)"
            disabled={!can("out")}
            onClick={() => selected && runMove(selected.id, "out")}
          >
            ⇤
          </button>
          <button
            type="button"
            aria-label="Move into container above"
            title="Move into the container above it (Alt+Right)"
            disabled={!can("in")}
            onClick={() => selected && runMove(selected.id, "in")}
          >
            ⇥
          </button>
          <button
            type="button"
            aria-label="Delete"
            title="Delete (Delete)"
            disabled={!selected || removeProblem(view, selected.id) !== null}
            onClick={() => selected && askDelete(selected.id)}
          >
            ✕
          </button>
        </div>
      </div>
      {confirmNode ? (
        <DeleteConfirm
          node={confirmNode}
          onCancel={() => {
            setConfirming(null);
            focusRow(confirmNode.id);
          }}
          onConfirm={() => {
            const parent = ancestorsOf(root, confirmNode.id).at(-1);
            setConfirming(null);
            onDelete(confirmNode.id);
            if (parent) requestAnimationFrame(() => focusRow(parent.id));
          }}
        />
      ) : null}
      <p className="oip-outline-notice" role="status">
        {notice}
      </p>
      <div className="oip-outline-scroll">
        <ul ref={treeRef} role="tree" aria-label={`${view.metadata.name} outline`} onKeyDown={onKeyDown}>
          {rows.map((row) => {
            const node = row.node;
            const hasChildren = node.kind === "container" && node.children.length > 0;
            return (
              <OutlineRow
                key={node.id}
                id={node.id}
                name={node.name}
                kind={node.kind}
                type={typeLabel(node)}
                glyph={glyph(node)}
                depth={row.depth}
                posinset={row.index + 1}
                setsize={row.siblings}
                expanded={hasChildren ? !collapsed.has(node.id) : undefined}
                selected={node.id === selectedId}
                tabbable={node.id === current}
                hiddenTab={row.hiddenTab}
                drop={drop?.id === node.id ? drop.zone : undefined}
                draggable={row.parent !== null}
                handlers={handlers}
              />
            );
          })}
        </ul>
      </div>
    </section>
  );
});

function findInRows(root: ContainerNode, id: string): ViewNode | undefined {
  if (root.id === id) return root;
  const parent = ancestorsOf(root, id).at(-1);
  return parent?.children.find((child) => child.id === id);
}

function DeleteConfirm({ node, onCancel, onConfirm }: { node: ViewNode; onCancel: () => void; onConfirm: () => void }) {
  const confirmRef = useRef<HTMLButtonElement>(null);
  useEffect(() => confirmRef.current?.focus(), [node.id]);
  const inside = node.kind === "container" ? countInside(node) : 0;
  return (
    <div
      className="oip-outline-confirm"
      role="alertdialog"
      aria-label={`Delete ${node.name}?`}
      aria-describedby="oip-outline-confirm-text"
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        event.preventDefault();
        event.stopPropagation();
        onCancel();
      }}
    >
      <p id="oip-outline-confirm-text">
        Delete {node.name}
        {inside > 0 ? ` and the ${inside} ${inside === 1 ? "node" : "nodes"} inside it` : ""}? This cannot be undone.
      </p>
      <button ref={confirmRef} type="button" onClick={onConfirm}>
        Delete
      </button>
      <button type="button" onClick={onCancel}>
        Cancel
      </button>
    </div>
  );
}

function countInside(node: ContainerNode): number {
  return node.children.reduce((sum, child) => sum + 1 + (child.kind === "container" ? countInside(child) : 0), 0);
}

function withoutAll(set: ReadonlySet<string>, ids: string[]): ReadonlySet<string> {
  const next = new Set(set);
  for (const id of ids) next.delete(id);
  return next;
}

function cssEscape(value: string): string {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") return CSS.escape(value);
  return value.replace(/["\\]/g, "\\$&");
}
