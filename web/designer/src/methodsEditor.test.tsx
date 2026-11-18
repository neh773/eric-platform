import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { allowedComponentTypes } from "@oip/components";
import { findNode, parseView, type ComponentNode, type ContainerNode, type ViewDocument } from "@oip/hmi-runtime";
import { Inspector } from "./Inspector";
import { fromResource, toResource } from "./persistence";
import { methodNameProblem, methodParamProblem } from "./eventModel";

/** HMV-114: custom methods of a component and of a view, in the Inspector. */

afterEach(cleanup);

function fixture(): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Methods", path: "methods" },
      spec: {
        width: 400,
        height: 200,
        params: {},
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "coordinate",
          props: { mode: "fixed" },
          children: [{ id: "pump", kind: "component", name: "Pump", type: "button", frame: { x: 0, y: 0, width: 100, height: 30 }, props: { text: "Pump" } }],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

function setup(selectedId: string | null) {
  const state: { view: ViewDocument } = { view: fixture() };
  function Harness() {
    const [view, setView] = useState(state.view);
    state.view = view;
    const selected = selectedId ? (findNode(view.spec.root, selectedId) as ComponentNode | ContainerNode) : null;
    return <Inspector view={view} selected={selected} viewPaths={["methods"]} scriptHooks={new Set()} onView={setView} />;
  }
  render(<Harness />);
  return { view: () => state.view, node: () => findNode(state.view.spec.root, "pump") as ComponentNode };
}

function addMethod(section: HTMLElement, name: string) {
  fireEvent.change(within(section).getByRole("textbox", { name: "New method name" }), { target: { value: name } });
  fireEvent.click(within(section).getByRole("button", { name: "+ Add method" }));
}

describe("method names", () => {
  it("checks method and parameter names like the view model does", () => {
    expect(methodNameProblem("")).toMatch(/Enter/);
    expect(methodNameProblem("9x")).toMatch(/Start with a letter/);
    expect(methodNameProblem("setLevel")).toBeNull();
    expect(methodParamProblem("a b")).toMatch(/letters/);
    expect(methodParamProblem("_speed")).toBeNull();
  });
});

describe("component methods", () => {
  it("declares a method with a parameter and a default, adds a script action and a call, and survives a save", () => {
    const result = setup("pump");
    const section = screen.getByRole("region", { name: "Custom methods" });
    addMethod(section, "start");
    expect(result.node().methods).toEqual({ start: { actions: [] } });

    const method = within(section).getByLabelText("Method start");
    fireEvent.change(within(method).getByRole("textbox", { name: "New parameter of start" }), { target: { value: "speed" } });
    fireEvent.click(within(method).getByRole("button", { name: "+ Add parameter" }));
    fireEvent.change(within(method).getByRole("textbox", { name: "Default of speed" }), { target: { value: "50" } });
    expect(result.node().methods!.start!.params).toEqual([{ name: "speed", default: 50 }]);

    fireEvent.click(within(method).getByRole("button", { name: /^run:/ }));
    const actions = within(method).getByRole("region", { name: "Actions for run" });
    fireEvent.change(within(actions).getByRole("combobox", { name: "New run action type" }), { target: { value: "call-method" } });
    fireEvent.click(within(actions).getByRole("button", { name: "+ Add action" }));
    const call = within(actions).getByRole("listitem", { name: "run action 1: Call method" });
    fireEvent.change(within(call).getByRole("textbox", { name: "Method arguments" }), { target: { value: '{"speed":"{{speed}}"}' } });
    fireEvent.change(within(call).getByRole("textbox", { name: "Method target" }), { target: { value: "view" } });
    expect(result.node().methods!.start!.actions).toEqual([{ kind: "call-method", method: "run", args: { speed: "{{speed}}" }, target: "view" }]);

    const reloaded = fromResource(toResource(result.view(), { name: "methods", version: 1 }));
    expect((findNode(reloaded.spec.root, "pump") as ComponentNode).methods!.start!.params).toEqual([{ name: "speed", default: 50 }]);
  });

  it("refuses a bad or repeated method name and removes a method", () => {
    const result = setup("pump");
    const section = screen.getByRole("region", { name: "Custom methods" });
    fireEvent.change(within(section).getByRole("textbox", { name: "New method name" }), { target: { value: "1bad" } });
    expect(within(section).getByRole("alert")).toHaveTextContent("Start with a letter");
    expect(within(section).getByRole("button", { name: "+ Add method" })).toBeDisabled();
    fireEvent.change(within(section).getByRole("textbox", { name: "New method name" }), { target: { value: "go" } });
    fireEvent.click(within(section).getByRole("button", { name: "+ Add method" }));
    fireEvent.change(within(section).getByRole("textbox", { name: "New method name" }), { target: { value: "go" } });
    expect(within(section).getByRole("alert")).toHaveTextContent("exists");
    fireEvent.click(within(section).getByRole("button", { name: "Remove method go" }));
    expect(result.node().methods).toBeUndefined();
  });
});

describe("view methods", () => {
  it("declares a method of the view while nothing is selected", () => {
    const result = setup(null);
    const section = screen.getByRole("region", { name: "View methods" });
    addMethod(section, "reset");
    expect(result.view().spec.methods).toEqual({ reset: { actions: [] } });
    fireEvent.click(within(section).getByRole("button", { name: "Remove method reset" }));
    expect(result.view().spec.methods).toBeUndefined();
  });
});
