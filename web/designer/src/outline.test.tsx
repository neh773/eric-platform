import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSession, findNode, type ContainerNode, type ViewDocument } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { Canvas, selectedComponent } from "./Canvas";
import { Inspector } from "./Inspector";
import { OUTLINE_DRAG_TYPE, Outline, visibleRows } from "./Outline";
import {
  ancestorsOf,
  followDesignTabs,
  moveNode,
  moveProblem,
  removeNode,
  removeProblem,
  revealNode,
  updateNode,
  withDesignTabs,
  type DesignTabs,
} from "./project";
import { sampleProject } from "./sampleProject";
import { createTagRuntime, seedStudio, type TagRuntime } from "./runtime";

/** D-2: the outline beside the canvas, nested selection, tab panels at design time, and reorder/reparent. */

const registry = componentRegistry();

/** Overview plus a flex group (one label) and a tab container whose second panel holds a flex container with a label. */
function nestedView(): ViewDocument {
  const view = structuredClone(sampleProject.views[0]!);
  const group: ContainerNode = {
    id: "group",
    kind: "container",
    name: "Group",
    layout: "flex",
    props: { direction: "row" },
    frame: { x: 400, y: 260, width: 240, height: 90 },
    children: [{ id: "inner", kind: "component", name: "Inner", type: "label", props: { text: "Inside" }, flex: { grow: 0, shrink: 1, basis: "100px", display: true } }],
  };
  const tabs: ContainerNode = {
    id: "tabs",
    kind: "container",
    name: "Tabs",
    layout: "tab",
    props: { tabs: [{ label: "First" }, { label: "Second" }] },
    frame: { x: 240, y: 130, width: 200, height: 110 },
    children: [
      { id: "first-label", kind: "component", name: "First label", type: "label", props: { text: "One" } },
      {
        id: "second",
        kind: "container",
        name: "Second panel",
        layout: "flex",
        props: { direction: "column" },
        children: [{ id: "deep", kind: "component", name: "Deep", type: "label", props: { text: "Deep text" }, flex: { grow: 0, shrink: 1, basis: "30px", display: true } }],
      },
    ],
  };
  view.spec.root.children.push(group, tabs);
  return view;
}

// happy-dom has no layout: give each rendered node the box its frame describes, so the overlay can hit-test.
const boxes: Record<string, { x: number; y: number; width: number; height: number }> = {
  root: { x: 0, y: 0, width: 680, height: 360 },
  title: { x: 16, y: 12, width: 300, height: 32 },
  start: { x: 460, y: 208, width: 160, height: 40 },
  group: { x: 400, y: 260, width: 240, height: 90 },
  inner: { x: 410, y: 270, width: 100, height: 30 },
};

function rect(box: { x: number; y: number; width: number; height: number }): DOMRect {
  return { ...box, left: box.x, top: box.y, right: box.x + box.width, bottom: box.y + box.height, toJSON: () => box } as DOMRect;
}

beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    if (this.classList.contains("oip-canvas-overlay")) return rect(boxes.root!);
    if (this.classList.contains("oip-outline-row")) return rect({ x: 0, y: 0, width: 200, height: 20 });
    const box = boxes[this.getAttribute("data-node-id") ?? ""];
    return rect(box ?? { x: 0, y: 0, width: 0, height: 0 });
  });
});

afterEach(() => vi.restoreAllMocks());

