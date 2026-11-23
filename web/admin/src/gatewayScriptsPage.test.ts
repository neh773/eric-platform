import { describe, expect, it } from "vitest";
import { specFromDraft } from "./gatewayScriptsPage";

const base = { name: "poll-level", type: "timer", description: "", enabled: true, delayMillis: "500", delayType: "fixedRate" as const, tagPaths: "", cron: "", source: "x = 1" };

describe("gateway script form", () => {
  it("builds a timer spec", () => {
    expect(specFromDraft(base)).toEqual({ type: "timer", enabled: true, description: "", source: "x = 1", delayMillis: 500, delayType: "fixedRate" });
  });
  it("needs tag paths for a tag change script and a cron for a scheduled one", () => {
    expect(specFromDraft({ ...base, type: "tagChange" })).toMatch(/tag path/);
    expect(specFromDraft({ ...base, type: "tagChange", tagPaths: "[default]A\n[default]B" })).toMatchObject({ tagPaths: ["[default]A", "[default]B"] });
    expect(specFromDraft({ ...base, type: "scheduled" })).toMatch(/cron/);
  });
  it("refuses a bad name or delay", () => {
    expect(specFromDraft({ ...base, name: "Bad Name" })).toMatch(/Name/);
    expect(specFromDraft({ ...base, delayMillis: "5" })).toMatch(/Delay/);
  });
});
