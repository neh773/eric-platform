import { describe, expect, it } from "vitest";
import { draftFromSpec, emptyGroup, groupProblem, specFromDraft } from "./groupModel";

describe("transaction group form", () => {
  it("writes a timer group and keeps a JSON-only column", () => {
    const draft = emptyGroup();
    draft.table = "samples";
    draft.items[0] = { ...draft.items[0], tag: "[default]tank", column: "level" };
    draft.extra = { timestampColumn: "ts" };
    const spec = specFromDraft(draft);
    expect(spec).toMatchObject({
      connection: "<default>",
      table: "samples",
      groupType: "standard",
      updateMode: "tagToDb",
      timestampColumn: "ts",
    });
    const back = draftFromSpec(spec);
    back.rateMillis = "5000";
    expect(specFromDraft(back).timestampColumn).toBe("ts");
    expect(groupProblem("level", draft, true)).toBeNull();
    expect(groupProblem("level", { ...draft, table: "" }, true)).toMatch(/table/);
  });
});
