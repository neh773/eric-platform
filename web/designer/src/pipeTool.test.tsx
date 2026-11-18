import { fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ViewRenderer, createSession, findNode, parseView, type ComponentNode, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes, componentRegistry } from "@oip/components";
import { createTagRuntime, seedStudio } from "./runtime";
import { Inspector } from "./Inspector";
import { PipeDrawLayer, PipeToolPanel } from "./PipeTool";
import { appendPipe, constrainAxis, dedupe, insertJoint, moveJoint, pipeAnchors, pipeEnds, pipeFrame, pipeToolProblem, pipeViewPoints, removeJoint, snapToAnchor } from "./pipeModel";
import { blankView } from "./persistence";
import { appendComponent, updateNode } from "./project";

/** HMP-128: drawing flow pipes between components of a coordinate view, and editing their joints. */

/** A view with a tank at (20,20) 80x120 and a pump at (300,100) 60x60. */
function plant(): ViewDocument {
  let view = blankView("plant", "Plant");
  const tank = appendComponent(view, "label", "Tank", {});
  view = updateNode(tank.view, tank.id, { frame: { x: 20, y: 20, width: 80, height: 120 } });
  const pump = appendComponent(view, "label", "Pump", {});
  return updateNode(pump.view, pump.id, { frame: { x: 300, y: 100, width: 60, height: 60 } });
}

const pipeOf = (view: ViewDocument) => view.spec.root.children.find((child) => child.kind === "component" && child.type === "pipe") as ComponentNode;

describe("pipe model", () => {
  it("anchors on the edges and centre of components, and snaps within the radius, edges first", () => {
    const anchors = pipeAnchors(plant());
    expect(anchors).toHaveLength(10);
    expect(anchors.find((anchor) => anchor.side === "right" && anchor.nodeId.startsWith("label-1"))).toMatchObject({ x: 100, y: 80 });
    expect(snapToAnchor({ x: 104, y: 84 }, anchors).anchor).toMatchObject({ side: "right" });
    expect(snapToAnchor({ x: 104, y: 84 }, anchors).point).toEqual({ x: 100, y: 80 });
    expect(snapToAnchor({ x: 200, y: 300 }, anchors)).toEqual({ point: { x: 200, y: 300 } });
    expect(constrainAxis({ x: 10, y: 10 }, { x: 50, y: 14 })).toEqual({ x: 50, y: 10 });
    expect(constrainAxis({ x: 10, y: 10 }, { x: 13, y: 50 })).toEqual({ x: 10, y: 50 });
    expect(dedupe([{ x: 1, y: 1 }, { x: 1, y: 1 }, { x: 2, y: 1 }])).toEqual([{ x: 1, y: 1 }, { x: 2, y: 1 }]);
  });

  it("needs a fixed-coordinate view", () => {
    expect(pipeToolProblem(plant())).toBeNull();
    const flex = parseView({ apiVersion: "oip.hmi/v1", kind: "View", metadata: { name: "F", path: "f" }, spec: { width: 100, height: 100, params: {}, root: { id: "r", kind: "container", name: "R", layout: "flex", props: {}, children: [] } } });
    expect(pipeToolProblem(flex)).toMatch(/coordinate view/);
    expect(pipeAnchors(flex)).toEqual([]);
    expect(() => appendPipe(flex, [{ x: 0, y: 0 }, { x: 5, y: 5 }])).toThrow(/coordinate view/);
    const percent = updateNode(plant(), "root", {});
    percent.spec.root.props.mode = "percent";
    expect(pipeToolProblem(percent)).toMatch(/percent/);
  });

  it("turns drawn points into a pipe whose frame is their box plus the pipe's own thickness", () => {
    const points = [{ x: 100, y: 80 }, { x: 200, y: 80 }, { x: 200, y: 130 }, { x: 300, y: 130 }];
    const { view, id } = appendPipe(plant(), points);
    const pipe = pipeOf(view);
    expect(pipe.id).toBe(id);
    // width 12, outline 2: pad 9 on each side.
    expect(pipe.frame).toEqual({ x: 91, y: 71, width: 218, height: 68 });
    expect(pipe.props.points).toEqual([{ x: 9, y: 9 }, { x: 109, y: 9 }, { x: 109, y: 59 }, { x: 209, y: 59 }]);
    expect(pipeViewPoints(pipe)).toEqual(points);
    expect(pipeFrame(points).frame).toEqual(pipe.frame);
    expect(() => appendPipe(plant(), [{ x: 1, y: 1 }, { x: 1, y: 1 }])).toThrow(/two points/);
    expect(() => parseView(JSON.parse(JSON.stringify(view)), { allowedTypes: allowedComponentTypes() })).not.toThrow();
  });

  it("reports the components a pipe's ends sit on", () => {
    const { view, id } = appendPipe(plant(), [{ x: 100, y: 80 }, { x: 200, y: 80 }, { x: 300, y: 130 }]);
    const ends = pipeEnds(view, id);
    expect(ends.start).toMatchObject({ side: "right" });
    expect(ends.end).toMatchObject({ side: "left" });
    expect(view.spec.root.children.find((child) => child.id === ends.start!.nodeId)!.name).toMatch(/Tank/);
    const loose = appendPipe(plant(), [{ x: 150, y: 200 }, { x: 250, y: 200 }]);
    expect(pipeEnds(loose.view, loose.id)).toEqual({ start: undefined, end: undefined });
  });

  it("edits joints: insert halfway, move, remove, keeping the pipe in place in the view", () => {
    const first = appendPipe(plant(), [{ x: 100, y: 80 }, { x: 200, y: 80 }, { x: 200, y: 130 }]);
    const inserted = insertJoint(first.view, first.id, 0);
    expect(pipeViewPoints(pipeOf(inserted))).toEqual([{ x: 100, y: 80 }, { x: 150, y: 80 }, { x: 200, y: 80 }, { x: 200, y: 130 }]);
    const moved = moveJoint(inserted, first.id, 1, { x: 150, y: 40 });
    expect(pipeViewPoints(pipeOf(moved))).toEqual([{ x: 100, y: 80 }, { x: 150, y: 40 }, { x: 200, y: 80 }, { x: 200, y: 130 }]);
    // The frame follows the points' box.
    expect(pipeOf(moved).frame!.y).toBe(40 - 9);
    const removed = removeJoint(moved, first.id, 1);
    expect(pipeViewPoints(pipeOf(removed))).toHaveLength(3);
    const two = appendPipe(plant(), [{ x: 0, y: 0 }, { x: 50, y: 0 }]);
    expect(removeJoint(two.view, two.id, 0)).toBe(two.view);
    // The last point gets a joint past the end.
    expect(pipeViewPoints(pipeOf(insertJoint(two.view, two.id, 1)))).toHaveLength(3);
  });

  it("follows a frame that was moved or resized", () => {
    const { view, id } = appendPipe(plant(), [{ x: 100, y: 80 }, { x: 200, y: 80 }]);
    const moved = updateNode(view, id, { frame: { ...pipeOf(view).frame!, x: pipeOf(view).frame!.x + 10 } });
    expect(pipeViewPoints(pipeOf(moved))[0]).toEqual({ x: 110, y: 80 });
    const stretched = updateNode(view, id, { frame: { ...pipeOf(view).frame!, width: pipeOf(view).frame!.width * 2 } });
    expect(pipeViewPoints(pipeOf(stretched))[1]!.x).toBeGreaterThan(300);
  });
});

