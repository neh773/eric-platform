import type { ComponentNode, ContainerNode, ContainerProps, CustomThemeSet, Frame, StyleClassSet, ViewDocument, ViewNode } from "@oip/hmi-runtime";
import { findNode, walkNodes } from "@oip/hmi-runtime";

export interface StudioProject {
  name: string;
  views: ViewDocument[];
  /** Project style classes (M4.2), edited in the style classes panel. */
  styleClasses?: StyleClassSet;
  /** Project themes, edited beside the style classes. */
  themes?: CustomThemeSet;
}

export function replaceView(project: StudioProject, view: ViewDocument): StudioProject {
  return {
    ...project,
    views: project.views.map((candidate) => (candidate.metadata.path === view.metadata.path ? view : candidate)),
  };
}

/** Patches a component or container. Bindings apply to components only. */
export function updateNode(view: ViewDocument, id: string, patch: Partial<ComponentNode> & { props?: Record<string, unknown> }): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node) throw new Error(`node ${id} not found`);
  if (patch.name !== undefined) node.name = patch.name;
  if (patch.props) node.props = { ...node.props, ...patch.props };
  if (patch.frame) node.frame = { ...(node.frame ?? { x: 0, y: 0, width: 80, height: 32 }), ...patch.frame };
  if (patch.flex) node.flex = { ...(node.flex ?? { grow: 0, shrink: 1, basis: "auto", display: true }), ...patch.flex };
  if (patch.columns) node.columns = patch.columns;
  if (patch.bindings && node.kind === "component") node.bindings = patch.bindings;
  if (patch.style) node.style = { ...node.style, ...patch.style };
  return next;
}

/**
 * The view with node [id] at [frame], copying only the nodes on the path to it. The canvas renders this
 * on every drag frame, so it must stay cheap for large views (no deep clone).
 */
export function withFrame(view: ViewDocument, id: string, frame: Frame): ViewDocument {
  return patchNode(view, id, (node) => ({ ...node, frame }));
}

/** The view with node [id] replaced by [change] of it, copying only the nodes on the path to it. */
function patchNode(view: ViewDocument, id: string, change: (node: ViewNode) => ViewNode): ViewDocument {
  const patch = (node: ViewNode): ViewNode | null => {
    if (node.id === id) return change(node);
    if (node.kind !== "container") return null;
    for (let index = 0; index < node.children.length; index += 1) {
      const changed = patch(node.children[index]!);
      if (!changed) continue;
      const children = node.children.slice();
      children[index] = changed;
      return { ...node, children };
    }
    return null;
  };
  const root = patch(view.spec.root);
  if (!root || root.kind !== "container") return view;
  return { ...view, spec: { ...view.spec, root } };
}

/** The container that holds node [id], or null for the root (or an unknown id). */
export function parentOf(root: ContainerNode, id: string): ContainerNode | null {
  let found: ContainerNode | null = null;
  walkNodes(root, (node, parent) => {
    if (node.id === id) found = parent;
  });
  return found;
}

/** Adds a component of [type] to the view's root, at [size] (the component's declared position size, HMP-115). */
export function appendComponent(
  view: ViewDocument,
  type: string,
  title: string,
  defaults: Record<string, unknown>,
  size?: { width: number; height: number },
): { view: ViewDocument; id: string } {
  const next = structuredClone(view);
  const count = countComponents(next.spec.root) + 1;
  const id = `${type}-${count}-${Math.random().toString(36).slice(2, 6)}`;
  const node: ComponentNode = {
    id,
    kind: "component",
    name: `${title} ${count}`,
    type,
    props: { ...defaults },
    bindings: {},
  };
  placeInRoot(next.spec.root, node, title, count, size, next.spec.width, next.spec.height);
  next.spec.root.children.push(node);
  return { view: next, id };
}

/**
 * Moves root child [id] to [at] (view pixels) when the root is a coordinate container; the palette's drop point.
 * Other layouts place children by order, so the view is returned as it is.
 */
export function placeAt(view: ViewDocument, id: string, at: { x: number; y: number }): ViewDocument {
  if (view.spec.root.layout !== "coordinate") return view;
  const node = view.spec.root.children.find((child) => child.id === id);
  if (!node?.frame) return view;
  const frame = node.frame;
  const x = Math.max(0, Math.min(Math.round(at.x), Math.max(0, view.spec.width - frame.width)));
  const y = Math.max(0, Math.min(Math.round(at.y), Math.max(0, view.spec.height - frame.height)));
  return updateNode(view, id, { frame: { ...frame, x, y } });
}

