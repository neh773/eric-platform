import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ScriptActionResult, ScriptHost } from "./bindings";
import { bindMethodArgs, MAX_METHOD_DEPTH, resolveMethodArgs, substituteText } from "./methods";
import { parseView, ViewModelError } from "./model";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import type { ComponentRegistry, HmiComponentProps, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** HMV-114: custom methods a component or a view declares, called by events and by scripts. */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "button") return undefined;
    return { type, title: type, schema: { type: "object" }, Component: ({ props }: HmiComponentProps) => <button type="button">{String(props.text ?? "")}</button> };
  },
  list: () => [],
};

const script = (hook: string) => ({ kind: "script", hook, source: "pass" });
const call = (method: string, args?: Record<string, unknown>, target?: string) => ({ kind: "call-method", method, ...(args ? { args } : {}), ...(target ? { target } : {}) });
const button = (id: string, text: string, extra: Record<string, unknown> = {}) => ({ id, kind: "component", name: id, type: "button", props: { text }, ...extra });
const onClick = (...actions: unknown[]) => ({ events: [{ event: "onClick", actions }] });

function view(children: unknown[], methods?: unknown): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "main", path: "main" },
    spec: { width: 300, height: 200, params: {}, custom: {}, ...(methods ? { methods } : {}), root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children } },
  });
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

type Call = { hook: string; event: string; nodeId: string; payload: unknown };
function host(answer?: (hook: string) => ScriptActionResult | undefined) {
  const calls: Call[] = [];
  const scriptHost: ScriptHost = {
    transform: (_hook, value) => value,
    action(hook, event, nodeId, _view, _params, _custom, payload) {
      calls.push({ hook, event, nodeId, payload });
      const result = answer?.(hook);
      return result ? Promise.resolve(result) : undefined;
    },
  };
  return { scriptHost, calls };
}

function show(doc: ViewDocument, scriptHost: ScriptHost, designMode = false) {
  return render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={scriptHost} designMode={designMode} />);
}

describe("method arguments", () => {
  it("takes each declared parameter from the call or its default, and ignores undeclared ones", () => {
    const def = { params: [{ name: "level", default: 10 }, { name: "label" }], actions: [] };
    expect(resolveMethodArgs(def, { level: 4, extra: 1 })).toEqual({ level: 4, label: null });
    expect(resolveMethodArgs(def, undefined)).toEqual({ level: 10, label: null });
    expect(resolveMethodArgs(def, { level: undefined, label: "x" })).toEqual({ level: 10, label: "x" });
    expect(resolveMethodArgs({ actions: [] }, { a: 1 })).toEqual({});
  });

  it("puts arguments into values, whole placeholders keeping their type, and never into paths or hooks", () => {
    const args = { n: 5, who: "ann", list: [1, 2] };
    expect(substituteText("{{n}}", args)).toBe(5);
    expect(substituteText("level {{n}} for {{who}}", args)).toBe("level 5 for ann");
    expect(substituteText("{{missing}} {{list}}", args)).toBe("{{missing}} [1,2]");
    expect(bindMethodArgs({ kind: "tag-write", path: "[d]{{who}}", value: "{{n}}" }, args)).toEqual({ kind: "tag-write", path: "[d]{{who}}", value: 5 });
    expect(bindMethodArgs({ kind: "navigation", mode: "page", page: "/{{who}}", params: { id: "{{n}}", nested: { s: "{{who}}" } } }, args)).toEqual({
      kind: "navigation",
      mode: "page",
      page: "/{{who}}",
      params: { id: 5, nested: { s: "ann" } },
    });
    expect(bindMethodArgs({ kind: "popup", mode: "open", id: "p{{n}}", view: "v", title: "For {{who}}" }, args)).toMatchObject({ id: "p{{n}}", title: "For ann" });
    expect(bindMethodArgs({ kind: "message", messageType: "m{{n}}", scope: "page", payload: { v: "{{n}}" } }, args)).toMatchObject({ messageType: "m{{n}}", payload: { v: 5 } });
    expect(bindMethodArgs({ kind: "script", hook: "h{{n}}" }, args)).toEqual({ kind: "script", hook: "h{{n}}" });
    expect(bindMethodArgs({ kind: "tag-write", path: "p", value: "{{list}}" }, args)).toMatchObject({ value: "[1,2]" });
  });
});

describe("declaring methods", () => {
  it("parses methods on a component and on the view", () => {
    const doc = view(
      [button("pump", "Pump", { methods: { start: { title: "Start", params: [{ name: "speed", default: 50 }], actions: [script("start-hook"), call("log", { s: "{{speed}}" }, "view")] } } })],
      { log: { actions: [{ kind: "message", messageType: "logged", scope: "page", payload: { s: "{{s}}" } }] } },
    );
    expect(doc.spec.methods!.log!.actions).toHaveLength(1);
    const pump = doc.spec.root.children[0]!;
    expect(pump.kind === "component" && pump.methods!.start!.params).toEqual([{ name: "speed", default: 50 }]);
  });

  it("refuses bad method names, bad or repeated parameters and bad actions", () => {
    const bad = (methods: unknown) => () => view([], methods);
    expect(bad({ "1x": { actions: [] } })).toThrow(ViewModelError);
    expect(bad({ go: { actions: [], params: [{ name: "a b" }] } })).toThrow(/letters, digits/);
    expect(bad({ go: { actions: [], params: [{ name: "a" }, { name: "a" }] } })).toThrow(/declared twice/);
    expect(bad({ go: { actions: [{ kind: "nope" }] } })).toThrow(/unknown/);
    expect(bad({ go: {} })).toThrow(/actions must be an array/);
    expect(bad({ go: { actions: [call("not valid")] } })).toThrow(ViewModelError);
  });
});