/** The Studio's selection wiring (App.tsx): outline, canvas and Inspector share one selection and the tab choices. */
function Harness({ initial, runtime, onView }: { initial: ViewDocument; runtime: TagRuntime; onView: (view: ViewDocument) => void }) {
  const [view, setView] = useState(initial);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [designTabs, setDesignTabs] = useState<DesignTabs>({});
  const commit = (next: ViewDocument) => {
    setView(next);
    setDesignTabs((tabs) => followDesignTabs(view, next, tabs));
    onView(next);
  };
  const select = (id: string | null) => {
    setSelectedId(id);
    if (id) setDesignTabs((tabs) => revealNode(view, id, tabs));
  };
  return (
    <>
      <Inspector view={view} selected={selectedComponent(view, selectedId)} onView={commit} onSelect={select} />
      <Canvas
        view={view}
        views={[view]}
        registry={registry}
        transport={runtime.transport}
        session={createSession({ id: "outline", locale: "en-US", theme: "daylight" })}
        selectedId={selectedId}
        onSelect={select}
        onFrame={(id, frame) => commit(updateNode(view, id, { frame }))}
        designTabs={designTabs}
      />
      <Outline
        view={view}
        selectedId={selectedId}
        designTabs={designTabs}
        onSelect={select}
        onMove={(id, parentId, index) => {
          const moved = moveNode(view, id, parentId, index);
          commit(moved);
          setSelectedId(id);
          setDesignTabs((tabs) => revealNode(moved, id, tabs));
        }}
        onDelete={(id) => {
          const parent = ancestorsOf(view.spec.root, id).at(-1);
          commit(removeNode(view, id));
          setSelectedId(parent?.id ?? null);
        }}
      />
    </>
  );
}

function setup(view = nestedView()) {
  const runtime = createTagRuntime(seedStudio);
  const views: ViewDocument[] = [];
  const result = render(<Harness initial={view} runtime={runtime} onView={(next) => views.push(next)} />);
  const overlay = screen.getByRole("application", { name: "View canvas" });
  const canvas = screen.getByTestId("canvas");
  const tree = screen.getByRole("tree", { name: "Overview outline" });
  const latest = () => views[views.length - 1];
  return { runtime, overlay, canvas, tree, latest, ...result };
}

const item = (tree: HTMLElement, name: string) => within(tree).getByRole("treeitem", { name });
const row = (tree: HTMLElement, name: string) => item(tree, name).querySelector(":scope > .oip-outline-row") as HTMLElement;

function click(overlay: HTMLElement, x: number, y: number, init: { detail?: number; altKey?: boolean } = {}) {
  fireEvent.pointerDown(overlay, { clientX: x, clientY: y, button: 0, pointerId: 1, altKey: init.altKey ?? false });
  fireEvent.pointerUp(window, { clientX: x, clientY: y, button: 0, pointerId: 1 });
  fireEvent.click(overlay, { clientX: x, clientY: y, button: 0, detail: init.detail ?? 1, altKey: init.altKey ?? false });
}

describe("outline tree", () => {
  it("lists the view's containers and components as an ARIA tree", () => {
    const { runtime, tree } = setup();
    const root = item(tree, "Root");
    expect(root).toHaveAttribute("aria-level", "1");
    expect(root).toHaveAttribute("aria-expanded", "true");
    expect(root).toHaveAttribute("aria-description", "Coordinate container");
    const group = item(tree, "Group");
    expect(group).toHaveAttribute("aria-level", "2");
    expect(group).toHaveAttribute("aria-description", "Flex container");
    expect(item(tree, "Inner")).toHaveAttribute("aria-level", "3");
    expect(item(tree, "Inner")).toHaveAttribute("aria-description", "Label");
    expect(item(tree, "Inner")).not.toHaveAttribute("aria-expanded");
    // Every node of the view is reachable, the inactive tab's panel included.
    expect(item(tree, "Deep")).toHaveAttribute("aria-description", "Label, on a hidden tab");
    expect(within(tree).getAllByRole("treeitem")).toHaveLength(visibleRows(nestedView().spec.root, new Set()).length);
    // Exactly one row is in the tab order (roving tabindex).
    expect(within(tree).getAllByRole("treeitem").filter((node) => node.tabIndex === 0)).toHaveLength(1);
    runtime.server.close();
  });

  it("keeps outline and canvas selection in step both ways", () => {
    const { runtime, tree, overlay } = setup();
    fireEvent.click(row(tree, "Inner"));
    expect(overlay).toHaveAttribute("data-selected-node", "inner");
    expect(item(tree, "Inner")).toHaveAttribute("aria-selected", "true");

    // Collapse the group, then select its child on the canvas: the outline opens it again and marks the row.
    fireEvent.click(row(tree, "Group").querySelector(".oip-outline-twisty")!);
    expect(item(tree, "Group")).toHaveAttribute("aria-expanded", "false");
    expect(within(tree).queryByRole("treeitem", { name: "Inner" })).toBeNull();
    click(overlay, 600, 340);
    expect(item(tree, "Group")).toHaveAttribute("aria-selected", "true");
    click(overlay, 420, 280);
    expect(item(tree, "Group")).toHaveAttribute("aria-expanded", "true");
    expect(item(tree, "Inner")).toHaveAttribute("aria-selected", "true");
    expect(item(tree, "Inner").tabIndex).toBe(0);
    runtime.server.close();
  });

  it("scrolls the selected row into view", () => {
    const scroll = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: scroll });
    const { runtime, overlay } = setup();
    click(overlay, 420, 280);
    expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
    expect(scroll.mock.contexts.at(-1)).toHaveAttribute("data-outline-row", "inner");
    delete (HTMLElement.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    runtime.server.close();
  });
});

