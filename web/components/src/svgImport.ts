import { buildDrawing, drawingAttribute, drawingElementAllowed } from "./media";

/**
 * SVG import (HMV-151): turns uploaded or pasted SVG markup into the parts of a recolorable HMI component.
 *
 * The markup is untrusted. It is read as XML and copied element by element through the drawing's filter
 * ([buildDrawing]): scripts, event handler attributes, `style`, `foreignObject`, images, links, SMIL animation and
 * every external `href` or `url()` are dropped, and the dropped parts are listed in [SvgImport.removed]. What is
 * kept is written back as clean markup in which every group, shape and text has an id, so each can be recoloured,
 * hidden or given text through the component's `fills`, `strokes`, `visibility` and `texts` properties, and each of
 * those can be bound to a view parameter like any other property.
 */

export const SVG_IMPORT_MAX_BYTES = 512 * 1024;

export type SvgElementKind = "group" | "shape" | "text";

export interface SvgElementInfo {
  id: string;
  kind: SvgElementKind;
  tag: string;
  /** Nesting depth below the root, for an indented list. */
  depth: number;
  /** The id of the group this element is in; null for the root's children. */
  parent: string | null;
  /** The element's own `fill` and `stroke`, when set to a colour. */
  fill?: string;
  stroke?: string;
  /** A text element's text. */
  text?: string;
}

export type SvgImport =
  | {
      ok: true;
      /** Clean markup, safe to store and render. */
      svg: string;
      width: number;
      height: number;
      elements: SvgElementInfo[];
      /** What was left out of the input, such as `script` or `onclick`, one entry each time. */
      removed: string[];
    }
  | { ok: false; reason: string };

