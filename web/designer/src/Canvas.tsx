import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type {
  DragEvent as ReactDragEvent,
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  PointerEvent as ReactPointerEvent,
  RefObject,
} from "react";
import type {
  ComponentRegistry,
  ContainerNode,
  ScriptHost,
  Frame,
  SessionModel,
  StringTransport,
  StyleClassSet,
  CustomThemeSet,
  ViewDocument,
  ViewNode,
} from "@oip/hmi-runtime";
import { ViewRenderer, findNode, walkNodes } from "@oip/hmi-runtime";
import { withDesignTabs, withFrame, type DesignTabs } from "./project";
import { TAG_DRAG_TYPE, decodeDraggedTag, type DraggedTag } from "./tagDrop";
import { PALETTE_DRAG_TYPE, decodePaletteDrag, type PaletteDrag } from "./paletteData";
import { PipeDrawLayer } from "./PipeTool";
import type { Pt } from "./pipeModel";

/**
 * The Designer canvas: the active view rendered live by the HMI runtime in design mode (bindings show real
 * values and quality; actions, writes and component input are off), with an editing overlay on top.
 * The overlay hit-tests the rendered `data-node-id` wrappers for hover, selection and tag drops, and moves
 * and resizes children of coordinate containers. Flex and column children are select-only.
 *
 * A click selects the innermost node under the pointer. Alt+click, or a double (or triple) click, walks up to the
 * selection's parent; Esc selects the parent and, at the view root, clears the selection. Tab containers show the
 * panel chosen at design time ([designTabs]); clicking a tab on the canvas chooses it without changing the saved view.
 */
export interface CanvasProps {
  view: ViewDocument;
  /** The project's views, for embedded views and view references. */
  views?: readonly ViewDocument[];
  registry: ComponentRegistry;
  transport: StringTransport;
  session: SessionModel;
  styleClasses?: StyleClassSet;
  themes?: CustomThemeSet;
  /** Runs script transforms on the gateway. Design mode never runs event scripts or actions. */
  scriptHost?: ScriptHost;
  onConnectionChange?: (connected: boolean) => void;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  onFrame: (id: string, frame: Frame) => void;
  /** A tag from the tag browser was dropped on component [id]. */
  onDropTag?: (id: string, tag: DraggedTag) => void;
  /** A tag dropped where no component is (HMP-110); [at] is the drop point in view pixels. */
  onDropTagOnView?: (tag: DraggedTag, at: { x: number; y: number }) => void;
  /** A palette entry dropped on the canvas (HMP-002); [at] is the drop point in view pixels. */
  onDropPalette?: (drag: PaletteDrag, at: { x: number; y: number }) => void;
  /** Design-time tab choices; they change what the canvas shows, never the view. */
  designTabs?: DesignTabs;
  /** A tab of tab container [id] was clicked on the canvas. */
  onDesignTab?: (id: string, index: number) => void;
  /** The pipe tool (HMP-128): while set, a layer over the canvas draws a pipe and ends with [onFinish] or [onCancel]. */
  pipeTool?: { onFinish: (points: Pt[]) => void; onCancel: () => void };
}

interface Entry {
  node: ViewNode;
  parent: ContainerNode | null;
}

interface Box {
  id: string;
  left: number;
  top: number;
  width: number;
  height: number;
}

interface Boxes {
  selected: Box | null;
  hover: Box | null;
}

const DEFAULT_FRAME: Frame = { x: 0, y: 0, width: 120, height: 36 };
const NO_TABS: DesignTabs = {};
/** A press this soon after a click, this close to it, continues a multi-click instead of re-selecting. */
const MULTI_CLICK_MS = 600;
const MULTI_CLICK_PX = 4;

