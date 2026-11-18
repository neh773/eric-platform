import { describe, expect, it } from "vitest";
import { applyBindings, collectSubscriptions, resolveContext, resolveTagPath } from "./bindings";
import { parseView } from "./model";
import type { TagUpdate } from "./tags";

const view = parseView({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Valves", path: "valves" },
  spec: {
    width: 200,
    height: 100,
    params: { index: { value: 2 } },
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "flex",
      props: { direction: "row" },
      children: [
        {
          id: "flow",
          kind: "component",
          name: "Flow",
          type: "numeric",
          props: { value: 0, decimals: 1 },
          bindings: {
            value: {
              kind: "tag",
              mode: "indirect",
              path: "plant/valve/{index}/flow",
              references: { index: { source: "param", name: "index" } },
            },
          },
        },
        {
          id: "held",
          kind: "component",
          name: "Held",
          type: "numeric",
          props: { value: 4 },
          bindings: {
            value: { kind: "tag", mode: "direct", path: "plant/missing", publishInitialUncertain: true },
          },
        },
        {
          id: "off",
          kind: "component",
          name: "Off",
          type: "label",
          props: { text: "authored" },
          bindings: { text: { kind: "tag", mode: "direct", path: "plant/hidden", enabled: false } },
        },
      ],
    },
  },
});

function update(path: string, value: TagUpdate["value"], code: TagUpdate["code"] = "good"): TagUpdate {
  const quality = code === "good" ? "good" : code.startsWith("bad") ? "bad" : "uncertain";
  return { path, value, quality, code, timestamp: 1 };
}

describe("tag bindings", () => {
  it("resolves an indirect path from a view param and subscribes to it", () => {
    const ctx = resolveContext(view);
    const flow = view.spec.root.children[0];
    expect(flow?.kind).toBe("component");
    if (flow?.kind !== "component") return;
    const binding = flow.bindings?.value;
    if (binding?.kind !== "tag") throw new Error("fixture");
    expect(resolveTagPath(binding, ctx)).toBe("plant/valve/2/flow");
    expect(collectSubscriptions(view)).toEqual(["plant/missing", "plant/valve/2/flow"]);
  });

  it("applies a direct value, skips a disabled binding, and can ignore the initial uncertain value", () => {
    const ctx = resolveContext(view);
    const flow = view.spec.root.children[0];
    const held = view.spec.root.children[1];
    const off = view.spec.root.children[2];
    if (flow?.kind !== "component" || held?.kind !== "component" || off?.kind !== "component") throw new Error("fixture");
    const values = new Map<string, TagUpdate>([
      ["plant/valve/2/flow", update("plant/valve/2/flow", 11)],
      ["plant/missing", update("plant/missing", null, "uncertain_initial")],
      ["plant/hidden", update("plant/hidden", "nope")],
    ]);
    expect(applyBindings(flow, values, ctx).props.value).toBe(11);
    expect(applyBindings(held, values, ctx).props.value).toBe(4);
    expect(applyBindings(off, values, ctx).props.text).toBe("authored");
    const uncertain = applyBindings(
      { ...held, bindings: { value: { kind: "tag", mode: "direct", path: "plant/missing" } } },
      values,
      ctx,
    );
    expect(uncertain.props.value).toBeNull();
    expect(uncertain.quality).toBe("uncertain");
  });

  it("applies a binding on a nested property path into the nested object, keeping its siblings", () => {
    const ctx = resolveContext(view);
    const held = view.spec.root.children[1];
    if (held?.kind !== "component") throw new Error("fixture");
    const elements = { level: { height: 10, fill: "#3d7ab8" }, shell: { height: 100 } };
    const node = {
      ...held,
      props: { elements },
      bindings: { "elements.level.height": { kind: "tag" as const, mode: "direct" as const, path: "plant/level" } },
    };
    const values = new Map<string, TagUpdate>([["plant/level", update("plant/level", 25)]]);
    const resolved = applyBindings(node, values, ctx);
    expect(resolved.props.elements).toEqual({ level: { height: 25, fill: "#3d7ab8" }, shell: { height: 100 } });
    expect(resolved.props).not.toHaveProperty(["elements.level.height"]);
    // The authored props are not changed.
    expect(elements.level.height).toBe(10);
  });
});
