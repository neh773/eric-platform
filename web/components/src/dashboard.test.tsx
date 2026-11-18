import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { createLinkedTransports, createSession, parseView, ViewRenderer, type ViewDocument } from "@oip/hmi-runtime";
import { addItem, moveItem, readAvailable, readLayout, resizeItem, resolve } from "./dashboard";
import { componentRegistry } from "./registry";

/** HMP-019: dashboard with user-arranged widgets, through a real session. doc source: FEATURES HMP-019 */

const registry = componentRegistry();

function card(path: string, title: string): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: title, path },
    spec: {
      width: 100,
      height: 40,
      params: { text: { value: title } },
      root: {
        id: `${path}-root`,
        kind: "container",
        name: title,
        layout: "flex",
        props: {},
        children: [{ id: `${path}-l`, kind: "component", name: "L", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "param", name: "text" } } } }],
      },
    },
  });
}

const alpha = card("alpha", "Alpha view");
const beta = card("beta", "Beta view");

const AVAILABLE = JSON.stringify([
  { id: "a", title: "Alpha", view: "alpha", w: 4, h: 2 },
  { id: "b", title: "Beta", view: "beta", params: { text: "Beta from params" }, w: 6, h: 2 },
  { id: "bad", title: "Bad" },
]);
const LAYOUT = JSON.stringify([
  { key: "one", id: "a", x: 0, y: 0 },
  { key: "two", id: "b", x: 4, y: 0 },
  { key: "gone", id: "unknown", x: 0, y: 0 },
]);

function host(props: Record<string, unknown>): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Host", path: "host" },
    spec: {
      width: 900,
      height: 600,
      params: {},
      root: {
        id: "host-root",
        kind: "container",
        name: "Host",
        layout: "flex",
        props: { direction: "column" },
        children: [{ id: "dash", kind: "component", name: "Dash", type: "dashboard", props }],
      },
    },
  });
}

function mount(props: Record<string, unknown>) {
  const view = host({ available: AVAILABLE, layout: LAYOUT, ...props });
  const [transport] = createLinkedTransports();
  return render(
    <ViewRenderer view={view} views={[alpha, beta, view]} registry={registry} transport={transport} session={createSession({ id: "s", theme: "daylight", locale: "en-US" })} viewportWidth={1200} />,
  );
}

function placed(): Record<string, string> {
  return Object.fromEntries([...document.querySelectorAll("[data-widget]")].map((el) => [el.getAttribute("data-widget")!, `${el.getAttribute("data-x")},${el.getAttribute("data-y")},${el.getAttribute("data-w")},${el.getAttribute("data-h")}`]));
}

beforeEach(() => window.localStorage.clear());

describe("layout maths", () => {
  const available = readAvailable(AVAILABLE);

  it("reads only usable widgets and layout entries", () => {
    expect(available.map((widget) => widget.id)).toEqual(["a", "b"]);
    expect(readLayout(LAYOUT, available, 12).map((item) => item.key)).toEqual(["one", "two"]);
    expect(readLayout("not json", available, 12)).toEqual([]);
  });

  it("keeps widgets inside the columns", () => {
    const items = readLayout('[{"key":"k","id":"b","x":99,"y":-3,"w":99}]', available, 12);
    expect(items[0]).toMatchObject({ x: 0, y: 0, w: 12 });
  });

  it("pushes the widgets under a moved widget down, never overlapping", () => {
    const items = readLayout(LAYOUT, available, 12);
    const moved = moveItem(items, "one", 4, 0, 12);
    expect(moved.find((item) => item.key === "one")).toMatchObject({ x: 4, y: 0 });
    expect(moved.find((item) => item.key === "two")).toMatchObject({ x: 4, y: 2 });
    expect(resolve(moved)).toEqual(moved);
  });

  it("limits a move to the grid and a resize to the columns", () => {
    const items = readLayout(LAYOUT, available, 12);
    expect(moveItem(items, "two", 50, 0, 12).find((item) => item.key === "two")!.x).toBe(6);
    expect(resizeItem(items, "two", 50, 1, 12).find((item) => item.key === "two")!.w).toBe(8);
  });

  it("adds a widget below the others at its starting size", () => {
    const items = addItem(readLayout(LAYOUT, available, 12), available[0]!, 12);
    expect(items[2]).toMatchObject({ id: "a", x: 0, y: 2, w: 4, h: 2 });
  });
});

