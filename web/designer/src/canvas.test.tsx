import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TagClient, createSession, findNode, type ContainerNode, type ViewDocument } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { Canvas, selectedComponent } from "./Canvas";
import { Inspector } from "./Inspector";
import { setCoordinateMode, updateNode } from "./project";
import { sampleProject } from "./sampleProject";
import { createTagRuntime, seedStudio, type TagRuntime } from "./runtime";
import { TAG_DRAG_TYPE, encodeDraggedTag } from "./tagDrop";

/** D-1: the canvas is the live view in design mode, with a selection and drag overlay. */

const registry = componentRegistry();

/** Overview plus a flex group (a coordinate child) holding one label. */
function overviewWithGroup(): ViewDocument {
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
  view.spec.root.children.push(group);
  return view;
}

// happy-dom has no layout: give each rendered node the box its frame describes, so the overlay can hit-test.
const boxes: Record<string, { x: number; y: number; width: number; height: number }> = {
  root: { x: 0, y: 0, width: 680, height: 360 },
  title: { x: 16, y: 12, width: 300, height: 32 },
  level: { x: 16, y: 56, width: 200, height: 64 },
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
    const box = boxes[this.getAttribute("data-node-id") ?? ""];
    return rect(box ?? { x: 0, y: 0, width: 0, height: 0 });
  });
});

afterEach(() => vi.restoreAllMocks());

function Harness({ initial, runtime, onView }: { initial: ViewDocument; runtime: TagRuntime; onView?: (view: ViewDocument) => void }) {
  const [view, setView] = useState(initial);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const commit = (next: ViewDocument) => {
    setView(next);
    onView?.(next);
  };
  return (
    <>
      <Inspector view={view} selected={selectedComponent(view, selectedId)} tags={runtime.server} onView={commit} />
      <Canvas
        view={view}
        views={[view]}
        registry={registry}
        transport={runtime.transport}
        session={createSession({ id: "canvas", locale: "en-US", theme: "daylight" })}
        selectedId={selectedId}
        onSelect={setSelectedId}
        onFrame={(id, frame) => commit(updateNode(view, id, { frame }))}
        onDropTag={(id, tag) => commit(updateNode(view, id, { bindings: { text: { kind: "tag", mode: "direct", path: tag.path } } }))}
      />
    </>
  );
}

function setup(view = overviewWithGroup()) {
  const runtime = createTagRuntime(seedStudio);
  const views: ViewDocument[] = [];
  const result = render(<Harness initial={view} runtime={runtime} onView={(next) => views.push(next)} />);
  const overlay = screen.getByRole("application", { name: "View canvas" });
  const canvas = screen.getByTestId("canvas");
  const latest = () => views[views.length - 1];
  return { runtime, overlay, canvas, latest, ...result };
}

const levelText = (canvas: HTMLElement) => canvas.querySelector("[data-node-id='level'] [data-testid='numeric-value']");

function press(overlay: HTMLElement, x: number, y: number) {
  fireEvent.pointerDown(overlay, { clientX: x, clientY: y, button: 0, pointerId: 1 });
}

function release(x: number, y: number) {
  fireEvent.pointerUp(window, { clientX: x, clientY: y, button: 0, pointerId: 1 });
}

