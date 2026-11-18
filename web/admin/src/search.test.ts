import { describe, expect, it } from "vitest";
import { matchResources, matchSections, parseConfigHits } from "./search";
import { routeFromPath } from "./sections";

describe("search", () => {
  it("filters sections by label, id, and keyword", () => {
    expect(matchSections("audit").map((section) => section.id)).toEqual(["audit"]);
    expect(matchSections("https").map((section) => section.id)).toEqual(["network"]);
    expect(matchSections("users").map((section) => section.id)).toEqual(["users"]);
    expect(matchSections("   ")).toEqual([]);
  });

  it("filters config resources and drops spec, including a password hash", () => {
    const hits = parseConfigHits({
      items: [
        {
          apiVersion: "oip/v1",
          kind: "User",
          scope: "gateway",
          metadata: { name: "admin", module: "org.oip.security" },
          spec: { passwordHash: "argon2id-secret", enabled: true },
        },
        {
          kind: "Role",
          scope: "gateway",
          metadata: { name: "operator", module: "org.oip.security" },
          spec: { permissions: ["gateway.read"] },
        },
      ],
    });
    expect(JSON.stringify(hits)).not.toContain("passwordHash");
    expect(JSON.stringify(hits)).not.toContain("argon2id-secret");
    expect(matchResources("admin", hits)).toEqual([
      { kind: "User", name: "admin", module: "org.oip.security", scope: "gateway" },
    ]);
    expect(matchResources("role", hits).map((hit) => hit.name)).toEqual(["operator"]);
  });
});

describe("routes", () => {
  it("maps the shell paths", () => {
    expect(routeFromPath("/admin")).toMatchObject({ kind: "section", section: { id: "overview" } });
    expect(routeFromPath("/admin/login")).toEqual({ kind: "login" });
    expect(routeFromPath("/admin/api-keys")).toMatchObject({ kind: "section", section: { id: "api-keys" } });
    expect(routeFromPath("/admin/missing")).toMatchObject({ kind: "section", section: { id: "overview" } });
  });
});