function placeInRoot(root: ContainerNode, node: ViewNode, title: string, count: number, size = { width: 160, height: 40 }, viewWidth = 0, viewHeight = 0) {
  if (root.layout === "coordinate") {
    const taken = (root.props.mode ?? "fixed") === "percent" ? [] : root.children.flatMap((child) => (child.frame ? [child.frame] : []));
    node.frame = { ...freeSpot(taken, size, viewWidth, viewHeight), ...size };
  } else if (root.layout === "flex") {
    node.flex = { grow: 0, shrink: 1, basis: `${size.width}px`, display: true };
  } else if (root.layout === "tab") {
    node.tabLabel = title;
  } else if (root.layout === "breakpoint" || root.layout === "split") {
    // Breakpoint and split children carry no placement beyond their order (and size).
  } else {
    node.columns = [
      { name: "sm", span: 12, rowIndex: count, colIndex: 0, order: 0 },
      { name: "md", span: 6, rowIndex: count, colIndex: 0, order: 0 },
      { name: "lg", span: 4, rowIndex: count, colIndex: 0, order: 0 },
    ];
  }
}

/**
 * The first place for a new [size] box that covers no [taken] frame: rows from the top-left with a 16 px margin,
 * stepping 8 px, inside [viewWidth] x [viewHeight] when they are known. A view with no free spot that fits gets the box
 * at the top-left margin, still inside the view where it can be seen and moved; with no known height it goes below
 * everything.
 */
export function freeSpot(
  taken: Array<{ x: number; y: number; width: number; height: number }>,
  size: { width: number; height: number },
  viewWidth: number,
  viewHeight = 0,
): { x: number; y: number } {
  const margin = 16;
  const gap = 8;
  const right = viewWidth > 0 ? viewWidth : 1280;
  const clear = (x: number, y: number) => taken.every((f) =>
    x + size.width + gap <= f.x || f.x + f.width + gap <= x || y + size.height + gap <= f.y || f.y + f.height + gap <= y);
  const bottom = taken.reduce((max, f) => Math.max(max, f.y + f.height), 0);
  const lastY = viewHeight > 0 ? viewHeight - size.height - margin : bottom + gap;
  for (let y = margin; y <= lastY; y += gap) {
    for (let x = margin; x + size.width <= Math.max(right - margin, margin + size.width); x += gap) {
      if (clear(x, y)) return { x, y };
    }
  }
  if (viewHeight > 0) return { x: margin, y: margin };
  return { x: margin, y: taken.length ? bottom + 2 * gap : margin };
}

/** Adds a breakpoint or split container (C-15) with its two starter children, each an empty flex container. */
export function appendContainer(
  view: ViewDocument,
  layout: "breakpoint" | "split",
  title: string,
  props: ContainerProps,
  children: { name: string; size?: "small" | "large" }[],
): { view: ViewDocument; id: string } {
  const next = structuredClone(view);
  const count = countComponents(next.spec.root) + next.spec.root.children.length + 1;
  const id = `${layout}-${count}-${Math.random().toString(36).slice(2, 6)}`;
  const node: ContainerNode = {
    id,
    kind: "container",
    name: `${title} ${count}`,
    layout,
    props: structuredClone(props),
    children: children.map((child, index) => ({
      id: `${id}-${index}`,
      kind: "container",
      name: child.name,
      layout: "flex",
      props: { direction: "column" },
      children: [],
      ...(child.size ? { size: child.size } : {}),
    })),
  };
  placeInRoot(next.spec.root, node, title, count, { width: 320, height: 200 }, next.spec.width, next.spec.height);
  next.spec.root.children.push(node);
  return { view: next, id };
}

function countComponents(node: ViewNode): number {
  let count = 0;
  walkNodes(node, (current) => {
    if (current.kind === "component") count += 1;
  });
  return count;
}

/**
 * Switches coordinate container [id] between fixed and percent mode and converts its children's frames, so every
 * child keeps its place and size. [size] is the container's size in pixels: the view size for the root, else its
 * own fixed frame. Percent results are clamped to the container (0..1), so nothing ends up off-screen.
 */
