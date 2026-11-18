import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { evaluateExpression } from "./expression";
import { createLinkedTransports, createSession, MemoryTagServer, type ComponentRegistry, type HmiComponentProps } from "./index";
import { parseView, ViewModelError } from "./model";
import { ViewRenderer } from "./ViewRenderer";

/**
 * P4-12 (HMP-118): an expression binding re-evaluates on its poll interval as well as on dependency change.
 * The clock is faked (`setInterval` and `Date`), so each step of the interval is exact.
 */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "probe") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ nodeId, props }: HmiComponentProps) => <span data-testid={nodeId}>{String(props.value ?? "")}</span>,
    };
  },
  list: () => [],
};

function doc(children: unknown[], custom: Record<string, unknown> = {}) {
  return {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "page", path: "page" },
    spec: { width: 200, height: 100, params: {}, custom, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: { direction: "column" }, children } },
  };
}

function probe(id: string, binding: Record<string, unknown>) {
  return { id, kind: "component", name: id, type: "probe", props: { value: "" }, bindings: { value: binding } };
}

const T0 = Date.UTC(2026, 0, 1);

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"], now: T0 });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("expression binding poll", () => {
  it("parses the poll interval and refuses a bad one", () => {
    const parsed = parseView(doc([probe("a", { kind: "expression", expression: "now()", pollMs: 1000 })]));
    const node = parsed.spec.root.children[0]!;
    expect(node.kind === "component" && node.bindings?.value).toEqual({ kind: "expression", expression: "now()", pollMs: 1000 });
    const none = parseView(doc([probe("a", { kind: "expression", expression: "now()", pollMs: 0 })])).spec.root.children[0]!;
    expect(none.kind === "component" && none.bindings?.value).toEqual({ kind: "expression", expression: "now()" });
    for (const bad of [-1, 50, 1.5, "1000"]) {
      expect(() => parseView(doc([probe("a", { kind: "expression", expression: "now()", pollMs: bad })])), String(bad)).toThrow(ViewModelError);
    }
  });

  it("now() is the current time in epoch milliseconds", () => {
    expect(evaluateExpression("now()", { resolve: () => undefined })).toBe(T0);
    expect(() => evaluateExpression("now(1000)", { resolve: () => undefined })).toThrow("now expects no arguments");
  });

  it("re-evaluates on each poll step, and still on dependency change; an unpolled binding does not", async () => {
    const server = new MemoryTagServer();
    server.set("plant/offset", 0);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const view = parseView(
      doc([
        probe("polled", { kind: "expression", expression: "now() + {[default]plant/offset}", pollMs: 1000 }),
        probe("still", { kind: "expression", expression: "now()" }),
      ]),
    );
    const { unmount } = render(<ViewRenderer view={view} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    // The tag's first value arrives (setTimeout is not faked) before the clock moves.
    for (let i = 0; i < 3; i++) {
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
    }
    expect(screen.getByTestId("polled")).toHaveTextContent(String(T0));
    expect(screen.getByTestId("still")).toHaveTextContent(String(T0));

    // Just short of the interval: no re-evaluation.
    await act(async () => {
      vi.advanceTimersByTime(999);
    });
    expect(screen.getByTestId("polled")).toHaveTextContent(String(T0));
    await act(async () => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByTestId("polled")).toHaveTextContent(String(T0 + 1000));
    await act(async () => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByTestId("polled")).toHaveTextContent(String(T0 + 3000));
    // A binding without a poll keeps its first value while nothing it reads changes.
    expect(screen.getByTestId("still")).toHaveTextContent(String(T0));

    // A dependency change re-evaluates between poll steps.
    vi.setSystemTime(T0 + 3500);
    server.set("plant/offset", 7);
    // (Not vi.waitFor: with a fake clock it moves the time while it waits.)
    for (let i = 0; i < 3; i++) {
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
    }
    expect(screen.getByTestId("polled")).toHaveTextContent(String(T0 + 3507));

    unmount();
    expect(vi.getTimerCount()).toBe(0);
    server.close();
  });

  it("polls an expression binding on a view custom property", async () => {
    const view = parseView(
      doc([probe("reader", { kind: "property", source: { scope: "custom", name: "clock" } })], {
        clock: { value: 0, binding: { kind: "expression", expression: "now()", pollMs: 500 } },
      }),
    );
    const [transport] = createLinkedTransports();
    render(<ViewRenderer view={view} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(screen.getByTestId("reader")).toHaveTextContent(String(T0));
    await act(async () => {
      vi.advanceTimersByTime(500);
    });
    expect(screen.getByTestId("reader")).toHaveTextContent(String(T0 + 500));
  });
});