describe("live canvas", () => {
  it("shows live tag values in design mode", async () => {
    const { runtime, canvas } = setup();
    await waitFor(() => expect(levelText(canvas)).toHaveTextContent("62.4 m"));
    act(() => runtime.server.set("plant/tank/level", 12.5));
    await waitFor(() => expect(levelText(canvas)).toHaveTextContent("12.5 m"));
    expect(canvas.querySelector(".oip-session")).toHaveAttribute("data-design-mode", "true");
    runtime.server.close();
  });

  it("selects a nested child, its container, and the root by clicking", async () => {
    const { runtime, overlay } = setup();
    press(overlay, 420, 280);
    release(420, 280);
    expect(overlay).toHaveAttribute("data-selected-node", "inner");
    expect(screen.getByTestId("canvas-selection")).toHaveAttribute("data-selected-node", "inner");
    // A flex child is select-only.
    expect(screen.queryByRole("button", { name: "Resize Inner" })).toBeNull();

    press(overlay, 600, 340);
    release(600, 340);
    expect(overlay).toHaveAttribute("data-selected-node", "group");
    // The container's props are in the Inspector.
    const inspector = screen.getByTestId("inspector");
    expect(inspector).toHaveAttribute("data-container", "flex");
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Group");
    expect(screen.getByRole("combobox", { name: "Direction" })).toHaveValue("row");
    expect(screen.getByRole("button", { name: "Resize Group" })).toBeInTheDocument();

    press(overlay, 670, 20);
    release(670, 20);
    expect(overlay).toHaveAttribute("data-selected-node", "root");
    runtime.server.close();
  });

  it("does not run a button's action when it is clicked in design mode", async () => {
    const { runtime, overlay, canvas } = setup();
    await waitFor(() => expect(levelText(canvas)).toHaveTextContent("62.4 m"));
    act(() => runtime.server.set("plant/pump/command", false));
    press(overlay, 500, 220);
    release(500, 220);
    expect(overlay).toHaveAttribute("data-selected-node", "start");
    // Even a click that reaches the component itself does nothing in design mode.
    const button = canvas.querySelector("[data-node-id='start'] button") as HTMLButtonElement;
    expect(button).toBeTruthy();
    fireEvent.click(button);
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(runtime.server.get("plant/pump/command")?.value).toBe(false);
    runtime.server.close();
  });

  it("drags a coordinate child without re-subscribing tags on each frame", async () => {
    const { runtime, overlay, canvas, latest } = setup();
    await waitFor(() => expect(levelText(canvas)).toHaveTextContent("62.4 m"));
    const subscribe = vi.spyOn(TagClient.prototype, "subscribe");
    press(overlay, 40, 20);
    for (let step = 1; step <= 8; step += 1) fireEvent.pointerMove(window, { clientX: 40 + step * 10, clientY: 20, pointerId: 1 });
    // The canvas shows the drag before it is committed.
    const title = canvas.querySelector("[data-node-id='title']") as HTMLElement;
    expect(title.style.left).toBe("96px");
    expect(latest()).toBeUndefined();
    release(120, 20);
    expect(findNode(latest()!.spec.root, "title")?.frame).toMatchObject({ x: 96, y: 12, width: 300, height: 32 });
    expect(screen.getByRole("spinbutton", { name: "X" })).toHaveValue(96);
    expect(subscribe).not.toHaveBeenCalled();
    expect(levelText(canvas)).toHaveTextContent("62.4 m");

    // Resize from the handle, then nudge with the keyboard.
    const handle = screen.getByRole("button", { name: "Resize Title" });
    fireEvent.pointerDown(handle, { clientX: 400, clientY: 44, button: 0, pointerId: 2 });
    fireEvent.pointerMove(window, { clientX: 440, clientY: 60, pointerId: 2 });
    fireEvent.pointerUp(window, { clientX: 440, clientY: 60, pointerId: 2 });
    expect(findNode(latest()!.spec.root, "title")?.frame).toMatchObject({ width: 340, height: 48 });
    fireEvent.keyDown(overlay, { key: "ArrowDown", shiftKey: true });
    expect(findNode(latest()!.spec.root, "title")?.frame).toMatchObject({ x: 96, y: 22 });
    runtime.server.close();
  });

  it("shows a binding change on the canvas at once", async () => {
    const { runtime, overlay, canvas } = setup();
    await waitFor(() => expect(levelText(canvas)).toHaveTextContent("62.4 m"));
    act(() => runtime.server.set("sim/level", 7.5));
    press(overlay, 30, 70);
    release(30, 70);
    // The binding editor (D-3) in the left Inspector edits the live canvas.
    fireEvent.click(screen.getByRole("button", { name: "Value: Tag binding" }));
    const editor = screen.getByRole("region", { name: "Binding for value" });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Tag path" }), { target: { value: "sim/level" } });
    await waitFor(() => expect(levelText(canvas)).toHaveTextContent("7.5 m"));
    runtime.server.close();
  });

  it("binds a tag dropped on a component", async () => {
    const { runtime, overlay, canvas, latest } = setup();
    act(() => runtime.server.set("plant/tank/note", "Dropped note"));
    const data = new Map<string, string>([[TAG_DRAG_TYPE, encodeDraggedTag({ path: "plant/tank/note" })]]);
    const dataTransfer = { types: [TAG_DRAG_TYPE], getData: (type: string) => data.get(type) ?? "", dropEffect: "none" };
    for (const type of ["dragover", "drop"]) {
      // happy-dom's DragEvent drops dataTransfer and coordinates from its init, so set them on the event.
      const event = new Event(type, { bubbles: true, cancelable: true });
      Object.defineProperties(event, { dataTransfer: { value: dataTransfer }, clientX: { value: 30 }, clientY: { value: 20 } });
      fireEvent(overlay, event);
    }
    const binding = findNode(latest()!.spec.root, "title");
    expect(binding?.kind === "component" ? binding.bindings?.text : undefined).toMatchObject({ path: "plant/tank/note" });
    await waitFor(() => expect(canvas.querySelector("[data-node-id='title']")).toHaveTextContent("Dropped note"));
    runtime.server.close();
  });

  it("reverts a drag on pointer cancel or Esc, and drops its listeners on unmount", async () => {
    const { runtime, overlay, canvas, latest, unmount } = setup();
    const title = () => canvas.querySelector("[data-node-id='title']") as HTMLElement;
    press(overlay, 40, 20);
    fireEvent.pointerMove(window, { clientX: 90, clientY: 20, pointerId: 1 });
    expect(title().style.left).toBe("66px");
    fireEvent.pointerCancel(window, { pointerId: 1 });
    expect(title().style.left).toBe("16px");
    expect(latest()).toBeUndefined();

    press(overlay, 40, 20);
    fireEvent.pointerMove(window, { clientX: 90, clientY: 40, pointerId: 1 });
    fireEvent.keyDown(window, { key: "Escape" });
    expect(title().style.left).toBe("16px");
    release(90, 40);
    expect(latest()).toBeUndefined();
    // Esc ends only the drag; the title stays selected.
    expect(overlay).toHaveAttribute("data-selected-node", "title");

    press(overlay, 40, 20);
    unmount();
    fireEvent.pointerMove(window, { clientX: 90, clientY: 40, pointerId: 1 });
    release(90, 40);
    expect(latest()).toBeUndefined();
    runtime.server.close();
  });

  it("converts child frames when the root switches between fixed and percent", async () => {
    const { runtime, overlay, canvas, latest } = setup();
    press(overlay, 670, 20);
    release(670, 20);
    expect(screen.getByTestId("inspector")).toHaveAttribute("data-container", "coordinate");
    fireEvent.change(screen.getByRole("combobox", { name: "Mode" }), { target: { value: "percent" } });
    const title = findNode(latest()!.spec.root, "title");
    expect(latest()!.spec.root.props.mode).toBe("percent");
    expect(title?.frame?.x).toBeCloseTo(16 / 680);
    expect(title?.frame?.width).toBeCloseTo(300 / 680);
    expect(title?.frame?.height).toBeCloseTo(32 / 360);
    expect((canvas.querySelector("[data-node-id='title']") as HTMLElement).style.width).toMatch(/%$/);

    fireEvent.change(screen.getByRole("combobox", { name: "Mode" }), { target: { value: "fixed" } });
    expect(findNode(latest()!.spec.root, "title")?.frame).toMatchObject({ x: 16, y: 12, width: 300, height: 32 });
    runtime.server.close();
  });
});

describe("setCoordinateMode", () => {
  it("keeps converted children inside the container", () => {
    const view = structuredClone(sampleProject.views[0]!);
    const title = findNode(view.spec.root, "title")!;
    title.frame = { x: 900, y: -20, width: 2000, height: 40 };
    const converted = findNode(setCoordinateMode(view, "root", "percent").spec.root, "title")!.frame!;
    expect(converted.width).toBe(1);
    expect(converted.x).toBe(0);
    expect(converted.y).toBe(0);
    expect(converted.x + converted.width).toBeLessThanOrEqual(1);
  });
});
