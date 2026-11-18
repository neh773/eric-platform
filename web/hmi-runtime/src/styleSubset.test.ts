import { describe, expect, it } from "vitest";
import { parseView, ViewModelError } from "./model";
import { parseStyleClassSet, resolveStyle, StyleClassError } from "./styleClasses";
import { styleToCss } from "./layout";
import { cssTextToStyle, isStyleColor, parseStyleObject, STYLE_PROPERTIES, StyleError, styleToCssText } from "./styleSubset";

/** P4-07 (HMP-093): the CSS subset a component style may set. */
describe("style subset", () => {
  it("accepts every allowed property with a value of its type", () => {
    const style = parseStyleObject(
      {
        color: "#c0392b",
        background: "var(--oip-color-surface)",
        fontSize: 18,
        fontWeight: 600,
        fontStyle: "italic",
        fontFamily: "'IBM Plex Sans', sans-serif",
        textAlign: "center",
        textDecoration: "underline",
        textTransform: "uppercase",
        lineHeight: 1.4,
        letterSpacing: -0.5,
        padding: 4,
        margin: 2,
        borderWidth: 2,
        borderStyle: "dashed",
        borderColor: "rgb(10, 20, 30)",
        borderRadius: 6,
        opacity: 0.8,
        overflow: "hidden",
        cursor: "pointer",
      },
      "style",
    );
    expect(Object.keys(style)).toHaveLength(STYLE_PROPERTIES.length);
    expect(styleToCss(style)).toMatchObject({
      color: "#c0392b",
      background: "var(--oip-color-surface)",
      fontSize: "18px",
      borderRadius: "6px",
      borderStyle: "dashed",
      opacity: 0.8,
      lineHeight: 1.4,
    });
  });

  it("refuses properties outside the subset and values of the wrong type", () => {
    expect(() => parseStyleObject({ position: "fixed" }, "style")).toThrow(/style.position is not a supported style property/);
    expect(() => parseStyleObject({ background: "url(https://example.test/x.png)" }, "style")).toThrow(StyleError);
    expect(() => parseStyleObject({ color: "red; position: fixed" }, "style")).toThrow(/must be a color/);
    expect(() => parseStyleObject({ color: "expression(alert(1))" }, "style")).toThrow(/must be a color/);
    expect(() => parseStyleObject({ fontSize: "12px" }, "style")).toThrow(/must be a number/);
    expect(() => parseStyleObject({ padding: -1 }, "style")).toThrow(/at least 0px/);
    expect(() => parseStyleObject({ opacity: 2 }, "style")).toThrow(/from 0 to 1/);
    expect(() => parseStyleObject({ textAlign: "middle" }, "style")).toThrow(/one of left, center, right, justify/);
    expect(() => parseStyleObject({ fontFamily: "x</style><script>" }, "style")).toThrow(/font names/);
  });

  it("knows colors", () => {
    for (const color of ["#fff", "#ffffff80", "red", "transparent", "currentColor", "rgba(0,0,0,0.5)", "hsl(120deg 50% 40%)", "var(--oip-color-accent)"]) {
      expect(isStyleColor(color), color).toBe(true);
    }
    for (const color of ["#ff", "url(x)", "red!important", "var(--a); color: red", "rgb(0,0,0) url(x)", "linear-gradient(red, blue)", "@import"]) {
      expect(isStyleColor(color), color).toBe(false);
    }
  });

  it("reads CSS text and refuses each declaration it cannot take", () => {
    const result = cssTextToStyle("color: #c00; Border-Radius: 4px; padding: 3; position: absolute; background: url(x); margin: 1em; text-align: CENTER");
    expect(result.style).toEqual({ color: "#c00", borderRadius: 4, padding: 3, textAlign: "center" });
    expect(result.errors).toEqual([
      "position is not a supported style property",
      'background: "url(x)" is not allowed',
      "margin takes a number of pixels (such as 4px)",
    ]);
    expect(cssTextToStyle("background-color: var(--oip-color-alarm)").style).toEqual({ background: "var(--oip-color-alarm)" });
    expect(cssTextToStyle("color: red !important").errors).toEqual(['color: "red !important" is not allowed']);
    expect(cssTextToStyle("} body { color: red").errors).toHaveLength(1);
    expect(cssTextToStyle("color: red /* x */").errors).toHaveLength(1);
    expect(cssTextToStyle("color").errors).toEqual(['"color" is not a declaration (name: value)']);
  });

  it("writes a style back as CSS text that reads the same", () => {
    const style = { color: "#c00", fontSize: 14, borderStyle: "solid", opacity: 0.5 };
    const text = styleToCssText(style);
    expect(text).toBe("color: #c00; font-size: 14px; border-style: solid; opacity: 0.5");
    expect(cssTextToStyle(text)).toEqual({ style, errors: [] });
  });

  it("is the rule for view documents and style classes", () => {
    const view = (style: unknown) => ({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "V", path: "v" },
      spec: {
        params: {},
        custom: {},
        width: 100,
        height: 100,
        root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children: [{ id: "a", kind: "component", name: "A", type: "label", props: {}, style }] },
      },
    });
    const parsed = parseView(view({ borderRadius: 8, borderStyle: "solid" }));
    expect(parsed.spec.root.children[0]!.style).toEqual({ borderRadius: 8, borderStyle: "solid" });
    expect(() => parseView(view({ background: "url(x)" }))).toThrow(ViewModelError);
    expect(() => parseView(view({ zIndex: 9 }))).toThrow(/zIndex is not a supported style property/);

    const classes = parseStyleClassSet({ warn: { style: { borderColor: "orange", borderWidth: 2 } } });
    expect(resolveStyle(["warn"], classes, { borderWidth: 4 })).toEqual({ borderColor: "orange", borderWidth: 4 });
    expect(() => parseStyleClassSet({ warn: { style: { background: "url(x)" } } })).toThrow(StyleClassError);
  });
});
