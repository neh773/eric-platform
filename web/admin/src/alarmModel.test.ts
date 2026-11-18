import { describe, expect, it } from "vitest";
import { alarmFromSpec, alarmProblem, emptyAlarm, specFromAlarm } from "./alarmModel";

describe("alarm editor", () => {
  it("builds a pipeline that notifies a roster and keeps extra settings", () => {
    const draft = emptyAlarm("pipeline");
    draft.roster = "ops";
    draft.profile = "mail";
    draft.delaySeconds = "30";
    draft.extra = { description: "night" };
    const spec = specFromAlarm(draft);
    expect(spec.blocks).toEqual([
      { id: "start", type: "start", next: "notify" },
      expect.objectContaining({ id: "notify", type: "notification", roster: "ops", next: "wait" }),
      { id: "wait", type: "delay", seconds: 30, next: "end" },
      { id: "end", type: "end" },
    ]);
    expect(spec.description).toBe("night");
    const back = alarmFromSpec("pipeline", spec);
    expect(back.delaySeconds).toBe("30");
    expect(alarmProblem("page", draft, true)).toBeNull();
    expect(alarmProblem("page", { ...draft, roster: "" }, true)).toMatch(/roster/);
  });
});
