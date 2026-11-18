import { describe, expect, it } from "vitest";
import { applyBindings, qualityLabel, resolveContext } from "./bindings";
import { parseBinding, parseView } from "./model";
import { applyFormat, applyMap, applyTransforms, transformSteps, type TransformContext } from "./transforms";
import type { MapTransform, Transform } from "./types";

/** Map, format, and script transforms.
 */

const scope = { resolve: (ref: string) => (ref === "value" ? undefined : null) };

function ctx(script?: TransformContext["script"]): TransformContext {
  return { locale: "en-US", timeZone: "UTC", scope, quality: "good", timestamp: 1551823366022, script };
}

describe("transforms", () => {
  it("runs an expression over the incoming value and stops when the expression fails", () => {
    const ok = applyTransforms(21, [{ kind: "expression", expression: "{value} * 2" }], ctx());
    expect(ok).toMatchObject({ value: 42, failed: false, quality: "good" });
    const bad = applyTransforms(21, [{ kind: "expression", expression: "" }], ctx());
    expect(bad.failed).toBe(true);
    expect(bad.quality).toBe("bad");
    expect(bad.value).toBe(21);
    const view = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "V", path: "v" },
      spec: {
        width: 100, height: 40, params: {},
        root: {
          id: "root", kind: "container", name: "Root", layout: "flex", props: {},
          children: [{
            id: "n", kind: "component", name: "N", type: "label", props: { text: "" },
            bindings: { text: { kind: "tag", mode: "direct", path: "[default]A", transforms: [{ kind: "expression", expression: "{value} + 1" }] } },
          }],
        },
      },
    });
    const binding = view.spec.root.children[0]!.kind === "component" ? view.spec.root.children[0].bindings?.text : undefined;
    expect(binding && "transforms" in binding ? binding.transforms?.[0] : null).toEqual({ kind: "expression", expression: "{value} + 1" });
  });

  it("maps the first matching value or range and uses the fallback", () => {
    const transform: MapTransform = {
      kind: "map",
      hasFallback: true,
      fallback: 99,
      entries: [
        { input: { type: "value", value: 0 }, output: { type: "value", value: "OFF" } },
        { input: { type: "range", min: 0, max: 25, minInclusive: false, maxInclusive: true }, output: { type: "value", value: 0 } },
        { input: { type: "range", min: null, max: 25, minInclusive: false, maxInclusive: true }, output: { type: "value", value: "Below 25" } },
        { input: { type: "value", value: 10 }, output: { type: "value", value: "later" } },
      ],
    };
    expect(applyMap(0, transform, scope)).toBe("OFF");
    expect(applyMap(10, transform, scope)).toBe(0);
    expect(applyMap(-4, transform, scope)).toBe("Below 25");
    expect(applyMap(40, transform, scope)).toBe(99);
  });

  it("formats the documented numeric and datetime examples", () => {
    expect(applyFormat(-35.893925, { kind: "format", type: "numeric", mode: "pattern", pattern: "#.00" }, "en-US", "UTC")).toBe("-35.89");
    expect(applyFormat(1234.5, { kind: "format", type: "numeric", mode: "pattern", pattern: "#,##0.00" }, "en-US", "UTC")).toBe("1,234.50");
    expect(applyFormat(7, { kind: "format", type: "numeric", mode: "pattern", pattern: "000" }, "en-US", "UTC")).toBe("007");
    expect(applyFormat(1.5, { kind: "format", type: "numeric", mode: "integer" }, "en-US", "UTC")).toBe("2");
    expect(applyFormat(-1.5, { kind: "format", type: "numeric", mode: "integer" }, "en-US", "UTC")).toBe("-2");
    expect(applyFormat(0.123, { kind: "format", type: "numeric", mode: "percent" }, "en-US", "UTC")).toBe("12.3%");
    expect(applyFormat(1234.5, { kind: "format", type: "numeric", mode: "number" }, "en-US", "UTC")).toBe("1,234.5");
    expect(applyFormat(1551823366022, { kind: "format", type: "datetime", mode: "pattern", pattern: "yyyy-MM-dd HH:mm:ss", timeZone: "UTC" }, "en-US", "UTC")).toBe("2019-03-05 22:02:46");
    expect(
      applyFormat(1551823366022, { kind: "format", type: "datetime", mode: "datetime", style: "short", timeZone: "Asia/Tokyo" }, "en-US", "UTC"),
    ).toBe("3/6/19, 7:02 AM");
  });

  it("runs a script hook with value, quality, and timestamp, and chains map then format", () => {
    const seen: unknown[] = [];
    const scripted = applyTransforms(3, [{ kind: "script", hook: "scale" }], ctx((hook, value, quality, timestamp) => {
      seen.push(hook, value, quality, timestamp);
      return Number(value) * 2;
    }));
    expect(seen).toEqual(["scale", 3, "good", 1551823366022]);
    expect(scripted.value).toBe(6);
    const chained = applyTransforms(0, [
      { kind: "map", entries: [{ input: { type: "value", value: 0 }, output: { type: "value", value: 12.5 } }] },
      { kind: "format", type: "numeric", mode: "pattern", pattern: "0.0" },
    ], ctx());
    expect(chained.value).toBe("12.5");
  });

  it("keeps the authored value and marks quality bad (not connected) when the script hook is missing", () => {
    const view = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Script", path: "script" },
      spec: {
        width: 100,
        height: 40,
        params: {},
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction: "row" },
          children: [
            {
              id: "label",
              kind: "component",
              name: "Label",
              type: "label",
              props: { text: "authored" },
              bindings: { text: { kind: "expression", expression: "'live'", transforms: [{ kind: "script", hook: "missing" }] } },
            },
          ],
        },
      },
    });
    const node = view.spec.root.children[0];
    if (node?.kind !== "component") throw new Error("fixture");
    const resolved = applyBindings(node, new Map(), resolveContext(view), { hasQuerySource: false });
    expect(resolved.props.text).toBe("authored");
    expect(resolved.quality).toBe("bad");
    expect(resolved.code).toBe("bad_not_connected");
    expect(resolved.reason).toBe("script_not_connected");
    expect(qualityLabel(resolved)).toBe("Script not connected");
    expect(qualityLabel({ code: "bad_not_connected" })).toBe("Not connected");
    expect(qualityLabel({ code: "uncertain" })).toBe("Uncertain");
  });

  it("previews each step and stops at a script step with no host instead of throwing", () => {
    const transforms = [
      { kind: "map", entries: [{ input: { type: "value", value: 1 }, output: { type: "value", value: 2.5 } }] },
      { kind: "format", type: "numeric", mode: "pattern", pattern: "0.00" },
      { kind: "script", hook: "shout", source: "return value" },
      { kind: "format", type: "numeric", mode: "integer" },
    ] satisfies Transform[];
    const steps = transformSteps(1, transforms, ctx());
    expect(steps.map((step) => [step.status, step.input, step.output])).toEqual([
      ["ok", 1, 2.5],
      ["ok", 2.5, "2.50"],
      ["not_connected", "2.50", undefined],
      ["skipped", undefined, undefined],
    ]);
    expect(applyTransforms(1, transforms, ctx())).toEqual({ value: 1, quality: "bad_not_connected", failed: true, reason: "script_not_connected" });
    const hosted = transformSteps(1, transforms, ctx((_hook, value) => `${String(value)}!`));
    expect(hosted.map((step) => step.status)).toEqual(["ok", "ok", "ok", "ok"]);
    expect(hosted[3]).toMatchObject({ input: "2.50!", output: "", status: "ok" });
    const broken = transformSteps(1, [{ kind: "map", entries: [{ input: { type: "expression", expression: "1 +" }, output: { type: "value", value: 0 } }] }], ctx());
    expect(broken[0]).toMatchObject({ status: "error" });
  });

  it("loads range rows in object form only with a bound, and keeps value rows tagged as range", () => {
    const binding = (input: unknown) => ({ kind: "expression", expression: "1", transforms: [{ kind: "map", entries: [{ input, output: 1 }] }] });
    const parsed = parseBinding(binding({ type: "range", min: 0, max: null, minInclusive: true }), "b");
    expect(parsed.transforms?.[0]).toMatchObject({ entries: [{ input: { type: "range", min: 0, max: null, minInclusive: true, maxInclusive: false } }] });
    expect(() => parseBinding(binding({ type: "range", min: null, max: null }), "b")).toThrow("range needs a min or a max");
    expect(() => parseBinding(binding({ type: "range", min: "x" }), "b")).toThrow("min must be a number or null");
    expect(parseBinding(binding({ type: "range", value: 5 }), "b").transforms?.[0]).toMatchObject({ entries: [{ input: { type: "value", value: 5 } }] });
  });
});

describe("HMP-101 time zones are independent", () => {
  const at = 1551823366022; // 2019-03-05 22:02:46 UTC
  const pattern = { kind: "format", type: "datetime", mode: "pattern", pattern: "yyyy-MM-dd HH:mm", timeZone: undefined } as const;
  it("formats in the session zone or in the component's own zone when it names one", () => {
    expect(applyFormat(at, pattern, "en-US", "Asia/Tokyo")).toBe("2019-03-06 07:02");
    expect(applyFormat(at, { ...pattern, timeZone: "America/Chicago" }, "en-US", "Asia/Tokyo")).toBe("2019-03-05 16:02");
    expect(applyFormat(at, { ...pattern, timeZone: "auto" }, "en-US", "Asia/Tokyo")).toBe("2019-03-06 07:02");
  });
});