describe("Dashboard", () => {
  it("shows the designer's layout with each widget's view and params", () => {
    mount({});
    expect(placed()).toEqual({ one: "0,0,4,2", two: "4,0,6,2" });
    expect(screen.getByText("Alpha view")).toBeInTheDocument();
    expect(screen.getByText("Beta from params")).toBeInTheDocument();
    expect(screen.queryByLabelText("Move Alpha")).toBeNull();
  });

  it("lets a user add, move, resize and remove widgets, and keeps the result", () => {
    const first = mount({ userKey: "ann" });
    fireEvent.click(screen.getByText("Edit layout"));
    fireEvent.change(screen.getByLabelText("Widget to add"), { target: { value: "b" } });
    fireEvent.click(screen.getByText("Add widget"));
    expect(placed().w2).toBe("0,2,6,2");

    fireEvent.keyDown(screen.getByLabelText("Move Alpha"), { key: "ArrowDown" });
    expect(placed().one).toBe("0,1,4,2");
    fireEvent.keyDown(screen.getByLabelText("Resize Alpha"), { key: "ArrowRight" });
    expect(placed().one).toBe("0,1,5,2");
    fireEvent.keyDown(screen.getAllByLabelText("Move Beta")[0]!, { key: "ArrowLeft", shiftKey: true });
    expect(placed().two).toBe("4,3,5,2"); // pushed down by the wider Alpha, then one column narrower
    fireEvent.click(within(document.querySelector("[data-widget='w2']") as HTMLElement).getByLabelText("Remove Beta"));
    expect(placed().w2).toBeUndefined();
    const expected = placed();
    first.unmount();

    // The same user gets the arrangement back, another user the designer's default.
    mount({ userKey: "ann" });
    expect(placed()).toEqual(expected);
  });

  it("keeps layouts separate per user", () => {
    const ann = mount({ userKey: "ann" });
    fireEvent.click(screen.getByText("Edit layout"));
    fireEvent.keyDown(screen.getByLabelText("Move Alpha"), { key: "ArrowDown" });
    ann.unmount();
    mount({ userKey: "bob" });
    expect(placed().one).toBe("0,0,4,2");
  });

  it("resets to the designer's default", () => {
    mount({ userKey: "ann" });
    fireEvent.click(screen.getByText("Edit layout"));
    fireEvent.keyDown(screen.getByLabelText("Move Alpha"), { key: "ArrowDown" });
    expect(window.localStorage.getItem("oip.dashboard.dashboard.ann")).not.toBeNull();
    fireEvent.click(screen.getByText("Reset layout"));
    expect(placed().one).toBe("0,0,4,2");
    expect(window.localStorage.getItem("oip.dashboard.dashboard.ann")).toBeNull();
  });

  it("drags a widget by its handle in whole cells", () => {
    mount({});
    fireEvent.click(screen.getByText("Edit layout"));
    const handle = screen.getByLabelText("Move Alpha");
    fireEvent.pointerDown(handle, { clientX: 0, clientY: 0, pointerId: 1 });
    const grid = document.querySelector(".oip-dashboard-grid")!;
    // 960 px wide fallback, 12 columns, 8 px gap: one column is 80 + 8 px; one row is 68 px.
    fireEvent.pointerMove(grid, { clientX: 170, clientY: 70, pointerId: 1 });
    fireEvent.pointerUp(grid, { clientX: 170, clientY: 70, pointerId: 1 });
    expect(placed().one).toBe("2,1,4,2");
  });

  it("is frozen when locked: no controls, and a saved layout is ignored", () => {
    window.localStorage.setItem("oip.dashboard.dashboard.ann", JSON.stringify([{ key: "one", id: "a", x: 5, y: 5, w: 2, h: 2 }]));
    mount({ userKey: "ann", locked: true });
    expect(screen.queryByText("Edit layout")).toBeNull();
    expect(placed()).toEqual({ one: "0,0,4,2", two: "4,0,6,2" });
  });

  it("ignores unreadable saved layouts and works without storage", () => {
    window.localStorage.setItem("oip.dashboard.dashboard.shared", "{broken");
    mount({});
    expect(placed()).toEqual({ one: "0,0,4,2", two: "4,0,6,2" });
  });

  it("is a registered dashboard component with a closed schema", () => {
    const module = registry.get("dashboard")!;
    expect(module.schema.additionalProperties).toBe(false);
    expect(module.schema.properties?.locked?.default).toBe(false);
  });
});
