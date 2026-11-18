import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { allowedComponentTypes } from "@oip/components";
import { findNode, parseView, type ComponentNode, type ContainerNode, type ViewDocument } from "@oip/hmi-runtime";
import { Inspector } from "./Inspector";
import { fromResource, toResource } from "./persistence";
import { suggestHook, withActions } from "./eventModel";
import { tagValue } from "./EventEditor";

/** D-5: a component's and a view's event handlers in the Inspector. */

afterEach(cleanup);

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
          children: [
            { id: "start", kind: "component", name: "Start", type: "button", frame: { x: 0, y: 0, width: 100, height: 30 }, props: { text: "Start" } },
          ],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

function setup(selectedId = "start", hooks: string[] = []) {
  const state: { view: ViewDocument } = { view: fixture() };
  function Harness() {
    const [view, setView] = useState(state.view);
    state.view = view;
    const selected = findNode(view.spec.root, selectedId) as ComponentNode | ContainerNode;
    return <Inspector view={view} selected={selected} viewPaths={["events", "pumps"]} scriptHooks={new Set(hooks)} onView={setView} />;
  }
  render(<Harness />);
  return {
    view: () => state.view,
    events: () => (findNode(state.view.spec.root, "start") as ComponentNode).events,
  };
}

function openEvent(name: string) {
  fireEvent.click(screen.getByRole("button", { name: new RegExp(`^${name}:`) }));
  return screen.getByRole("region", { name: `Actions for ${name}` });
}

function addAction(region: HTMLElement, eventName: string, type: string) {
  fireEvent.change(within(region).getByRole("combobox", { name: `New ${eventName} action type` }), { target: { value: type } });
  fireEvent.click(within(region).getByRole("button", { name: "+ Add action" }));
}

