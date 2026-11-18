import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ADMIN_SECTIONS } from "./sections";

/** DAT-030: every admin page linked from docs/sql-overview.md exists, and every SQL use is listed. */
const doc = readFileSync(resolve(__dirname, "../../../docs/sql-overview.md"), "utf8");

describe("SQL usage overview", () => {
  it("links only admin routes that exist", () => {
    const links = [...doc.matchAll(/\]\(\/admin\/([a-z-]+)\)/g)].map((m) => m[1]);
    expect(links.length).toBeGreaterThanOrEqual(6);
    for (const id of links) expect(ADMIN_SECTIONS.some((s) => s.id === id), id).toBe(true);
  });
  it("lists every SQL use", () => {
    for (const use of ["Database connections", "Named queries", "Transaction groups", "Tag history", "Alarm journal", "Audit", "Scripting db functions"]) {
      expect(doc).toContain(`| ${use} |`);
    }
  });
});
