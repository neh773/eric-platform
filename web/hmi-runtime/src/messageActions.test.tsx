import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ScriptHost } from "./bindings";
import { parseView, ViewModelError } from "./model";
import { parsePage } from "./pages";
import { PageSession } from "./PageSession";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import type { ComponentRegistry, HmiComponentProps, HmiProject, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** P4-11 (HMP-117): message and logout actions on component events. */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "button") return undefined;
    return { type, title: type, schema: { type: "object" }, Component: ({ props }: HmiComponentProps) => <button type="button">{String(props.text ?? "")}</button> };
  },
  list: () => [],
};

function button(id: string, text: string, extra: Record<string, unknown> = {}) {
  return { id, kind: "component", name: id, type: "button", props: { text }, ...extra };
}

function handler(messageType: string, scopes: string[], hook: string) {
  return { messageType, scopes, script: { kind: "script", hook, source: "pass" } };
}

function view(path: string, children: unknown[]): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: { width: 300, height: 200, params: { line: { value: 3 } }, custom: {}, root: { id: `${path}-root`, kind: "container", name: "Root", layout: "flex", props: {}, children } },
  });
}

function onClick(...actions: unknown[]) {
  return { events: [{ event: "onClick", actions }] };
}

let server: MemoryTagServer;
let transport: StringTransport;
beforeEach(() => {
  server = new MemoryTagServer();
  const [client, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  transport = client;
});
afterEach(() => server.close());

function recordingHost() {
  const calls: Array<{ hook: string; event: string; nodeId: string; view: string; params: Record<string, unknown>; payload: unknown }> = [];
  const host: ScriptHost = {
    transform: (_hook, value) => value,
    action(hook, event, nodeId, viewPath, params, _custom, payload) {
      calls.push({ hook, event, nodeId, view: viewPath, params, payload });
    },
  };
  return { host, calls };
}

describe("message action", () => {
  it("runs the handlers listening at the message's scope with its payload", async () => {
    const { host, calls } = recordingHost();
    const main = view("main", [
      button("send-page", "Send page", onClick({ kind: "message", messageType: "refresh", scope: "page", payload: { area: "north", count: 2 } })),
      button("send-view", "Send view", onClick({ kind: "message", messageType: "refresh", scope: "view" })),
      button("local", "Local", { messageHandlers: [handler("refresh", ["view", "page"], "main-refresh"), handler("other", ["page"], "main-other")] }),
    ]);
    const dock = view("dock", [button("remote", "Remote", { messageHandlers: [handler("refresh", ["page"], "dock-refresh"), handler("refresh", ["session"], "dock-session")] })]);
    const project: HmiProject = {
      name: "p",
      views: [main, dock],
      pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Main" }, spec: { url: "/", primaryView: "main", docks: [{ id: "d", edge: "left", view: "dock", size: 120, display: "visible", content: "push", handle: "hide" }] } })],
    };
    render(<PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location="/" scriptHost={host} viewportWidth={1200} />);
    await act(async () => {});

    fireEvent.click(screen.getByRole("button", { name: "Send page" }));
    expect(calls.map((call) => call.hook).sort()).toEqual(["dock-refresh", "main-refresh", "pageChange", "sessionStartup"]);
    const local = calls.find((call) => call.hook === "main-refresh")!;
    expect(local).toMatchObject({ event: "refresh", nodeId: "local", view: "main", params: { line: 3 }, payload: { area: "north", count: 2 } });
    expect(calls.find((call) => call.hook === "dock-refresh")).toMatchObject({ nodeId: "remote", view: "dock" });

    // View scope stays in the sending view; a handler that does not listen at that scope does not run.
    calls.length = 0;
    fireEvent.click(screen.getByRole("button", { name: "Send view" }));
    expect(calls.map((call) => call.hook)).toEqual(["main-refresh"]);
    expect(calls[0]!.payload).toEqual({});
  });

  it("reaches session-scope handlers and runs nothing in design mode", () => {
    const { host, calls } = recordingHost();
    const doc = view("main", [
      button("send", "Send", onClick({ kind: "message", messageType: "ack", scope: "session" })),
      button("h", "H", { messageHandlers: [handler("ack", ["session"], "on-ack")] }),
    ]);
    const { unmount } = render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={host} />);
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(calls.map((call) => call.hook)).toEqual(["on-ack"]);
    unmount();
    calls.length = 0;
    render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={host} designMode />);
    fireEvent.click(document.querySelector("[data-node-id='send'] button")!);
    expect(calls).toEqual([]);
  });

  it("stops listening when the view closes", () => {
    const { host, calls } = recordingHost();
    const doc = view("main", [button("h", "H", { messageHandlers: [handler("ack", ["page"], "on-ack")] })]);
    const sender = view("other", [button("send", "Send", onClick({ kind: "message", messageType: "ack", scope: "page" }))]);
    const { rerender } = render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={host} />);
    rerender(<ViewRenderer view={sender} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={host} />);
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(calls).toEqual([]);
  });

  it("validates message actions and handlers", () => {
    expect(() => view("v", [button("b", "B", onClick({ kind: "message", messageType: "9x", scope: "page" }))])).toThrow(ViewModelError);
    expect(() => view("v", [button("b", "B", onClick({ kind: "message", messageType: "go", scope: "everywhere" }))])).toThrow(/view, page, or session/);
    expect(() => view("v", [button("b", "B", { messageHandlers: [{ messageType: "go", scopes: [], script: { kind: "script", hook: "h" } }] })])).toThrow(/scopes/);
    expect(() => view("v", [button("b", "B", { messageHandlers: [{ messageType: "go", scopes: ["page"], script: { kind: "logout" } }] })])).toThrow(/script action/);
    const parsed = view("v", [button("b", "B", onClick({ kind: "message", messageType: "go" }))]);
    expect(parsed.spec.root.children[0]).toMatchObject({ events: [{ actions: [{ kind: "message", messageType: "go", scope: "page" }] }] });
  });
});

describe("logout action", () => {
  it("asks the host to sign the user out", () => {
    const onLogout = vi.fn();
    const doc = view("main", [button("out", "Sign out", onClick({ kind: "tag-write", path: "plant/last", value: "bye" }, { kind: "logout" }))]);
    const project: HmiProject = {
      name: "p",
      views: [doc],
      pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Main" }, spec: { url: "/", primaryView: "main", docks: [] } })],
    };
    render(<PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location="/" onLogout={onLogout} />);
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it("does nothing on the design canvas", () => {
    const onLogout = vi.fn();
    const doc = view("main", [button("out", "Sign out", onClick({ kind: "logout" }))]);
    render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s" })} designMode onLogout={onLogout} />);
    fireEvent.click(document.querySelector("[data-node-id='out'] button")!);
    expect(onLogout).not.toHaveBeenCalled();
  });
});
