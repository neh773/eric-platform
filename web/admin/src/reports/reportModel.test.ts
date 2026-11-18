import { describe, expect, it } from "vitest";
import {
  availableDataKeys,
  bindingTargets,
  bindingToTarget,
  componentsOf,
  insertDataKey,
  marginsOf,
  newSpec,
  parametersOf,
  setPath,
  sqlPlaceholders,
  targetToBinding,
  textToDefault,
  unknownDataKeys,
  withComponents,
  withMargins,
  withParameters,
} from "./reportModel";

describe("report model", () => {
  it("writes margins as one number when equal and as sides when not", () => {
    expect(marginsOf({})).toEqual({ top: 40, right: 40, bottom: 40, left: 40 });
    expect(withMargins({}, { top: 60, right: 60, bottom: 60, left: 60 })).toEqual({ page: { margins: 60 } });
    expect(withMargins({ page: { size: "Letter" } }, { top: 30, right: 40, bottom: 50, left: 60 })).toEqual({
      page: { size: "Letter", margins: { top: 30, right: 40, bottom: 50, left: 60 } },
    });
    expect(marginsOf({ page: { margins: { left: 90 } } })).toEqual({ top: 40, right: 40, bottom: 40, left: 90 });
  });

  it("sets and removes nested paths without leaving empty parents", () => {
    expect(setPath({}, "header.title", "Daily")).toEqual({ header: { title: "Daily" } });
    expect(setPath({ header: { title: "Daily" } }, "header.title", undefined)).toEqual({});
    expect(setPath({ a: 1 }, "b", "")).toEqual({ a: 1, b: "" });
  });

  it("keeps components in the layout and parameters in order", () => {
    const spec = withComponents(newSpec("T", "q"), (list) => [...list, { type: "text", text: "hi" }]);
    expect(componentsOf(spec)).toEqual([{ type: "text", text: "hi" }]);
    const withParams = withParameters(spec, [
      { name: "range", type: "timeRange", default: { last: "24h" } },
      { name: "from", type: "timestamp", expression: "startOfDay(now)", default: 5 },
    ]);
    expect(withParams.parameters).toEqual([
      { name: "range", type: "timeRange", default: { last: "24h" } },
      { name: "from", type: "timestamp", expression: "startOfDay(now)" },
    ]);
    expect(parametersOf(withParams)[1]!.expression).toBe("startOfDay(now)");
  });

  it("turns default text into typed values", () => {
    expect(textToDefault("number", "2.5")).toBe(2.5);
    expect(textToDefault("boolean", "true")).toBe(true);
    expect(textToDefault("timeRange", "24h")).toEqual({ last: "24h" });
    expect(textToDefault("timestamp", "1700000000000")).toBe(1700000000000);
    expect(textToDefault("timestamp", "now")).toBe("now");
    expect(textToDefault("string", "  ")).toBeUndefined();
  });

  it("maps bindings to select targets and back", () => {
    const targets = bindingTargets([{ name: "range", type: "timeRange" }, { name: "line", type: "string" }]);
    expect(targets.map((t) => t.value)).toEqual(["range.from", "range.to", "line"]);
    expect(targetToBinding("range.from", "")).toEqual({ param: "range", field: "from" });
    expect(targetToBinding("line", "")).toEqual({ param: "line" });
    expect(targetToBinding("", "12")).toEqual({ value: 12 });
    expect(bindingToTarget({ param: "range", field: "to" })).toBe("range.to");
    expect(bindingToTarget({ value: 3 })).toBe("");
  });

  it("finds SQL placeholders but not casts", () => {
    expect(sqlPlaceholders("SELECT * FROM t WHERE a = :line AND b::text = :min AND c = :line")).toEqual(["line", "min"]);
  });

  it("offers built-in, parameter and data keys and inserts one over the selection", () => {
    const spec = withParameters(newSpec("T", "q"), [{ name: "line", type: "string" }]);
    const keys = availableDataKeys(spec, { datasets: [{ name: "rows", columns: [{ name: "count" }] }] });
    expect(keys.map((k) => k.key)).toEqual(["Date", "Report", "Page", "line", "rows.count"]);
    expect(insertDataKey("Line  ok", "line", 5, 5)).toEqual({ value: "Line @line@ ok", caret: 11 });
    expect(insertDataKey("abc", "Date", null, null).value).toBe("abc@Date@");
    expect(insertDataKey("Hello XXX", "line", 6, 9).value).toBe("Hello @line@");
    expect(unknownDataKeys("@line@ @count@ @rows.count@ @nope@", keys)).toEqual(["nope"]);
  });
});
