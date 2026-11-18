import { describe, expect, it } from "vitest";
import { emptyProvider, providerProblem, specFromProvider } from "./historyModel";

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
