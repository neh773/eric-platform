import { describe, expect, it } from "vitest";
import { buildChart, chartProblem, emptyChart } from "./chartModel";

describe("chart form", () => {
  it("builds a loop with an action, a parallel, and a jump", () => {
    const draft = {
      ...emptyChart(),
      parameters: "target",
      variables: "count",
      actionScript: "count = count + 1",
      parallelLeft: "left()",
      parallelRight: "right()",
      expression: "count >= target",
      loop: true,
      executionMode: "runAlways" as const,
    };
    expect(chartProblem("fill", draft)).toBeNull();
    const spec = buildChart(draft);
    expect(spec.executionMode).toBe("runAlways");
    const elements = spec.elements as Array<{ type: string; id: string }>;
    expect(elements.map((item) => item.type)).toEqual(["begin", "anchor", "action", "parallel", "transition", "jump"]);
    const links = spec.links as Array<{ from: string; to: string }>;
    expect(links.at(-1)).toEqual({ from: "done", to: "back" });
  });

  it("rejects a parallel with one side", () => {
    expect(chartProblem("fill", { ...emptyChart(), parallelLeft: "only" })).toMatch(/both sides/);
  });
});
