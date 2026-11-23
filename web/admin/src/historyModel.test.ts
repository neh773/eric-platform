import { describe, expect, it } from "vitest";
import { emptyProvider, historyTagFromSpec, providerProblem, specFromHistoryTag, specFromProvider } from "./historyModel";

describe("history providers", () => {
  it("builds a simulator and rejects a splitter that names one provider twice", () => {
    const sim = emptyProvider("simulator");
    expect(specFromProvider(sim)).toMatchObject({ type: "simulator", enabled: true });
    expect(providerProblem("lab", sim, true)).toBeNull();
    const split = emptyProvider("splitter");
    split.first = "local";
    split.second = "local";
    expect(providerProblem("both", split, true)).toMatch(/two different/);
  });
});

describe("historyTagFromSpec", () => {
  it("keeps every saved field so an edit does not reset it", () => {
    const spec = {
      tagPath: "[default]LS1/Wetwell/Level",
      sampleMode: "periodic",
      sampleRateMillis: 5000,
      deadband: { mode: "absolute", value: 0.5 },
      maxTimeBetweenSamplesMillis: 60000,
      storageProvider: "plant-history",
      note: "kept",
    };
    const draft = historyTagFromSpec(spec);
    expect(draft.sampleRateMillis).toBe("5000");
    expect(draft.deadbandMode).toBe("absolute");
    expect(draft.deadbandValue).toBe("0.5");
    expect(draft.maxTimeBetweenSamplesMillis).toBe("60000");
    expect(specFromHistoryTag(draft)).toEqual(spec);
  });
});
