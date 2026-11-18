import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { createLinkedTransports, createSession, parseView, ViewRenderer, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";
import { canvasInstances, gridFrame } from "./templateCanvas";

/** HMV-133: a template canvas repeats one reusable view for each parameter set, positioned absolutely or with flex. */

const registry = componentRegistry();

function view(path: string, width: number, height: number, params: Record<string, unknown>, children: unknown[], layout = "flex"): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: path, path },
      spec: { width, height, params, root: { id: `${path}-root`, kind: "container", name: path, layout, props: { direction: "column" }, children } },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

/** The template: a label that shows its `name` and `index` params. */
const card = view("card", 120, 40, { name: { value: "default" }, index: { value: -1 } }, [
  {
    id: "card-label",
    kind: "component",
    name: "Label",
    type: "label",
    props: { text: "" },
    bindings: { text: { kind: "expression", expression: "{view.params.name} + ' #' + {view.params.index}" } },
  },
]);
const small = view("small", 50, 20, { name: { value: "" } }, [
  { id: "small-label", kind: "component", name: "L", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "param", name: "name" } } } },
]);

function host(props: Record<string, unknown>): ViewDocument {
  return view("host", 600, 400, {}, [{ id: "canvas", kind: "component", name: "Canvas", type: "template-canvas", props }]);
}

function show(doc: ViewDocument) {
  const [transport] = createLinkedTransports();
  return (
    <ViewRenderer view={doc} views={[card, small, doc]} registry={registry} transport={transport} session={createSession({ id: "s", theme: "daylight", locale: "en-US" })} viewportWidth={800} />
  );
}

const labels = () => [...document.querySelectorAll("[data-component='template-canvas'] [data-component='label']")].map((el) => el.textContent ?? "");

describe("template canvas model", () => {
  it("builds params from each entry and keeps frame, view and position out of them", () => {
    const list = canvasInstances([
      { name: "A", instanceFrame: { x: 10, y: 20, width: 90, height: 30, junk: 1 }, instanceView: "small" },
      { name: "B", index: 9, instanceFrame: { x: "no" } },
      "plain",
    ]);
    expect(list.map((entry) => entry.params)).toEqual([{ index: 0, name: "A" }, { index: 9, name: "B" }, { index: 2, value: "plain" }]);
    expect(list[0]).toMatchObject({ view: "small", frame: { x: 10, y: 20, width: 90, height: 30 } });
    expect(list[1]!.frame).toBeUndefined();
    expect(canvasInstances("not json")).toEqual([]);
  });

  it("places entries without a frame in a grid", () => {
    expect(gridFrame(0, 3, 100, 50, 10)).toEqual({ x: 0, y: 0, width: 100, height: 50 });
    expect(gridFrame(4, 3, 100, 50, 10)).toEqual({ x: 110, y: 60, width: 100, height: 50 });
    expect(gridFrame(2, 0, 100, 50, 0)).toEqual({ x: 0, y: 100, width: 100, height: 50 });
  });

  it("is registered as a container with defaults", () => {
    const module = registry.get("template-canvas")!;
    expect(module.category).toBe("container");
    expect(defaultsFromSchema(module.schema)).toMatchObject({ layout: "absolute", columns: 3, instances: "[]", path: "" });
  });
});

describe("template canvas", () => {
  it("repeats the template once per parameter set, following the list", () => {
    const { rerender } = render(show(host({ path: "card", instances: [{ name: "Pump 1" }, { name: "Pump 2" }, { name: "Pump 3" }] })));
    const list = screen.getByRole("list", { name: "Template canvas" });
    expect(list).toHaveAttribute("data-instance-count", "3");
    expect(labels()).toEqual(["Pump 1 #0", "Pump 2 #1", "Pump 3 #2"]);
    expect(document.querySelector("[data-node-id='canvas-0__card-label']")).not.toBeNull();
    rerender(show(host({ path: "card", instances: '[{"name":"Only"}]' })));
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    rerender(show(host({ path: "card", instances: [] })));
    expect(within(list).queryAllByRole("listitem")).toHaveLength(0);
  });

  it("positions absolutely: a frame places an entry, the others fill a grid", () => {
    render(
      show(host({ path: "card", columns: 2, cellWidth: 100, cellHeight: 40, gap: 10, instances: [{ name: "a" }, { name: "b" }, { name: "c" }, { name: "free", instanceFrame: { x: 300, y: 5 } }] })),
    );
    const items = within(screen.getByRole("list")).getAllByRole("listitem");
    const at = (item: HTMLElement) => [item.style.left, item.style.top, item.style.width, item.style.height];
    expect(items.map(at)).toEqual([
      ["0px", "0px", "100px", "40px"],
      ["110px", "0px", "100px", "40px"],
      ["0px", "50px", "100px", "40px"],
      ["300px", "5px", "100px", "40px"],
    ]);
    expect(items[0]!.style.position).toBe("absolute");
  });

  it("sizes an entry to its frame, and an entry can name another template", () => {
    render(show(host({ path: "card", instances: [{ name: "x", instanceView: "small", instanceFrame: { x: 1, y: 2, width: 70, height: 30 } }] })));
    const item = within(screen.getByRole("list")).getByRole("listitem");
    expect(item.style.width).toBe("70px");
    expect(document.querySelector("[data-embedded-view='small']")).not.toBeNull();
    expect(labels()).toEqual(["x"]);
  });

  it("uses flex positioning when asked, with the flex repeater's props", () => {
    render(show(host({ path: "card", layout: "flex", direction: "column", wrap: "wrap", elementPosition: { grow: 1, shrink: 0, basis: "50px" }, instances: [{ name: "a" }, { name: "b" }] })));
    const list = screen.getByRole("list");
    expect(list.style.flexDirection).toBe("column");
    expect(list.style.display).toBe("flex");
    expect(within(list).getAllByRole("listitem")[0]!.style.flexBasis).toBe("50px");
    expect(labels()).toEqual(["a #0", "b #1"]);
  });

  it("says why an unreadable list is empty and shows an unknown template", () => {
    render(show(host({ path: "missing", instances: "{bad" })));
    expect(screen.getByRole("alert")).toHaveTextContent("Instances is not valid JSON");
    expect(within(screen.getByRole("list")).queryAllByRole("listitem")).toHaveLength(0);
  });

  it("refuses a canvas that repeats its own view", () => {
    render(show(host({ path: "host", instances: [{}, {}] })));
    for (const alert of screen.getAllByRole("alert")) expect(alert).toHaveTextContent("View embeds itself: host");
    expect(document.querySelectorAll("[data-component='template-canvas']")).toHaveLength(1);
  });
});
