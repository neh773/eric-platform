import { describe, expect, it } from "vitest";
import { BASELINE_P95_MS, CI, FULL, latencyStats, percentile, prf011Config, regressionFails } from "./prf011";

describe("PRF-011 sizes", () => {
  it("keeps the ADR 0010 load in both sizes", () => {
    for (const size of [CI, FULL]) {
      expect(size.sessions).toBe(50);
      expect(size.rendered).toBeGreaterThan(0);
      expect(size.rendered).toBeLessThanOrEqual(size.sessions);
      expect(size.tags).toBe(300);
      expect(size.tagPeriodMs).toBe(1_000);
    }
    expect(FULL.measureMs).toBe(60_000);
  });

  it("picks the size from the environment and checks overrides", () => {
    expect(prf011Config({}).size).toBe("ci");
    expect(prf011Config({ OIP_PRF011_SIZE: "full" })).toEqual(FULL);
    expect(prf011Config({ OIP_PRF011_SESSIONS: "4" })).toMatchObject({ sessions: 4, rendered: 4 });
    expect(prf011Config({ OIP_PRF011_RENDERED: "50" }).rendered).toBe(50);
    expect(() => prf011Config({ OIP_PRF011_TAGS: "0" })).toThrow(/positive integer/);
    expect(() => prf011Config({ OIP_PRF011_TAGS: "2.5" })).toThrow(/positive integer/);
  });
});

describe("latency statistics", () => {
  it("uses nearest-rank percentiles", () => {
    const sorted = Array.from({ length: 100 }, (_, index) => index + 1);
    expect(percentile(sorted, 0.5)).toBe(50);
    expect(percentile(sorted, 0.95)).toBe(95);
    expect(percentile(sorted, 0.99)).toBe(99);
    expect(percentile([7], 0.95)).toBe(7);
    expect(percentile([], 0.95)).toBeNaN();
  });

  it("summarises unsorted samples", () => {
    const stats = latencyStats([30, 10, 20, 40]);
    expect(stats).toEqual({ count: 4, p50: 20, p95: 40, p99: 40, max: 40, mean: 25 });
    expect(latencyStats([]).count).toBe(0);
  });
});

describe("PRF-013 against the accepted baseline", () => {
  it("fails only past 15% worse", () => {
    expect(regressionFails(100, 115)).toBe(false);
    expect(regressionFails(100, 116)).toBe(true);
    expect(regressionFails(100, 80)).toBe(false);
  });

  it("keeps a baseline under the 250 ms ceiling", () => {
    expect(BASELINE_P95_MS).toBeGreaterThan(0);
    expect(BASELINE_P95_MS).toBeLessThan(250);
  });
});