describe("outline keyboard", () => {
  it("moves with the arrows, collapses and expands, and selects with Enter", () => {
    const { runtime, tree, overlay } = setup();
    const root = item(tree, "Root");
    root.focus();
    fireEvent.keyDown(root, { key: "ArrowDown" });
    expect(document.activeElement).toBe(item(tree, "Title"));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowUp" });
    expect(document.activeElement).toBe(root);
    fireEvent.keyDown(root, { key: "ArrowLeft" });
    expect(root).toHaveAttribute("aria-expanded", "false");
    expect(within(tree).getAllByRole("treeitem")).toHaveLength(1);
    fireEvent.keyDown(root, { key: "ArrowRight" });
    expect(root).toHaveAttribute("aria-expanded", "true");
    fireEvent.keyDown(root, { key: "ArrowRight" });
    expect(document.activeElement).toBe(item(tree, "Title"));
    // Focus alone does not select; Enter does.
    expect(overlay).not.toHaveAttribute("data-selected-node");
    fireEvent.keyDown(document.activeElement!, { key: "Enter" });
    expect(overlay).toHaveAttribute("data-selected-node", "title");

    fireEvent.keyDown(document.activeElement!, { key: "End" });
    expect(document.activeElement).toBe(item(tree, "Deep"));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowLeft" });
    expect(document.activeElement).toBe(item(tree, "Second panel"));
    fireEvent.keyDown(document.activeElement!, { key: "Home" });
    expect(document.activeElement).toBe(root);
    runtime.server.close();
  });

  it("deletes only after confirming", () => {
    const { runtime, tree, latest } = setup();
    item(tree, "Inner").focus();
    fireEvent.keyDown(item(tree, "Inner"), { key: "Delete" });
    let dialog = screen.getByRole("alertdialog", { name: "Delete Inner?" });
    expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: "Delete" }));
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(latest()).toBeUndefined();

    fireEvent.click(row(tree, "Group"));
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    dialog = screen.getByRole("alertdialog", { name: "Delete Group?" });
    expect(dialog).toHaveTextContent("and the 1 node inside it");
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete" }));
    expect(findNode(latest()!.spec.root, "group")).toBeUndefined();
    expect(findNode(latest()!.spec.root, "inner")).toBeUndefined();
    expect(within(tree).queryByRole("treeitem", { name: "Group" })).toBeNull();
    // The parent is selected afterwards.
    expect(item(tree, "Root")).toHaveAttribute("aria-selected", "true");
    runtime.server.close();
  });

  it("moves the focused node with Alt+arrows and the toolbar", async () => {
    const { runtime, tree, latest } = setup();
    item(tree, "Level").focus();
    fireEvent.keyDown(item(tree, "Level"), { key: "ArrowUp", altKey: true });
    expect(latest()!.spec.root.children.map((child) => child.id).slice(0, 2)).toEqual(["level", "title"]);
    fireEvent.keyDown(item(tree, "Level"), { key: "ArrowDown", altKey: true });
    expect(latest()!.spec.root.children.map((child) => child.id).slice(0, 2)).toEqual(["title", "level"]);

    // Into the container above (Group is right before Tabs), then back out with the toolbar.
    fireEvent.click(row(tree, "Tabs"));
    fireEvent.click(screen.getByRole("button", { name: "Move into container above" }));
    expect((findNode(latest()!.spec.root, "group") as ContainerNode).children.map((child) => child.id)).toEqual(["inner", "tabs"]);
    expect(item(tree, "Tabs")).toHaveAttribute("aria-level", "3");
    fireEvent.click(screen.getByRole("button", { name: "Move out of container" }));
    expect(latest()!.spec.root.children.map((child) => child.id).slice(-2)).toEqual(["group", "tabs"]);
    expect(findNode(latest()!.spec.root, "tabs")?.frame).toBeDefined();
    runtime.server.close();
  });
});