export function Canvas({
  view,
  views,
  registry,
  transport,
  session,
  styleClasses,
  themes,
  scriptHost,
  onConnectionChange,
  selectedId,
  onSelect,
  onFrame,
  onDropTag,
  onDropPalette,
  onDropTagOnView,
  designTabs = NO_TABS,
  onDesignTab,
  pipeTool,
}: CanvasProps) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const renderRef = useRef<HTMLDivElement>(null);
  const [hoverId, setHoverId] = useState<string | null>(null);
  // While a drag runs the canvas renders a locally patched view; the project is updated once, on release.
  const [drag, setDrag] = useState<{ id: string; frame: Frame } | null>(null);
  const tabbed = useMemo(() => withDesignTabs(view, designTabs), [view, designTabs]);
  const shown = useMemo(() => (drag ? withFrame(tabbed, drag.id, drag.frame) : tabbed), [tabbed, drag]);
  const index = useMemo(() => indexNodes(view), [view]);
  const boxes = useBoxes(renderRef, overlayRef, selectedId, hoverId, shown);

  // Drag and pointer handlers outlive a render: they read the latest view and callbacks through refs.
  const latest = useRef({ index, onFrame, onSelect, onDropTag, onDropPalette, onDropTagOnView, onDesignTab });
  latest.current = { index, onFrame, onSelect, onDropTag, onDropPalette, onDropTagOnView, onDesignTab };
  // The last canvas click and what it left selected; a press continues it only while that is still the selection.
  const lastClick = useRef<{ x: number; y: number; at: number; selected: string | null } | null>(null);
  const pressed = useRef<string | null>(null);
  const endDrag = useRef<((commit: boolean) => void) | null>(null);
  useEffect(() => () => endDrag.current?.(false), []);

  // Hover hit-testing runs at most once per animation frame.
  const pending = useRef<{ x: number; y: number } | null>(null);
  const hoverFrame = useRef(0);
  useEffect(() => () => cancelAnimationFrame(hoverFrame.current), []);
  function scheduleHover(x: number, y: number) {
    pending.current = { x, y };
    if (hoverFrame.current) return;
    hoverFrame.current = requestAnimationFrame(() => {
      hoverFrame.current = 0;
      const point = pending.current;
      pending.current = null;
      if (!point || endDrag.current) return;
      const id = hitTest(point.x, point.y);
      setHoverId((current) => (current === id ? current : id));
    });
  }

  /** The innermost node of this view under the point. Later elements in document order win (they paint on top). */
  function hitTest(clientX: number, clientY: number): string | null {
    const root = renderRef.current;
    if (!root) return null;
    const known = latest.current.index;
    let hit: string | null = null;
    for (const element of root.querySelectorAll<HTMLElement>("[data-node-id]")) {
      const id = element.getAttribute("data-node-id");
      if (!id || !known.has(id)) continue;
      const rect = element.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) continue;
      if (clientX >= rect.left && clientX <= rect.right && clientY >= rect.top && clientY <= rect.bottom) hit = id;
    }
    return hit;
  }

  /** [id] and the containers above it, innermost first. */
  function chainOf(id: string): string[] {
    const chain: string[] = [];
    for (let entry = latest.current.index.get(id); entry; entry = entry.parent ? latest.current.index.get(entry.parent.id) : undefined) {
      chain.push(entry.node.id);
    }
    return chain;
  }

  /** The parent of [id], or [id] itself at the view root. */
  function parentId(id: string): string {
    return latest.current.index.get(id)?.parent?.id ?? id;
  }

  /** A tab of a tab container of this view under the point, as the container id and tab index. */
  function tabAt(clientX: number, clientY: number): { id: string; index: number } | null {
    const root = renderRef.current;
    if (!root) return null;
    for (const container of root.querySelectorAll<HTMLElement>('[data-layout="tab"][data-node-id]')) {
      const id = container.getAttribute("data-node-id");
      if (!id || !latest.current.index.has(id)) continue;
      const tabs = container.querySelectorAll<HTMLElement>(':scope > [role="tablist"] > [role="tab"]');
      for (let index = 0; index < tabs.length; index += 1) {
        const rect = tabs[index]!.getBoundingClientRect();
        if (rect.width > 0 && clientX >= rect.left && clientX <= rect.right && clientY >= rect.top && clientY <= rect.bottom) return { id, index };
      }
    }
    return null;
  }

  function movable(id: string | null): { node: ViewNode; parent: ContainerNode } | null {
    const entry = id ? latest.current.index.get(id) : undefined;
    if (!entry || !entry.parent || entry.parent.layout !== "coordinate") return null;
    return { node: entry.node, parent: entry.parent };
  }

  function beginDrag(event: ReactPointerEvent<HTMLElement>, id: string, kind: "move" | "resize") {
    const target = movable(id);
    if (!target) return;
    endDrag.current?.(false);
    const mode = target.parent.props.mode ?? "fixed";
    const start = { ...(target.node.frame ?? DEFAULT_FRAME) };
    const reference = findElement(renderRef.current, target.parent.id);
    const startX = event.clientX;
    const startY = event.clientY;
    const pointerId = event.pointerId;
    const capture = overlayRef.current;
    try {
      capture?.setPointerCapture(pointerId);
    } catch {
      // No capture (an inactive pointer, or a test DOM): window listeners still see the moves.
    }
    let last: Frame | null = null;
    function move(ev: PointerEvent) {
      if (ev.pointerId !== pointerId) return;
      const dx = ev.clientX - startX;
      const dy = ev.clientY - startY;
      if (kind === "move" && !last && Math.abs(dx) + Math.abs(dy) < 2) return;
      last = nextFrame(start, dx, dy, kind, mode, reference);
      setDrag({ id, frame: last });
    }
    function finish(commit: boolean) {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("keydown", key, true);
      try {
        if (capture?.hasPointerCapture?.(pointerId)) capture.releasePointerCapture(pointerId);
      } catch {
        // Already released.
      }
      endDrag.current = null;
      setDrag(null);
      if (commit && last) latest.current.onFrame(id, last);
    }
    function up(ev: PointerEvent) {
      if (ev.pointerId === pointerId) finish(true);
    }
    function cancel(ev: PointerEvent) {
      if (ev.pointerId === pointerId) finish(false);
    }
    // Esc during a drag puts the node back where it was.
    function key(ev: KeyboardEvent) {
      if (ev.key !== "Escape") return;
      ev.preventDefault();
      ev.stopPropagation();
      finish(false);
    }
    endDrag.current = finish;
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", cancel);
    window.addEventListener("keydown", key, true);
  }

  function onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return;
    event.preventDefault();
    overlayRef.current?.focus();
    const id = hitTest(event.clientX, event.clientY);
    const tab = tabAt(event.clientX, event.clientY);
    if (tab) latest.current.onDesignTab?.(tab.id, tab.index);
    if (!id) {
      pressed.current = null;
      onSelect(null);
      return;
    }
    const chain = chainOf(id);
    const holdsSelection = selectedId !== null && chain.includes(selectedId);
    if (event.altKey) {
      // Alt+click: the parent of the selection when the click is inside it, else the parent of the clicked node.
      pressed.current = parentId(holdsSelection ? selectedId! : id);
      onSelect(pressed.current);
      return;
    }
    const previous = lastClick.current;
    const repeat =
      previous !== null &&
      previous.selected === selectedId &&
      event.timeStamp - previous.at < MULTI_CLICK_MS &&
      Math.abs(event.clientX - previous.x) <= MULTI_CLICK_PX &&
      Math.abs(event.clientY - previous.y) <= MULTI_CLICK_PX;
    // The second press of a double click keeps the selection; the click that follows walks up (onClick).
    if (repeat && holdsSelection) {
      pressed.current = selectedId;
      return;
    }
    pressed.current = id;
    onSelect(id);
    beginDrag(event, id, "move");
  }

  function onClick(event: ReactMouseEvent<HTMLDivElement>) {
    if (event.button !== 0 || event.altKey) return;
    let selected = pressed.current;
    const id = hitTest(event.clientX, event.clientY);
    if (event.detail >= 2 && selected && id && chainOf(id).includes(selected)) {
      selected = parentId(selected);
      onSelect(selected);
    }
    lastClick.current = { x: event.clientX, y: event.clientY, at: event.timeStamp, selected };
  }

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      // Esc walks up: the parent first, and only from the view root does it clear the selection.
      const entry = selectedId ? index.get(selectedId) : undefined;
      onSelect(entry?.parent?.id ?? null);
      return;
    }
    const target = movable(selectedId);
    if (!target) return;
    const step = event.shiftKey ? 10 : 1;
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    const change = delta[event.key];
    if (!change) return;
    event.preventDefault();
    const mode = target.parent.props.mode ?? "fixed";
    const reference = findElement(renderRef.current, target.parent.id);
    const frame = target.node.frame ?? DEFAULT_FRAME;
    onFrame(target.node.id, nextFrame(frame, change[0], change[1], "move", mode, reference));
  }

  function carriesPalette(event: ReactDragEvent<HTMLDivElement>): boolean {
    return Boolean(onDropPalette) && event.dataTransfer.types.includes(PALETTE_DRAG_TYPE);
  }

  function carriesTag(event: ReactDragEvent<HTMLDivElement>): boolean {
    return Boolean(onDropTag) && event.dataTransfer.types.includes(TAG_DRAG_TYPE);
  }

  // The live tree only re-renders when what it shows changes, never for hover, selection or overlay boxes.
  const rendered = useMemo(
    () => (
      <ViewRenderer
        view={shown}
        views={views}
        registry={registry}
        transport={transport}
        session={session}
        styleClasses={styleClasses}
        themes={themes}
        scriptHost={scriptHost}
        onConnectionChange={onConnectionChange}
        designMode
      />
    ),
    [shown, views, registry, transport, session, styleClasses, themes, scriptHost, onConnectionChange],
  );

  const selected = selectedId ? index.get(selectedId)?.node : undefined;
  const canResize = Boolean(movable(selectedId));
  return (
    <div className="oip-canvas-scroll">
      <div
        className="oip-artboard"
        data-testid="canvas"
        data-active-view={view.metadata.path}
        style={{ width: view.spec.width, height: view.spec.height }}
      >
        <div ref={renderRef} className="oip-canvas-render">
          {rendered}
        </div>
        <div
          ref={overlayRef}
          className="oip-canvas-overlay"
          role="application"
          aria-label="View canvas"
          aria-roledescription="design canvas"
          aria-description={selected ? `Selected: ${selected.name}` : undefined}
          data-layout={view.spec.root.layout}
          data-selected-node={selectedId ?? undefined}
          data-dragging={drag ? "true" : undefined}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onClick={onClick}
          onPointerMove={(event) => {
            if (!drag) scheduleHover(event.clientX, event.clientY);
          }}
          onPointerLeave={() => {
            pending.current = null;
            setHoverId(null);
          }}
          onKeyDown={onKeyDown}
          onDragOver={(event) => {
            if (carriesPalette(event)) {
              event.preventDefault();
              event.dataTransfer.dropEffect = "copy";
              return;
            }
            // Accept the tag anywhere on the canvas; the drop itself hit-tests for a component.
            if (!carriesTag(event)) return;
            event.preventDefault();
            event.dataTransfer.dropEffect = "link";
            scheduleHover(event.clientX, event.clientY);
          }}
          onDragLeave={() => setHoverId(null)}
          onDrop={(event) => {
            if (carriesPalette(event)) {
              event.preventDefault();
              const drag = decodePaletteDrag(event.dataTransfer.getData(PALETTE_DRAG_TYPE));
              const box = event.currentTarget.getBoundingClientRect();
              if (drag) latest.current.onDropPalette?.(drag, { x: event.clientX - box.left, y: event.clientY - box.top });
              return;
            }
            if (!carriesTag(event)) return;
            event.preventDefault();
            setHoverId(null);
            const id = hitTest(event.clientX, event.clientY);
            const tag = decodeDraggedTag(event.dataTransfer.getData(TAG_DRAG_TYPE));
            if (!tag) return;
            if (!id || latest.current.index.get(id)?.node.kind !== "component") {
              // No component under the drop: the view's drop configuration decides what the tag becomes.
              const box = event.currentTarget.getBoundingClientRect();
              latest.current.onDropTagOnView?.(tag, { x: event.clientX - box.left, y: event.clientY - box.top });
              return;
            }
            latest.current.onDropTag?.(id, tag);
          }}
        >
          {boxes.hover && boxes.hover.id !== selectedId ? (
            <div className="oip-canvas-hover" data-hover-node={boxes.hover.id} style={boxStyle(boxes.hover)} />
          ) : null}
          {boxes.selected && selected ? (
            <div className="oip-canvas-selection" data-testid="canvas-selection" data-selected-node={selected.id} style={boxStyle(boxes.selected)}>
              <span className="oip-canvas-badge">{selected.name}</span>
              <span className="oip-handle oip-handle-nw" aria-hidden="true" />
              <span className="oip-handle oip-handle-ne" aria-hidden="true" />
              <span className="oip-handle oip-handle-sw" aria-hidden="true" />
              {canResize ? (
                <button
                  type="button"
                  className="oip-resize"
                  data-resize="se"
                  aria-label={`Resize ${selected.name}`}
                  tabIndex={-1}
                  onPointerDown={(event) => {
                    if (event.button !== 0) return;
                    event.preventDefault();
                    event.stopPropagation();
                    beginDrag(event, selected.id, "resize");
                  }}
                />
              ) : null}
            </div>
          ) : null}
        </div>
        {pipeTool ? <PipeDrawLayer view={view} width={view.spec.width} height={view.spec.height} onFinish={pipeTool.onFinish} onCancel={pipeTool.onCancel} /> : null}
      </div>
    </div>
  );
}

