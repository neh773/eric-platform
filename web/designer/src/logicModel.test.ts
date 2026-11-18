import { describe, expect, it } from "vitest";
import { mappingDocument, rowsFromMapping } from "./logicModel";

describe("logic mapping", () => {
  it("writes the eric-plc mapping file from the form rows", () => {
    const file = mappingDocument([
      { name: "Start", address: "%IX0.0", tag: "pid/start", type: "BOOL", direction: "in", safe: "false" },
      { name: "Motor", address: "%QX1.0", tag: "pid/motor", type: "BOOL", direction: "out", safe: "false" },
      { name: "Level", address: "%QW4", tag: "pid/level", type: "INT", direction: "out", safe: "0" },
    ]);
    expect(file.inputs).toEqual([{ name: "Start", address: "%IX0.0", tag: "pid/start", type: "BOOL", safe: false }]);
    expect(file.outputs).toEqual([
      { name: "Motor", address: "%QX1.0", tag: "pid/motor", type: "BOOL", safe: false },
      { name: "Level", address: "%QW4", tag: "pid/level", type: "INT", safe: 0 },
    ]);
    expect(rowsFromMapping(file).map((row) => row.name)).toEqual(["Start", "Motor", "Level"]);
  });
});
