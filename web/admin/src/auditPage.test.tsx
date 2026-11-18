import { readFileSync } from "node:fs";
import path from "node:path";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "./api";
import { AuditPage } from "./auditPage";
import { ADMIN_SECTIONS } from "./sections";

/** U-1: the audit table uses the page's width; long values wrap or are clipped, with the whole value in a tooltip. */

const target = "designer/project/a-project-name-that-goes-on-and-on/View/" + "deep/".repeat(20) + "leaf";
const detail = "keys=" + Array.from({ length: 40 }, (_, index) => `field${index}`).join(",");

function client(): ApiClient {
  return new ApiClient({
    fetch: async () =>
      new Response(
        JSON.stringify({
          items: [
            { seq: 1, timestamp: "2026-10-29T02:20:23.556Z", actor: "an-operator-with-a-very-long-account-name", action: "gateway config updated", target, detail, status: "SUCCESS" },
          ],
          total: 1,
          limit: 25,
          offset: 0,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
  });
}

describe("audit page", () => {
  /** SEC-002: the filters on the page become the actor, action, target and time-range parameters of the query. */
  it("sends the actor, action, target and time range filters with the query", async () => {
    const urls: string[] = [];
    const recording = new ApiClient({
      fetch: async (input: RequestInfo | URL) => {
        urls.push(String(input));
        return new Response(JSON.stringify({ items: [], total: 0, limit: 25, offset: 0 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      },
    });
    const section = ADMIN_SECTIONS.find((item) => item.id === "audit")!;
    render(<AuditPage section={section} client={recording} />);
    await screen.findByText("No audit entries.");
    fireEvent.change(screen.getByLabelText("Actor"), { target: { value: "alice" } });
    fireEvent.change(screen.getByLabelText("Action"), { target: { value: "login" } });
    fireEvent.change(screen.getByLabelText("Target"), { target: { value: "levels" } });
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-11-01T00:00" } });
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-11-02T00:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await vi.waitFor(() => expect(urls.length).toBeGreaterThan(1));
    const params = new URL(urls[urls.length - 1]!, "http://gateway.test").searchParams;
    expect(params.get("actor")).toBe("alice");
    expect(params.get("action")).toBe("login");
    expect(params.get("target")).toBe("levels");
    expect(Number(params.get("from"))).toBe(new Date("2026-11-01T00:00").getTime());
    expect(Number(params.get("to"))).toBe(new Date("2026-11-02T00:00").getTime());
  });

  it("clips short columns, wraps target and detail, and puts every value in a tooltip", async () => {
    const section = ADMIN_SECTIONS.find((item) => item.id === "audit")!;
    render(<AuditPage section={section} client={client()} />);
    const table = await screen.findByRole("table", { name: "Audit entries" });
    expect(table).toHaveClass("audit-table");
    expect(table.parentElement).toHaveClass("table-scroll");
    // Line-break hints add no text, so each cell's text is still the whole value.
    const cell = (text: string) => {
      const found = screen.getAllByRole("cell").find((element) => element.textContent === text);
      if (!found) throw new Error(`no cell holds ${text}`);
      return found;
    };
    expect(cell(target)).toHaveClass("cell-wrap");
    expect(cell(target)).toHaveAttribute("title", target);
    expect(cell(detail)).toHaveClass("cell-wrap");
    expect(cell(detail)).toHaveAttribute("title", detail);
    expect(cell(target).querySelectorAll("wbr").length).toBeGreaterThan(20);
    for (const value of ["2026-10-29T02:20:23.556Z", "an-operator-with-a-very-long-account-name", "gateway config updated", "SUCCESS"]) {
      expect(cell(value)).toHaveClass("cell-clip");
      expect(cell(value)).toHaveAttribute("title", value);
    }
  });

  it("keeps the workspace column within the window and lays the table out at its full width", () => {
    const css = readFileSync(path.resolve(__dirname, "admin.css"), "utf8").replace(/\s+/g, " ");
    expect(css).toMatch(/\.workspace \{[^}]*grid-template-columns: minmax\(0, 1fr\);/);
    expect(css).toMatch(/\.audit-table \{[^}]*table-layout: fixed;/);
    expect(css).toMatch(/\.data-table \.cell-clip \{[^}]*text-overflow: ellipsis;/);
    expect(css).toMatch(/\.data-table \.cell-wrap \{[^}]*overflow-wrap: anywhere;/);
  });
});