function boxStyle(box: Box) {
  return { left: box.left, top: box.top, width: box.width, height: box.height };
}

function indexNodes(view: ViewDocument): Map<string, Entry> {
  const map = new Map<string, Entry>();
  walkNodes(view.spec.root, (node, parent) => map.set(node.id, { node, parent }));
  return map;
}

/** The rendered wrapper of node [id]. Embedded views prefix their node ids, so only this view's nodes match. */
function findElement(root: HTMLElement | null, id: string): HTMLElement | null {
  if (!root) return null;
  return root.querySelector<HTMLElement>(`[data-node-id="${cssEscape(id)}"]`);
}

function cssEscape(value: string): string {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") return CSS.escape(value);
  return value.replace(/["\\]/g, "\\$&");
}

/**
 * Overlay boxes for the selected and hovered nodes, relative to the overlay. Measured after each canvas render and
 * whenever the rendered tree changes (a live value can resize a component), at most once per animation frame.
 */
function useBoxes(
  renderRef: RefObject<HTMLDivElement | null>,
  overlayRef: RefObject<HTMLDivElement | null>,
  selectedId: string | null,
  hoverId: string | null,
  shown: ViewDocument,
): Boxes {
  const [boxes, setBoxes] = useState<Boxes>({ selected: null, hover: null });
  const measureRef = useRef(() => {});
  measureRef.current = () => {
    const origin = overlayRef.current?.getBoundingClientRect();
    const measure = (id: string | null): Box | null => {
      if (!id || !origin) return null;
      const element = findElement(renderRef.current, id);
      if (!element) return null;
      const rect = element.getBoundingClientRect();
      return { id, left: rect.left - origin.left, top: rect.top - origin.top, width: rect.width, height: rect.height };
    };
    const next = { selected: measure(selectedId), hover: measure(hoverId) };
    setBoxes((current) => (sameBox(current.selected, next.selected) && sameBox(current.hover, next.hover) ? current : next));
  };

  useLayoutEffect(() => {
    measureRef.current();
  }, [selectedId, hoverId, shown]);

  useEffect(() => {
    const element = renderRef.current;
    if (!element) return undefined;
    let frame = 0;
    const schedule = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        measureRef.current();
      });
    };
    const mutations = new MutationObserver(schedule);
    mutations.observe(element, { subtree: true, childList: true, attributes: true, characterData: true });
    const resize = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(schedule);
    resize?.observe(element);
    window.addEventListener("resize", schedule);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      mutations.disconnect();
      resize?.disconnect();
      window.removeEventListener("resize", schedule);
    };
  }, [renderRef]);

  return boxes;
}

