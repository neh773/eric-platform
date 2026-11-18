import { describe, expect, it } from "vitest";
import { applyBindings, historyAggregateUrl, historyRequests, parseHistoryWindows, queryRequests, resolveContext, resolveTagPath } from "./bindings";
import { parseBinding, parseView, ViewModelError } from "./model";

/** Property, expression, tag indirection, and the named-query interface.
 */

function viewWith(binding: unknown, props: Record<string, unknown> = { text: "authored" }) {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Bind", path: "bind" },
    spec: {
      width: 100,
      height: 40,
      params: { line: { value: 3 } },
      custom: { area: { value: "north" } },
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "flex",
        props: { direction: "row" },
        children: [
          { id: "source", kind: "component", name: "Source", type: "label", props: { text: "from-prop" } },
          { id: "label", kind: "component", name: "Label", type: "label", props, bindings: { text: binding } },
        ],
      },
    },
  });
}

describe("binding kinds", () => {
  it("reads a same-view property and a custom indirect tag", () => {
    const property = viewWith({ kind: "property", source: { scope: "prop", nodeId: "source", prop: "text" } });
    const label = property.spec.root.children[1];
    if (label?.kind !== "component") throw new Error("fixture");
    expect(applyBindings(label, new Map(), resolveContext(property)).props.text).toBe("from-prop");

    const indirect = viewWith({
      kind: "tag",
      mode: "indirect",
      path: "plant/{area}/flow",
      references: { area: { source: "custom", name: "area" } },
    });
    const bound = indirect.spec.root.children[1];
    if (bound?.kind !== "component" || bound.bindings?.text?.kind !== "tag") throw new Error("fixture");
    expect(resolveTagPath(bound.bindings.text, resolveContext(indirect))).toBe("plant/north/flow");
  });

  it("does not write an expression result back and keeps the authored value when a query source is missing", () => {
    const expression = viewWith({ kind: "expression", expression: "1 + 2" });
    const label = expression.spec.root.children[1];
    if (label?.kind !== "component") throw new Error("fixture");
    const resolved = applyBindings(label, new Map(), resolveContext(expression));
    expect(resolved.props.text).toBe(3);
    expect(label.bindings?.text && "bidirectional" in label.bindings.text).toBe(false);

    const query = viewWith({ kind: "query", path: "wells", returnFormat: "scalar" });
    const queryLabel = query.spec.root.children[1];
    if (queryLabel?.kind !== "component") throw new Error("fixture");
    const missing = applyBindings(queryLabel, new Map(), resolveContext(query), { hasQuerySource: false });
    expect(missing.props.text).toBe("authored");
    expect(missing.code).toBe("bad");
  });

  it("resolves query parameters and formats a scalar, skipping a disabled query", () => {
    const enabled = viewWith({
      kind: "query",
      path: "wells",
      returnFormat: "scalar",
      parameters: { line: { expression: "{view.params.line}" } },
    });
    const disabled = viewWith({ kind: "query", path: "hidden", enabled: false, parameters: { line: { value: 1 } } });
    const ctx = resolveContext(enabled);
    const requests = queryRequests(enabled, ctx, new Map());
    expect(requests).toEqual([{ key: "label.text", path: "wells", parameters: { line: 3 }, returnFormat: "scalar", pollMs: 0 }]);
    expect(queryRequests(disabled, resolveContext(disabled), new Map())).toEqual([]);
    const label = enabled.spec.root.children[1];
    if (label?.kind !== "component") throw new Error("fixture");
    const applied = applyBindings(label, new Map(), ctx, {
      hasQuerySource: true,
      queries: new Map([["label.text", { ok: true, value: 8 }]]),
    });
    expect(applied.props.text).toBe(8);
  });

  it("returns aggregate windows for a history binding and refuses a range that is too wide", () => {
    const history = viewWith(
      { kind: "history", path: "[default]plant/level", rangeMs: 3_600_000, intervalMs: 3_600_000, aggregate: "LastValue" },
      { data: [] },
    );
    const label = history.spec.root.children[1];
    if (label?.kind !== "component" || label.bindings?.text?.kind !== "history") throw new Error("fixture");
    const requests = historyRequests(history);
    expect(requests).toEqual([{ key: "label.text", path: "[default]plant/level", rangeMs: 3_600_000, intervalMs: 3_600_000, aggregate: "LastValue", pollMs: 0 }]);
    expect(historyAggregateUrl(requests[0]!, 5_000)).toBe(
      "/api/v1/history/aggregate?tagPath=%5Bdefault%5Dplant%2Flevel&from=-3595000&to=5000&interval=3600000&aggregate=LastValue",
    );
    const waiting = applyBindings(label, new Map(), resolveContext(history));
    expect(waiting.props.text).toBeUndefined();
    expect(waiting.code).toBe("uncertain");
    const windows = [{ start: "t0", end: "t1", value: 14, rows: 4 }];
    const applied = applyBindings(label, new Map(), resolveContext(history), {
      histories: new Map([["label.text", { ok: true, value: windows }]]),
    });
    expect(applied.props.text).toEqual(windows);
    expect(parseHistoryWindows({ windows, quality: "Bad_AccessDenied" }).ok).toBe(false);
    expect(parseHistoryWindows({ windows }).ok).toBe(true);
    expect(() => parseBinding({ kind: "history", path: "a", rangeMs: 10_001, intervalMs: 1 }, "binding")).toThrow(ViewModelError);
    expect(() => parseBinding({ kind: "history", path: "a", rangeMs: 60_000, intervalMs: 60_000, aggregate: "Median" }, "binding")).toThrow(/aggregate/);
  });
});
