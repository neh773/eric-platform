import { describe, expect, it } from "vitest";
import { freeSpot } from "./project";

describe("freeSpot", () => {
  const size = { width: 160, height: 40 };

  it("starts at the top-left margin on an empty view", () => {
    expect(freeSpot([], size, 1280)).toEqual({ x: 16, y: 16 });
  });

  it("puts each new component beside the last, then on the next row, never on top", () => {
    const taken: Array<{ x: number; y: number; width: number; height: number }> = [];
    for (let i = 0; i < 12; i++) {
      const at = freeSpot(taken, size, 800);
      for (const f of taken) {
        const overlaps = at.x < f.x + f.width && f.x < at.x + size.width && at.y < f.y + f.height && f.y < at.y + size.height;
        expect(overlaps).toBe(false);
      }
      expect(at.x + size.width).toBeLessThanOrEqual(800);
      taken.push({ ...at, ...size });
    }
    expect(taken[1]).toMatchObject({ x: 184, y: 16 });
    expect(new Set(taken.map((f) => f.y)).size).toBeGreaterThan(1);
  });

  it("keeps a new box inside a full view instead of below it", () => {
    const full = [{ x: 0, y: 0, width: 680, height: 360 }];
    expect(freeSpot(full, { width: 320, height: 200 }, 680, 360)).toEqual({ x: 16, y: 16 });
    const half = [{ x: 0, y: 0, width: 680, height: 120 }];
    const at = freeSpot(half, { width: 320, height: 200 }, 680, 360);
    expect(at.y + 200).toBeLessThanOrEqual(360);
    expect(at.y).toBeGreaterThanOrEqual(128);
  });
});
