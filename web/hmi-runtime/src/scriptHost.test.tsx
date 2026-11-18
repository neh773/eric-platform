import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { applyBindings, resolveContext } from "./bindings";
import { parseView } from "./model";
import { GatewayScriptHost, type ScriptClientFrame, type ScriptResultFrame } from "./scriptHost";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import { ScriptPending, ScriptUnavailable } from "./transforms";
import type { HmiComponentProps, NavigationAction, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** D-5a: script transforms and event scripts run on the gateway through `GatewayScriptHost`. */

type Answer = (frame: ScriptClientFrame) => Omit<ScriptResultFrame, "type" | "requestId"> | null;

/** A fake gateway session socket: answers `hello`, records `script` frames, and answers them with [answer]. */
function fakeGateway(answer: Answer) {
  const frames: ScriptClientFrame[] = [];
  const held: Array<{ frame: ScriptClientFrame; reply: (body: Omit<ScriptResultFrame, "type" | "requestId">) => void }> = [];
  let sockets = 0;
  let serverSide: StringTransport | null = null;
  const connect = async () => {
    sockets += 1;
    const [client, server] = createLinkedTransports();
    serverSide = server;
    server.onMessage((data) => {
      const frame = JSON.parse(data) as { type: string; nonce?: string };
      if (frame.type === "hello") {
        server.send(JSON.stringify({ type: "welcome", nonce: frame.nonce, sessionId: "s", resumeToken: "t", serverTime: 0 }));
        return;
      }
      if (frame.type !== "script") return;
      const call = frame as ScriptClientFrame;
      frames.push(call);
      const reply = (body: Omit<ScriptResultFrame, "type" | "requestId">) =>
        server.send(JSON.stringify({ type: "scriptResult", requestId: call.requestId, ...body }));
      const body = answer(call);
      if (body) reply(body);
      else held.push({ frame: call, reply });
    });
    return client;
  };
  return {
    connect,
    frames,
    held,
    sockets: () => sockets,
    drop: () => serverSide?.close(),
  };
}

function nextTick() {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function settle(host: GatewayScriptHost) {
  await new Promise<void>((resolve) => {
    const off = host.subscribe(() => {
      off();
      resolve();
    });
  });
}

const scriptView = (hook = "double"): ViewDocument =>
  parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Main", path: "main" },
    spec: {
      width: 200,
      height: 80,
      params: { level: { value: 21 } },
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "flex",
        props: { direction: "column" },
        children: [
          {
            id: "label",
            kind: "component",
            name: "Label",
            type: "label",
            props: { text: "authored" },
            bindings: {
              text: {
                kind: "property",
                source: { scope: "param", name: "level" },
                transforms: [{ kind: "script", hook }, { kind: "format", type: "numeric", mode: "pattern", pattern: "0.0" }],
              },
            },
          },
          {
            id: "go",
            kind: "component",
            name: "Go",
            type: "button",
            props: { text: "Go" },
            events: [{ event: "onClick", actions: [{ kind: "script", hook: "goPumps" }] }],
          },
        ],
      },
    },
  });

const registry = {
  get(type: string) {
    if (type !== "label" && type !== "button") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" as const },
      Component: ({ props }: HmiComponentProps) =>
        type === "button" ? <button type="button">{String(props.text ?? "")}</button> : <span data-testid="label">{String(props.text ?? "")}</span>,
    };
  },
  list: () => [],
};