describe("calling methods", () => {
  it("runs a component's method (its script sees the owner as the component) with the call's arguments, the defaults filling the rest", () => {
    const { scriptHost, calls } = host();
    show(
      view([
        button("pump", "Pump", { methods: { start: { params: [{ name: "speed", default: 50 }, { name: "who" }], actions: [script("start-hook")] } } }),
        button("go", "Go", onClick(call("start", { who: "ann" }, "pump"))),
        button("self", "Self", { ...onClick(call("start", { speed: 80 })), methods: { start: { params: [{ name: "speed" }], actions: [script("self-hook")] } } }),
      ]),
      scriptHost,
    );
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    expect(calls).toEqual([{ hook: "start-hook", event: "method.start", nodeId: "pump", payload: { speed: 50, who: "ann" } }]);
    calls.length = 0;
    // No target: the component the event is on.
    fireEvent.click(screen.getByRole("button", { name: "Self" }));
    expect(calls).toEqual([{ hook: "self-hook", event: "method.start", nodeId: "self", payload: { speed: 80 } }]);
  });

  it("runs a view method, falls back to it from a component, and substitutes into message payloads", () => {
    const { scriptHost, calls } = host();
    show(
      view(
        [
          button("a", "A", onClick(call("announce", { text: "hi" }, "view"))),
          button("b", "B", onClick(call("announce", { text: "from b" }))),
          button("h", "H", { messageHandlers: [{ messageType: "said", scopes: ["page", "view"], script: script("on-said") }] }),
        ],
        { announce: { params: [{ name: "text", default: "" }], actions: [{ kind: "message", messageType: "said", scope: "view", payload: { text: "{{text}} / {{text}}" } }] } },
      ),
      scriptHost,
    );
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    expect(calls).toEqual([{ hook: "on-said", event: "said", nodeId: "h", payload: { text: "hi / hi" } }]);
    calls.length = 0;
    fireEvent.click(screen.getByRole("button", { name: "B" }));
    expect(calls[0]!.payload).toEqual({ text: "from b / from b" });
  });

  it("does nothing for an unknown method or a disabled action, and nothing in design mode", () => {
    const { scriptHost, calls } = host();
    const doc = view(
      [button("a", "A", onClick(call("missing", {}, "view"), call("hasOwnProperty"), call("constructor"), call("off", {}, "view")))],
      { off: { actions: [{ ...script("off-hook"), enabled: false }] } },
    );
    const { unmount } = show(doc, scriptHost);
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    expect(calls).toEqual([]);
    unmount();
    show(view([button("a", "A", onClick(call("run", {}, "view")))], { run: { actions: [script("run-hook")] } }), scriptHost, true);
    fireEvent.click(document.querySelector("[data-node-id='a'] button")!);
    expect(calls).toEqual([]);
  });

  it("lets a method call another, and stops a method that calls itself", () => {
    const { scriptHost, calls } = host();
    show(
      view([button("go", "Go", onClick(call("outer", { n: 7 }, "view"), call("loop", {}, "view")))], {
        outer: { params: [{ name: "n" }], actions: [call("inner", { m: "{{n}}" }, "view")] },
        inner: { params: [{ name: "m" }], actions: [script("inner-hook")] },
        loop: { actions: [script("loop-hook"), call("loop", {}, "view")] },
      }),
      scriptHost,
    );
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    expect(calls.filter((entry) => entry.hook === "inner-hook")).toEqual([{ hook: "inner-hook", event: "method.inner", nodeId: "root", payload: { m: 7 } }]);
    // Called from an event (depth 0), then once per nested depth until the limit.
    expect(calls.filter((entry) => entry.hook === "loop-hook")).toHaveLength(MAX_METHOD_DEPTH);
  });

  it("is called by a script's answer", async () => {
    const { scriptHost, calls } = host((hook) =>
      hook === "ask" ? { method: [{ kind: "method", method: "call", nodeId: "pump", name: "start", value: { speed: 12 } }] } : undefined,
    );
    show(
      view([
        button("pump", "Pump", { methods: { start: { params: [{ name: "speed" }], actions: [script("started")] } } }),
        button("go", "Go", onClick(script("ask"))),
      ]),
      scriptHost,
    );
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    await act(async () => {});
    expect(calls.map((entry) => entry.hook)).toEqual(["ask", "started"]);
    expect(calls[1]).toMatchObject({ event: "method.start", nodeId: "pump", payload: { speed: 12 } });
  });
});