function dragEvent(type: string, data: Map<string, string>, clientY = 10) {
  const dataTransfer = {
    get types() {
      return [...data.keys()];
    },
    setData: (kind: string, value: string) => data.set(kind, value),
    getData: (kind: string) => data.get(kind) ?? "",
    dropEffect: "none",
    effectAllowed: "all",
  };
  // happy-dom's DragEvent drops dataTransfer and coordinates from its init, so set them on the event.
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, { dataTransfer: { value: dataTransfer }, clientX: { value: 20 }, clientY: { value: clientY } });
  return event;
}

describe("outline drag and drop", () => {
  it("reparents a coordinate child into a flex container and back out", () => {
    const { runtime, tree, latest } = setup();
    const data = new Map<string, string>();
    fireEvent(row(tree, "Title"), dragEvent("dragstart", data));
    // The middle of a container row drops inside it.
    const over = dragEvent("dragover", data, 10);
    fireEvent(row(tree, "Group"), over);
    expect(over.defaultPrevented).toBe(true);
    expect(row(tree, "Group")).toHaveAttribute("data-drop", "inside");
    fireEvent(row(tree, "Group"), dragEvent("drop", data, 10));
    const title = findNode(latest()!.spec.root, "title")!;
    expect((findNode(latest()!.spec.root, "group") as ContainerNode).children.map((child) => child.id)).toEqual(["inner", "title"]);
    expect(title.frame).toBeUndefined();
    expect(title.flex).toEqual({ grow: 0, shrink: 1, basis: "300px", display: true });
    expect(item(tree, "Title")).toHaveAttribute("aria-level", "3");

    // The top quarter of a row drops before it.
    fireEvent(row(tree, "Title"), dragEvent("dragstart", data));
    fireEvent(row(tree, "Level"), dragEvent("dragover", data, 2));
    fireEvent(row(tree, "Level"), dragEvent("drop", data, 2));
    const back = findNode(latest()!.spec.root, "title")!;
    expect(latest()!.spec.root.children[0]!.id).toBe("title");
    expect(back.flex).toBeUndefined();
    expect(back.frame).toMatchObject({ width: 300, height: 40 });
    runtime.server.close();
  });

  it("refuses a drop into the node itself or out of a split container", () => {
    const { runtime, tree, latest } = setup();
    const data = new Map<string, string>();
    fireEvent(row(tree, "Group"), dragEvent("dragstart", data));
    const over = dragEvent("dragover", data, 10);
    fireEvent(row(tree, "Inner"), over);
    expect(over.defaultPrevented).toBe(false);
    fireEvent(row(tree, "Inner"), dragEvent("drop", data, 10));
    expect(latest()).toBeUndefined();
    expect(data.has(OUTLINE_DRAG_TYPE)).toBe(true);
    runtime.server.close();
  });
});

describe("tab panels at design time", () => {
  it("shows a hidden tab's child when it is selected, without changing the view", () => {
    const { runtime, tree, canvas, latest } = setup();
    const panel = (id: string) => canvas.querySelector(`[data-node-id='${id}']`)!.closest("[role='tabpanel']") as HTMLElement;
    expect(panel("deep").hidden).toBe(true);
    expect(row(tree, "Deep")).toHaveAttribute("data-hidden-tab", "true");
    fireEvent.click(row(tree, "Deep"));
    expect(panel("deep").hidden).toBe(false);
    expect(panel("first-label").hidden).toBe(true);
    expect(canvas.querySelector("[data-node-id='tabs']")).toHaveAttribute("data-active-tab", "1");
    expect(row(tree, "Deep")).not.toHaveAttribute("data-hidden-tab");
    expect(row(tree, "First label")).toHaveAttribute("data-hidden-tab", "true");
    // Nothing was written into the view.
    expect(latest()).toBeUndefined();
    fireEvent.click(row(tree, "First label"));
    expect(panel("first-label").hidden).toBe(false);
    runtime.server.close();
  });

  it("lets the author's saved current tab win over the design-time choice", () => {
    const view = nestedView();
    let tabs = revealNode(view, "deep", {});
    expect(tabs).toEqual({ tabs: { index: 1, base: 0 } });
    expect((findNode(withDesignTabs(view, tabs).spec.root, "tabs") as ContainerNode).props.currentTab).toBe(1);
    // The saved view itself is untouched.
    expect((findNode(view.spec.root, "tabs") as ContainerNode).props.currentTab).toBeUndefined();
    // Revealing what is already shown keeps the same object (no re-render of the canvas).
    expect(revealNode(view, "deep", tabs)).toBe(tabs);
    const authored = updateNode(view, "tabs", { props: { currentTab: 0 } });
    tabs = { tabs: { index: 1, base: 1 } };
    expect((findNode(withDesignTabs(authored, tabs).spec.root, "tabs") as ContainerNode).props.currentTab).toBe(0);
  });
});

