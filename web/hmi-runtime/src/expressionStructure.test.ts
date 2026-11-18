import { describe, expect, it } from "vitest";
import { applyBindings, collectSubscriptions, expressionPollMs, resolveContext } from "./bindings";
import { parseBinding, parseView, ViewModelError } from "./model";
import type { TagUpdate } from "./tags";

/** HMP-112: expression structure binding. doc source: FEATURES HMP-112 */

function viewWith(binding: unknown) {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "S", path: "s" },
    spec: {
      width: 100,
      height: 40,
      params: { limit: { value: 80 } },
      custom: {},
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "flex",
        props: { direction: "row" },
        children: [{ id: "gauge", kind: "component", name: "Gauge", type: "label", props: { text: "authored" }, bindings: { values: binding } }],
      },
    },
  });
}

function update(path: string, value: number, code: TagUpdate["code"] = "good"): TagUpdate {
  return { path, value, quality: code === "good" ? "good" : "bad", code, timestamp: 100 };
}

function resolve(binding: unknown, values: Array<[string, TagUpdate]> = []) {
  const view = viewWith(binding);
  const node = view.spec.root.children[0];
  if (node?.kind !== "component") throw new Error("fixture");
  return { view, node, result: applyBindings(node, new Map(values), resolveContext(view)) };
}

describe("expression structure binding", () => {
  it("computes several named expressions into one object", () => {
    const { result } = resolve(
      { kind: "expression-structure", expressions: { value: "{[default]plant/level} * 2", high: "{view.params.limit}", label: "'Tank ' + 'A'" } },
      [["plant/level", update("plant/level", 21)]],
    );
    expect(result.props.values).toEqual({ value: 42, high: 80, label: "Tank A" });
    expect(result.code).toBe("good");
  });

  it("gives null for an expression that fails and still answers the others", () => {
    const { result } = resolve({ kind: "expression-structure", expressions: { ok: "1 + 1", broken: "1 +" } });
    expect(result.props.values).toEqual({ ok: 2, broken: null });
  });

  it("takes the worst quality among the tags the expressions read", () => {
    const { result } = resolve(
      { kind: "expression-structure", expressions: { a: "{[default]x}", b: "{[default]y}" } },
      [
        ["x", update("x", 1)],
        ["y", update("y", 2, "bad")],
      ],
    );
    expect(result.code).toBe("bad");
  });

  it("subscribes to every tag any of its expressions reads", () => {
    const { view } = resolve({ kind: "expression-structure", expressions: { a: "{[default]x}", b: "{[default]y} + {[default]x}" } });
    expect([...collectSubscriptions(view)].sort()).toEqual(["x", "y"]);
  });

  it("is skipped when disabled and keeps the authored value", () => {
    const { result } = resolve({ kind: "expression-structure", enabled: false, expressions: { a: "1" } });
    expect(result.props.values).toBeUndefined();
  });

  it("polls like an expression binding", () => {
    const binding = parseBinding({ kind: "expression-structure", expressions: { a: "now()" }, pollMs: 500 }, "b");
    expect(expressionPollMs([binding])).toBe(500);
    expect(() => parseBinding({ kind: "expression-structure", expressions: {}, pollMs: 10 }, "b")).toThrow(ViewModelError);
  });

  it("is read-only and refuses names that are not plain keys", () => {
    expect(() => parseBinding({ kind: "expression-structure", expressions: {}, bidirectional: true }, "b")).toThrow(/read-only/);
    expect(() => parseBinding({ kind: "expression-structure", expressions: { "a.b": "1" } }, "b")).toThrow(/key/);
    expect(() => parseBinding({ kind: "expression-structure", expressions: { a: 1 } }, "b")).toThrow(/string/);
    expect(() => parseBinding({ kind: "expression-structure" }, "b")).toThrow(ViewModelError);
    expect(parseBinding({ kind: "expression-structure", expressions: { a: "1" } }, "b")).toEqual({ kind: "expression-structure", expressions: { a: "1" } });
  });

  it("runs transforms on the object", () => {
    const { result } = resolve({
      kind: "expression-structure",
      expressions: { a: "2" },
      transforms: [{ kind: "expression", expression: "{value}" }],
    });
    expect(result.props.values).toEqual({ a: 2 });
  });
});
