import { describe, expect, it } from "vitest";
import { parseJdbcUrl } from "../databasesPage";
import { cleanDefinition, definitionProblems, resourceNameForType } from "./udtModel";

describe("database connection form", () => {
  it("round-trips the URLs it builds", () => {
    expect(parseJdbcUrl("postgres", "jdbc:postgresql://127.0.0.1:5432/eric_historian")).toEqual({ host: "127.0.0.1", port: "5432", database: "eric_historian" });
    expect(parseJdbcUrl("sqlserver", "jdbc:sqlserver://db:1433;databaseName=plant")).toEqual({ host: "db", port: "1433", database: "plant" });
    expect(parseJdbcUrl("sqlite", "jdbc:sqlite:data/x.db")?.database).toBe("data/x.db");
    expect(parseJdbcUrl("postgres", "jdbc:postgresql://a:1/b?ssl=true")).toBeNull();
  });
});

describe("UDT definition model", () => {
  it("names the resource from the type path", () => {
    expect(resourceNameForType("Types/LiftStation_Duplex")).toBe("types-lift-station-duplex");
  });

  it("reports missing bindings and cleans empty rows", () => {
    const spec = {
      path: "Types/Lift",
      parameters: [{ name: "Device", dataType: "String" }, { name: "" }],
      members: [
        { name: "Level", type: "opc", opcServer: "{Device}", opcItemPath: "", scaling: { mode: "none" }, alarms: [{ name: "" }] },
        { name: "Level", type: "memory" },
      ],
    };
    const problems = definitionProblems(spec);
    expect(problems).toContain("Every parameter needs a name.");
    expect(problems).toContain("Level: pick the device and enter the address.");
    expect(problems).toContain("Member Level is listed twice.");
    const clean = cleanDefinition(spec);
    expect(clean.parameters).toEqual([{ name: "Device", dataType: "String" }]);
    const first = (clean.members as Record<string, unknown>[])[0]!;
    expect(first.scaling).toBeUndefined();
    expect(first.alarms).toBeUndefined();
  });
});
