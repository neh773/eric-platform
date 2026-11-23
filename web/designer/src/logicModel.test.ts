import { describe, expect, it } from "vitest";
import { bindInstance, groupWatched, mappingDocument, rowsFromMapping, setMemberDirection, udtBindings } from "./logicModel";

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

describe("UDT bindings (0.1.1)", () => {
  it("binds, rebinds and clears an instance", () => {
    let b = bindInstance([], "Cls1", "[default]MiamiDade/CLS-1");
    b = setMemberDirection(b, "Cls1", "AlmHH", "none");
    expect(b).toEqual([{ variable: "Cls1", instance: "[default]MiamiDade/CLS-1", members: { AlmHH: "none" } }]);
    expect(bindInstance(b, "cls1", "[default]MiamiDade/CLS-1")[0]!.members).toEqual({ AlmHH: "none" });
    expect(bindInstance(b, "Cls1", "[default]MiamiDade/CLS-2")[0]!.members).toEqual({});
    expect(setMemberDirection(b, "Cls1", "AlmHH", "auto")[0]!.members).toEqual({});
    expect(bindInstance(b, "Cls1", "")).toEqual([]);
  });

  it("keeps generated member rows out of the hand mapping and saves bindings", () => {
    const file = {
      inputs: [
        { name: "Start", address: "%IX0.0", tag: "a/start", type: "BOOL" },
        { name: "Cls1.Level", address: "%IL1000", tag: "[default]MiamiDade/CLS-1/Level", type: "LREAL", udt: "Cls1" },
      ],
      outputs: [],
      udts: [{ variable: "Cls1", instance: "[default]MiamiDade/CLS-1", members: {} }],
    };
    const rows = rowsFromMapping(file);
    expect(rows.map((row) => row.name)).toEqual(["Start"]);
    expect(mappingDocument(rows, udtBindings(file)).udts).toEqual(file.udts);
    expect(mappingDocument(rows).udts).toBeUndefined();
  });

  it("groups watched rows by UDT variable", () => {
    const groups = groupWatched([{ name: "A", udt: null }, { name: "Cls1.Level", udt: "Cls1" }, { name: "Cls1.CommOk", udt: "Cls1" }]);
    expect(groups.map((g) => [g.udt, g.rows.length])).toEqual([["", 1], ["Cls1", 2]]);
  });
});

describe("online mode and the tag map type", () => {
  it("shows scalars and structure members by variable", async () => {
    const { onlineMarks } = await import("./logicModel");
    expect(onlineMarks([
      { name: "Level", value: 56.9, type: "REAL" },
      { name: "Pump", value: true, forced: true },
      { name: "Run", value: 0, type: "BOOL" },
      { name: "Os1A.Level", value: 96 },
      { name: "Os1A.InhibitCmd", value: true },
    ])).toEqual({
      level: { text: "56.90", kind: "number", forced: false },
      pump: { text: "TRUE", kind: "true", forced: true },
      run: { text: "FALSE", kind: "false", forced: false },
      "os1a.level": { text: "96", kind: "number", forced: false },
      "os1a.inhibitcmd": { text: "TRUE", kind: "true", forced: false },
      os1a: { text: "Level 96, InhibitCmd TRUE", kind: "struct", forced: false },
    });
  });

  it("derives the type from the address size", async () => {
    const { typeForAddress } = await import("./logicModel");
    expect(typeForAddress("%IX0.0")).toBe("BOOL");
    expect(typeForAddress("%ID3")).toBe("REAL");
    expect(typeForAddress("%QL20")).toBe("LREAL");
    expect(typeForAddress("%IW1")).toBe("INT");
    expect(typeForAddress("x")).toBeNull();
  });
});