describe("GatewayScriptHost", () => {
  it("sends a transform call and answers from the cache once the gateway replies", async () => {
    const gateway = fakeGateway((frame) => (frame.call === "transform" ? { ok: true, value: (frame.value as number) * 2 } : null));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    const first = host.transform("double", 21, "good", 5, "label", "text", "Main");
    expect(first).toBeInstanceOf(ScriptPending);
    expect((first as ScriptPending).hasLast).toBe(false);
    await settle(host);
    expect(host.transform("double", 21, "good", 6, "label", "text", "Main")).toBe(42);
    expect(gateway.frames).toHaveLength(1);
    expect(gateway.frames[0]).toMatchObject({ call: "transform", project: "plant", view: "Main", hook: "double", value: 21, quality: "good", nodeId: "label", prop: "text" });

    // A new input: the previous answer is shown while the new one is on its way.
    const next = host.transform("double", 30, "good", 7, "label", "text", "Main");
    expect(next).toBeInstanceOf(ScriptPending);
    expect((next as ScriptPending).last).toBe(42);
    await settle(host);
    expect(host.transform("double", 30, "good", 7, "label", "text", "Main")).toBe(60);
    expect(gateway.sockets()).toBe(1);
    host.close();
  });

  it("turns a script error into a failed transform and keeps it for the same input", async () => {
    const gateway = fakeGateway(() => ({ ok: false, error: "ValueError: bad input" }));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    host.transform("boom", 1, "good", 0, "label", "text");
    await settle(host);
    expect(() => host.transform("boom", 1, "good", 0, "label", "text")).toThrow("ValueError: bad input");
    expect(gateway.frames).toHaveLength(1);
    host.close();
  });

  it("treats a hook with no saved source as not connected, without asking again for the same input", async () => {
    const gateway = fakeGateway(() => ({ ok: false, error: "unknown script hook 'shout'", code: "unknown_hook" }));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    host.transform("shout", 1, "good", 0, "label", "text");
    await settle(host);
    expect(host.transform("shout", 1, "good", 0, "label", "text")).toBeInstanceOf(ScriptUnavailable);
    expect(gateway.frames).toHaveLength(1);
    host.close();
  });

  it("is not connected when the gateway socket cannot be opened, and tries again later", async () => {
    let attempts = 0;
    const host = new GatewayScriptHost({
      connect: async () => {
        attempts += 1;
        throw new Error("gateway socket unavailable");
      },
      project: "plant",
      retryMs: 20,
    });
    expect(host.transform("double", 1, "good", 0, "label", "text")).toBeInstanceOf(ScriptPending);
    await settle(host);
    expect(host.transform("double", 1, "good", 0, "label", "text")).toBeInstanceOf(ScriptUnavailable);
    expect(attempts).toBe(1);
    await settle(host); // the retry wait ends
    host.transform("double", 1, "good", 0, "label", "text");
    await nextTick();
    expect(attempts).toBe(2);
    host.close();
  });

  it("backs off once for the whole host: many properties make one connect try per wait, and the wait doubles", async () => {
    let tries = 0;
    const host = new GatewayScriptHost({
      connect: async () => {
        tries += 1;
        throw new Error("gateway socket unavailable");
      },
      project: "plant",
      retryMs: 200,
      maxRetryMs: 5_000,
    });
    const all = () => Array.from({ length: 10 }, (_, index) => host.transform("double", index, "good", 0, `n${index}`, "text"));
    const allUnavailable = async () => {
      for (let round = 0; round < 50; round += 1) {
        if (all().every((result) => result instanceof ScriptUnavailable)) return true;
        await nextTick();
      }
      return false;
    };
    expect(await allUnavailable()).toBe(true);
    expect(tries).toBe(1);
    await new Promise((resolve) => setTimeout(resolve, 230)); // the first wait (200 ms) ends
    expect(await allUnavailable()).toBe(true);
    expect(tries).toBe(2);
    await new Promise((resolve) => setTimeout(resolve, 230)); // inside the doubled wait (400 ms): no new try
    expect(await allUnavailable()).toBe(true);
    expect(tries).toBe(2);
    host.close();
  });

  it("asks again after a busy answer instead of keeping it as an error", async () => {
    let busy = true;
    const gateway = fakeGateway((frame) => (busy ? { ok: false, error: "busy", code: "busy" } : { ok: true, value: frame.call === "transform" ? (frame.value as number) * 2 : null }));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant", retryMs: 20 });
    host.transform("double", 4, "good", 0, "label", "text");
    await settle(host);
    // Busy is not the script's answer: still pending, no error, and no second call before the wait ends.
    expect(host.transform("double", 4, "good", 0, "label", "text")).toBeInstanceOf(ScriptPending);
    expect(gateway.frames).toHaveLength(1);
    busy = false;
    await settle(host); // the wait ends
    host.transform("double", 4, "good", 0, "label", "text");
    await settle(host);
    expect(host.transform("double", 4, "good", 0, "label", "text")).toBe(8);
    expect(gateway.frames).toHaveLength(2);
    host.close();
  });

  describe("a wake timer that fires before Date.now() reaches its deadline", () => {
    // Timers run on the event loop's monotonic clock, which can be up to a millisecond ahead of Date.now() when
    // they fire. A wake that notified then found the retry time still in the future, made no call, and armed no
    // new wake, so the binding stayed pending (busy) or not connected for good (review-2 F2).
    let now = 1_000_000;
    const useEarlyTimers = () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      vi.spyOn(Date, "now").mockImplementation(() => now);
    };
    afterEach(() => {
      vi.useRealTimers();
      vi.restoreAllMocks();
    });

    it("asks again after a busy answer", async () => {
      useEarlyTimers();
      let busy = true;
      const gateway = fakeGateway(() => (busy ? { ok: false, error: "busy", code: "busy" } : { ok: true, value: 8 }));
      const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant", retryMs: 20 });
      const evaluate = () => host.transform("double", 4, "good", 0, "label", "text");
      // Like a view: the binding is evaluated again only when the host notifies.
      host.subscribe(() => {
        evaluate();
      });
      evaluate();
      await vi.advanceTimersByTimeAsync(0);
      expect(gateway.frames).toHaveLength(1);
      busy = false;
      now += 19; // the wake fires at +20 ms on the timer clock while Date.now() says +19 ms
      await vi.advanceTimersByTimeAsync(20);
      now += 5;
      await vi.advanceTimersByTimeAsync(5);
      expect(gateway.frames).toHaveLength(2);
      expect(evaluate()).toBe(8);
      host.close();
    });

    it("tries the gateway socket again after the reconnect wait", async () => {
      useEarlyTimers();
      let attempts = 0;
      const host = new GatewayScriptHost({
        connect: async () => {
          attempts += 1;
          throw new Error("gateway socket unavailable");
        },
        project: "plant",
        retryMs: 20,
      });
      const evaluate = () => host.transform("double", 1, "good", 0, "label", "text");
      host.subscribe(() => {
        evaluate();
      });
      evaluate();
      await vi.advanceTimersByTimeAsync(0);
      expect(evaluate()).toBeInstanceOf(ScriptUnavailable);
      expect(attempts).toBe(1);
      now += 19;
      await vi.advanceTimersByTimeAsync(20);
      expect(attempts).toBe(1); // a wake before the wait is over does not try yet
      now += 5;
      await vi.advanceTimersByTimeAsync(5);
      expect(attempts).toBe(2);
      host.close();
    });
  });

  it("uses a channel on a socket that already said hello, and closing the host leaves that socket open", async () => {
    const [client, server] = createLinkedTransports();
    const seen: Array<{ type: string }> = [];
    server.onMessage((data) => {
      const frame = JSON.parse(data) as ScriptClientFrame & { type: string };
      seen.push(frame);
      if (frame.type === "script") server.send(JSON.stringify({ type: "scriptResult", requestId: frame.requestId, ok: true, value: "x" }));
    });
    let closedSocket = false;
    client.onClose(() => {
      closedSocket = true;
    });
    const detached: string[] = [];
    const channel: StringTransport = {
      send: (data) => client.send(data),
      onMessage: (handler) => client.onMessage(handler),
      onClose: (handler) => client.onClose(handler),
      close: () => detached.push("closed"),
    };
    const host = new GatewayScriptHost({ connect: async () => channel, welcomed: true, project: "plant" });
    host.transform("label", 1, "good", 0, "label", "text");
    await settle(host);
    expect(host.transform("label", 1, "good", 0, "label", "text")).toBe("x");
    expect(seen.map((frame) => frame.type)).toEqual(["script"]);
    host.close();
    expect(detached).toEqual(["closed"]);
    expect(closedSocket).toBe(false);
  });

  it("keeps at most maxInFlight calls on the socket and queues the rest", async () => {
    const gateway = fakeGateway(() => null);
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant", maxInFlight: 2 });
    for (let index = 0; index < 5; index += 1) host.transform("slow", index, "good", 0, `n${index}`, "text");
    await waitFor(() => expect(gateway.frames).toHaveLength(2));
    gateway.held.shift()!.reply({ ok: true, value: "a" });
    await waitFor(() => expect(gateway.frames).toHaveLength(3));
    for (const pending of gateway.held.splice(0)) pending.reply({ ok: true, value: "b" });
    await waitFor(() => expect(gateway.frames).toHaveLength(5));
    expect(gateway.sockets()).toBe(1);
    host.close();
  });

  it("fails calls in flight when the socket closes", async () => {
    const gateway = fakeGateway(() => null);
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant", retryMs: 60_000 });
    host.transform("slow", 1, "good", 0, "label", "text");
    await waitFor(() => expect(gateway.frames).toHaveLength(1));
    gateway.drop();
    await settle(host);
    expect(host.transform("slow", 1, "good", 0, "label", "text")).toBeInstanceOf(ScriptUnavailable);
    host.close();
  });

  it("runs an event script and returns the navigations it asked for", async () => {
    const navigate: NavigationAction = { kind: "navigation", mode: "page", page: "/pumps", params: { line: 3 } };
    const gateway = fakeGateway((frame) => (frame.call === "event" ? { ok: true, value: null, actions: [navigate, { kind: "popup" } as never] } : null));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    const result = await host.action("goPumps", "onClick", "go", "Main", { line: 3 }, {});
    expect(result).toEqual({ navigate: [navigate] });
    const popup = { kind: "popup" as const, mode: "open" as const, id: "hi", view: "Popup/Hi", title: "Hello" };
    const message = { kind: "message" as const, messageType: "refresh", scope: "session" as const, payload: { ok: true } };
    const richer = fakeGateway((frame) => (frame.call === "event" ? { ok: true, value: null, actions: [popup, message] } : null));
    const richerHost = new GatewayScriptHost({ connect: richer.connect, project: "plant" });
    expect(await richerHost.action("go", "onClick", "go", "Main", {}, {})).toEqual({ popup: [popup], message: [message] });
    richerHost.close();
    expect(gateway.frames[0]).toMatchObject({ call: "event", hook: "goPumps", event: "onClick", nodeId: "go", view: "Main", params: { line: 3 } });
    host.close();
  });

  it("sends the DOM event payload with an event script call (D-5)", async () => {
    const gateway = fakeGateway((frame) => (frame.call === "event" ? { ok: true, value: null } : null));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    await host.action("clicked", "onClick", "go", "Main", {}, {}, { type: "click", clientX: 4, clientY: 5 });
    await host.action("started", "onStartup", "root", "Main", {}, {});
    expect(gateway.frames[0]).toMatchObject({ call: "event", hook: "clicked", payload: { type: "click", clientX: 4, clientY: 5 } });
    expect(gateway.frames[1]).not.toHaveProperty("payload");
    host.close();
  });
});

