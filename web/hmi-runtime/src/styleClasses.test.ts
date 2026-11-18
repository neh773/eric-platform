import { describe, expect, it } from "vitest";
import { parseView } from "./model";
import {
  classApplicationOrder,
  isValidStyleClassName,
  parseStyleClassSet,
  resolveStyle,
  StyleClassError,
  unknownClassNames,
} from "./styleClasses";
import type { StyleClassSet } from "./types";

/** Style class resolution and precedence.
 */

const registry: StyleClassSet = {
  alpha: { style: { color: "red", padding: 4 } },
  bravo: { style: { color: "blue", fontWeight: 700 } },
  charlie: { style: { color: "green", background: "#ffffff" } },
  themed: { style: { color: "var(--oip-color-alarm)" } },
};

describe("resolveStyle", () => {
  it("applies one class's properties", () => {
    expect(resolveStyle(["alpha"], registry, undefined)).toEqual({ color: "red", padding: 4 });
  });

  it("applies several classes alphabetically: a later name wins a conflict, unset properties carry over", () => {
    expect(resolveStyle(["alpha", "bravo"], registry, undefined)).toEqual({ color: "blue", padding: 4, fontWeight: 700 });
  });

  it("ignores the order the names are listed in", () => {
    const listed = resolveStyle(["charlie", "alpha", "bravo"], registry, undefined);
    expect(listed).toEqual({ color: "green", padding: 4, fontWeight: 700, background: "#ffffff" });
    expect(resolveStyle(["bravo", "alpha"], registry, undefined)).toEqual(resolveStyle(["alpha", "bravo"], registry, undefined));
  });

  it("lets inline style override every class, keeping class properties inline does not set", () => {
    expect(resolveStyle(["alpha", "charlie"], registry, { color: "black" })).toEqual({ color: "black", padding: 4, background: "#ffffff" });
  });

  it("skips a missing class and keeps the rest", () => {
    expect(resolveStyle(["ghost", "bravo"], registry, undefined)).toEqual({ color: "blue", fontWeight: 700 });
    expect(resolveStyle(["ghost"], registry, { padding: 2 })).toEqual({ padding: 2 });
  });

  it("does not resolve Object prototype names as classes", () => {
    expect(resolveStyle(["toString", "constructor"], registry, undefined)).toEqual({});
  });

  it("returns only inline style when there is no registry or no classes", () => {
    expect(resolveStyle(["alpha"], undefined, { color: "x" })).toEqual({ color: "x" });
    expect(resolveStyle(undefined, registry, undefined)).toEqual({});
  });

  it("keeps theme variables as values for the browser to resolve against the session theme", () => {
    expect(resolveStyle(["themed"], registry, undefined)).toEqual({ color: "var(--oip-color-alarm)" });
  });

  it("reflects an edited class definition on the next resolve", () => {
    const edited: StyleClassSet = { ...registry, bravo: { style: { color: "purple" } } };
    expect(resolveStyle(["alpha", "bravo"], edited, undefined)).toEqual({ color: "purple", padding: 4 });
  });
});

describe("classApplicationOrder and unknownClassNames", () => {
  it("sorts by code point and drops duplicates", () => {
    expect(classApplicationOrder(["b", "B", "a", "b"])).toEqual(["B", "a", "b"]);
  });

  it("lists names the registry does not define", () => {
    expect(unknownClassNames(["alpha", "ghost", "toString"], registry)).toEqual(["ghost", "toString"]);
    expect(unknownClassNames(["alpha"], undefined)).toEqual(["alpha"]);
    expect(unknownClassNames(undefined, registry)).toEqual([]);
  });
});

describe("style class sets", () => {
  it("accepts valid names and supported properties", () => {
    expect(parseStyleClassSet({ heading: { style: { color: "var(--oip-color-accent)", fontSize: 20 } }, empty: {} })).toEqual({
      heading: { style: { color: "var(--oip-color-accent)", fontSize: 20 } },
      empty: { style: {} },
    });
    expect(isValidStyleClassName("tank-level_2")).toBe(true);
  });

  it.each([
    [[]],
    [{ "1bad": { style: {} } }],
    [{ "has space": { style: {} } }],
    [{ ok: { style: { border: "1px" } } }],
    [{ ok: { style: { fontSize: "20px" } } }],
    [{ ok: { style: { color: 3 } } }],
    [{ ok: "red" }],
  ])("rejects %j", (input) => {
    expect(() => parseStyleClassSet(input)).toThrow(StyleClassError);
  });

  it("parses the ordered list of classes a node references", () => {
    const view = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "V", path: "v" },
      spec: {
        params: {},
        width: 100,
        height: 50,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction: "row" },
          styleClasses: ["panel"],
          children: [{ id: "t", kind: "component", name: "T", type: "label", props: {}, styleClasses: ["b", "a"] }],
        },
      },
    });
    expect(view.spec.root.styleClasses).toEqual(["panel"]);
    expect(view.spec.root.children[0]!.styleClasses).toEqual(["b", "a"]);
    const bad = (styleClasses: unknown) => () =>
      parseView({
        apiVersion: "oip.hmi/v1",
        kind: "View",
        metadata: { name: "V", path: "v" },
        spec: { params: {}, width: 100, height: 50, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, styleClasses, children: [] } },
      });
    expect(bad("panel")).toThrow(/must be an array/);
    expect(bad([""])).toThrow(/non-empty string/);
  });
});