describe("pipe drawing layer", () => {
  function layer(onFinish = vi.fn(), onCancel = vi.fn()) {
    render(<PipeDrawLayer view={plant()} width={400} height={300} onFinish={onFinish} onCancel={onCancel} />);
    const surface = screen.getByRole("application", { name: "Pipe drawing" });
    surface.getBoundingClientRect = () => ({ left: 0, top: 0, width: 400, height: 300, right: 400, bottom: 300, x: 0, y: 0, toJSON: () => ({}) });
    return { surface, onFinish, onCancel };
  }
  const click = (surface: HTMLElement, x: number, y: number, extra: Record<string, unknown> = {}) => fireEvent.click(surface, { clientX: x, clientY: y, ...extra });

  it("adds a joint per click, snaps the ends to component edges, and a double click finishes", () => {
    const { surface, onFinish } = layer();
    click(surface, 103, 82);
    click(surface, 200, 82);
    expect(surface).toHaveAttribute("data-points", "2");
    click(surface, 297, 128);
    click(surface, 297, 128, { detail: 2 });
    expect(onFinish).toHaveBeenCalledTimes(1);
    // First joint snapped to the tank's right edge; the last to the pump's left edge; the duplicate was dropped.
    expect(onFinish.mock.calls[0]![0]).toEqual([{ x: 100, y: 80 }, { x: 200, y: 82 }, { x: 300, y: 130 }]);
  });

  it("holds a segment straight with Shift, removes the last joint with Backspace, finishes with Enter, cancels with Escape", () => {
    const { surface, onFinish, onCancel } = layer();
    click(surface, 150, 220);
    click(surface, 250, 226, { shiftKey: true });
    click(surface, 255, 260);
    fireEvent.keyDown(surface, { key: "Backspace" });
    expect(surface).toHaveAttribute("data-points", "2");
    fireEvent.keyDown(surface, { key: "Enter" });
    expect(onFinish).toHaveBeenCalledWith([{ x: 150, y: 220 }, { x: 250, y: 220 }]);
    fireEvent.keyDown(surface, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("does not finish a pipe with one point", () => {
    const { surface, onFinish } = layer();
    click(surface, 150, 220);
    fireEvent.keyDown(surface, { key: "Enter" });
    expect(onFinish).not.toHaveBeenCalled();
  });
});

describe("pipe tool panel and joints", () => {
  it("toggles, and says why it is off in another layout", () => {
    const onToggle = vi.fn();
    const { rerender } = render(<PipeToolPanel view={plant()} active={false} onToggle={onToggle} />);
    fireEvent.click(screen.getByRole("button", { name: "Draw pipe" }));
    expect(onToggle).toHaveBeenCalled();
    rerender(<PipeToolPanel view={plant()} active onToggle={onToggle} />);
    expect(screen.getByRole("button", { name: "Stop drawing pipe" })).toHaveAttribute("aria-pressed", "true");
    const flex = parseView({ apiVersion: "oip.hmi/v1", kind: "View", metadata: { name: "F", path: "f" }, spec: { width: 100, height: 100, params: {}, root: { id: "r", kind: "container", name: "R", layout: "flex", props: {}, children: [] } } });
    rerender(<PipeToolPanel view={flex} active={false} onToggle={onToggle} />);
    expect(screen.getByRole("button", { name: "Draw pipe" })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("coordinate view");
  });

  it("lists a pipe's joints in the Inspector and edits them, with its colour and flow bindable like any property", () => {
    const first = appendPipe(plant(), [{ x: 100, y: 80 }, { x: 200, y: 80 }, { x: 300, y: 130 }]);
    const state = { view: first.view };
    function Harness() {
      const [view, setView] = useState(state.view);
      state.view = view;
      return <Inspector view={view} selected={findNode(view.spec.root, first.id) as ComponentNode} onView={setView} />;
    }
    render(<Harness />);
    const joints = screen.getByTestId("pipe-points");
    expect(joints).toHaveTextContent("From Tank");
    expect(joints).toHaveTextContent("to Pump");
    fireEvent.change(within(joints).getByLabelText("Joint 2 y"), { target: { value: "60" } });
    expect(pipeViewPoints(pipeOf(state.view))[1]).toEqual({ x: 200, y: 60 });
    fireEvent.click(within(joints).getByLabelText("Insert joint after joint 1"));
    expect(pipeViewPoints(pipeOf(state.view))).toHaveLength(4);
    fireEvent.click(within(joints).getByLabelText("Remove joint 2"));
    expect(pipeViewPoints(pipeOf(state.view))).toHaveLength(3);
    // Properties of the pipe are bindable from the Inspector.
    expect(screen.getByLabelText("Bind Animate flow")).toBeInTheDocument();
    expect(screen.getByLabelText("Bind Pipe colour")).toBeInTheDocument();
  });
});

describe("bound pipes", () => {
  it("a pipe's flow and colour follow view parameters", () => {
    const { view, id } = appendPipe(plant(), [{ x: 100, y: 80 }, { x: 200, y: 80 }]);
    const doc = structuredClone(view);
    doc.spec.params.running = { value: false, direction: "input" };
    doc.spec.params.tint = { value: "#336699", direction: "input" };
    const node = findNode(doc.spec.root, id) as ComponentNode;
    node.bindings = {
      flow: { kind: "property", source: { scope: "param", name: "running" } },
      stroke: { kind: "property", source: { scope: "param", name: "tint" } },
    };
    const parsed = parseView(JSON.parse(JSON.stringify(doc)), { allowedTypes: allowedComponentTypes() });
    const runtime = createTagRuntime(seedStudio, async () => []);
    const show = (overrides: Record<string, unknown>) => (
      <ViewRenderer view={parsed} registry={componentRegistry()} transport={runtime.transport} session={createSession({ id: "s", locale: "en-US" })} paramOverrides={overrides} />
    );
    const { container, rerender } = render(show({}));
    const pipe = () => container.querySelector(".oip-pipe")!;
    expect(pipe().getAttribute("data-flowing")).toBe("false");
    expect(container.querySelector(".oip-pipe-body")!.getAttribute("stroke")).toBe("#336699");
    rerender(show({ running: true, tint: "#aa2200" }));
    expect(pipe().getAttribute("data-flowing")).toBe("true");
    expect(container.querySelector(".oip-pipe-body")!.getAttribute("stroke")).toBe("#aa2200");
    runtime.server.close();
  });
});
