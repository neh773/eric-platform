import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { buildDrawing } from "./media";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";
import { SvgComponent, svgParts } from "./svgComponent";
import { importSvg } from "./svgImport";

/** HMV-151: imported SVGs become grouped, recolorable components. */

const PUMP = `<?xml version="1.0"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="200" height="100" viewBox="0 0 200 100">
  <g id="housing"><circle cx="50" cy="50" r="40" fill="#cccccc" stroke="#333333"/><rect x="80" y="10" width="40" height="20" fill="none" stroke="#333"/></g>
  <g><path d="M0 0 L10 10" fill="#ff0000"/><text x="5" y="90">Pump</text></g>
  <text id="label.name" x="10" y="20">P-101</text>
</svg>`;

describe("importSvg", () => {
  it("lists groups, shapes and texts, gives each an id, and keeps the viewBox", () => {
    const result = importSvg(document, PUMP);
    if (!result.ok) throw new Error(result.reason);
    expect(result.width).toBe(200);
    expect(result.height).toBe(100);
    const ids = result.elements.map((entry) => `${entry.kind}:${entry.id}`);
    expect(ids).toEqual(["group:housing", "shape:shape1", "shape:shape2", "group:group1", "shape:shape3", "text:text1", "text:label_name"]);
    const circle = result.elements.find((entry) => entry.id === "shape1")!;
    expect(circle).toMatchObject({ tag: "circle", fill: "#cccccc", stroke: "#333333", parent: "housing", depth: 1 });
    expect(result.elements.find((entry) => entry.id === "shape2")!.fill).toBeUndefined();
    expect(result.elements.find((entry) => entry.id === "label_name")!.text).toBe("P-101");
    expect(result.svg).toContain('id="shape1"');
    expect(result.svg).not.toContain("label.name");
  });

  it("removes scripts, event handlers, foreignObject, external references and styles, and says what it removed", () => {
    const dirty = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 10 10" onload="alert(1)">
      <script>alert(1)</script>
      <style>rect{fill:red}</style>
      <foreignObject><div xmlns="http://www.w3.org/1999/xhtml">hi</div></foreignObject>
      <a xlink:href="javascript:alert(1)"><rect id="r" width="5" height="5" onclick="x()" style="fill:red" fill="url(https://evil.test/a.svg#p)"/></a>
      <image href="https://evil.test/a.png"/>
      <use id="u" xlink:href="https://evil.test/a.svg#x"/>
      <animate attributeName="x" to="3"/>
      <rect id="ok" width="3" height="3" fill="#0f0" onmouseover="x()" stroke="url(https://evil.test/a.svg#p)"/>
    </svg>`;
    const result = importSvg(document, dirty);
    if (!result.ok) throw new Error(result.reason);
    for (const bad of ["script", "onload", "onclick", "onmouseover", "foreignObject", "javascript", "evil.test", "<style", "style=", "<image", "<animate", "<a "]) {
      expect(result.svg, bad).not.toContain(bad);
    }
    expect(result.svg).toContain('id="ok"');
    expect(result.removed).toEqual(expect.arrayContaining(["script", "style", "foreignObject", "a", "image", "animate", "onload", "onmouseover", "stroke", "href"]));
  });

  it("refuses input that is not SVG, is empty, or is too large", () => {
    expect(importSvg(document, "")).toEqual({ ok: false, reason: "Paste or upload SVG markup" });
    expect(importSvg(document, "<html xmlns='http://www.w3.org/1999/xhtml'/>").ok).toBe(false);
    expect(importSvg(document, "<svg><g></svg>").ok).toBe(false);
    expect(importSvg(document, `<svg xmlns="http://www.w3.org/2000/svg">${"<g/>".repeat(200_000)}</svg>`)).toEqual({ ok: false, reason: "The SVG is larger than 512 KB" });
  });

  it("reads markup without xmlns and gives it a viewBox", () => {
    const result = importSvg(document, '<svg width="30" height="20"><rect width="30" height="20"/></svg>');
    if (!result.ok) throw new Error(result.reason);
    expect(result.elements).toHaveLength(1);
    expect(result.svg).toContain("viewBox");
  });
});

describe("svg component", () => {
  const imported = importSvg(document, PUMP);
  if (!imported.ok) throw new Error("fixture");
  const svg = imported.svg;
  const draw = (props: Record<string, unknown>) =>
    render(<SvgComponent nodeId="s" locale="en-US" onChange={vi.fn()} props={{ svg, ...props }} />);
  const find = (container: HTMLElement, id: string) => container.querySelector(`[id$="-${id}"]`)!;

  it("is registered with the display components and its defaults draw", () => {
    const module = componentRegistry().get("svg-component")!;
    expect(module.category).toBe("display");
    expect(allowedComponentTypes().has("svg-component")).toBe(true);
    const { container } = render(<SvgComponent nodeId="s" locale="en-US" onChange={vi.fn()} props={defaultsFromSchema(module.schema)} />);
    expect(container.querySelector('[id$="-level"]')).not.toBeNull();
    expect(container.querySelector("text")!.textContent).toBe("Tank");
  });

  it("recolours an element, a whole group, hides parts and replaces text from its properties", () => {
    const { container } = draw({
      fills: { housing: "#00aa00", shape3: "#0000ff" },
      strokes: { housing: "#111111" },
      visibility: { shape2: false },
      texts: { label_name: "P-202", text1: 7 },
    });
    // The group's colour reaches the circle that paints itself, not the rectangle with fill none.
    expect(find(container, "housing").getAttribute("fill")).toBe("#00aa00");
    expect(find(container, "shape1").getAttribute("fill")).toBe("#00aa00");
    expect(find(container, "shape1").getAttribute("stroke")).toBe("#111111");
    expect(find(container, "shape2").getAttribute("fill")).toBe("none");
    expect(find(container, "shape2").getAttribute("display")).toBe("none");
    // An element's own colour wins over the group it is in.
    expect(find(container, "shape3").getAttribute("fill")).toBe("#0000ff");
    expect(find(container, "label_name").textContent).toBe("P-202");
    expect(find(container, "text1").textContent).toBe("7");
  });

  it("follows a changed property, as a binding on fills.<id> would drive it", () => {
    const { container, rerender } = draw({ fills: { shape1: "#111111" } });
    expect(find(container, "shape1").getAttribute("fill")).toBe("#111111");
    rerender(<SvgComponent nodeId="s" locale="en-US" onChange={vi.fn()} props={{ svg, fills: { shape1: "#222222" }, visibility: { housing: "false" } }} />);
    expect(find(container, "shape1").getAttribute("fill")).toBe("#222222");
    expect(find(container, "housing").getAttribute("display")).toBe("none");
  });

  it("filters hostile markup again at render and hostile property values", () => {
    const { container } = draw({
      svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>x()</script><rect id="r" width="3" height="3" onclick="x()"/></svg>',
      fills: { r: "url(https://evil.test/x.svg#p)", other: "red" },
      texts: { r: "<b>x</b>" },
    });
    expect(container.querySelector("script")).toBeNull();
    expect(find(container, "r").hasAttribute("onclick")).toBe(false);
    expect(find(container, "r").hasAttribute("fill")).toBe(false);
  });

  it("maps properties to overrides", () => {
    expect(svgParts({ fills: { a: "red", b: 3 }, visibility: { a: true, b: "false", c: 1 }, texts: { t: "x", u: {} } })).toEqual({
      overrides: { a: { fill: "red", display: "inline" }, b: { display: "none" } },
      texts: { t: "x" },
    });
  });

  it("buildDrawing without cascade leaves group colours on the group only", () => {
    const built = buildDrawing(document, '<svg xmlns="http://www.w3.org/2000/svg"><g id="g"><rect id="r" fill="#fff"/></g></svg>', "p-", { g: { fill: "#f00" } });
    if (!built.ok) throw new Error();
    expect(built.svg.querySelector("#p-r")!.getAttribute("fill")).toBe("#fff");
  });
});