export function setCoordinateMode(view: ViewDocument, id: string, mode: "fixed" | "percent"): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node || node.kind !== "container" || node.layout !== "coordinate") throw new Error(`coordinate container ${id} not found`);
  const from = node.props.mode ?? "fixed";
  node.props = { ...node.props, mode };
  if (from === mode) return next;
  const size = coordinateSize(next, node);
  for (const child of node.children) {
    if (!child.frame) continue;
    child.frame = mode === "percent" ? toPercentFrame(child.frame, size) : toFixedFrame(child.frame, size);
  }
  return next;
}

interface Size {
  width: number;
  height: number;
}

const clampUnit = (value: number, min: number) => Math.min(1, Math.max(min, value));

/** A pixel frame as fractions of a container of [size], clamped inside it (a child past the edge is pulled back). */
export function toPercentFrame(frame: Frame, size: Size): Frame {
  const width = clampUnit(frame.width / size.width, 0.02);
  const height = clampUnit(frame.height / size.height, 0.02);
  return {
    ...frame,
    x: Math.min(clampUnit(frame.x / size.width, 0), 1 - width),
    y: Math.min(clampUnit(frame.y / size.height, 0), 1 - height),
    width,
    height,
  };
}

/** A percent frame in pixels of a container of [size]. */
export function toFixedFrame(frame: Frame, size: Size): Frame {
  return {
    ...frame,
    x: Math.round(frame.x * size.width),
    y: Math.round(frame.y * size.height),
    width: Math.max(8, Math.round(frame.width * size.width)),
    height: Math.max(8, Math.round(frame.height * size.height)),
  };
}

/** A coordinate container's size in pixels: the view's for the root, its frame's when its parent is fixed, else the view's. */
function coordinateSize(view: ViewDocument, node: ContainerNode): Size {
  const fallback = { width: Math.max(1, view.spec.width), height: Math.max(1, view.spec.height) };
  const parent = parentOf(view.spec.root, node.id);
  if (!parent || !node.frame || parent.layout !== "coordinate") return fallback;
  if ((parent.props.mode ?? "fixed") === "fixed") return { width: Math.max(1, node.frame.width), height: Math.max(1, node.frame.height) };
  const outer = coordinateSize(view, parent);
  return { width: Math.max(1, node.frame.width * outer.width), height: Math.max(1, node.frame.height * outer.height) };
}

/** The containers from the view root down to the parent of node [id]; empty for the root or an unknown id. */
export function ancestorsOf(root: ContainerNode, id: string): ContainerNode[] {
  const path: ContainerNode[] = [];
  const visit = (node: ViewNode): boolean => {
    if (node.id === id) return true;
    if (node.kind !== "container") return false;
    path.push(node);
    for (const child of node.children) if (visit(child)) return true;
    path.pop();
    return false;
  };
  return visit(root) && path.length > 0 ? path : [];
}

/** True when [id] is [ancestorId] or sits anywhere inside it. */
export function isWithin(root: ContainerNode, id: string, ancestorId: string): boolean {
  return id === ancestorId || ancestorsOf(root, id).some((node) => node.id === ancestorId);
}

/** Breakpoint and split containers hold a fixed pair of children, so their children cannot be moved in or out. */
const FIXED_CHILDREN = new Set(["breakpoint", "split"]);

/** Why node [id] cannot move into container [parentId], or null when it can. */
export function moveProblem(view: ViewDocument, id: string, parentId: string): string | null {
  const root = view.spec.root;
  if (id === root.id) return "The view root cannot move.";
  const node = findNode(root, id);
  const target = findNode(root, parentId);
  const from = parentOf(root, id);
  if (!node || !from) return "Unknown node.";
  if (!target || target.kind !== "container") return "Only containers hold children.";
  if (isWithin(root, parentId, id)) return "A container cannot move into itself.";
  if (from.id === target.id) return null;
  if (FIXED_CHILDREN.has(from.layout)) return `The ${from.layout} container keeps its two children.`;
  if (FIXED_CHILDREN.has(target.layout)) return `The ${target.layout} container holds exactly two children.`;
  return null;
}