describe("event editor", () => {
  it("adds an onClick script action with its own source, and the saved view keeps it", () => {
    const result = setup();
    expect(screen.getByRole("button", { name: "onClick: no actions" })).toHaveAttribute("aria-expanded", "false");
    const region = openEvent("onClick");
    addAction(region, "onClick", "script");
    // No source key until one is typed, so the gateway falls back to the project hook of that name.
    expect(result.events()).toEqual([{ event: "onClick", actions: [{ kind: "script", hook: "start.onClick" }] }]);

    const action = within(region).getByRole("listitem", { name: "onClick action 1: Script" });
    fireEvent.change(within(action).getByRole("textbox", { name: "Hook name" }), { target: { value: "startPump" } });
    fireEvent.change(within(action).getByRole("textbox", { name: "Script source" }), {
      target: { value: "actions.writeTag('[default]pump/run', event.payload.type == 'click')" },
    });
    expect(result.events()?.[0]?.actions[0]).toEqual({
      kind: "script",
      hook: "startPump",
      source: "actions.writeTag('[default]pump/run', event.payload.type == 'click')",
    });
    expect(screen.getByRole("button", { name: "onClick: 1 action" })).toHaveTextContent("Script");

    // Through the gateway resource and back, as a save and reload do.
    const reloaded = fromResource(toResource(result.view(), { name: "events", version: 1 }));
    const node = findNode(reloaded.spec.root, "start") as ComponentNode;
    expect(node.events?.[0]?.actions[0]).toMatchObject({ kind: "script", hook: "startPump", source: expect.stringContaining("event.payload") });
  });

  it("an empty source runs the project hook of that name, and says whether it exists", () => {
    const result = setup("start", ["startPump"]);
    const region = openEvent("onClick");
    addAction(region, "onClick", "script");
    expect(within(region).getByTestId("project-hook-note")).toHaveTextContent('runs the project script hook "start.onClick", which does not exist yet');
    fireEvent.change(within(region).getByRole("textbox", { name: "Hook name" }), { target: { value: "startPump" } });
    expect(within(region).getByTestId("project-hook-note")).toHaveTextContent('Runs the project script hook "startPump".');
    // Typing a source and clearing it again removes the key, not just its text.
    const source = within(region).getByRole("textbox", { name: "Script source" });
    fireEvent.change(source, { target: { value: "pass" } });
    expect(within(region).queryByTestId("project-hook-note")).toBeNull();
    fireEvent.change(source, { target: { value: "" } });
    expect(result.events()?.[0]?.actions[0]).toEqual({ kind: "script", hook: "startPump" });
  });

  it("keeps an invalid or empty required field out of the view, so it still opens", () => {
    const result = setup();
    const region = openEvent("onClick");
    addAction(region, "onClick", "script");
    const hook = within(region).getByRole("textbox", { name: "Hook name" });
    fireEvent.change(hook, { target: { value: "" } });
    expect(hook).toHaveValue("");
    expect(hook).toHaveAttribute("aria-invalid", "true");
    expect(within(region).getByText(/Enter a hook name\. Kept: start\.onClick/)).toBeInTheDocument();
    fireEvent.change(hook, { target: { value: "9 bad" } });
    expect(result.events()?.[0]?.actions[0]).toMatchObject({ hook: "start.onClick" });
    fireEvent.blur(hook);
    expect(hook).toHaveValue("start.onClick");
    expect(() => fromResource(toResource(result.view(), { name: "events", version: 1 }))).not.toThrow();
  });

  it("edits navigation, popup and tag write actions, reorders, disables and removes them", () => {
    const result = setup();
    const region = openEvent("onClick");
    addAction(region, "onClick", "navigation");
    addAction(region, "onClick", "popup");
    addAction(region, "onClick", "tag-write");

    const nav = within(region).getByRole("listitem", { name: "onClick action 1: Navigation" });
    fireEvent.change(within(nav).getByRole("textbox", { name: "Page URL" }), { target: { value: "/pumps" } });
    fireEvent.change(within(nav).getByRole("combobox", { name: "Navigate to" }), { target: { value: "view" } });
    expect(within(nav).getByRole("combobox", { name: "View path" })).toHaveValue("events");
    fireEvent.change(within(nav).getByRole("combobox", { name: "View path" }), { target: { value: "pumps" } });

    const popup = within(region).getByRole("listitem", { name: "onClick action 2: Popup" });
    fireEvent.change(within(popup).getByRole("textbox", { name: "Popup id" }), { target: { value: "detail" } });
    fireEvent.change(within(popup).getByRole("textbox", { name: "Popup title" }), { target: { value: "Pump detail" } });
    fireEvent.click(within(popup).getByRole("checkbox", { name: "Modal" }));

    const write = within(region).getByRole("listitem", { name: "onClick action 3: Tag write" });
    expect(within(write).getByRole("alert")).toHaveTextContent("Enter a tag path.");
    fireEvent.change(within(write).getByRole("textbox", { name: "Tag path" }), { target: { value: "[default]pump/speed" } });
    fireEvent.change(within(write).getByRole("textbox", { name: "Value to write" }), { target: { value: "42" } });
    fireEvent.click(within(write).getByRole("checkbox", { name: "Stop propagation" }));

    expect(result.events()?.[0]?.actions).toEqual([
      { kind: "navigation", mode: "view", page: "/pumps", view: "pumps" },
      { kind: "popup", mode: "open", id: "detail", view: "events", title: "Pump detail", modal: true },
      { kind: "tag-write", path: "[default]pump/speed", value: 42, stopPropagation: true },
    ]);

    fireEvent.click(within(region).getByRole("button", { name: "Move onClick action 3: Tag write up" }));
    expect(result.events()?.[0]?.actions.map((action) => action.kind)).toEqual(["navigation", "tag-write", "popup"]);
    fireEvent.click(within(within(region).getByRole("listitem", { name: "onClick action 1: Navigation" })).getByRole("checkbox", { name: "Enabled" }));
    expect(result.events()?.[0]?.actions[0]).toMatchObject({ enabled: false });
    for (const name of ["onClick action 3: Popup", "onClick action 2: Tag write", "onClick action 1: Navigation"]) {
      fireEvent.click(within(region).getByRole("button", { name: `Remove ${name}` }));
    }
    // The last action removed takes the event entry with it.
    expect(result.events()).toBeUndefined();
  });

  it("edits the view's startup and shutdown events on the root container", () => {
    const result = setup("root");
    const section = screen.getByRole("region", { name: "View events" });
    expect(within(section).queryByRole("button", { name: /^onClick:/ })).toBeNull();
    const region = openEvent("onStartup");
    addAction(region, "onStartup", "tag-write");
    fireEvent.change(within(region).getByRole("textbox", { name: "Tag path" }), { target: { value: "[default]view/opened" } });
    expect(result.view().spec.events).toEqual([{ event: "onStartup", actions: [{ kind: "tag-write", path: "[default]view/opened", value: true }] }]);
    fireEvent.click(within(region).getByRole("button", { name: "Remove onStartup action 1: Tag write" }));
    expect(result.view().spec.events).toBeUndefined();
  });
});