describe("nested canvas selection", () => {
  it("walks up with Alt+click, a double click and Esc", () => {
    const { runtime, overlay } = setup();
    click(overlay, 420, 280, { altKey: true });
    expect(overlay).toHaveAttribute("data-selected-node", "group");
    click(overlay, 420, 280, { altKey: true });
    expect(overlay).toHaveAttribute("data-selected-node", "root");

    click(overlay, 420, 280);
    expect(overlay).toHaveAttribute("data-selected-node", "inner");
    click(overlay, 420, 280, { detail: 2 });
    expect(overlay).toHaveAttribute("data-selected-node", "group");
    click(overlay, 420, 280, { detail: 3 });
    expect(overlay).toHaveAttribute("data-selected-node", "root");

    // A single click somewhere else is a fresh selection again.
    click(overlay, 30, 20);
    expect(overlay).toHaveAttribute("data-selected-node", "title");
    click(overlay, 420, 280);
    fireEvent.keyDown(overlay, { key: "Escape" });
    expect(overlay).toHaveAttribute("data-selected-node", "group");
    fireEvent.keyDown(overlay, { key: "Escape" });
    expect(overlay).toHaveAttribute("data-selected-node", "root");
    fireEvent.keyDown(overlay, { key: "Escape" });
    expect(overlay).not.toHaveAttribute("data-selected-node");
    runtime.server.close();
  });

  it("offers the parents in the Inspector breadcrumb", () => {
    const { runtime, overlay } = setup();
    click(overlay, 420, 280);
    const path = screen.getByRole("navigation", { name: "Selection path" });
    expect(within(path).getAllByRole("button").map((button) => button.textContent)).toEqual(["Root", "Group"]);
    expect(within(path).getByText("Inner")).toHaveAttribute("aria-current", "location");
    act(() => fireEvent.click(within(path).getByRole("button", { name: "Group" })));
    expect(overlay).toHaveAttribute("data-selected-node", "group");
    expect(screen.getByTestId("inspector")).toHaveAttribute("data-container", "flex");
    runtime.server.close();
  });
});