export interface MoveOptions {
  /** The node's rendered size in pixels, used when it has no pixel frame to convert (a flex or column child). */
  rendered?: Size | null;
}

/**
 * Moves node [id] into container [parentId] before the child now at [index] (the end when out of range). Within one
 * parent this only reorders. Into another parent the node's placement is converted so it keeps its size and, between
 * coordinate containers whose position in the view is known, its place on screen: pixel and percent frames convert
 * through the coordinate frame helpers, a flex child gets a pixel basis along the flex direction, a column child a
 * starter placement, and a tab panel a label. Placement for the old parent is dropped.
 */
export function moveNode(view: ViewDocument, id: string, parentId: string, index: number, options: MoveOptions = {}): ViewDocument {
  const problem = moveProblem(view, id, parentId);
  if (problem) throw new Error(problem);
  const next = structuredClone(view);
  const root = next.spec.root;
  const from = parentOf(root, id)!;
  const target = findNode(root, parentId) as ContainerNode;
  const oldIndex = from.children.findIndex((child) => child.id === id);
  const node = from.children[oldIndex]!;
  let at = Math.max(0, Math.min(index, target.children.length));
  if (from.id === target.id && oldIndex < at) at -= 1;
  if (from.id === target.id && oldIndex === at) return view;
  // Placement is worked out against the view as it stands, before the node leaves its parent.
  const placed = from.id === target.id ? null : placement(next, node, from, target, at, options.rendered ?? null);
  const shown = shownPanels([from, target]);
  const label = takeTabLabel(from, oldIndex);
  from.children.splice(oldIndex, 1);
  target.children.splice(at, 0, node);
  if (placed) {
    delete node.frame;
    delete node.flex;
    delete node.columns;
    delete node.tabLabel;
    delete node.size;
    Object.assign(node, placed);
  }
  if (target.layout === "tab") {
    const text = label ?? node.tabLabel ?? node.name;
    // A label that cannot go into the target's label list (it has none, or it is shorter) stays on the panel.
    if (!putTabLabel(target, at, text) && label !== undefined) node.tabLabel = label;
  }
  keepShownPanels(shown);
  return next;
}

/** Removes node [id] (and everything inside it). The view root cannot be removed. */
export function removeNode(view: ViewDocument, id: string): ViewDocument {
  const problem = removeProblem(view, id);
  if (problem) throw new Error(problem);
  const next = structuredClone(view);
  const parent = parentOf(next.spec.root, id)!;
  const at = parent.children.findIndex((child) => child.id === id);
  const shown = shownPanels([parent]);
  takeTabLabel(parent, at);
  parent.children.splice(at, 1);
  keepShownPanels(shown);
  return next;
}

/** Why node [id] cannot be deleted, or null when it can. Breakpoint and split children are refused like their moves. */
export function removeProblem(view: ViewDocument, id: string): string | null {
  if (id === view.spec.root.id) return "The view root cannot be removed.";
  const parent = parentOf(view.spec.root, id);
  if (!parent) return "Unknown node.";
  if (FIXED_CHILDREN.has(parent.layout)) return `The ${parent.layout} container keeps its two children.`;
  return null;
}

/** For each tab container among [containers], the panel operators see first (its saved `currentTab`). */
function shownPanels(containers: ContainerNode[]): { container: ContainerNode; index: number; panel: string | undefined }[] {
  return containers
    .filter((container, at) => container.layout === "tab" && containers.indexOf(container) === at)
    .map((container) => {
      const index = savedTab(container);
      return { container, index, panel: container.children[index]?.id };
    });
}

/**
 * After panels moved, points each tab container's `currentTab` back at the panel it showed. When that panel left,
 * the same position is kept, clamped to the panels that remain. An unset `currentTab` (the first panel) is only
 * written when it has to change.
 */
function keepShownPanels(shown: ReturnType<typeof shownPanels>) {
  for (const { container, index, panel } of shown) {
    const found = panel === undefined ? -1 : container.children.findIndex((child) => child.id === panel);
    const next = found >= 0 ? found : Math.max(0, Math.min(index, container.children.length - 1));
    if (next !== (container.props.currentTab ?? 0)) container.props = { ...container.props, currentTab: next };
  }
}

/** A tab container's saved panel index, as the renderer reads it. */
function savedTab(container: ContainerNode): number {
  return Math.max(0, Math.min(container.props.currentTab ?? 0, container.children.length - 1));
}