describe("message and logout actions (P4-11, HMP-117)", () => {
  it("adds a message action with type, scope and payload, and a logout action", () => {
    const result = setup();
    const region = openEvent("onClick");
    addAction(region, "onClick", "message");
    const message = within(region).getByRole("listitem", { name: "onClick action 1: Message" });
    fireEvent.change(within(message).getByRole("textbox", { name: "Message type" }), { target: { value: "9bad" } });
    expect(within(message).getByText(/Start with a letter/)).toBeInTheDocument();
    fireEvent.change(within(message).getByRole("textbox", { name: "Message type" }), { target: { value: "pump.started" } });
    fireEvent.change(within(message).getByRole("combobox", { name: "Message scope" }), { target: { value: "session" } });
    fireEvent.change(within(message).getByRole("textbox", { name: "Message payload" }), { target: { value: "[1]" } });
    expect(within(message).getByText("Enter a JSON object.")).toBeInTheDocument();
    fireEvent.change(within(message).getByRole("textbox", { name: "Message payload" }), { target: { value: '{"pump": "P-101"}' } });
    addAction(region, "onClick", "logout");
    expect(within(region).getByRole("listitem", { name: "onClick action 2: Logout" })).toHaveTextContent("Signs the user out");
    expect(result.events()).toEqual([
      {
        event: "onClick",
        actions: [{ kind: "message", messageType: "pump.started", scope: "session", payload: { pump: "P-101" } }, { kind: "logout" }],
      },
    ]);
    const reloaded = fromResource(toResource(result.view(), { name: "events", version: 1 }));
    expect((findNode(reloaded.spec.root, "start") as ComponentNode).events).toEqual(result.events());
  });

  it("edits a component's message handlers", () => {
    const result = setup();
    const section = screen.getByRole("region", { name: "Message handlers" });
    fireEvent.click(within(section).getByRole("button", { name: "+ Add message handler" }));
    const handler = within(section).getByRole("listitem", { name: "Message handler 1" });
    fireEvent.change(within(handler).getByRole("textbox", { name: "Handler message type" }), { target: { value: "pump.started" } });
    fireEvent.click(within(handler).getByRole("checkbox", { name: "Session" }));
    fireEvent.click(within(handler).getByRole("checkbox", { name: "Page" }));
    // The last scope cannot be turned off.
    fireEvent.click(within(handler).getByRole("checkbox", { name: "Session" }));
    fireEvent.change(within(handler).getByRole("textbox", { name: "Handler script" }), { target: { value: "actions.writeTag('[default]last', event.payload.pump)" } });
    const node = () => findNode(result.view().spec.root, "start") as ComponentNode;
    expect(node().messageHandlers).toEqual([
      { messageType: "pump.started", scopes: ["session"], script: { kind: "script", hook: "start.on-refresh", source: "actions.writeTag('[default]last', event.payload.pump)" } },
    ]);
    const reloaded = fromResource(toResource(result.view(), { name: "events", version: 1 }));
    expect((findNode(reloaded.spec.root, "start") as ComponentNode).messageHandlers).toEqual(node().messageHandlers);
    fireEvent.click(within(section).getByRole("button", { name: "Remove Message handler 1" }));
    expect(node().messageHandlers).toBeUndefined();
  });
});

describe("event model", () => {
  it("suggests a hook name not used in the view yet", () => {
    const view = fixture();
    expect(suggestHook(view, "start", "onClick")).toBe("start.onClick");
    const start = findNode(view.spec.root, "start") as ComponentNode;
    start.events = [{ event: "onClick", actions: [{ kind: "script", hook: "start.onClick" }] }];
    expect(suggestHook(view, "start", "onClick")).toBe("start.onClick-2");
    expect(suggestHook(view, "1-x", "onChange")).toBe("x.onChange");
  });

  it("merges duplicate entries of one event and keeps other events in place", () => {
    const tag = { kind: "tag-write" as const, path: "a", value: 1 };
    expect(
      withActions(
        [
          { event: "onClick", actions: [tag] },
          { event: "onChange", actions: [tag] },
          { event: "onClick", actions: [tag] },
        ],
        "onClick",
        [tag, tag],
      ),
    ).toEqual([
      { event: "onClick", actions: [tag, tag] },
      { event: "onChange", actions: [tag] },
    ]);
  });

  it("reads a typed tag value as a JSON scalar or text", () => {
    expect(tagValue("42")).toBe(42);
    expect(tagValue("true")).toBe(true);
    expect(tagValue("null")).toBeNull();
    expect(tagValue('"7"')).toBe("7");
    expect(tagValue("running")).toBe("running");
    expect(tagValue("[1, 2]")).toBe("[1, 2]");
  });
});