function sameBox(a: Box | null, b: Box | null): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return a.id === b.id && a.left === b.left && a.top === b.top && a.width === b.width && a.height === b.height;
}

/** A dragged or resized frame. Percent frames are fractions of the parent container's size. */
export function nextFrame(
  start: Frame,
  dx: number,
  dy: number,
  kind: "move" | "resize",
  mode: "fixed" | "percent",
  reference: HTMLElement | null,
): Frame {
  const width = reference?.clientWidth || 1;
  const height = reference?.clientHeight || 1;
  const sx = mode === "percent" ? dx / width : dx;
  const sy = mode === "percent" ? dy / height : dy;
  if (kind === "resize") {
    return {
      ...start,
      width: clampSize(start.width + sx, mode),
      height: clampSize(start.height + sy, mode),
    };
  }
  if (mode === "percent") {
    return {
      ...start,
      x: Math.min(1, Math.max(0, start.x + sx)),
      y: Math.min(1, Math.max(0, start.y + sy)),
    };
  }
  return {
    ...start,
    x: Math.round(start.x + sx),
    y: Math.round(start.y + sy),
  };
}

function clampSize(value: number, mode: "fixed" | "percent"): number {
  if (mode === "percent") return Math.min(1, Math.max(0.02, value));
  return Math.max(8, Math.round(value));
}

/** The selected node, component or container, or null. */
export function selectedComponent(view: ViewDocument, id: string | null): ViewNode | null {
  if (!id) return null;
  return findNode(view.spec.root, id) ?? null;
}

/** The rendered size of node [id] on the Studio canvas, in pixels, or null when it is not shown. */
export function renderedSize(id: string): { width: number; height: number } | null {
  const element = findElement(document.querySelector<HTMLElement>(".oip-canvas-render"), id);
  if (!element) return null;
  const rect = element.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0 ? { width: rect.width, height: rect.height } : null;
}