/** Tab containers can list labels by index (`props.tabs`); a moved panel takes its label along. */
function takeTabLabel(parent: ContainerNode, index: number): string | undefined {
  if (parent.layout !== "tab" || !parent.props.tabs) return undefined;
  const [removed] = parent.props.tabs.splice(index, 1);
  return removed?.label;
}

/** Puts [label] into the tab container's label list at [index]; false when there is no list or it ends before [index]. */
function putTabLabel(parent: ContainerNode, index: number, label: string): boolean {
  if (parent.layout !== "tab" || !parent.props.tabs) return false;
  const tabs = parent.props.tabs;
  // Labels past the panel count are ignored by the renderer; keep the list aligned with the panels it names.
  if (index > tabs.length) return false;
  tabs.splice(index, 0, { label });
  return true;
}

type Placement = Pick<ViewNode, "frame" | "flex" | "columns" | "tabLabel">;

const DEFAULT_SIZE: Size = { width: 160, height: 40 };

function placement(view: ViewDocument, node: ViewNode, from: ContainerNode, target: ContainerNode, index: number, rendered: Size | null): Placement {
  const box = pixelBox(view, node, from, rendered);
  if (target.layout === "coordinate") {
    const size = coordinateSize(view, target);
    const fromOrigin = from.layout === "coordinate" && node.frame ? originOf(view, from) : null;
    const toOrigin = originOf(view, target);
    let x = 16;
    let y = 16;
    if (fromOrigin && toOrigin) {
      x = fromOrigin.x + box.x - toOrigin.x;
      y = fromOrigin.y + box.y - toOrigin.y;
    }
    const fixed: Frame = {
      x: Math.round(Math.max(0, Math.min(x, size.width - box.width))),
      y: Math.round(Math.max(0, Math.min(y, size.height - box.height))),
      width: Math.round(box.width),
      height: Math.round(box.height),
      ...(node.frame?.rotate !== undefined ? { rotate: node.frame.rotate } : {}),
    };
    return { frame: (target.props.mode ?? "fixed") === "percent" ? toPercentFrame(fixed, size) : fixed };
  }
  if (target.layout === "flex") {
    const column = (target.props.direction ?? "row").startsWith("column");
    return { flex: { grow: 0, shrink: 1, basis: `${Math.round(column ? box.height : box.width)}px`, display: true } };
  }
  if (target.layout === "column") {
    return {
      columns: [
        { name: "sm", span: 12, rowIndex: index, colIndex: 0, order: 0 },
        { name: "md", span: 6, rowIndex: index, colIndex: 0, order: 0 },
        { name: "lg", span: 4, rowIndex: index, colIndex: 0, order: 0 },
      ],
    };
  }
  if (target.layout === "tab") return { tabLabel: node.tabLabel ?? node.name };
  return {};
}

/** The node's box in pixels relative to its parent: its frame when it has one, else its basis or rendered size. */
function pixelBox(view: ViewDocument, node: ViewNode, parent: ContainerNode, rendered: Size | null): Frame {
  if (parent.layout === "coordinate" && node.frame) {
    return (parent.props.mode ?? "fixed") === "percent" ? toFixedFrame(node.frame, coordinateSize(view, parent)) : node.frame;
  }
  const size = { ...(rendered && rendered.width > 0 && rendered.height > 0 ? rendered : DEFAULT_SIZE) };
  if (!rendered && parent.layout === "flex" && node.flex) {
    const basis = typeof node.flex.basis === "number" ? node.flex.basis : /^\d+(\.\d+)?px$/.test(node.flex.basis) ? parseFloat(node.flex.basis) : null;
    if (basis !== null && basis > 0) {
      if ((parent.props.direction ?? "row").startsWith("column")) size.height = basis;
      else size.width = basis;
    }
  }
  return { x: 0, y: 0, width: Math.max(8, size.width), height: Math.max(8, size.height) };
}

/**
 * Where container [node]'s top-left sits in the view, in pixels, or null when some container on the way is placed by
 * a layout (flex, column, tab...) rather than a frame, so its position is only known after layout.
 */
