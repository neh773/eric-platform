import { describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { parseView, type ComponentNode, type ViewDocument } from "@oip/hmi-runtime";
import { Inspector } from "./Inspector";
import { convertCustom, customTypeOf } from "./PropertyCategories";

/** P4-09 (HMP-115) variants and P4-10 (HMP-127) property categories in the Studio Inspector. */
function view(layout: "coordinate" | "flex" | "tab" = "coordinate", extra: Record<string, unknown> = {}): ViewDocument {
  const placement = layout === "coordinate" ? { frame: { x: 10, y: 20, width: 160, height: 40 } } : layout === "flex" ? { flex: { grow: 0, shrink: 1, basis: "auto", display: true } } : {};
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Main", path: "main" },
    spec: {
      params: {},
      custom: {},
      width: 400,
      height: 300,
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout,
        props: {},
        children: [{ id: "title", kind: "component", name: "Title", type: "label", props: { text: "Hi" }, ...placement, ...extra }],
      },
    },
  });
}

/** Renders the Inspector on [start] and follows every edit, like the Studio does. */
function studio(start: ViewDocument) {
  let current = start;
  const selected = () => current.spec.root.children[0] as ComponentNode;
  const result = render(<Inspector view={current} selected={selected()} onView={(next) => update(next)} />);
  function update(next: ViewDocument) {
    current = next;
    result.rerender(<Inspector view={current} selected={selected()} onView={(view) => update(view)} />);
  }
  const group = (name: string) => screen.getByRole("group", { name });
  return { view: () => current, node: selected, group };
}

