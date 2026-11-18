import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { allowedComponentTypes } from "@oip/components";
import { MemoryTagServer, findNode, parseView, type Binding, type ComponentNode, type ViewDocument } from "@oip/hmi-runtime";
import { Inspector, type TagLookup } from "./Inspector";
import { bindingSummary, expressionError, literalText, parseLiteral, placeholders } from "./bindingModel";
import { SCRIPT_NOT_CONNECTED, moveItem } from "./TransformEditor";
import { QUERY_NOT_CONNECTED } from "./BindingEditor";

/** D-3: per-property binding editor and transform chain in the Designer Inspector. */

function fixture(bindings: Record<string, unknown> = {}): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Bindings", path: "bindings" },
      spec: {
        width: 400,
        height: 200,
        params: { area: { value: "tank" } },
        custom: { unit: { value: "m" } },
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "coordinate",
          props: { mode: "fixed" },
          children: [
            {
              id: "title",
              kind: "component",
              name: "Title",
              type: "label",
              frame: { x: 0, y: 0, width: 200, height: 30 },
              props: { text: "North tank", align: "start" },
              bindings,
            },
            {
              id: "level",
              kind: "component",
              name: "Level",
              type: "numeric",
              frame: { x: 0, y: 40, width: 200, height: 30 },
              props: { value: 7, decimals: 1, prefix: "", suffix: "", announce: false },
            },
          ],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

function setup(initial: ViewDocument, tags?: TagLookup) {
  const state: { view: ViewDocument } = { view: initial };
  function Harness() {
    const [view, setView] = useState(initial);
    state.view = view;
    const selected = findNode(view.spec.root, "title") as ComponentNode;
    return <Inspector view={view} selected={selected} tags={tags} onView={setView} />;
  }
  render(<Harness />);
  return {
    binding: (): Binding | undefined => (findNode(state.view.spec.root, "title") as ComponentNode).bindings?.text,
    node: () => findNode(state.view.spec.root, "title") as ComponentNode,
  };
}

function openEditor(label = "Text") {
  fireEvent.click(screen.getByRole("button", { name: `Bind ${label}` }));
  return screen.getByRole("region", { name: "Binding for text" });
}

function pickKind(editor: HTMLElement, kind: string) {
  fireEvent.click(within(editor).getByRole("radio", { name: kind }));
}

describe("binding editor: tag", () => {
  it("creates a direct tag binding, shows the live value, switches to indirect with references, and removes it", () => {
    const server = new MemoryTagServer();
    server.set("plant/tank/note", "Holding");
    server.set("plant/tank/level", 12);
    const result = setup(fixture(), server);
    const editor = openEditor();
    pickKind(editor, "Tag");
    expect(result.binding()).toBeUndefined();
    fireEvent.change(within(editor).getByRole("textbox", { name: "Tag path" }), { target: { value: "plant/tank/note" } });
    expect(result.binding()).toEqual({ kind: "tag", mode: "direct", path: "plant/tank/note" });
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent('Value "Holding"');
    expect(screen.getByRole("button", { name: "Text: Tag binding" })).toHaveTextContent('plant/tank/note"Holding"');

    fireEvent.click(within(editor).getByRole("checkbox", { name: "Bidirectional" }));
    fireEvent.change(within(editor).getByRole("combobox", { name: "Tag mode" }), { target: { value: "indirect" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Tag path" }), { target: { value: "plant/{area}/level" } });
    const ref = within(editor).getByRole("group", { name: "Reference area" });
    fireEvent.change(within(ref).getByRole("combobox", { name: "area name" }), { target: { value: "area" } });
    expect(result.binding()).toEqual({
      kind: "tag",
      mode: "indirect",
      path: "plant/{area}/level",
      bidirectional: true,
      references: { area: { source: "param", name: "area" } },
    });
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent("Value 12");

    fireEvent.change(within(ref).getByRole("combobox", { name: "area source" }), { target: { value: "prop" } });
    fireEvent.change(within(ref).getByRole("combobox", { name: "area property" }), { target: { value: "text" } });
    expect(result.binding()).toMatchObject({ references: { area: { source: "prop", nodeId: "title", prop: "text" } } });

    fireEvent.click(within(editor).getByRole("checkbox", { name: "Enabled" }));
    expect(result.binding()?.enabled).toBe(false);
    fireEvent.click(within(editor).getByRole("checkbox", { name: "Enabled" }));
    expect(result.binding()?.enabled).toBeUndefined();

    fireEvent.click(within(editor).getByRole("button", { name: "Remove binding" }));
    expect(result.binding()).toBeUndefined();
    expect(result.node().bindings).toEqual({});
    expect(screen.getByRole("textbox", { name: "Text" })).toHaveValue("North tank");
  });

  it("accepts a tag dropped from the tag browser on the path field", () => {
    const result = setup(fixture({ text: { kind: "tag", mode: "direct", path: "old/path" } }));
    const editor = openEditor();
    const data = new Map<string, string>([["application/x-oip-tag", JSON.stringify({ path: "[default]Line/Speed" })]]);
    fireEvent.drop(within(editor).getByRole("textbox", { name: "Tag path" }), {
      dataTransfer: { getData: (type: string) => data.get(type) ?? "", types: [...data.keys()] },
    });
    expect(result.binding()).toEqual({ kind: "tag", mode: "direct", path: "[default]Line/Speed" });
  });
});

describe("binding editor: overlay opt-out (P4-08, HMP-111)", () => {
  it("turns the quality overlay off for one binding and back on", () => {
    const result = setup(fixture({ text: { kind: "tag", mode: "direct", path: "plant/tank/note" } }));
    fireEvent.click(screen.getByRole("button", { name: "Text: Tag binding" }));
    const editor = screen.getByRole("region", { name: "Binding for text" });
    const optOut = within(editor).getByRole("checkbox", { name: "Overlay opt-out" });
    expect(optOut).not.toBeChecked();
    fireEvent.click(optOut);
    expect(result.binding()).toEqual({ kind: "tag", mode: "direct", path: "plant/tank/note", overlayOptOut: true });
    fireEvent.click(within(editor).getByRole("checkbox", { name: "Overlay opt-out" }));
    expect(result.binding()).toEqual({ kind: "tag", mode: "direct", path: "plant/tank/note" });
  });
});

describe("binding editor: property", () => {
  it("binds to another component prop, a view param, and the session", () => {
    const result = setup(fixture());
    const editor = openEditor();
    pickKind(editor, "Property");
    fireEvent.change(within(editor).getByRole("combobox", { name: "Property source" }), { target: { value: "prop" } });
    fireEvent.change(within(editor).getByRole("combobox", { name: "Source component" }), { target: { value: "level" } });
    fireEvent.change(within(editor).getByRole("combobox", { name: "Source property" }), { target: { value: "value" } });
    expect(result.binding()).toEqual({ kind: "property", source: { scope: "prop", nodeId: "level", prop: "value" } });
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent("Value 7");

    fireEvent.change(within(editor).getByRole("combobox", { name: "Property source" }), { target: { value: "custom" } });
    fireEvent.change(within(editor).getByRole("combobox", { name: "Source name" }), { target: { value: "unit" } });
    expect(result.binding()).toEqual({ kind: "property", source: { scope: "custom", name: "unit" } });
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent('Value "m"');

    fireEvent.change(within(editor).getByRole("combobox", { name: "Property source" }), { target: { value: "session" } });
    fireEvent.change(within(editor).getByRole("combobox", { name: "Source path" }), { target: { value: "theme" } });
    expect(result.binding()).toEqual({ kind: "property", source: { scope: "session", path: "theme" } });
    expect(within(editor).getByText("Session and page values resolve in a live session.")).toBeVisible();

    fireEvent.click(within(editor).getByRole("button", { name: "Remove binding" }));
    expect(result.binding()).toBeUndefined();
  });
});

describe("binding editor: expression", () => {
  it("validates live, shows the evaluated value, and keeps transforms when the type changes", () => {
    const result = setup(fixture());
    const editor = openEditor();
    pickKind(editor, "Expression");
    const input = within(editor).getByRole("textbox", { name: "Expression" });
    expect(within(editor).getByRole("alert")).toHaveTextContent("Expression is empty");
    fireEvent.change(input, { target: { value: "2 * (" } });
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(within(editor).getByRole("alert")).toHaveTextContent("unexpected token in expression");
    fireEvent.change(input, { target: { value: "nope(1)" } });
    expect(within(editor).getByRole("alert")).toHaveTextContent("unknown function: nope");
    fireEvent.change(input, { target: { value: "{level.props.value} * 6" } });
    expect(within(editor).queryByRole("alert")).toBeNull();
    expect(result.binding()).toEqual({ kind: "expression", expression: "{level.props.value} * 6" });
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent("Value 42");
    expect(screen.getByRole("button", { name: "Text: Expression binding" })).toHaveTextContent("42");

    fireEvent.click(within(editor).getByRole("button", { name: "+ Format" }));
    pickKind(editor, "Tag");
    // An empty tag path would not load back, so the view keeps the last complete binding until a path is typed.
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent("Not applied yet: path must be a non-empty string");
    expect(result.binding()).toMatchObject({ kind: "expression", transforms: [{ kind: "format" }] });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Tag path" }), { target: { value: "plant/tank/level" } });
    expect(result.binding()).toMatchObject({ kind: "tag", path: "plant/tank/level", transforms: [{ kind: "format" }] });
    expect(within(editor).queryByTestId("binding-incomplete")).toBeNull();
  });
});

describe("binding editor: expression poll (P4-12, HMP-118)", () => {
  it("shows the poll interval, saves it on the binding, refuses one under the minimum, and clears it at 0", () => {
    const result = setup(fixture({ text: { kind: "expression", expression: "now()" } }));
    const editor = openEditor();
    const poll = within(editor).getByRole("spinbutton", { name: "Poll interval" });
    expect(poll).toHaveValue(0);
    fireEvent.change(poll, { target: { value: "1000" } });
    expect(result.binding()).toEqual({ kind: "expression", expression: "now()", pollMs: 1000 });
    // Under the minimum: shown as not applied, and the view keeps the last complete binding.
    fireEvent.change(poll, { target: { value: "50" } });
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent("pollMs must be 0 (no poll) or at least 100");
    expect(result.binding()).toEqual({ kind: "expression", expression: "now()", pollMs: 1000 });
    fireEvent.change(poll, { target: { value: "250" } });
    expect(result.binding()).toEqual({ kind: "expression", expression: "now()", pollMs: 250 });
    expect(within(editor).queryByTestId("binding-incomplete")).toBeNull();
    fireEvent.change(poll, { target: { value: "0" } });
    expect(result.binding()).toEqual({ kind: "expression", expression: "now()" });
  });

  it("loads a saved poll interval into the field", () => {
    setup(fixture({ text: { kind: "expression", expression: "now()", pollMs: 2000 } }));
    const editor = openEditor();
    expect(within(editor).getByRole("spinbutton", { name: "Poll interval" })).toHaveValue(2000);
  });
});

describe("binding editor: query", () => {
  it("edits a named query with parameters and says it is not connected here", () => {
    const result = setup(fixture());
    const editor = openEditor();
    pickKind(editor, "Query");
    fireEvent.change(within(editor).getByRole("textbox", { name: "Named query" }), { target: { value: "plant/history" } });
    fireEvent.change(within(editor).getByRole("combobox", { name: "Return format" }), { target: { value: "scalar" } });
    fireEvent.click(within(editor).getByRole("button", { name: "+ Parameter" }));
    fireEvent.change(within(editor).getByRole("textbox", { name: "Parameter 1 name" }), { target: { value: "limit" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Parameter 1 value" }), { target: { value: "25" } });
    fireEvent.click(within(editor).getByRole("button", { name: "+ Parameter" }));
    fireEvent.change(within(editor).getByRole("combobox", { name: "Parameter 2 kind" }), { target: { value: "expression" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Parameter 2 expression" }), { target: { value: "{view.params.area}" } });
    expect(result.binding()).toEqual({
      kind: "query",
      path: "plant/history",
      returnFormat: "scalar",
      parameters: { limit: { value: 25 }, param2: { expression: "{view.params.area}" } },
    });
    expect(within(editor).getByText(QUERY_NOT_CONNECTED)).toBeVisible();
    expect(screen.getByRole("button", { name: "Text: Query binding" })).toHaveTextContent("not connected");

    fireEvent.click(within(editor).getByRole("button", { name: "Remove parameter 1" }));
    expect(result.binding()).toMatchObject({ parameters: { param2: { expression: "{view.params.area}" } } });
    fireEvent.click(within(editor).getByRole("button", { name: "Remove binding" }));
    expect(result.binding()).toBeUndefined();
  });
});

describe("transform chain", () => {
  it("adds, previews before and after each step, reorders, and removes transforms", () => {
    const result = setup(fixture({ text: { kind: "expression", expression: "5" } }));
    const editor = openEditor();
    const transforms = within(editor).getByRole("region", { name: "Transforms" });
    fireEvent.click(within(transforms).getByRole("button", { name: "+ Map" }));
    const map = within(transforms).getByRole("listitem", { name: "Map transform 1" });
    fireEvent.change(within(map).getByRole("textbox", { name: "Row 1 input" }), { target: { value: "5" } });
    fireEvent.change(within(map).getByRole("textbox", { name: "Row 1 output" }), { target: { value: "12.345" } });
    expect(within(map).getByLabelText("Step preview")).toHaveTextContent("In 5 → Out 12.345");

    fireEvent.click(within(transforms).getByRole("button", { name: "+ Format" }));
    const format = within(transforms).getByRole("listitem", { name: "Format transform 2" });
    fireEvent.change(within(format).getByRole("textbox", { name: "Pattern" }), { target: { value: "0.0" } });
    expect(within(format).getByLabelText("Step preview")).toHaveTextContent('In 12.345 → Out "12.3"');
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent('Value 5 → result "12.3"');

    fireEvent.click(within(map).getByRole("checkbox", { name: "Fallback" }));
    fireEvent.change(within(map).getByRole("textbox", { name: "Fallback value" }), { target: { value: "-1" } });
    expect(result.binding()?.transforms).toEqual([
      {
        kind: "map",
        entries: [{ input: { type: "value", value: 5 }, output: { type: "value", value: 12.345 } }],
        hasFallback: true,
        fallback: -1,
      },
      { kind: "format", type: "numeric", mode: "pattern", pattern: "0.0" },
    ]);

    fireEvent.click(within(transforms).getByRole("button", { name: "Move Format transform 2 up" }));
    expect(result.binding()?.transforms?.map((entry) => entry.kind)).toEqual(["format", "map"]);
    const moved = within(transforms).getByRole("listitem", { name: "Format transform 1" });
    expect(within(moved).getByLabelText("Step preview")).toHaveTextContent('In 5 → Out "5.0"');
    // "5.0" is a string, so the map row for the number 5 no longer matches and the fallback is used.
    expect(within(transforms).getByRole("listitem", { name: "Map transform 2" })).toHaveTextContent('In "5.0" → Out -1');

    fireEvent.click(within(transforms).getByRole("button", { name: "Remove Format transform 1" }));
    expect(result.binding()?.transforms?.map((entry) => entry.kind)).toEqual(["map"]);
    fireEvent.click(within(transforms).getByRole("button", { name: "Remove Map transform 1" }));
    expect(result.binding()).toEqual({ kind: "expression", expression: "5" });
  });

  it("edits range and expression map rows and shows range errors inline", () => {
    const result = setup(fixture({ text: { kind: "expression", expression: "30" } }));
    const editor = openEditor();
    fireEvent.click(within(editor).getByRole("button", { name: "+ Map" }));
    const map = within(editor).getByRole("listitem", { name: "Map transform 1" });
    fireEvent.change(within(map).getByRole("combobox", { name: "Row 1 input type" }), { target: { value: "range" } });
    const range = within(map).getByRole("textbox", { name: "Row 1 range" });
    fireEvent.change(range, { target: { value: "(0,x]" } });
    expect(within(map).getByRole("alert")).toHaveTextContent("range endpoints must be numbers");
    fireEvent.change(range, { target: { value: "[25,50)" } });
    fireEvent.change(within(map).getByRole("combobox", { name: "Row 1 output type" }), { target: { value: "expression" } });
    fireEvent.change(within(map).getByRole("textbox", { name: "Row 1 output expression" }), { target: { value: "{value} * 2" } });
    expect(result.binding()?.transforms?.[0]).toEqual({
      kind: "map",
      entries: [{ input: { type: "range", min: 25, max: 50, minInclusive: true, maxInclusive: false }, output: { type: "expression", expression: "{value} * 2" } }],
    });
    expect(within(map).getByLabelText("Step preview")).toHaveTextContent("In 30 → Out 60");
  });

  it("stores script hook and source and shows not connected instead of throwing", () => {
    const result = setup(fixture({ text: { kind: "expression", expression: "'live'" } }));
    const editor = openEditor();
    fireEvent.click(within(editor).getByRole("button", { name: "+ Script" }));
    fireEvent.change(within(editor).getByRole("textbox", { name: "Hook name" }), { target: { value: "shout" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Script source" }), { target: { value: "return value.upper()" } });
    expect(result.binding()?.transforms).toEqual([{ kind: "script", hook: "shout", source: "return value.upper()" }]);
    expect(within(editor).getByText(SCRIPT_NOT_CONNECTED)).toBeVisible();
    expect(within(editor).getByRole("status", { name: "Binding value" })).toHaveTextContent("result none (not connected)");
    expect(screen.getByRole("button", { name: "Text: Expression binding" })).toHaveTextContent("not connected");
  });
});

describe("round-trip of hand-written bindings", () => {
  const handWritten: Record<string, Binding> = {
    text: {
      kind: "tag",
      mode: "indirect",
      path: "plant/{area}/{leaf}",
      references: { area: { source: "param", name: "area" }, leaf: { source: "prop", nodeId: "level", prop: "suffix" }, spare: { source: "custom", name: "unit" } },
      bidirectional: true,
      publishInitialUncertain: true,
      transforms: [
        { kind: "map", entries: [{ input: { type: "range", min: null, max: 10, minInclusive: false, maxInclusive: true }, output: { type: "expression", expression: "{value} + 1" } }], hasFallback: true, fallback: null },
        { kind: "script", hook: "scale", source: "return value * 2" },
      ],
    },
    align: {
      kind: "query",
      path: "lookup/align",
      parameters: { id: { expression: "{view.params.area}" }, fixed: { value: [1, 2] } },
      returnFormat: "json",
      pollMs: 5000,
      enabled: false,
      transforms: [{ kind: "format", type: "datetime", mode: "date", style: "short", timeZone: "Europe/Paris" }],
    },
    hidden: { kind: "property", source: { scope: "page", path: "urlParams.id" }, bidirectional: true },
  };

  it("keeps every binding kind unchanged through a parse and through Inspector edits", () => {
    const view = fixture(structuredClone(handWritten));
    const parsedTitle = findNode(view.spec.root, "title") as ComponentNode;
    expect(parsedTitle.bindings).toEqual(handWritten);

    const result = setup(view);
    expect(screen.getByText(/Also bound \(kept as authored\): hidden \(Property\)/)).toBeVisible();
    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), { target: { value: "Renamed" } });
    expect(result.node().name).toBe("Renamed");
    expect(result.node().bindings).toEqual(handWritten);

    for (const label of ["Text", "Align"]) {
      fireEvent.click(screen.getByRole("button", { name: `Bind ${label}` }));
      const editor = screen.getByRole("region", { name: `Binding for ${label.toLowerCase()}` });
      const enabled = within(editor).getByRole("checkbox", { name: "Enabled" });
      fireEvent.click(enabled);
      fireEvent.click(enabled);
      fireEvent.click(within(editor).getByRole("button", { name: "Close binding editor" }));
    }
    expect(result.node().bindings).toEqual(handWritten);
    expect(screen.getByRole("button", { name: "Text: Tag binding" })).toHaveTextContent("plant/{area}/{leaf}");
    expect(screen.getByRole("button", { name: "Align: Query binding" })).toHaveTextContent("disabled");

    const reparsed = parseView(JSON.parse(JSON.stringify({ ...view, spec: { ...view.spec, root: { ...view.spec.root, children: [result.node(), view.spec.root.children[1]] } } })), {
      allowedTypes: allowedComponentTypes(),
    });
    expect((findNode(reparsed.spec.root, "title") as ComponentNode).bindings).toEqual(handWritten);
  });

  it("refreshes a bound tag value while the tag changes", () => {
    vi.useFakeTimers();
    try {
      const server = new MemoryTagServer();
      server.set("plant/tank/note", "one");
      setup(fixture({ text: { kind: "tag", mode: "direct", path: "plant/tank/note" } }), server);
      const summary = screen.getByRole("button", { name: "Text: Tag binding" });
      expect(summary).toHaveTextContent('"one"');
      server.set("plant/tank/note", "two");
      act(() => {
        vi.advanceTimersByTime(600);
      });
      expect(summary).toHaveTextContent('"two"');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("binding model helpers", () => {
  it("reads placeholders, literals, expression errors, and moves items", () => {
    expect(placeholders("a/{x}/{y}/{x}")).toEqual(["x", "y"]);
    expect(parseLiteral("12")).toBe(12);
    expect(parseLiteral("true")).toBe(true);
    expect(parseLiteral("Running")).toBe("Running");
    expect(literalText("12")).toBe('"12"');
    expect(literalText("Running")).toBe("Running");
    expect(parseLiteral(literalText("12"))).toBe("12");
    expect(expressionError("1 +")).not.toBeNull();
    expect(expressionError("if({this.props.x} > 1, 'a', 'b')")).toBeNull();
    expect(moveItem(["a", "b", "c"], 2, 0)).toEqual(["c", "a", "b"]);
    expect(moveItem(["a", "b"], 0, -1)).toEqual(["a", "b"]);
  });
});

describe("review fixes", () => {
  it("keeps every query parameter when a rename collides or a name is cleared, and flags it as not applied", () => {
    const saved: Binding = { kind: "query", path: "plant/history", parameters: { limit: { value: 25 }, zone: { value: "north" } } };
    const result = setup(fixture({ text: saved }));
    const editor = openEditor();
    const second = within(editor).getByRole("textbox", { name: "Parameter 2 name" });
    fireEvent.change(second, { target: { value: "limit" } });
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent('two parameters are named "limit"');
    expect(within(editor).getByRole("textbox", { name: "Parameter 1 name" })).toHaveValue("limit");
    expect(second).toHaveValue("limit");
    expect(within(editor).getByRole("textbox", { name: "Parameter 2 value" })).toHaveValue("north");
    expect(result.binding()).toEqual(saved);

    fireEvent.change(within(editor).getByRole("textbox", { name: "Parameter 1 name" }), { target: { value: "" } });
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent("parameter 1 needs a name");
    // Other fields still edit the draft while the rows are unfinished, but nothing is committed.
    fireEvent.change(within(editor).getByRole("textbox", { name: "Named query" }), { target: { value: "plant/other" } });
    expect(result.binding()).toEqual(saved);

    fireEvent.change(within(editor).getByRole("textbox", { name: "Parameter 1 name" }), { target: { value: "max" } });
    expect(within(editor).queryByTestId("binding-incomplete")).toBeNull();
    expect(result.binding()).toEqual({ kind: "query", path: "plant/other", parameters: { max: { value: 25 }, limit: { value: "north" } } });
  });

  it("flags indirect placeholders that have no reference", () => {
    const saved: Binding = { kind: "tag", mode: "indirect", path: "plant/{area}/level", references: { area: { source: "param", name: "area" } } };
    const result = setup(fixture({ text: saved }));
    const editor = openEditor();
    fireEvent.change(within(editor).getByRole("textbox", { name: "Tag path" }), { target: { value: "plant/{zone}/level" } });
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent("no reference for {zone}");
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent("discards this unfinished change");
    expect(within(editor).getByText(/Kept but unused by the path: area/)).toBeVisible();
    expect(result.binding()).toEqual(saved);
    fireEvent.change(within(editor).getByRole("combobox", { name: "zone name" }), { target: { value: "area" } });
    expect(result.binding()).toEqual({
      kind: "tag",
      mode: "indirect",
      path: "plant/{zone}/level",
      references: { area: { source: "param", name: "area" }, zone: { source: "param", name: "area" } },
    });
  });

  it("edits the authored value of a bound property from the binding editor", () => {
    const saved: Binding = { kind: "expression", expression: "'x'" };
    const result = setup(fixture({ text: saved }));
    const editor = openEditor();
    fireEvent.change(within(editor).getByRole("textbox", { name: "Authored value" }), { target: { value: "Offline" } });
    expect(result.node().props.text).toBe("Offline");
    expect(result.binding()).toEqual(saved);
  });

  it("keeps array and object literals as typed", () => {
    const result = setup(fixture({ text: { kind: "query", path: "q", parameters: { ids: { value: "" } } } }));
    const editor = openEditor();
    const input = within(editor).getByRole("textbox", { name: "Parameter 1 value" });
    fireEvent.change(input, { target: { value: "[1, 2,  3]" } });
    expect(input).toHaveValue("[1, 2,  3]");
    fireEvent.change(input, { target: { value: '{ "a": 1 }' } });
    expect(input).toHaveValue('{ "a": 1 }');
    expect(result.binding()).toMatchObject({ parameters: { ids: { value: { a: 1 } } } });
  });
});

describe("binding editor: expression structure (HMP-112)", () => {
  it("builds named expressions row by row, previews the object, and flags a repeated name as not applied", () => {
    const server = new MemoryTagServer();
    server.set("plant/tank/level", 12);
    const result = setup(fixture(), server);
    const editor = openEditor();
    pickKind(editor, "Expression structure");
    expect(result.binding()).toEqual({ kind: "expression-structure", expressions: {} });
    fireEvent.click(within(editor).getByRole("button", { name: "+ Named expression" }));
    fireEvent.change(within(editor).getByRole("textbox", { name: "Expression 1 name" }), { target: { value: "value" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Expression 1 text" }), { target: { value: "{[default]plant/tank/level} * 2" } });
    expect(result.binding()).toEqual({ kind: "expression-structure", expressions: { value: "{[default]plant/tank/level} * 2" } });
    fireEvent.click(within(editor).getByRole("button", { name: "+ Named expression" }));
    fireEvent.change(within(editor).getByRole("textbox", { name: "Expression 2 name" }), { target: { value: "value" } });
    expect(within(editor).getByTestId("binding-incomplete")).toHaveTextContent('two expressions are named "value"');
    // the saved binding is unchanged while the rows are not valid
    expect(result.binding()).toEqual({ kind: "expression-structure", expressions: { value: "{[default]plant/tank/level} * 2", key2: "" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Expression 2 name" }), { target: { value: "high" } });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Expression 2 text" }), { target: { value: "80" } });
    expect(result.binding()).toEqual({
      kind: "expression-structure",
      expressions: { value: "{[default]plant/tank/level} * 2", high: "80" },
    });
    fireEvent.click(within(editor).getByRole("button", { name: "Remove expression 1" }));
    expect(result.binding()).toEqual({ kind: "expression-structure", expressions: { high: "80" } });
  });

  it("shows a syntax error beside the row that has one", () => {
    setup(fixture());
    const editor = openEditor();
    pickKind(editor, "Expression structure");
    fireEvent.click(within(editor).getByRole("button", { name: "+ Named expression" }));
    fireEvent.change(within(editor).getByRole("textbox", { name: "Expression 1 text" }), { target: { value: "1 +" } });
    expect(within(editor).getByRole("textbox", { name: "Expression 1 text" })).toHaveAttribute("aria-invalid", "true");
  });

  it("summarizes the keys", () => {
    expect(bindingSummary({ kind: "expression-structure", expressions: { a: "1", b: "2" } })).toBe("{a, b}");
    expect(bindingSummary({ kind: "expression-structure", expressions: {} })).toBe("(no expressions)");
  });
});