function originOf(view: ViewDocument, node: ContainerNode): { x: number; y: number } | null {
  if (node.id === view.spec.root.id) return { x: 0, y: 0 };
  const parent = parentOf(view.spec.root, node.id);
  if (!parent || parent.layout !== "coordinate" || !node.frame) return null;
  const outer = originOf(view, parent);
  if (!outer) return null;
  const box = (parent.props.mode ?? "fixed") === "percent" ? toFixedFrame(node.frame, coordinateSize(view, parent)) : node.frame;
  return { x: outer.x + box.x, y: outer.y + box.y };
}

/**
 * Design-time tab choices: which panel the canvas shows for each tab container, without touching the saved view.
 * `base` is the container's saved `currentTab` when the choice was made; once the author changes that, it wins.
 */
export type DesignTabs = Readonly<Record<string, { index: number; base: number }>>;

/** [tabs] plus the choices that show node [id]: each tab container above it shows the panel that holds it. */
export function revealNode(view: ViewDocument, id: string, tabs: DesignTabs): DesignTabs {
  const path = ancestorsOf(view.spec.root, id);
  let next: Record<string, { index: number; base: number }> | null = null;
  path.forEach((container, depth) => {
    if (container.layout !== "tab") return;
    const childId = depth + 1 < path.length ? path[depth + 1]!.id : id;
    const index = container.children.findIndex((child) => child.id === childId);
    if (index < 0) return;
    const choice = { index, base: container.props.currentTab ?? 0 };
    const current = (next ?? tabs)[container.id];
    if (current && current.index === choice.index && current.base === choice.base) return;
    next ??= { ...tabs };
    next[container.id] = choice;
  });
  return next ?? tabs;
}

/**
 * The design-time tab choices after an edit turned [before] into [after]. A choice follows its panel when panels move
 * (and is dropped when its panel or container is gone). When the author changes a container's saved `currentTab` to
 * another panel, the author's choice wins and the design choice is dropped for good, so changing it back later does not
 * bring the old choice back. A choice that was already overridden is dropped too.
 */
export function followDesignTabs(before: ViewDocument, after: ViewDocument, tabs: DesignTabs): DesignTabs {
  let next: Record<string, { index: number; base: number }> | null = null;
  const set = (id: string, choice: { index: number; base: number } | null) => {
    next ??= { ...tabs };
    if (choice) next[id] = choice;
    else delete next[id];
  };
  for (const [id, choice] of Object.entries(tabs)) {
    const old = findNode(before.spec.root, id);
    const now = findNode(after.spec.root, id);
    if (!old || !now || old.kind !== "container" || now.kind !== "container" || now.layout !== "tab" || choice.base !== (old.props.currentTab ?? 0)) {
      set(id, null);
      continue;
    }
    const savedBefore = old.children[savedTab(old)]?.id;
    const savedAfter = now.children[savedTab(now)]?.id;
    const savedStillThere = savedBefore !== undefined && now.children.some((child) => child.id === savedBefore);
    if (savedStillThere && savedBefore !== savedAfter) {
      set(id, null);
      continue;
    }
    const panel = old.children[choice.index]?.id;
    const index = panel === undefined ? -1 : now.children.findIndex((child) => child.id === panel);
    if (index < 0) {
      set(id, null);
      continue;
    }
    const moved = { index, base: now.props.currentTab ?? 0 };
    if (moved.index !== choice.index || moved.base !== choice.base) set(id, moved);
  }
  return next ?? tabs;
}

/** The panel tab container [node] shows on the canvas. */
export function shownTab(node: ContainerNode, tabs: DesignTabs): number {
  const saved = node.props.currentTab ?? 0;
  const choice = tabs[node.id];
  return choice && choice.base === saved ? choice.index : saved;
}

/** The view the canvas renders: each tab container's `currentTab` set to its shown panel, copying only changed paths. */
export function withDesignTabs(view: ViewDocument, tabs: DesignTabs): ViewDocument {
  let shown = view;
  for (const id of Object.keys(tabs)) {
    const node = findNode(shown.spec.root, id);
    if (!node || node.kind !== "container" || node.layout !== "tab") continue;
    const index = shownTab(node, tabs);
    if (node.props.currentTab === index) continue;
    shown = patchNode(shown, id, (current) => ({ ...current, props: { ...(current as ContainerNode).props, currentTab: index } }) as ViewNode);
  }
  return shown;
}
