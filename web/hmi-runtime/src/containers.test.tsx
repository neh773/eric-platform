import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { breakpointChild, breakpointSize, clampSplit, resolveSplitPosition } from "./layout";
import { parseView, ViewModelError } from "./model";
import { createSession } from "./session";
import { createLinkedTransports } from "./tags";
import type { ComponentRegistry, HmiComponentProps, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** C-15 breakpoint and split containers.
 */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "label") return undefined;
    return { type, title: type, schema: { type: "object" }, Component: ({ props }: HmiComponentProps) => <span>{String(props.text ?? "")}</span> };
  },
  list: () => [],
};

const label = (id: string, text: string, extra: Record<string, unknown> = {}) => ({ id, kind: "component", name: id, type: "label", props: { text }, ...extra });

function view(root: Record<string, unknown>): ViewDocument {
  return parseView({ apiVersion: "oip.hmi/v1", kind: "View", metadata: { name: "C", path: "c" }, spec: { width: 400, height: 300, params: {}, root } });
}

function renderAt(doc: ViewDocument, viewportWidth: number) {
  const [transport] = createLinkedTransports();
  const session = createSession({ id: "s", theme: "daylight", locale: "en-US" });
  return render(<ViewRenderer view={doc} registry={registry} transport={transport} session={session} viewportWidth={viewportWidth} />);
}

describe("breakpoint container", () => {
  it("switches exactly at the configured width", () => {
    expect(breakpointSize(639, 640)).toBe("small");
    expect(breakpointSize(639.5, 640)).toBe("small");
    expect(breakpointSize(640, 640)).toBe("large");
    expect(breakpointSize(641, 640)).toBe("large");
    expect(breakpointSize(0, 0)).toBe("large");
  });

  it("picks the child by size, else by order", () => {
    const tagged = [{ id: "l", size: "large" as const }, { id: "s", size: "small" as const }];
    expect(breakpointChild(tagged, "small")?.id).toBe("s");
    expect(breakpointChild(tagged, "large")?.id).toBe("l");
    const plain: { id: string; size?: "small" | "large" }[] = [{ id: "a" }, { id: "b" }];
    expect(breakpointChild(plain, "small")?.id).toBe("a");
    expect(breakpointChild(plain, "large")?.id).toBe("b");
    const mixed: { id: string; size?: "small" | "large" }[] = [{ id: "x", size: "large" }, { id: "y" }];
    expect(breakpointChild(mixed, "small")?.id).toBe("y");
    expect(breakpointChild(plain.slice(0, 0), "large")).toBeUndefined();
  });

  const doc = () =>
    view({
      id: "bp",
      kind: "container",
      name: "Responsive",
      layout: "breakpoint",
      props: { breakpoint: 640 },
      children: [label("narrow", "Narrow layout", { size: "small" }), label("wide", "Wide layout", { size: "large" })],
    });

  it("renders only the child for the measured width, flipping at the breakpoint", () => {
    const first = renderAt(doc(), 639);
    expect(screen.getByText("Narrow layout")).toBeInTheDocument();
    expect(screen.queryByText("Wide layout")).toBeNull();
    expect(document.querySelector("[data-node-id='bp']")).toHaveAttribute("data-current-breakpoint", "small");
    first.unmount();
    renderAt(doc(), 640);
    expect(screen.getByText("Wide layout")).toBeInTheDocument();
    expect(screen.queryByText("Narrow layout")).toBeNull();
    expect(document.querySelector("[data-node-id='bp']")).toHaveAttribute("data-current-breakpoint", "large");
  });

  it("parses defaults and rejects bad children", () => {
    const parsed = view({ id: "bp", kind: "container", name: "B", layout: "breakpoint", props: {}, children: [] });
    expect(parsed.spec.root.props).toMatchObject({ breakpoint: 640, determinant: "width" });
    expect(() => view({ id: "bp", kind: "container", name: "B", layout: "breakpoint", props: {}, children: [label("a", "a"), label("b", "b"), label("c", "c")] })).toThrow(ViewModelError);
    expect(() => view({ id: "bp", kind: "container", name: "B", layout: "breakpoint", props: {}, children: [label("a", "a", { size: "small" }), label("b", "b", { size: "small" })] })).toThrow(/once/);
    expect(() => view({ id: "bp", kind: "container", name: "B", layout: "breakpoint", props: {}, children: [label("a", "a", { size: "medium" })] })).toThrow(ViewModelError);
    expect(() => view({ id: "bp", kind: "container", name: "B", layout: "breakpoint", props: { determinant: "depth" }, children: [] })).toThrow(ViewModelError);
  });
});

