import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { createSession, MemoryTagServer, createLinkedTransports, type ComponentRegistry, type HmiComponentProps } from "./index";
import { parseView } from "./model";
import { ViewRenderer } from "./ViewRenderer";

/**
 * PRF-011: a tag update renders only the components whose bindings read a changed tag. A page of 300 tag-bound
 * components re-rendered on every update was what held 50 sessions over the 250 ms target.
 */

const renders = new Map<string, number>();

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "probe") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ nodeId, props }: HmiComponentProps) => {
        renders.set(nodeId, (renders.get(nodeId) ?? 0) + 1);
        return <span data-testid={nodeId}>{String(props.value ?? "")}</span>;
      },
    };
  },
  list: () => [],
};

function probe(id: string, binding: Record<string, unknown>) {
  return { id, kind: "component", name: id, type: "probe", props: { value: "" }, bindings: { value: binding } };
}

function view(path: string, children: unknown[]) {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: { width: 200, height: 100, params: {}, root: { id: `${path}-root`, kind: "container", name: "Root", layout: "flex", props: { direction: "column" }, children } },
  });
}

async function flush() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

describe("tag updates render only what read the tag", () => {
  it("re-renders a tag-bound or expression-bound component when its tag changes, and leaves the others", async () => {
    renders.clear();
    const server = new MemoryTagServer();
    server.setMany([
      { path: "plant/a", value: 1 },
      { path: "plant/b", value: 10 },
    ]);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const page = view("page", [
      probe("a", { kind: "tag", mode: "direct", path: "plant/a" }),
      probe("b", { kind: "tag", mode: "direct", path: "plant/b" }),
      probe("twice-b", { kind: "expression", expression: "{[default]plant/b} * 2" }),
      { id: "fixed", kind: "component", name: "fixed", type: "probe", props: { value: "still" } },
    ]);
    render(<ViewRenderer view={page} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(await screen.findByText("10")).toBeInTheDocument();
    expect(screen.getByTestId("twice-b")).toHaveTextContent("20");
    await flush();
    const before = new Map(renders);

    server.set("plant/a", 2);
    await flush();
    expect(screen.getByTestId("a")).toHaveTextContent("2");
    expect(renders.get("a")).toBeGreaterThan(before.get("a")!);
    expect(renders.get("b")).toBe(before.get("b"));
    expect(renders.get("twice-b")).toBe(before.get("twice-b"));
    expect(renders.get("fixed")).toBe(before.get("fixed"));

    const afterA = new Map(renders);
    server.set("plant/b", 11);
    await flush();
    expect(screen.getByTestId("b")).toHaveTextContent("11");
    expect(screen.getByTestId("twice-b")).toHaveTextContent("22");
    expect(renders.get("a")).toBe(afterA.get("a"));
    expect(renders.get("fixed")).toBe(afterA.get("fixed"));
    server.close();
  });

  it("still updates an embedded view's components when only their tag changes", async () => {
    renders.clear();
    const server = new MemoryTagServer();
    server.set("plant/inner", 5);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const inner = view("inner", [probe("inner-value", { kind: "tag", mode: "direct", path: "plant/inner" })]);
    const page = view("page", [{ id: "host", kind: "component", name: "Host", type: "embedded-view", props: { path: "inner", params: {} } }]);
    render(<ViewRenderer view={page} views={[page, inner]} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(await screen.findByText("5")).toBeInTheDocument();
    server.set("plant/inner", 6);
    expect(await screen.findByText("6")).toBeInTheDocument();
    server.close();
  });

  it("renders a component again when a view param it reads changes, whatever its tags did", async () => {
    renders.clear();
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const page = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "page", path: "page" },
      spec: {
        width: 200,
        height: 100,
        params: { label: { value: "first" } },
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction: "column" },
          children: [probe("param", { kind: "property", source: { scope: "param", name: "label" } })],
        },
      },
    });
    const { rerender } = render(
      <ViewRenderer view={page} paramOverrides={{ label: "first" }} registry={registry} transport={transport} session={createSession({ id: "s" })} />,
    );
    expect(await screen.findByText("first")).toBeInTheDocument();
    rerender(<ViewRenderer view={page} paramOverrides={{ label: "second" }} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(await screen.findByText("second")).toBeInTheDocument();
    server.close();
  });
});