describe("Inspector categories", () => {
  it("shows props, position, custom and meta in that order", () => {
    studio(view());
    const legends = [...document.querySelectorAll(".oip-inspector > fieldset > legend, .oip-inspector > .oip-category > fieldset > legend")].map((legend) => legend.textContent);
    expect(legends.slice(0, 4)).toEqual(["Props", "Position", "Custom", "Meta"]);
  });

  it("edits a variant (HMP-115)", () => {
    const s = studio(view());
    const picker = within(s.group("Props")).getByRole("combobox", { name: "Variant" });
    expect(within(picker).getAllByRole("option").map((option) => option.textContent)).toEqual(["Default", "Heading", "Caption"]);
    fireEvent.change(picker, { target: { value: "heading" } });
    expect(s.node().variant).toBe("heading");
    expect(parseView(JSON.parse(JSON.stringify(s.view()))).spec.root.children[0]).toMatchObject({ variant: "heading" });
    fireEvent.change(within(s.group("Props")).getByRole("combobox", { name: "Variant" }), { target: { value: "" } });
    expect(s.node().variant).toBeUndefined();
  });

  it("edits position by the parent's layout", () => {
    const s = studio(view());
    const position = s.group("Position");
    fireEvent.change(within(position).getByRole("spinbutton", { name: "X" }), { target: { value: "42" } });
    expect(s.node().frame).toMatchObject({ x: 42, y: 20, width: 160, height: 40 });
    cleanup();

    const flex = studio(view("flex"));
    fireEvent.change(within(flex.group("Position")).getByRole("spinbutton", { name: "Grow" }), { target: { value: "2" } });
    expect(flex.node().flex).toMatchObject({ grow: 2, shrink: 1 });
  });

  it("edits a tab child's label as its position", () => {
    const s = studio(view("tab"));
    fireEvent.change(within(s.group("Position")).getByRole("textbox", { name: "Tab label" }), { target: { value: "Pumps" } });
    expect(s.node().tabLabel).toBe("Pumps");
  });

  it("adds custom properties of every data type and edits each by its type (HMP-127)", () => {
    const s = studio(view());
    const custom = () => s.group("Custom");
    const add = (name: string, type: string) => {
      fireEvent.change(within(custom()).getByRole("textbox", { name: "New custom property name" }), { target: { value: name } });
      fireEvent.change(within(custom()).getByRole("combobox", { name: "New custom property type" }), { target: { value: type } });
      fireEvent.click(within(custom()).getByRole("button", { name: "Add custom property" }));
    };
    add("area", "string");
    add("limit", "number");
    add("armed", "boolean");
    add("ranges", "object");
    add("pens", "array");
    add("spare", "null");
    expect(s.node().custom).toEqual({ area: "", limit: 0, armed: false, ranges: {}, pens: [], spare: null });

    fireEvent.change(within(custom()).getByRole("textbox", { name: "Custom area" }), { target: { value: "North" } });
    fireEvent.change(within(custom()).getByRole("spinbutton", { name: "Custom limit" }), { target: { value: "80.5" } });
    fireEvent.click(within(custom()).getByRole("checkbox", { name: "Custom armed" }));
    fireEvent.change(within(custom()).getByRole("textbox", { name: "Custom ranges" }), { target: { value: '{"high": 90, "low": {"value": 5}}' } });
    fireEvent.change(within(custom()).getByRole("textbox", { name: "Custom pens" }), { target: { value: '["a", 2]' } });
    // A value that is not JSON of the row's type is refused and not saved.
    fireEvent.change(within(custom()).getByRole("textbox", { name: "Custom pens" }), { target: { value: '{"a": 1}' } });
    expect(within(custom()).getByRole("alert")).toHaveTextContent("Enter a JSON array.");
    expect(s.node().custom).toEqual({ area: "North", limit: 80.5, armed: true, ranges: { high: 90, low: { value: 5 } }, pens: ["a", 2], spare: null });

    // Changing a type converts the value.
    fireEvent.change(within(custom()).getByRole("combobox", { name: "Type of limit" }), { target: { value: "string" } });
    expect(s.node().custom!.limit).toBe("80.5");
    fireEvent.click(within(custom()).getByRole("button", { name: "Remove custom spare" }));
    expect(Object.keys(s.node().custom!)).toEqual(["area", "limit", "armed", "ranges", "pens"]);

    // A bad name is refused.
    fireEvent.change(within(custom()).getByRole("textbox", { name: "New custom property name" }), { target: { value: "9 lives" } });
    fireEvent.click(within(custom()).getByRole("button", { name: "Add custom property" }));
    expect(within(custom()).getAllByRole("alert").at(-1)).toHaveTextContent("Start with a letter or _");
  });

  it("edits meta: name, visible and tooltip", () => {
    const s = studio(view());
    const meta = s.group("Meta");
    fireEvent.change(within(meta).getByRole("textbox", { name: "Name" }), { target: { value: "Tank title" } });
    fireEvent.click(within(s.group("Meta")).getByRole("checkbox", { name: "Visible" }));
    fireEvent.change(within(s.group("Meta")).getByRole("textbox", { name: "Tooltip" }), { target: { value: "North tank level" } });
    expect(s.node()).toMatchObject({ name: "Tank title", meta: { visible: false, tooltip: "North tank level" } });
    fireEvent.click(within(s.group("Meta")).getByRole("checkbox", { name: "Visible" }));
    expect(s.node().meta).toEqual({ tooltip: "North tank level" });
  });

  it("round-trips every category through the saved document", () => {
    const s = studio(view());
    fireEvent.change(within(s.group("Props")).getByRole("combobox", { name: "Variant" }), { target: { value: "caption" } });
    fireEvent.change(within(s.group("Position")).getByRole("spinbutton", { name: "Width" }), { target: { value: "220" } });
    fireEvent.change(within(s.group("Custom")).getByRole("textbox", { name: "New custom property name" }), { target: { value: "cfg" } });
    fireEvent.change(within(s.group("Custom")).getByRole("combobox", { name: "New custom property type" }), { target: { value: "object" } });
    fireEvent.click(within(s.group("Custom")).getByRole("button", { name: "Add custom property" }));
    fireEvent.change(within(s.group("Custom")).getByRole("textbox", { name: "Custom cfg" }), { target: { value: '{"on": true, "list": [1, null]}' } });
    fireEvent.change(within(s.group("Meta")).getByRole("textbox", { name: "Tooltip" }), { target: { value: "Hello" } });
    const saved = JSON.parse(JSON.stringify(s.view())) as unknown;
    const back = parseView(saved).spec.root.children[0] as ComponentNode;
    expect(back).toMatchObject({
      props: { text: "Hi" },
      variant: "caption",
      frame: { x: 10, y: 20, width: 220, height: 40 },
      custom: { cfg: { on: true, list: [1, null] } },
      meta: { tooltip: "Hello" },
    });
    expect(parseView(saved)).toEqual(s.view());
  });

  it("knows each data type and converts between them", () => {
    expect([null, "a", 1, true, { a: 1 }, [1]].map(customTypeOf)).toEqual(["null", "string", "number", "boolean", "object", "array"]);
    expect(convertCustom(12, "string")).toBe("12");
    expect(convertCustom("12.5", "number")).toBe(12.5);
    expect(convertCustom("x", "number")).toBe(0);
    expect(convertCustom("true", "boolean")).toBe(true);
    expect(convertCustom({ a: 1 }, "string")).toBe('{"a":1}');
    expect(convertCustom("x", "object")).toEqual({});
    expect(convertCustom([1], "array")).toEqual([1]);
    expect(convertCustom(5, "null")).toBeNull();
  });
});
