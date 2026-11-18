import { describe, expect, it } from "vitest";
import { deviceProblem, draftFromSpec, emptyDevice, specFromDraft } from "./deviceModel";

describe("device wizard", () => {
  it("builds a modbus device and keeps JSON-only settings", () => {
    const draft = emptyDevice("modbus");
    draft.host = "10.1.1.20";
    draft.extra = { timeoutMs: 2500 };
    const spec = specFromDraft(draft);
    expect(spec).toMatchObject({ host: "10.1.1.20", port: 502, mode: "TCP", unitId: 1, timeoutMs: 2500 });
    const back = draftFromSpec("modbus", spec);
    back.host = "10.1.1.21";
    expect(specFromDraft(back).timeoutMs).toBe(2500);
    expect(deviceProblem("Pump", draft, true)).toMatch(/lowercase/);
  });

  it("requires an opc.tcp endpoint and a listener port", () => {
    const opc = emptyDevice("opcua");
    opc.endpointUrl = "http://nope";
    expect(deviceProblem("line", opc, true)).toMatch(/opc.tcp/);
    const udp = emptyDevice("udp");
    udp.ports = "";
    expect(deviceProblem("scale", udp, true)).toMatch(/port/);
  });
});
