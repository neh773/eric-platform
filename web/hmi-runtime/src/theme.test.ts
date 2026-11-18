import { describe, expect, it } from "vitest";
import { parseStyleClassSet, styleClassCss } from "./styleClasses";
import { parseCustomThemes, themeStyle, themes as builtInThemes } from "./theme";

describe("project themes and style states", () => {
  it("extends a built-in theme and refuses a bad token", () => {
    const themes = parseCustomThemes({
      plant: { extends: "daylight", tokens: { "color-alarm": "#cc0000" } },
    });
    const plant = themeStyle("plant", themes) as Record<string, string>;
    expect(plant["--oip-color-alarm"]).toBe("#cc0000");
    expect(plant["--oip-color-ink"]).toBe("#0b0d10");
    const fallback = themeStyle("missing", themes) as Record<string, string>;
    expect(fallback["--oip-color-canvas"]).toBe("#0b0d10");
    expect(builtInThemes.process).toBe(builtInThemes.nightwatch);
    expect(builtInThemes.process["color-alarm"]).not.toBe(builtInThemes.process["color-running"]);
    expect(themeStyle("process")).toEqual(themeStyle("nightwatch"));
    expect((themeStyle("daylight") as Record<string, string>)["--oip-color-canvas"]).toBe("#f4f5f7");
    expect(() => parseCustomThemes({ plant: { extends: "other", tokens: {} } })).toThrow(/daylight or nightwatch/);
    expect(() => parseCustomThemes({ plant: { extends: "daylight", tokens: { "color-alarm": "url(x)" } } })).toThrow(/theme token/);
  });

  it("emits hover and disabled rules in class order", () => {
    const set = parseStyleClassSet({
      Warm: { style: { color: "#111111" }, states: { hover: { color: "#aa0000" }, disabled: { opacity: 0.4 } } },
      Cool: { style: { color: "#222222" }, states: { hover: { background: "#00aa00" } } },
    });
    const css = styleClassCss(set);
    expect(css.indexOf(".oip-sc-Cool:hover")).toBeLessThan(css.indexOf(".oip-sc-Warm:hover"));
    expect(css).toContain(".oip-sc-Warm[data-disabled=\"true\"]");
    expect(css).toContain("opacity: 0.4");
    expect(() => parseStyleClassSet({ Bad: { style: {}, states: { visited: { color: "red" } } } })).toThrow(/not a style state/);
  });
});
