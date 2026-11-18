import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { allowedComponentTypes } from "@oip/components";
import { findNode, parseView, type ComponentNode, type ContainerNode, type ViewDocument } from "@oip/hmi-runtime";
import { Inspector } from "./Inspector";
import { appendScript, buildScript, builderProblem, emptyForm, scriptDict, scriptValue } from "./scriptBuilders";

/** HMV-130: script builders generate navigation, tag-write and named-query handler scripts. */

afterEach(cleanup);

describe("script values", () => {
  it("reads numbers, booleans, none, text and code", () => {
    expect(scriptValue("42")).toBe("42");
    expect(scriptValue("-1.5")).toBe("-1.5");
    expect(scriptValue("true")).toBe("True");
    expect(scriptValue("False")).toBe("False");
    expect(scriptValue("none")).toBe("None");
    expect(scriptValue("pump 1")).toBe('"pump 1"');
    expect(scriptValue(`say "hi"`)).toBe(`"say \\"hi\\""`);
    expect(scriptValue("=event.payload.clientX")).toBe("event.payload.clientX");
  });

  it("reads name = value lines", () => {
    expect(scriptDict("line = 3\n\nmode = auto\nrun = true")).toEqual({ text: '{"line": 3, "mode": "auto", "run": True}', problem: null });
    expect(scriptDict("")).toEqual({ text: "", problem: null });
    expect(scriptDict("= 3").problem).toMatch(/name/);
    expect(scriptDict("lonely").problem).toMatch(/value/);
  });
});

describe("builders", () => {
  it("emits a page navigation, with and without parameters", () => {
    expect(buildScript({ ...emptyForm("navigate-page"), target: "/pumps" })).toBe('actions.navigate(page="/pumps")');
    expect(buildScript({ ...emptyForm("navigate-page"), target: "/line/3", params: "mode = auto" })).toBe('actions.navigate(page="/line/3", params={"mode": "auto"})');
  });

  it("emits a view navigation", () => {
    expect(buildScript({ ...emptyForm("navigate-view"), target: "Folder/View", params: "id = 7" })).toBe('actions.navigate(view="Folder/View", params={"id": 7})');
  });

  it("emits a tag write", () => {
    expect(buildScript({ ...emptyForm("write-tag"), target: "[default]pump/run", value: "true" })).toBe('actions.writeTag("[default]pump/run", True)');
    expect(buildScript({ ...emptyForm("write-tag"), target: "[default]pump/sp", value: "=event.payload.x" })).toBe('actions.writeTag("[default]pump/sp", event.payload.x)');
  });

  it("emits a named query run, with and without parameters", () => {
    expect(buildScript({ ...emptyForm("named-query"), target: "recent-alarms" })).toBe('rows = system.db.runNamedQuery("recent-alarms")');
    expect(buildScript({ ...emptyForm("named-query"), target: "by-line", params: "line = 3\nlabel = north" })).toBe('rows = system.db.runNamedQuery("by-line", {"line": 3, "label": "north"})');
  });

  it("names what is missing and emits nothing", () => {
    expect(builderProblem(emptyForm("navigate-page"))).toBe("Enter a page URL.");
    expect(builderProblem({ ...emptyForm("navigate-page"), target: "pumps" })).toBe("A page URL starts with /.");
    expect(builderProblem({ ...emptyForm("write-tag"), target: "[default]a" })).toBe("Enter the value to write.");
    expect(buildScript(emptyForm("named-query"))).toBeNull();
  });

  it("appends after existing script lines", () => {
    expect(appendScript("", "a()")).toBe("a()");
    expect(appendScript("a()\n", "b()")).toBe("a()\nb()");
  });

  it("uses names the session script runtime defines", () => {
    const runtime = readFileSync(resolve(process.cwd(), "../gateway/scripting/src/main/kotlin/org/oip/scripting/HmiScripts.kt"), "utf8");
    expect(runtime).toContain("def navigate(self, page=None, view=None, params=None)");
    expect(runtime).toContain("def writeTag(self, path, value)");
    const library = readFileSync(resolve(process.cwd(), "../gateway/scripting/src/main/resources/oip_system.py"), "utf8");
    expect(library).toContain("def runNamedQuery(*args):");
  });
});

function fixture(): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Events", path: "events" },
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
          children: [{ id: "start", kind: "component", name: "Start", type: "button", frame: { x: 0, y: 0, width: 100, height: 30 }, props: { text: "Start" } }],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

describe("script builder in the event editor", () => {
  it("writes the generated script into the event's script source", () => {
    const state: { view: ViewDocument } = { view: fixture() };
    function Harness() {
      const [view, setView] = useState(state.view);
      state.view = view;
      const selected = findNode(view.spec.root, "start") as ComponentNode | ContainerNode;
      return <Inspector view={view} selected={selected} viewPaths={["events"]} scriptHooks={new Set()} onView={setView} />;
    }
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /^onClick:/ }));
    const region = screen.getByRole("region", { name: "Actions for onClick" });
    fireEvent.change(within(region).getByRole("combobox", { name: "New onClick action type" }), { target: { value: "script" } });
    fireEvent.click(within(region).getByRole("button", { name: "+ Add action" }));

    fireEvent.click(within(region).getByRole("button", { name: "Add to script" }));
    expect(within(region).getByRole("alert")).toHaveTextContent("Enter a page URL.");

    fireEvent.change(within(region).getByLabelText("Builder page url"), { target: { value: "/pumps" } });
    fireEvent.click(within(region).getByRole("button", { name: "Add to script" }));
    fireEvent.change(within(region).getByLabelText("Builder handler"), { target: { value: "write-tag" } });
    fireEvent.change(within(region).getByLabelText("Builder tag path"), { target: { value: "[default]pump/run" } });
    fireEvent.change(within(region).getByLabelText("Builder value"), { target: { value: "true" } });
    fireEvent.click(within(region).getByRole("button", { name: "Add to script" }));

    const action = (findNode(state.view.spec.root, "start") as ComponentNode).events?.[0]?.actions[0];
    expect(action).toMatchObject({ kind: "script", source: 'actions.navigate(page="/pumps")\nactions.writeTag("[default]pump/run", True)' });
    expect(within(region).getByRole("textbox", { name: "Script source" })).toHaveValue('actions.navigate(page="/pumps")\nactions.writeTag("[default]pump/run", True)');
  });
});