const SHAPES = new Set(["path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "use"]);
const XLINK = "http://www.w3.org/1999/xlink";
const SVG_NS = "http://www.w3.org/2000/svg";
const BINDABLE_ID = /^[A-Za-z_][A-Za-z0-9_-]*$/;
/** Elements that only define things other elements use: their insides are not offered for recolouring. */
const DEFINITIONS = new Set(["defs", "clipPath", "mask", "pattern", "linearGradient", "radialGradient", "symbol", "title", "desc"]);

function colour(value: string | null): string | undefined {
  if (value === null) return undefined;
  const paint = value.trim();
  if (paint === "" || /^none$|^inherit$|^transparent$|^url\(/i.test(paint)) return undefined;
  return paint;
}

/** Lists what [source] has that the drawing filter would drop. */
function removedParts(source: Element, removed: string[]): void {
  const stack: Element[] = [source];
  while (stack.length > 0) {
    const element = stack.pop()!;
    if (element.namespaceURI !== SVG_NS || !drawingElementAllowed(element.localName)) {
      removed.push(element.localName);
      continue;
    }
    for (const attribute of Array.from(element.attributes)) {
      if (attribute.namespaceURI === XLINK && attribute.localName === "href") {
        if (drawingAttribute("href", attribute.value, "") === null) removed.push("href");
      } else if (!attribute.namespaceURI) {
        if (attribute.name === "xmlns" || attribute.name === "version") continue;
        if (drawingAttribute(attribute.name, attribute.value, "") === null) removed.push(attribute.name);
      }
    }
    for (const child of Array.from(element.children).reverse()) stack.push(child);
  }
}

/** Sanitises [markup] and describes the groups, shapes and texts a designer can parameterise. */
export function importSvg(doc: Document, markup: string): SvgImport {
  if (typeof markup !== "string" || markup.trim() === "") return { ok: false, reason: "Paste or upload SVG markup" };
  if (markup.length > SVG_IMPORT_MAX_BYTES) return { ok: false, reason: "The SVG is larger than 512 KB" };
  // The first read finds what the input held that is not kept, so the designer can see what was dropped.
  const removed: string[] = [];
  let parsed = new DOMParser().parseFromString(markup, "image/svg+xml");
  if (parsed.documentElement?.localName === "svg" && !parsed.documentElement.namespaceURI) {
    parsed = new DOMParser().parseFromString(markup.replace(/^(\s*(?:<\?xml[^>]*\?>\s*)?<svg)(?=[\s>/])/, `$1 xmlns="${SVG_NS}"`), "image/svg+xml");
  }
  if (parsed.documentElement && parsed.documentElement.localName === "svg" && parsed.documentElement.namespaceURI === SVG_NS) {
    removedParts(parsed.documentElement, removed);
  }
  const built = buildDrawing(doc, markup, "");
  if (!built.ok) return { ok: false, reason: built.reason };
  const root = built.svg;

  // Ids that cannot be a binding path segment (a `.`) are rewritten; generated ids fill the gaps.
  const taken = new Set<string>();
  const all = [root, ...Array.from(root.querySelectorAll("*"))];
  for (const element of all) {
    const id = element.getAttribute("id");
    if (id) taken.add(id);
  }
  const renamed = new Map<string, string>();
  const unique = (base: string) => {
    let candidate = base;
    for (let n = 2; taken.has(candidate); n += 1) candidate = `${base}_${String(n)}`;
    taken.add(candidate);
    return candidate;
  };
  for (const id of [...taken]) {
    if (!BINDABLE_ID.test(id)) renamed.set(id, unique(id.replace(/[^A-Za-z0-9_-]/g, "_").replace(/^[^A-Za-z_]/, "_$&")));
  }
  const counters: Record<string, number> = {};
  for (const element of all) {
    const tag = element.localName;
    if (element === root) continue;
    const current = element.getAttribute("id");
    if (current && renamed.has(current)) element.setAttribute("id", renamed.get(current)!);
    else if (!current && !insideDefinition(element, root) && (tag === "g" || SHAPES.has(tag) || tag === "text")) {
      const base = tag === "g" ? "group" : tag === "text" ? "text" : "shape";
      counters[base] = (counters[base] ?? 0) + 1;
      element.setAttribute("id", unique(`${base}${String(counters[base])}`));
    }
  }
  if (renamed.size > 0) {
    for (const element of all) {
      for (const name of ["href", "fill", "stroke", "clip-path", "mask", "marker-start", "marker-mid", "marker-end"]) {
        const value = element.getAttribute(name);
        if (value === null) continue;
        let next = value;
        for (const [from, to] of renamed) next = next.split(`#${from})`).join(`#${to})`);
        if (name === "href" && renamed.has(value.slice(1))) next = `#${renamed.get(value.slice(1))!}`;
        if (next !== value) element.setAttribute(name, next);
      }
    }
  }
  // No fixed pixel size: the component scales the drawing to its frame through the viewBox.
  const viewBox = (root.getAttribute("viewBox") ?? "").trim().split(/[\s,]+/).map(Number);
  const [width, height] = viewBox.length === 4 && viewBox.every(Number.isFinite) ? [viewBox[2]!, viewBox[3]!] : [100, 100];
  if (viewBox.length !== 4) root.setAttribute("viewBox", "0 0 100 100");
  root.removeAttribute("width");
  root.removeAttribute("height");

  const elements: SvgElementInfo[] = [];
  const walk = (parent: Element, depth: number, group: string | null) => {
    for (const child of Array.from(parent.children)) {
      const tag = child.localName;
      if (DEFINITIONS.has(tag)) continue;
      const id = child.getAttribute("id");
      const kind: SvgElementKind | null = tag === "g" ? "group" : SHAPES.has(tag) ? "shape" : tag === "text" ? "text" : null;
      if (kind && id) {
        const info: SvgElementInfo = { id, kind, tag, depth, parent: group };
        const fill = colour(child.getAttribute("fill"));
        const stroke = colour(child.getAttribute("stroke"));
        if (fill) info.fill = fill;
        if (stroke) info.stroke = stroke;
        if (kind === "text") info.text = child.textContent ?? "";
        elements.push(info);
      }
      if (tag === "g") walk(child, depth + 1, id ?? group);
    }
  };
  walk(root, 0, null);
  const serialised = new XMLSerializer().serializeToString(root);
  return { ok: true, svg: serialised, width, height, elements, removed };
}

function insideDefinition(element: Element, root: Element): boolean {
  for (let node: Element | null = element.parentElement; node && node !== root; node = node.parentElement) {
    if (DEFINITIONS.has(node.localName)) return true;
  }
  return false;
}
