import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { pickJsonPath, type HttpRequest, type HttpResult, type NamedQuerySource } from "./bindings";
import { createLinkedTransports, createSession, MemoryTagServer, type ComponentRegistry, type HmiComponentProps } from "./index";
import { parseView, ViewModelError } from "./model";
import { ViewRenderer } from "./ViewRenderer";

/** HMP-114 (doc source: FEATURES HMP-114): an HTTP binding polls a REST endpoint through the gateway and binds parsed JSON. */
const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "probe") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ nodeId, props }: HmiComponentProps) => <span data-testid={nodeId} data-quality={String(props.quality ?? "")}>{String(props.value ?? "none")}</span>,
    };
  },
  list: () => [],
};

function doc(binding: Record<string, unknown>) {
  return {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "page", path: "page" },
    spec: {
      width: 200, height: 100, params: {}, custom: {},
      root: { id: "root", kind: "container", name: "Root", layout: "flex", props: { direction: "column" }, children: [
        { id: "t", kind: "component", name: "t", type: "probe", props: { value: "none" }, bindings: { value: binding } },
      ] },
    },
  };
}

const http = { kind: "http", source: "weather", path: "/temp", query: { unit: "c" }, jsonPath: "data.items.1.temp", pollMs: 1000 };

beforeEach(() => vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] }));
afterEach(() => vi.useRealTimers());

async function flush() {
  for (let i = 0; i < 3; i++) await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
}

function mount(source: NamedQuerySource | undefined, binding: Record<string, unknown> = http) {
  const server = new MemoryTagServer();
  const [transport, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  return render(<ViewRenderer view={parseView(doc(binding))} registry={registry} transport={transport} session={createSession({ id: "s" })} querySource={source} />);
}

describe("HTTP binding", () => {
  it("parses and refuses an unsafe address or a bad interval", () => {
    expect(() => parseView(doc(http))).not.toThrow();
    for (const bad of [{ path: "//evil.example/x" }, { path: "temp" }, { path: "/../x" }, { path: "/a?b=1" }, { source: "Has Space" }, { pollMs: 10 }, { query: { a: { b: 1 } } }, { bidirectional: true }]) {
      expect(() => parseView(doc({ ...http, ...bad })), JSON.stringify(bad)).toThrow(ViewModelError);
    }
  });

  it("picks a value out of the JSON by path", () => {
    const body = { data: { items: [{ temp: 1 }, { temp: 2 }] } };
    expect(pickJsonPath(body, "data.items.1.temp")).toBe(2);
    expect(pickJsonPath(body, "data.missing")).toBeUndefined();
    expect(pickJsonPath(body, "data.items.9")).toBeUndefined();
    expect(pickJsonPath(body, undefined)).toBe(body);
    expect(pickJsonPath({}, "__proto__")).toBeUndefined();
  });

  it("fetches through the source and binds the picked value and polls on the interval", async () => {
    const seen: HttpRequest[] = [];
    let temp = 20;
    const source: NamedQuerySource = {
      execute: () => ({ columns: [], rows: [] }),
      fetchHttp: async (request): Promise<HttpResult> => {
        seen.push(request);
        return { ok: true, body: { data: { items: [{ temp: 0 }, { temp }] } } };
      },
    };
    const { unmount } = mount(source);
    await flush();
    expect(screen.getByTestId("t")).toHaveTextContent("20");
    expect(seen).toEqual([{ source: "weather", path: "/temp", query: { unit: "c" } }]);
    temp = 21;
    await act(async () => { vi.advanceTimersByTime(1000); });
    await flush();
    expect(screen.getByTestId("t")).toHaveTextContent("21");
    expect(seen).toHaveLength(2);
    unmount();
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(seen).toHaveLength(2);
  });

  it("is in the error state when the request fails or the path is missing or there is no gateway source", async () => {
    const failing: NamedQuerySource = { execute: () => ({ columns: [], rows: [] }), fetchHttp: async () => ({ ok: false, error: "upstream answered 503" }) };
    const first = mount(failing);
    await flush();
    expect(screen.getByTestId("t")).toHaveTextContent("none");
    first.unmount();
    const wrongShape: NamedQuerySource = { execute: () => ({ columns: [], rows: [] }), fetchHttp: async () => ({ ok: true, body: { other: 1 } }) };
    const second = mount(wrongShape);
    await flush();
    expect(screen.getByTestId("t")).toHaveTextContent("none");
    second.unmount();
    mount(undefined);
    await flush();
    expect(screen.getByTestId("t")).toHaveTextContent("none");
  });
});