describe("split container", () => {
  const bar = { size: 6, minFirst: 100, minSecond: 150 };

  it("clamps the split ratio to the min sizes", () => {
    expect(clampSplit(50, 600, bar)).toBe(100);
    expect(clampSplit(300, 600, bar)).toBe(300);
    expect(clampSplit(500, 600, bar)).toBe(600 - 6 - 150);
    expect(clampSplit(444, 600, bar)).toBe(444);
    expect(clampSplit(445, 600, bar)).toBe(444);
    // Both minimums cannot fit: the first pane keeps its minimum.
    expect(clampSplit(200, 200, bar)).toBe(100);
    // Unknown size: only the first minimum applies.
    expect(clampSplit(20, 0, bar)).toBe(100);
    expect(clampSplit(9000, 0, bar)).toBe(9000);
  });

  it("resolves pixel and percent positions", () => {
    expect(resolveSplitPosition(120, 500)).toBe(120);
    expect(resolveSplitPosition("120px", 500)).toBe(120);
    expect(resolveSplitPosition("40%", 500)).toBe(200);
    expect(resolveSplitPosition("50 %", 500)).toBe(250);
  });

  const doc = (split: Record<string, unknown>) =>
    view({
      id: "sp",
      kind: "container",
      name: "Panes",
      layout: "split",
      props: { orientation: "horizontal", split },
      children: [label("left", "Left pane"), label("right", "Right pane")],
    });

  it("renders both panes and clamps keyboard moves of the divider", () => {
    renderAt(doc({ position: "50%", size: 6, minFirst: 100, minSecond: 150 }), 606);
    expect(screen.getByText("Left pane")).toBeInTheDocument();
    expect(screen.getByText("Right pane")).toBeInTheDocument();
    const divider = screen.getByRole("separator", { name: "Resize Panes" });
    expect(divider).toHaveAttribute("aria-valuenow", "300");
    expect(divider).toHaveAttribute("aria-valuemin", "100");
    expect(divider).toHaveAttribute("aria-valuemax", String(606 - 6 - 150));
    act(() => {
      fireEvent.keyDown(divider, { key: "End" });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "450");
    act(() => {
      fireEvent.keyDown(divider, { key: "ArrowRight" });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "450");
    act(() => {
      fireEvent.keyDown(divider, { key: "Home" });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "100");
    act(() => {
      fireEvent.keyDown(divider, { key: "ArrowRight", shiftKey: true });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "150");
    const first = document.querySelector("[data-node-id='sp'] [data-pane='first']") as HTMLElement;
    expect(first.style.width).toBe("150px");
  });

  it("clamps a pointer drag of the divider", () => {
    renderAt(doc({ position: 200, size: 6, minFirst: 100, minSecond: 150 }), 606);
    const divider = screen.getByRole("separator", { name: "Resize Panes" });
    act(() => {
      fireEvent.pointerDown(divider, { pointerId: 1, clientX: 200 });
      fireEvent.pointerMove(divider, { pointerId: 1, clientX: 900 });
      fireEvent.pointerUp(divider, { pointerId: 1, clientX: 900 });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "450");
    act(() => {
      fireEvent.pointerDown(divider, { pointerId: 2, clientX: 450 });
      fireEvent.pointerMove(divider, { pointerId: 2, clientX: 0 });
      fireEvent.pointerUp(divider, { pointerId: 2, clientX: 0 });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "100");
    // After pointerup, moves no longer drag.
    act(() => {
      fireEvent.pointerMove(divider, { pointerId: 2, clientX: 300 });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "100");
  });

  it("ends a drag on pointercancel or lost capture, and never listens on window", () => {
    const added: string[] = [];
    const original = window.addEventListener.bind(window);
    window.addEventListener = ((type: string, ...rest: unknown[]) => {
      added.push(type);
      return (original as (...args: unknown[]) => void)(type, ...rest);
    }) as typeof window.addEventListener;
    try {
      const { unmount } = renderAt(doc({ position: 200, size: 6, minFirst: 100, minSecond: 150 }), 606);
      const divider = screen.getByRole("separator", { name: "Resize Panes" });
      act(() => {
        fireEvent.pointerDown(divider, { pointerId: 3, clientX: 200 });
        fireEvent.pointerMove(divider, { pointerId: 3, clientX: 260 });
        fireEvent.pointerCancel(divider, { pointerId: 3 });
        fireEvent.pointerMove(divider, { pointerId: 3, clientX: 400 });
      });
      expect(divider).toHaveAttribute("aria-valuenow", "260");
      act(() => {
        fireEvent.pointerDown(divider, { pointerId: 4, clientX: 260 });
        fireEvent(divider, new PointerEvent("lostpointercapture", { bubbles: true, pointerId: 4 }));
        fireEvent.pointerMove(divider, { pointerId: 4, clientX: 400 });
      });
      expect(divider).toHaveAttribute("aria-valuenow", "260");
      act(() => {
        fireEvent.pointerDown(divider, { pointerId: 5, clientX: 260 });
      });
      unmount();
      expect(added.filter((type) => type.startsWith("pointer"))).toEqual([]);
    } finally {
      window.addEventListener = original;
    }
  });

  it("ignores End before the container is measured", () => {
    const [transport] = createLinkedTransports();
    const session = createSession({ id: "s", theme: "daylight", locale: "en-US" });
    render(<ViewRenderer view={doc({ position: "50%", minFirst: 80 })} registry={registry} transport={transport} session={session} />);
    const divider = screen.getByRole("separator", { name: "Resize Panes" });
    const before = divider.getAttribute("aria-valuenow");
    act(() => {
      fireEvent.keyDown(divider, { key: "End" });
    });
    expect(divider.getAttribute("aria-valuenow")).toBe(before);
    expect(Number.isFinite(Number(before))).toBe(true);
    const first = document.querySelector("[data-node-id='sp'] [data-pane='first']") as HTMLElement;
    expect(first.style.flex).not.toContain("Infinity");
    expect(clampSplit(Number.POSITIVE_INFINITY, 0, { size: 6, minFirst: 80, minSecond: 0 })).toBe(80);
  });

  it("does not move a divider that is not draggable", () => {
    renderAt(doc({ position: 200, draggable: false }), 606);
    const divider = screen.getByRole("separator", { name: "Resize Panes" });
    act(() => {
      fireEvent.keyDown(divider, { key: "End" });
    });
    expect(divider).toHaveAttribute("aria-valuenow", "200");
  });

  it("parses split defaults and rejects bad values", () => {
    const parsed = view({ id: "sp", kind: "container", name: "S", layout: "split", props: {}, children: [] });
    expect(parsed.spec.root.props).toMatchObject({
      orientation: "horizontal",
      split: { position: "50%", size: 6, visible: true, draggable: true, minFirst: 0, minSecond: 0 },
    });
    expect(() => view({ id: "sp", kind: "container", name: "S", layout: "split", props: { split: { position: "wide" } }, children: [] })).toThrow(ViewModelError);
    expect(() => view({ id: "sp", kind: "container", name: "S", layout: "split", props: { split: { minFirst: -1 } }, children: [] })).toThrow(ViewModelError);
    expect(() => view({ id: "sp", kind: "container", name: "S", layout: "split", props: { orientation: "diagonal" }, children: [] })).toThrow(ViewModelError);
  });
});

describe("nested container placement", () => {
  it("sizes nested containers by their frame while the root fills the view", () => {
    const doc = view({
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        { id: "inner", kind: "container", name: "Inner", layout: "coordinate", props: { mode: "fixed" }, frame: { x: 10, y: 20, width: 120, height: 80 }, children: [label("a", "A", { frame: { x: 0, y: 0, width: 40, height: 20 } })] },
        { id: "row", kind: "container", name: "Row", layout: "flex", props: { direction: "row" }, frame: { x: 150, y: 20, width: 200, height: 40 }, children: [label("b", "B", { flex: { grow: 0, shrink: 1, basis: "auto", display: true } })] },
        {
          id: "split",
          kind: "container",
          name: "Split",
          layout: "split",
          props: { orientation: "horizontal" },
          frame: { x: 10, y: 120, width: 320, height: 160 },
          children: [
            { id: "p1", kind: "container", name: "P1", layout: "flex", props: {}, children: [] },
            { id: "p2", kind: "container", name: "P2", layout: "flex", props: {}, children: [] },
          ],
        },
      ],
    });
    const { container } = renderAt(doc, 400);
    const node = (id: string) => container.querySelector(`[data-node-id='${id}']`) as HTMLElement;
    expect(node("root").style.width).toBe("400px");
    expect(node("root").style.height).toBe("300px");
    for (const [id, box] of Object.entries({ inner: [10, 20, 120, 80], row: [150, 20, 200, 40], split: [10, 120, 320, 160] })) {
      const style = node(id).style;
      expect(style.position).toBe("absolute");
      expect([style.left, style.top, style.width, style.height]).toEqual(box.map((value) => `${value}px`));
    }
    // A container's own layout settings still apply.
    expect(node("row").style.display).toBe("flex");
    expect(node("split").style.display).toBe("flex");
    // Children of a flex container keep filling it (no frame there).
    expect(node("p1").style.width).toBe("100%");
  });
});

describe("tab container", () => {
  // A tab container placed in a 320 x 160 frame: each panel's child must take the panel's size, not its content's.
  const tabs = view({
    id: "root",
    kind: "container",
    name: "Root",
    layout: "coordinate",
    props: { mode: "fixed" },
    children: [
      {
        id: "tabs",
        kind: "container",
        name: "Tabs",
        layout: "tab",
        frame: { x: 10, y: 20, width: 320, height: 160 },
        props: { tabs: [{ label: "One" }, { label: "Two" }] },
        children: [
          label("first", "first panel"),
          { id: "inner", kind: "container", name: "Inner", layout: "coordinate", props: { mode: "fixed" }, children: [label("deep", "deep", { frame: { x: 4, y: 4, width: 60, height: 20 } })] },
        ],
      },
    ],
  });

  for (const designMode of [false, true]) {
    it(`fills its frame and gives each panel's child the whole panel${designMode ? " in design mode" : ""}`, () => {
      const [transport] = createLinkedTransports();
      const session = createSession({ id: "s", theme: "daylight", locale: "en-US" });
      const { container } = render(<ViewRenderer view={tabs} registry={registry} transport={transport} session={session} designMode={designMode} />);
      const tab = container.querySelector<HTMLElement>("[data-node-id='tabs']")!;
      expect(tab).toHaveClass("oip-tab");
      expect(tab.style.position).toBe("absolute");
      expect(tab.style.width).toBe("320px");
      expect(tab.style.height).toBe("160px");
      for (const id of ["first", "inner"]) {
        const child = container.querySelector<HTMLElement>(`[data-node-id='${id}']`)!;
        expect(child.parentElement).toHaveAttribute("role", "tabpanel");
        expect(child.style.width, id).toBe("100%");
        expect(child.style.height, id).toBe("100%");
      }
      // A coordinate container in a panel no longer takes the whole view's 400 x 300 size.
      expect(container.querySelector<HTMLElement>("[data-node-id='inner']")!.style.height).not.toBe("300px");
    });
  }
});
