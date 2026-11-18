import { describe, expect, it } from "vitest";
import { routeFromPath } from "./sections";
import { formatBufferFill, formatRate, forwardRates, parseStoreForward, quarantineExportPath } from "./storeForward";

describe("store and forward figures", () => {
  it("computes forward throughput from the cumulative counter", () => {
    const next = { at: 2_000, forwarded: { plant: 5, local: 0 } };
    expect(forwardRates(null, next)).toEqual({ plant: null, local: null });
    expect(forwardRates({ at: 0, forwarded: { plant: 0, local: 0 } }, next)).toEqual({ plant: 2.5, local: 0 });
    // A restart drops the counter. The next interval starts over instead of showing a negative rate.
    expect(forwardRates({ at: 0, forwarded: { plant: 9 } }, { at: 1_000, forwarded: { plant: 4 } })).toEqual({ plant: null });
    expect(formatRate(null)).toBe("—");
    expect(formatRate(2.5)).toBe("2.5 /s");
    expect(formatRate(0)).toBe("0.0 /s");
    expect(formatBufferFill(12)).toBe("12 records");
  });

  it("parses provider, quarantine, and journal payloads", () => {
    const loaded = parseStoreForward(
      {
        collector: { pending: 3, dropped: 1 },
        providers: [{ name: "plant", pending: 4, quarantined: 2, forwarded: 9, connected: false, storeAndForward: true, configured: true, lastError: "down" }],
      },
      { records: [{ id: 7, provider: "plant", tagPath: "Plant/Flow", timestamp: "t", value: 500, quality: "Good", error: "bad", quarantinedAt: "q" }] },
      { queued: 6, retrying: true, lostRows: 2 },
      false,
    );
    expect(loaded.collector).toEqual({ pending: 3, dropped: 1 });
    expect(loaded.providers[0]?.pending).toBe(4);
    expect(loaded.records).toHaveLength(1);
    expect(loaded.journal).toEqual({ queued: 6, retrying: true, lostRows: 2 });
    expect(loaded.forwarded).toEqual({ plant: 9 });
  });

  it("builds an export link for the listed records and routes the page", () => {
    expect(quarantineExportPath({ format: "json", ids: [3, 4] })).toContain("format=json");
    expect(quarantineExportPath({ format: "json", ids: [3, 4] })).toContain("ids=3%2C4");
    expect(quarantineExportPath({ format: "csv", provider: "plant", ids: [] })).toContain("ids=0");
    expect(routeFromPath("/admin/store-and-forward")).toMatchObject({ kind: "section", section: { id: "store-and-forward" } });
  });
});
