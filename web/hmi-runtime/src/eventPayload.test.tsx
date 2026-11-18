import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createLinkedTransports, createSession, MemoryTagServer, type ComponentRegistry, type ScriptHost } from "./index";
import { eventPayload } from "./eventPayload";
import { parseView } from "./model";
import { ViewRenderer } from "./ViewRenderer";

/** D-5: DOM event data reaches an event script as `event.payload`. */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "button") return undefined;
    return { type, title: type, schema: { type: "object" }, Component: () => <button type="button">Go</button> };
  },
  list: () => [],
};

const view = parseView({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Home", path: "home" },
  spec: {
    params: {},
    width: 400,
    height: 200,
    events: [{ event: "onStartup", actions: [{ kind: "script", hook: "started" }] }],
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        {
          id: "go",
          kind: "component",
          name: "Go",
          type: "button",
          frame: { x: 0, y: 0, width: 100, height: 20 },
          props: {},
          events: [{ event: "onClick", actions: [{ kind: "script", hook: "clicked", source: "actions.writeTag('a', 1)" }] }],
        },
      ],
    },
  },
});

describe("event payload", () => {
  it("copies the type, pointer position, buttons and modifiers of a click", () => {
    const target = { getBoundingClientRect: () => ({ left: 10, top: 20 }) };
    const payload = eventPayload({
      type: "click",
      clientX: 15.256,
      clientY: 30,
      pageX: 15,
      pageY: 130,
      screenX: 100,
      screenY: 200,
      button: 0,
      buttons: 1,
      altKey: false,
      ctrlKey: true,
      shiftKey: false,
      metaKey: false,
      pointerType: "mouse",
      currentTarget: target,
      view: {},
    });
    expect(payload).toEqual({
      type: "click",
      clientX: 15.26,
      clientY: 30,
      pageX: 15,
      pageY: 130,
      screenX: 100,
      screenY: 200,
      button: 0,
      buttons: 1,
      x: 5.26,
      y: 10,
      pointerType: "mouse",
      altKey: false,
      ctrlKey: true,
      shiftKey: false,
      metaKey: false,
    });
  });

  it("keeps only the type for an event without a position, and nothing for a non-event", () => {
    expect(eventPayload({ type: "change", stopPropagation() {} })).toEqual({ type: "change" });
    expect(eventPayload({ type: "keydown", key: "Enter", code: "Enter" })).toEqual({ type: "keydown", key: "Enter", code: "Enter" });
    expect(eventPayload({ stopPropagation() {} })).toBeUndefined();
    expect(eventPayload(null)).toBeUndefined();
  });

  it("passes the click's payload to the event script, and none to the startup script", async () => {
    const action = vi.fn<ScriptHost["action"]>(() => undefined);
    const host: ScriptHost = { transform: (_hook, value) => value, action };
    const [client, side] = createLinkedTransports();
    const server = new MemoryTagServer();
    server.connect(side);
    render(
      <ViewRenderer view={view} views={[view]} registry={registry} transport={client} session={createSession({ id: "s", locale: "en-US" })} scriptHost={host} />,
    );
    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
    expect(action.mock.calls[0]![1]).toBe("onStartup");
    expect(action.mock.calls[0]![6]).toBeUndefined();

    fireEvent.click(screen.getByRole("button", { name: "Go" }), { clientX: 42, clientY: 7, ctrlKey: true });
    await waitFor(() => expect(action).toHaveBeenCalledTimes(2));
    const [hook, event, nodeId, viewPath, , , payload] = action.mock.calls[1]!;
    expect([hook, event, nodeId, viewPath]).toEqual(["clicked", "onClick", "go", "home"]);
    expect(payload).toMatchObject({ type: "click", clientX: 42, clientY: 7, button: 0, ctrlKey: true, shiftKey: false });
    // jsdom lays nothing out, so the component's box is at 0,0.
    expect(payload).toMatchObject({ x: 42, y: 7 });
    server.close();
  });

  it("keeps a script action's inline source through the view model", () => {
    const node = view.spec.root.children[0];
    expect(node?.kind === "component" && node.events?.[0]?.actions[0]).toEqual(
      expect.objectContaining({ kind: "script", hook: "clicked", source: "actions.writeTag('a', 1)" }),
    );
    expect(() =>
      parseView({
        ...view,
        spec: { ...view.spec, events: [{ event: "onStartup", actions: [{ kind: "script", hook: "x", source: 3 }] }] },
      }),
    ).toThrow("source must be a string");
  });
});
