import { describe, expect, it } from "vitest";
import { formatNumber } from "./read";

describe("formatNumber", () => {
  it("keeps each locale and digit count apart while reusing formatters", () => {
    expect(formatNumber(1234.5, 1, "en-US")).toBe("1234.5");
    expect(formatNumber(1234.5, 1, "de-DE")).toBe("1234,5");
    expect(formatNumber(1234.5, 0, "en-US")).toBe("1235");
    expect(formatNumber(2.25, 3, "en-US")).toBe("2.250");
    // Again, now from the cache.
    expect(formatNumber(1234.5, 1, "de-DE")).toBe("1234,5");
    expect(formatNumber(-0.04, 1, "en-US")).toBe("-0.0");
  });

  it("still formats after many locale and digit combinations", () => {
    for (let decimals = 0; decimals <= 6; decimals += 1) {
      for (const locale of ["en-US", "en-GB", "fr-FR", "de-DE", "es-ES", "it-IT", "ja-JP", "pt-BR", "nl-NL", "sv-SE"]) {
        expect(formatNumber(1, decimals, locale)).toMatch(/^1([.,]0+)?$/);
      }
    }
    expect(formatNumber(3.5, 1, "en-US")).toBe("3.5");
  });
});
