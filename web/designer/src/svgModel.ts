import type { ComponentNode, PropertyBinding, ViewDocument } from "@oip/hmi-runtime";
import { findNode } from "@oip/hmi-runtime";
import { componentModules, defaultsFromSchema, type SvgImport } from "@oip/components";
import { appendComponent } from "./project";

/**
 * Designer model for imported SVG components (HMV-151): adding one from a cleaned import, setting what one element
 * shows, and binding one element's colour, visibility or text to a view parameter.
 */

/** The property maps of the SVG component an element can be set through. */
export type SvgPart = "fills" | "strokes" | "visibility" | "texts";

export const SVG_PARTS: readonly { part: SvgPart; label: string }[] = [
  { part: "fills", label: "Fill" },
  { part: "strokes", label: "Stroke" },
  { part: "visibility", label: "Visible" },
  { part: "texts", label: "Text" },
];

const SVG_TYPE = "svg-component";
const PARAM_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

/** Adds an SVG component drawn from [imported] to the view's root. */
export function addSvgComponent(view: ViewDocument, imported: Extract<SvgImport, { ok: true }>, title: string): { view: ViewDocument; id: string } {
  const module = componentModules.find((entry) => entry.type === SVG_TYPE);
  if (!module) throw new Error("the SVG component is not registered");
  const size = scaledSize(imported.width, imported.height, module.position);
  const added = appendComponent(view, SVG_TYPE, title, { ...defaultsFromSchema(module.schema), svg: imported.svg, alt: title }, size);
  return added;
}

/** A size with the drawing's aspect ratio that fits the component's default box. */
function scaledSize(width: number, height: number, box: { width: number; height: number }): { width: number; height: number } {
  if (!(width > 0) || !(height > 0)) return box;
  const scale = Math.min(box.width / width, box.height / height);
  return { width: Math.max(16, Math.round(width * scale)), height: Math.max(16, Math.round(height * scale)) };
}

function svgNode(view: ViewDocument, id: string): ComponentNode {
  const node = findNode(view.spec.root, id);
  if (!node || node.kind !== "component" || node.type !== SVG_TYPE) throw new Error(`SVG component ${id} not found`);
  return node;
}

/** The binding prop path of one element: `fills.pump_body`. */
export function partPath(part: SvgPart, elementId: string): string {
  return `${part}.${elementId}`;
}

/** The view with [part] of element [elementId] set to [value], or cleared when [value] is undefined. */
export function setSvgPart(view: ViewDocument, id: string, part: SvgPart, elementId: string, value: string | boolean | undefined): ViewDocument {
  const next = structuredClone(view);
  const node = svgNode(next, id);
  const map: Record<string, unknown> = { ...((node.props[part] as Record<string, unknown> | undefined) ?? {}) };
  if (value === undefined) delete map[elementId];
  else map[elementId] = value;
  node.props[part] = map;
  return next;
}

/** A view parameter name made from an element id and a part: `pump_body_fill`. */
export function suggestParamName(part: SvgPart, elementId: string): string {
  const suffix = { fills: "fill", strokes: "stroke", visibility: "visible", texts: "text" }[part];
  const base = `${elementId}_${suffix}`.replace(/[^A-Za-z0-9_]/g, "_");
  return /^[A-Za-z_]/.test(base) ? base.slice(0, 64) : `p_${base}`.slice(0, 64);
}

/** Whether [name] is usable as a view parameter name. */
export function paramNameProblem(name: string): string | null {
  return PARAM_NAME.test(name) ? null : "Use letters, digits and _, starting with a letter or _";
}

/** The parameter bound to element [elementId]'s [part], or null. */
export function boundParam(node: ComponentNode, part: SvgPart, elementId: string): string | null {
  const binding = node.bindings?.[partPath(part, elementId)];
  return binding?.kind === "property" && binding.source.scope === "param" ? binding.source.name : null;
}

/**
 * Binds element [elementId]'s [part] to view parameter [param]. A parameter that does not exist yet is added, holding
 * the element's current value so the view looks the same until the parameter is changed.
 */
export function bindSvgPart(view: ViewDocument, id: string, part: SvgPart, elementId: string, param: string, current: string | boolean | undefined): ViewDocument {
  const problem = paramNameProblem(param);
  if (problem) throw new Error(problem);
  const next = structuredClone(view);
  const node = svgNode(next, id);
  if (!next.spec.params[param]) next.spec.params[param] = { value: current ?? (part === "visibility" ? true : ""), direction: "input" };
  const binding: PropertyBinding = { kind: "property", source: { scope: "param", name: param } };
  node.bindings = { ...(node.bindings ?? {}), [partPath(part, elementId)]: binding };
  return next;
}

/** The view with the binding of element [elementId]'s [part] removed. The parameter stays. */
export function unbindSvgPart(view: ViewDocument, id: string, part: SvgPart, elementId: string): ViewDocument {
  const next = structuredClone(view);
  const node = svgNode(next, id);
  const bindings = { ...(node.bindings ?? {}) };
  delete bindings[partPath(part, elementId)];
  node.bindings = bindings;
  return next;
}
