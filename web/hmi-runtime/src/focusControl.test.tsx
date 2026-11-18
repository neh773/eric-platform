import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ScriptHost } from "./bindings";
import { parseView } from "./model";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer } from "./tags";
import type { ComponentRegistry, HmiComponentProps } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** HMV-118: tab order and request-focus; component events reach the node's actions. */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "field" && type !== "firing") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props, onEvent }: HmiComponentProps) =>
        type === "field" ? <input aria-label={String(props.text)} /> : (
          <button type="button" onClick={() => onEvent?.("tick")}>
            fire
          </button>
        ),
    };
  },
  list: () => [],
};

function make(children: unknown[]) {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "V", path: "v" },
    spec: { width: 300, height: 200, params: {}, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children } },
  });
}

const field = (id: string, props: Record<string, unknown>) => ({ id, kind: "component", name: id, type: "field", props: { text: id, ...props } });

function mount(view: ReturnType<typeof make>, scriptHost?: ScriptHost) {
  const server = new MemoryTagServer();
  const [transport, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  const utils = render(<ViewRenderer view={view} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={scriptHost} />);
  return { server, ...utils };
}

describe("keyboard focus (HMV-118)", () => {
  it("puts the first focusable element at the tab index the prop names", () => {
    const { server } = mount(make([field("a", { tabIndex: 2 }), field("b", { tabIndex: 1 }), field("c", {})]));
    expect(screen.getByLabelText("a")).toHaveAttribute("tabindex", "2");
    expect(screen.getByLabelText("b")).toHaveAttribute("tabindex", "1");
    expect(screen.getByLabelText("c")).not.toHaveAttribute("tabindex");
    server.close();
  });

  it("moves focus when requestFocus changes after the first value", () => {
    const view = make([field("a", { requestFocus: 0 }), field("b", {})]);
    const { server, rerender } = mount(view);
    expect(document.body).toHaveFocus();
    const next = make([field("a", { requestFocus: 1 }), field("b", {})]);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    rerender(<ViewRenderer view={next} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(screen.getByLabelText("a")).toHaveFocus();
    server.close();
  });
});

describe("component events", () => {
  it("runs the node's actions for an event the component raises through onEvent", () => {
    const action = vi.fn();
    const host: ScriptHost = { transform: (_hook, value) => value, action };
    const view = make([
      { id: "t", kind: "component", name: "t", type: "firing", props: {}, events: [{ event: "tick", actions: [{ kind: "script", hook: "on-tick", source: "pass" }] }] },
    ]);
    const { server } = mount(view, host);
    screen.getByRole("button", { name: "fire" }).click();
    expect(action).toHaveBeenCalledTimes(1);
    expect(action.mock.calls[0].slice(0, 3)).toEqual(["on-tick", "tick", "t"]);
    server.close();
  });
});
