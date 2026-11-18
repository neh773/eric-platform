import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createSession, MemoryTagServer, createLinkedTransports, type ComponentRegistry, type HmiComponentProps } from "./index";
import { parseView } from "./model";
import { ViewRenderer } from "./ViewRenderer";

/**
 * P4-08 (HMP-111): the binding framework's quality overlay and update coalescing, on top of the bindings and
 * transforms that were already built.
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

function view(children: unknown[]) {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "page", path: "page" },
    spec: { width: 200, height: 100, params: {}, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: { direction: "column" }, children } },
  });
}

function probe(id: string, binding: Record<string, unknown>) {
  return { id, kind: "component", name: id, type: "probe", props: { value: "" }, bindings: { value: binding } };
}

async function tick() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function overlayOf(id: string): HTMLElement | null {
  return document.querySelector(`[data-node-id='${id}'] > .oip-quality-overlay`);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("quality overlay", () => {
  it("covers a component whose bound value is Bad, names the quality, and clears when it is Good again", async () => {
    const server = new MemoryTagServer();
    server.set("plant/level", 40);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(
      <ViewRenderer
        view={view([probe("level", { kind: "tag", mode: "direct", path: "plant/level" }), probe("quiet", { kind: "tag", mode: "direct", path: "plant/level", overlayOptOut: true })])}
        registry={registry}
        transport={transport}
        session={createSession({ id: "s" })}
      />,
    );
    expect(await screen.findAllByText("40")).toHaveLength(2);
    await tick();
    expect(overlayOf("level")).toBeNull();

    server.set("plant/level", 40, "bad", "bad_not_connected");
    await tick();
    await tick();
    const overlay = overlayOf("level");
    expect(overlay).not.toBeNull();
    expect(overlay!.dataset.quality).toBe("bad");
    expect(overlay!.dataset.code).toBe("bad_not_connected");
    expect(overlay).toHaveTextContent("Not connected");
    expect(screen.getByTestId("level").closest(".oip-node")).toHaveAttribute("data-quality", "bad");
    // A binding that opts out of the overlay still shows the value, without the overlay.
    expect(overlayOf("quiet")).toBeNull();
    expect(screen.getByTestId("quiet")).toHaveTextContent("40");

    server.set("plant/level", 41, "uncertain", "uncertain");
    await tick();
    await tick();
    expect(overlayOf("level")!.dataset.quality).toBe("uncertain");

    server.set("plant/level", 42);
    await tick();
    await tick();
    expect(overlayOf("level")).toBeNull();
    expect(screen.getByTestId("level")).toHaveTextContent("42");
    server.close();
  });

  it("parses the overlay opt-out on every binding kind", () => {
    const parsed = view([
      probe("t", { kind: "tag", mode: "direct", path: "a", overlayOptOut: true }),
      probe("p", { kind: "property", source: { scope: "param", name: "x" }, overlayOptOut: true }),
      probe("e", { kind: "expression", expression: "1", overlayOptOut: true }),
      probe("q", { kind: "query", path: "q", overlayOptOut: true }),
    ]);
    for (const child of parsed.spec.root.children) expect(child.kind === "component" && child.bindings!.value!.overlayOptOut).toBe(true);
    expect(() => view([probe("t", { kind: "tag", mode: "direct", path: "a", overlayOptOut: "yes" })])).toThrow(/overlayOptOut must be a boolean/);
  });
});

describe("update coalescing", () => {
  it("renders a burst of updates once, with the last value, at the next frame", async () => {
    vi.useFakeTimers();
    const frames: FrameRequestCallback[] = [];
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => frames.push(callback));
    vi.stubGlobal("cancelAnimationFrame", () => {});
    renders.clear();
    const server = new MemoryTagServer();
    server.set("plant/flow", 0);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={view([probe("flow", { kind: "tag", mode: "direct", path: "plant/flow" })])} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    const step = () => act(async () => { await vi.advanceTimersByTimeAsync(0); });
    // The first value arrives at the first frame. The fallback timer is not allowed to elapse.
    await step();
    await act(async () => frames.splice(0).forEach((frame) => frame(0)));
    expect(screen.getByTestId("flow")).toHaveTextContent("0");
    const before = renders.get("flow")!;

    // Twenty updates, each in its own socket message and task, before the next frame.
    for (let value = 1; value <= 20; value += 1) {
      server.set("plant/flow", value);
      await step();
    }
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    expect(renders.get("flow")).toBe(before);
    expect(screen.getByTestId("flow")).toHaveTextContent("0");
    expect(frames).toHaveLength(1);

    await act(async () => frames.splice(0).forEach((frame) => frame(16)));
    expect(screen.getByTestId("flow")).toHaveTextContent("20");
    expect(renders.get("flow")).toBe(before + 1);
    server.close();
    vi.useRealTimers();
  });

  it("still delivers updates when no frame comes (a hidden page)", async () => {
    vi.stubGlobal("requestAnimationFrame", () => 1);
    vi.stubGlobal("cancelAnimationFrame", () => {});
    const server = new MemoryTagServer();
    server.set("plant/flow", 5);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={view([probe("flow", { kind: "tag", mode: "direct", path: "plant/flow" })])} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(await screen.findByText("5", {}, { timeout: 1000 })).toBeInTheDocument();
    server.set("plant/flow", 6);
    expect(await screen.findByText("6", {}, { timeout: 1000 })).toBeInTheDocument();
    server.close();
  });
});
