import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { componentModules, containerModules } from "@oip/components";
import { COMPONENT_GROUP, PALETTE_DRAG_TYPE, PALETTE_GROUPS, decodePaletteDrag, encodePaletteDrag, groupPalette } from "./paletteData";
import { Palette } from "./Palette";
import { placeAt } from "./project";
import { blankView } from "./persistence";
import { appendComponent } from "./project";

/** P4-05 (HMP-002): the Studio palette grouped into chart, container, display, input and navigation, with search. */

describe("palette grouping", () => {
  it("gives every registered component exactly one of the five groups", () => {
    const types = componentModules.map((module) => module.type).sort();
    expect(Object.keys(COMPONENT_GROUP).sort()).toEqual(types);
    for (const group of Object.values(COMPONENT_GROUP)) expect(PALETTE_GROUPS).toContain(group);
  });

  it("groups in a fixed order, each group sorted by title, containers under container", () => {
    const groups = groupPalette(componentModules, containerModules);
    expect(groups.map((entry) => entry.group)).toEqual(["chart", "container", "display", "input", "navigation"]);
    const byGroup = Object.fromEntries(groups.map((entry) => [entry.group, entry.items.map((item) => item.title)]));
    expect(byGroup.chart).toEqual(expect.arrayContaining(["Time series chart", "Trend", "Sparkline", "Simple gauge"]));
    expect(byGroup.container).toEqual(expect.arrayContaining(["Breakpoint container", "Split container", "Flex repeater", "Embedded view"]));
    expect(byGroup.display).toEqual(expect.arrayContaining(["Label", "Numeric", "Indicator", "Pump"]));
    expect(byGroup.input).toEqual(expect.arrayContaining(["Button", "Numeric entry field", "Slider", "Text input"]));
    expect(byGroup.navigation).toEqual(["Horizontal menu", "Link", "Menu tree", "Navigation drawer", "Tree"]);
    for (const entry of groups) expect(entry.items.map((item) => item.title)).toEqual([...entry.items.map((item) => item.title)].sort((a, b) => a.localeCompare(b)));
    const total = groups.reduce((sum, entry) => sum + entry.items.length, 0);
    expect(total).toBe(componentModules.length + containerModules.length);
  });

  it("searches titles, types and group names, every word must match", () => {
    const titles = (query: string) => groupPalette(componentModules, containerModules, query).flatMap((entry) => entry.items.map((item) => item.title));
    expect(titles("gauge")).toEqual(["Linear gauge", "Radial gauge", "Simple gauge"]);
    expect(titles("NUMERIC entry")).toEqual(["Numeric entry field"]);
    expect(titles("menu-tree")).toEqual(["Menu tree"]);
    expect(groupPalette(componentModules, containerModules, "navigation").map((entry) => entry.group)).toEqual(["navigation"]);
    expect(titles("no-such-thing")).toEqual([]);
  });

  it("encodes a drag and refuses a malformed one", () => {
    const [chart] = groupPalette(componentModules, containerModules, "trend");
    expect(decodePaletteDrag(encodePaletteDrag(chart!.items[0]!))).toEqual({ kind: "component", type: "trend" });
    const [split] = groupPalette(componentModules, containerModules, "split");
    expect(decodePaletteDrag(encodePaletteDrag(split!.items[0]!))).toEqual({ kind: "container", layout: "split" });
    expect(decodePaletteDrag("{")).toBeNull();
    expect(decodePaletteDrag('{"kind":"component"}')).toBeNull();
  });

  it("places a dropped component at the drop point on a coordinate view, kept inside the view", () => {
    const added = appendComponent(blankView("v", "V"), "label", "Label", {});
    const placed = placeAt(added.view, added.id, { x: 200.4, y: 90.6 });
    expect(placed.spec.root.children[0]!.frame).toMatchObject({ x: 200, y: 91 });
    const clamped = placeAt(added.view, added.id, { x: 5000, y: -20 });
    expect(clamped.spec.root.children[0]!.frame).toMatchObject({ x: 680 - 160, y: 0 });
  });
});

describe("palette panel", () => {
  it("shows a section per group, filters on search, and adds on click", () => {
    const onAdd = vi.fn();
    render(<Palette components={componentModules} containers={containerModules} onAdd={onAdd} />);
    for (const name of ["Chart components", "Container components", "Display components", "Input components", "Navigation components"]) {
      expect(screen.getByRole("region", { name })).toBeInTheDocument();
    }
    fireEvent.click(within(screen.getByRole("region", { name: "Input components" })).getByRole("button", { name: "Add numeric entry field" }));
    expect(onAdd).toHaveBeenCalledWith(expect.objectContaining({ kind: "component", type: "numeric-entry" }));

    fireEvent.change(screen.getByRole("searchbox", { name: "Search components" }), { target: { value: "menu" } });
    expect(screen.queryByRole("region", { name: "Chart components" })).toBeNull();
    expect(within(screen.getByRole("region", { name: "Navigation components" })).getAllByRole("button").map((button) => button.textContent)).toEqual([
      "Add horizontal menu",
      "Add menu tree",
    ]);
    fireEvent.change(screen.getByRole("searchbox", { name: "Search components" }), { target: { value: "zzz" } });
    expect(screen.getByRole("status")).toHaveTextContent('No component matches "zzz"');
  });

  it("puts the entry on the drag", () => {
    render(<Palette components={componentModules} containers={containerModules} onAdd={() => {}} />);
    const data = new Map<string, string>();
    const dataTransfer = { setData: (type: string, value: string) => data.set(type, value), effectAllowed: "" };
    fireEvent.dragStart(screen.getByRole("button", { name: "Add tree" }), { dataTransfer });
    expect(JSON.parse(data.get(PALETTE_DRAG_TYPE)!)).toEqual({ kind: "component", type: "tree" });
  });
});
