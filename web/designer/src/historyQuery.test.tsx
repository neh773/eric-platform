import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { GatewayClient } from "./gatewayClient";
import { HistoryQueryTool } from "./HistoryQueryTool";
import { bindParameters, buildRequests, resolveTime } from "./historyQuery";

/** HIS-003: the Studio tag-history query tool builds gateway history requests from parameters and shows a table. */

const NOW = Date.parse("2026-01-01T12:00:00Z");

describe("history query helpers", () => {
  it("resolves relative and absolute times", () => {
    expect(resolveTime("last 1 h", NOW)).toBe(NOW - 3_600_000);
    expect(resolveTime("last 30 min", NOW)).toBe(NOW - 1_800_000);
    expect(resolveTime("2026-01-01T00:00:00Z", NOW)).toBe(Date.parse("2026-01-01T00:00:00Z"));
    expect(() => resolveTime("bogus", NOW)).toThrow();
  });
  it("binds named parameters and rejects unbound ones", () => {
    expect(bindParameters("[default]{line}/temp", { line: "l1" })).toBe("[default]l1/temp");
    expect(() => bindParameters("{x}", {})).toThrow("Parameter x has no value");
  });
  it("builds aggregate and raw requests", () => {
    const spec = { tagPaths: ["[d]{t}"], start: "last 1 h", end: "now", mode: "Maximum" as const, intervalSeconds: 60 };
    const [agg] = buildRequests(spec, { t: "a" }, NOW);
    expect(agg.path).toContain("/api/v1/history/aggregate?");
    expect(agg.path).toContain("aggregate=Maximum");
    expect(agg.path).toContain("interval=60000");
    expect(buildRequests({ ...spec, mode: "Raw" }, { t: "a" }, NOW)[0].path).toContain("/api/v1/history/data?");
  });
});

describe("history query tool", () => {
  it("runs a parameterised query and renders the windows as a table", async () => {
    const seen: string[] = [];
    const client = {
      historyQuery: async (path: string) => {
        seen.push(path);
        return { windows: [{ start: "2026-01-01T11:00:00Z", value: 4.5, rows: 3 }] };
      },
    } as unknown as GatewayClient;
    render(<HistoryQueryTool client={client} now={() => NOW} />);
    fireEvent.change(screen.getByLabelText("History tag paths"), { target: { value: "[d]{tag}" } });
    fireEvent.click(screen.getByRole("button", { name: "Add parameter" }));
    fireEvent.change(screen.getByLabelText("Parameter 1 name"), { target: { value: "tag" } });
    fireEvent.change(screen.getByLabelText("Parameter 1 value"), { target: { value: "temp" } });
    fireEvent.click(screen.getByRole("button", { name: "Run history query" }));
    const table = await screen.findByRole("table", { name: "History result" });
    expect(table.textContent).toContain("4.5");
    expect(table.textContent).toContain("[d]temp");
    expect(seen[0]).toContain("tagPath=%5Bd%5Dtemp");
  });
  it("shows an error for an unbound parameter", async () => {
    const client = { historyQuery: async () => ({}) } as unknown as GatewayClient;
    render(<HistoryQueryTool client={client} now={() => NOW} />);
    fireEvent.change(screen.getByLabelText("History tag paths"), { target: { value: "{missing}" } });
    fireEvent.click(screen.getByRole("button", { name: "Run history query" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("missing"));
  });
});