describe("moveNode frame conversion", () => {
  const percentGroup = (): ViewDocument => {
    const view = nestedView();
    const box: ContainerNode = {
      id: "box",
      kind: "container",
      name: "Box",
      layout: "coordinate",
      props: { mode: "percent" },
      frame: { x: 100, y: 50, width: 400, height: 200 },
      children: [],
    };
    view.spec.root.children.push(box);
    return view;
  };

  it("keeps a coordinate child's place on screen when it moves into a nested coordinate container", () => {
    const view = percentGroup();
    // Title sits at (16, 12) 300x32 in the root; Box's content starts at (100, 50) and is 400x200 pixels.
    findNode(view.spec.root, "title")!.frame = { x: 150, y: 80, width: 200, height: 40 };
    const moved = moveNode(view, "title", "box", 0);
    const frame = findNode(moved.spec.root, "title")!.frame!;
    expect(frame.x).toBeCloseTo(50 / 400);
    expect(frame.y).toBeCloseTo(30 / 200);
    expect(frame.width).toBeCloseTo(200 / 400);
    expect(frame.height).toBeCloseTo(40 / 200);
    // And back out to the fixed root: the same pixels as before.
    const back = moveNode(moved, "title", "root", 0);
    expect(findNode(back.spec.root, "title")!.frame).toEqual({ x: 150, y: 80, width: 200, height: 40 });
  });

  it("converts between flex, coordinate and column parents", () => {
    const view = percentGroup();
    // Flex child (basis 100px) into a percent container: its basis becomes its width, at the container's corner.
    const intoBox = moveNode(view, "inner", "box", 0, { rendered: null });
    expect(findNode(intoBox.spec.root, "inner")!.flex).toBeUndefined();
    expect(findNode(intoBox.spec.root, "inner")!.frame).toMatchObject({ width: 100 / 400, height: 40 / 200 });
    // A rendered size wins over the basis.
    const measured = moveNode(view, "inner", "root", 0, { rendered: { width: 120, height: 30 } });
    expect(findNode(measured.spec.root, "inner")!.frame).toMatchObject({ x: 16, y: 16, width: 120, height: 30 });
    // A column-direction flex parent takes the height as its basis.
    const intoColumn = moveNode(view, "title", "second", 1);
    expect(findNode(intoColumn.spec.root, "title")!.flex?.basis).toBe("32px");
    // A reorder inside a column container keeps the node's placement.
    const grid = structuredClone(sampleProject.views[2]!);
    const columnTarget = moveNode(grid, "sum-pump", "summary-root", 0);
    expect(columnTarget.spec.root.children[0]!.id).toBe("sum-pump");
    expect(findNode(columnTarget.spec.root, "sum-pump")!.columns).toEqual(findNode(grid.spec.root, "sum-pump")!.columns);
  });

  it("keeps tab labels with their panels", () => {
    const view = nestedView();
    const out = moveNode(view, "second", "root", 0);
    const tabs = findNode(out.spec.root, "tabs") as ContainerNode;
    expect(tabs.props.tabs).toEqual([{ label: "First" }]);
    const back = moveNode(out, "second", "tabs", 0);
    const restored = findNode(back.spec.root, "tabs") as ContainerNode;
    expect(restored.children.map((child) => child.id)).toEqual(["second", "first-label"]);
    expect(restored.props.tabs).toEqual([{ label: "Second panel" }, { label: "First" }]);
    const reordered = moveNode(view, "first-label", "tabs", 2);
    expect((findNode(reordered.spec.root, "tabs") as ContainerNode).props.tabs).toEqual([{ label: "Second" }, { label: "First" }]);
  });

  it("refuses moves that would break the tree", () => {
    const view = nestedView();
    view.spec.root.children.push({
      id: "split",
      kind: "container",
      name: "Split",
      layout: "split",
      props: {},
      frame: { x: 0, y: 0, width: 100, height: 100 },
      children: [
        { id: "pane-a", kind: "container", name: "A", layout: "flex", props: {}, children: [] },
        { id: "pane-b", kind: "container", name: "B", layout: "flex", props: {}, children: [] },
      ],
    });
    expect(moveProblem(view, "root", "group")).toMatch(/root/);
    expect(moveProblem(view, "tabs", "deep")).toMatch(/containers/);
    expect(moveProblem(view, "tabs", "second")).toMatch(/itself/);
    expect(moveProblem(view, "pane-a", "root")).toMatch(/two children/);
    expect(moveProblem(view, "title", "split")).toMatch(/two children/);
    expect(moveProblem(view, "title", "pane-a")).toBeNull();
    expect(moveProblem(view, "pane-b", "split")).toBeNull();
    expect(() => moveNode(view, "group", "inner", 0)).toThrow();
    // Moving a node to where it already is returns the same view.
    expect(moveNode(view, "title", "root", 0)).toBe(view);
    expect(moveNode(view, "title", "root", 1)).toBe(view);
  });
});

/** A tab container "t" with panels a, b, c (labels only for some) inside a coordinate root. */
function tabView(props: ContainerNode["props"]): ViewDocument {
  const view = structuredClone(sampleProject.views[0]!);
  const panel = (id: string) => ({ id, kind: "component" as const, name: id.toUpperCase(), type: "label", props: { text: id } });
  view.spec.root.children = [
    { id: "t", kind: "container", name: "T", layout: "tab", props, frame: { x: 0, y: 0, width: 300, height: 200 }, children: [panel("a"), panel("b"), panel("c")] },
    { id: "u", kind: "container", name: "U", layout: "tab", props: {}, frame: { x: 320, y: 0, width: 300, height: 200 }, children: [panel("d")] },
  ];
  return view;
}