describe("script transforms in bindings", () => {
  it("shows the authored value at uncertain quality while pending, then the answer", async () => {
    const gateway = fakeGateway((frame) => (frame.call === "transform" ? { ok: true, value: (frame.value as number) * 2 } : null));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    const view = scriptView();
    const node = view.spec.root.kind === "container" ? view.spec.root.children[0] : undefined;
    if (node?.kind !== "component") throw new Error("fixture");
    const pending = applyBindings(node, new Map(), resolveContext(view), { scriptHost: host, viewPath: "main" });
    expect(pending.props.text).toBe("authored");
    expect(pending.code).toBe("uncertain");
    await settle(host);
    const resolved = applyBindings(node, new Map(), resolveContext(view), { scriptHost: host, viewPath: "main" });
    expect(resolved.props.text).toBe("42.0");
    expect(resolved.code).toBe("good");
    host.close();
  });

  it("is a not-connected quality, not an exception, without a script host", () => {
    const view = scriptView();
    const node = view.spec.root.kind === "container" ? view.spec.root.children[0] : undefined;
    if (node?.kind !== "component") throw new Error("fixture");
    const resolved = applyBindings(node, new Map(), resolveContext(view), {});
    expect(resolved.props.text).toBe("authored");
    expect(resolved.code).toBe("bad_not_connected");
  });

  it("renders the answer without blocking, and follows an event script's navigation", async () => {
    const gateway = fakeGateway((frame) => {
      if (frame.call === "transform") return { ok: true, value: (frame.value as number) * 2 };
      return {
        ok: true,
        value: null,
        actions: [
          { kind: "navigation", mode: "page", page: "/pumps" },
          { kind: "popup", mode: "open", id: "hi", view: "Popup/Hi", title: "Hello" },
        ],
      };
    });
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const navigations: NavigationAction[] = [];
    render(
      <ViewRenderer
        view={scriptView()}
        registry={registry}
        transport={transport}
        session={createSession({ id: "s" })}
        scriptHost={host}
        onNavigate={(action) => navigations.push(action)}
      />,
    );
    expect(screen.getByTestId("label")).toHaveTextContent("authored");
    await waitFor(() => expect(screen.getByTestId("label")).toHaveTextContent("42.0"));
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    await waitFor(() => expect(navigations).toEqual([{ kind: "navigation", mode: "page", page: "/pumps" }]));
    expect(screen.getByRole("dialog")).toHaveTextContent("Hello");
    host.close();
    server.close();
  });
  it("drops an event script's navigation when its view has unmounted before the answer", async () => {
    const gateway = fakeGateway((frame) => (frame.call === "transform" ? { ok: true, value: 1 } : null));
    const host = new GatewayScriptHost({ connect: gateway.connect, project: "plant" });
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const navigations: NavigationAction[] = [];
    const mounted = render(
      <ViewRenderer
        view={scriptView()}
        registry={registry}
        transport={transport}
        session={createSession({ id: "s" })}
        scriptHost={host}
        onNavigate={(action) => navigations.push(action)}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    await waitFor(() => expect(gateway.held.some((held) => held.frame.call === "event")).toBe(true));
    mounted.unmount();
    gateway.held.find((held) => held.frame.call === "event")!.reply({ ok: true, value: null, actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(navigations).toEqual([]);
    host.close();
    server.close();
  });
});
