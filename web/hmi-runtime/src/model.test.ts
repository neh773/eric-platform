import { describe, expect, it } from "vitest";
import { parseView, ViewModelError } from "./model";
import { activeBreakpoint, aspectRatioCss, placementFor } from "./layout";

const base = {
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Overview", path: "overview" },
  spec: {
    width: 400,
    height: 300,
    params: {},
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        {
          id: "title",
          kind: "component",
          name: "Title",
          type: "label",
          frame: { x: 10, y: 12, width: 80, height: 24 },
          props: { text: "Hello" },
        },
      ],
    },
  },
};

function draft() {
  return structuredClone(base) as {
    apiVersion: string;
    kind: string;
    metadata: { name: string; path: string };
    spec: {
      width: number;
      height: number;
      params: Record<string, unknown>;
      root: {
        id: string;
        kind: string;
        name: string;
        layout: string;
        props: Record<string, unknown>;
        children: Array<Record<string, unknown>>;
      };
    };
  };
}

describe("parseView", () => {
  it("accepts a coordinate view and rejects duplicate ids", () => {
    const view = parseView(base);
    expect(view.spec.root.children[0]?.id).toBe("title");
    const duplicate = draft();
    duplicate.spec.root.children.push({ ...duplicate.spec.root.children[0]!, id: "title" });
    expect(() => parseView(duplicate)).toThrow(ViewModelError);
    expect(() => parseView(duplicate)).toThrow(/duplicate id: title/);
  });

  it("rejects an unknown layout and a non-slug path", () => {
    const badLayout = draft();
    badLayout.spec.root.layout = "stack";
    expect(() => parseView(badLayout)).toThrow(/layout is unknown/);
    const badPath = draft();
    badPath.metadata.path = "Not A Path";
    expect(() => parseView(badPath)).toThrow(/not a slug/);
  });

  it("rejects percent frames outside 0..1 and column spans outside 1..12", () => {
    const percent = draft();
    percent.spec.root.props.mode = "percent";
    percent.spec.root.children[0]!.frame = { x: 0.1, y: 0.1, width: 1.5, height: 0.2 };
    expect(() => parseView(percent)).toThrow(/between 0 and 1/);

    const column = draft();
    column.spec.root.layout = "column";
    column.spec.root.props = {};
    column.spec.root.children[0] = {
      id: "cell",
      kind: "component",
      name: "Cell",
      type: "label",
      props: { text: "A" },
      columns: [{ name: "lg", span: 13, rowIndex: 0, colIndex: 0, order: 0 }],
    };
    expect(() => parseView(column)).toThrow(/span must be an integer from 1 to 12/);
  });

  it("rejects unknown component types when a registry is supplied", () => {
    expect(() => parseView(base, { allowedTypes: new Set(["numeric"]) })).toThrow(/not registered: label/);
    expect(parseView(base, { allowedTypes: new Set(["label"]) }).metadata.name).toBe("Overview");
  });
});

describe("column breakpoints", () => {
  const breakpoints = [
    { name: "sm" as const, minWidth: 0 },
    { name: "md" as const, minWidth: 280 },
    { name: "lg" as const, minWidth: 500 },
  ];
  const placements = [
    { name: "sm" as const, span: 12, rowIndex: 1, colIndex: 0, order: 0 },
    { name: "md" as const, span: 6, rowIndex: 0, colIndex: 0, order: 0 },
    { name: "lg" as const, span: 4, rowIndex: 0, colIndex: 8, order: 1 },
  ];

  it("picks the greatest minWidth that still fits, matching the documented example", () => {
    expect(activeBreakpoint(100, breakpoints)).toBe("sm");
    expect(activeBreakpoint(280, breakpoints)).toBe("md");
    expect(activeBreakpoint(499, breakpoints)).toBe("md");
    expect(activeBreakpoint(500, breakpoints)).toBe("lg");
  });

  it("falls back to the next smaller placement when the active one is missing", () => {
    expect(placementFor(700, placements.slice(0, 2), breakpoints).span).toBe(6);
    expect(placementFor(800, placements, breakpoints).colIndex).toBe(8);
  });

  it("parses an x:y aspect ratio and ignores anything else", () => {
    expect(aspectRatioCss("16:9")).toBe("16 / 9");
    expect(aspectRatioCss("")).toBeUndefined();
    expect(aspectRatioCss("wide")).toBeUndefined();
  });
});