const tab = (view: ViewDocument, id = "t") => findNode(view.spec.root, id) as ContainerNode;

describe("review fixes: tab labels, current tab, fixed pairs", () => {
  it("keeps a moved panel's label when the label list cannot take it", () => {
    // Same-parent reorder past the end of a short label list.
    const short = moveNode(tabView({ tabs: [{ label: "Alpha" }] }), "a", "t", 3);
    expect(tab(short).children.map((child) => child.id)).toEqual(["b", "c", "a"]);
    expect(tab(short).props.tabs).toEqual([]);
    expect(findNode(short.spec.root, "a")!.tabLabel).toBe("Alpha");
    // Into a tab container that has no label list.
    const across = moveNode(tabView({ tabs: [{ label: "Alpha" }, { label: "Beta" }] }), "b", "u", 0);
    expect(tab(across).props.tabs).toEqual([{ label: "Alpha" }]);
    expect(findNode(across.spec.root, "b")!.tabLabel).toBe("Beta");
    // A label that fits goes back into the list, as before.
    const fits = moveNode(tabView({ tabs: [{ label: "Alpha" }, { label: "Beta" }, { label: "Gamma" }] }), "a", "t", 3);
    expect(tab(fits).props.tabs).toEqual([{ label: "Beta" }, { label: "Gamma" }, { label: "Alpha" }]);
    expect(findNode(fits.spec.root, "a")!.tabLabel).toBeUndefined();
  });

  it("keeps the saved current tab on the same panel, or clamps it when that panel goes", () => {
    const view = tabView({ currentTab: 1 });
    expect(tab(moveNode(view, "a", "t", 3)).props.currentTab).toBe(0);
    expect(tab(moveNode(view, "c", "t", 0)).props.currentTab).toBe(2);
    // The shown panel leaves: the same position stays, clamped to what is left.
    expect(tab(removeNode(view, "b")).props.currentTab).toBe(1);
    expect(tab(moveNode(view, "b", "root", 0)).props.currentTab).toBe(1);
    expect(tab(removeNode(tabView({ currentTab: 2 }), "c")).props.currentTab).toBe(1);
    // An unset current tab (the first panel) is written only when it must change.
    expect(tab(removeNode(tabView({}), "c")).props.currentTab).toBeUndefined();
    expect(tab(moveNode(tabView({}), "d", "t", 0)).props.currentTab).toBe(1);
    expect(tab(removeNode(tabView({}), "a")).props.currentTab).toBeUndefined();
  });

  it("moves the design-time tab choice with its panel, and drops it with its panel", () => {
    const view = tabView({ currentTab: 1 });
    const tabs = revealNode(view, "c", {});
    expect(tabs).toEqual({ t: { index: 2, base: 1 } });
    const moved = moveNode(view, "a", "t", 3);
    const followed = followDesignTabs(view, moved, tabs);
    expect(followed).toEqual({ t: { index: 1, base: 0 } });
    expect(tab(withDesignTabs(moved, followed)).children[tab(withDesignTabs(moved, followed)).props.currentTab!]!.id).toBe("c");
    expect(followDesignTabs(view, removeNode(view, "c"), tabs)).toEqual({});
    // Unrelated edits keep the same object.
    expect(followDesignTabs(view, updateNode(view, "a", { name: "Renamed" }), tabs)).toBe(tabs);
  });

  it("does not bring a design choice back after the author changes the current tab and changes it back", () => {
    const view = tabView({});
    let tabs = revealNode(view, "c", {});
    expect(tab(withDesignTabs(view, tabs)).props.currentTab).toBe(2);
    const authored = updateNode(view, "t", { props: { currentTab: 1 } });
    tabs = followDesignTabs(view, authored, tabs);
    expect(tabs).toEqual({});
    const back = updateNode(authored, "t", { props: { currentTab: 0 } });
    tabs = followDesignTabs(authored, back, tabs);
    expect(tab(withDesignTabs(back, tabs)).props.currentTab).toBe(0);
  });

  it("shows the author's tab on the canvas after an Inspector edit, even when set back", () => {
    const { runtime, tree, canvas } = setup();
    const active = () => canvas.querySelector("[data-node-id='tabs']")!.getAttribute("data-active-tab");
    fireEvent.click(row(tree, "Deep"));
    expect(active()).toBe("1");
    fireEvent.click(row(tree, "Tabs"));
    const current = screen.getByRole("spinbutton", { name: "Current tab" });
    fireEvent.change(current, { target: { value: "1" } });
    fireEvent.change(screen.getByRole("spinbutton", { name: "Current tab" }), { target: { value: "0" } });
    expect(active()).toBe("0");
    runtime.server.close();
  });

  it("refuses to delete a child of a split or breakpoint container", () => {
    const view = nestedView();
    view.spec.root.children.push({
      id: "split",
      kind: "container",
      name: "Split",
      layout: "split",
      props: {},
      frame: { x: 0, y: 0, width: 100, height: 100 },
      children: [
        { id: "pane-a", kind: "container", name: "Pane A", layout: "flex", props: {}, children: [] },
        { id: "pane-b", kind: "container", name: "Pane B", layout: "flex", props: {}, children: [] },
      ],
    });
    expect(removeProblem(view, "pane-a")).toMatch(/two children/);
    expect(() => removeNode(view, "pane-a")).toThrow(/two children/);
    expect(removeProblem(view, "split")).toBeNull();
    expect(removeProblem(view, "root")).toMatch(/root/);

    const { runtime, tree, latest } = setup(view);
    fireEvent.click(row(tree, "Pane A"));
    expect(screen.getByRole("button", { name: "Delete" })).toBeDisabled();
    fireEvent.keyDown(item(tree, "Pane A"), { key: "Delete" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(tree.closest("section")!.querySelector(".oip-outline-notice")).toHaveTextContent("keeps its two children");
    expect(latest()).toBeUndefined();
    runtime.server.close();
  });

  it("leaves a collapsed container collapsed through edits that keep the selection", () => {
    const { runtime, tree, overlay } = setup();
    click(overlay, 420, 280);
    expect(item(tree, "Inner")).toHaveAttribute("aria-selected", "true");
    fireEvent.click(row(tree, "Group").querySelector(".oip-outline-twisty")!);
    expect(item(tree, "Group")).toHaveAttribute("aria-expanded", "false");
    // An edit of the selected node commits a new view; the author's collapse stays.
    fireEvent.change(screen.getByRole("textbox", { name: "Text" }), { target: { value: "Edited" } });
    expect(item(tree, "Group")).toHaveAttribute("aria-expanded", "false");
    // A new selection inside it opens it.
    click(overlay, 30, 20);
    click(overlay, 420, 280);
    expect(item(tree, "Group")).toHaveAttribute("aria-expanded", "true");
    runtime.server.close();
  });
});

describe("review fixes: memoised outline", () => {
  it("does not re-render the outline for a parent render that changes none of its props", async () => {
    // The outline asks removeProblem about the selection once per render (the Delete button), so it counts renders.
    const project = await import("./project");
    const renders = vi.spyOn(project, "removeProblem");
    const view = nestedView();
    const noop = () => {};
    const tabs: DesignTabs = {};
    function Parent({ tick, shown }: { tick: number; shown: ViewDocument }) {
      return (
        <div data-tick={tick}>
          <Outline view={shown} selectedId="inner" designTabs={tabs} onSelect={noop} onMove={noop} onDelete={noop} />
        </div>
      );
    }
    const { rerender } = render(<Parent tick={0} shown={view} />);
    const before = renders.mock.calls.length;
    expect(before).toBeGreaterThan(0);
    rerender(<Parent tick={1} shown={view} />);
    rerender(<Parent tick={2} shown={view} />);
    expect(renders.mock.calls.length).toBe(before);
    // A new view does render it.
    rerender(<Parent tick={3} shown={updateNode(view, "inner", { name: "Renamed" })} />);
    expect(renders.mock.calls.length).toBeGreaterThan(before);
    expect(screen.getByRole("treeitem", { name: "Renamed" })).toBeInTheDocument();
  });
});
