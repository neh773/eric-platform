import type { ComponentNode, ComponentVariant, ContainerNode, CoordinateMode, HmiComponentModule, JsonSchema, LayoutKind, StyleProps } from "./types";

/**
 * The component model (HMP-115, P4-09): every registered component declares a category (the palette group it is
 * listed under), the size it is placed at, and optionally named variants. A component's position properties come from
 * the container it is placed in, so the position schema the Studio edits is chosen by the parent's layout.
 *
 * Doc source: FEATURES HMP-115.
 */

export const COMPONENT_CATEGORIES = ["chart", "container", "display", "input", "navigation"] as const;
export type ComponentCategory = (typeof COMPONENT_CATEGORIES)[number];

export function isComponentCategory(value: unknown): value is ComponentCategory {
  return typeof value === "string" && (COMPONENT_CATEGORIES as readonly string[]).includes(value);
}

/** Coordinate container children: x, y, width, height, rotate. Percent mode keeps the frame inside 0..1. */
export function frameSchema(mode: CoordinateMode): JsonSchema {
  const maximum = mode === "percent" ? 1 : undefined;
  const field = (title: string): JsonSchema => ({ type: "number", title, minimum: mode === "percent" ? 0 : undefined, maximum });
  return {
    type: "object",
    title: "Frame",
    additionalProperties: false,
    properties: {
      x: field("X"),
      y: field("Y"),
      width: { ...field("Width"), minimum: mode === "percent" ? 0 : 1 },
      height: { ...field("Height"), minimum: mode === "percent" ? 0 : 1 },
      rotate: { type: "number", title: "Rotate", minimum: -360, maximum: 360 },
    },
  };
}

const FLEX_SCHEMA: JsonSchema = {
  type: "object",
  title: "Flex",
  properties: {
    grow: { type: "number", title: "Grow", minimum: 0 },
    shrink: { type: "number", title: "Shrink", minimum: 0 },
    basis: { type: "string", title: "Basis" },
    display: { type: "boolean", title: "Display" },
  },
};

/**
 * The position category of a node placed in a container of [layout]: the keys of the node that hold its placement,
 * each with the schema the Studio edits. Column placements (one per breakpoint) and split panes are placed by the
 * container's own editor and have no fields here.
 */
export function positionSchema(layout: LayoutKind | null, mode: CoordinateMode = "fixed"): Record<string, JsonSchema> {
  if (layout === "coordinate") return { frame: frameSchema(mode) };
  if (layout === "flex") return { flex: FLEX_SCHEMA };
  if (layout === "tab") return { tabLabel: { type: "string", title: "Tab label" } };
  if (layout === "breakpoint") return { size: { type: "string", title: "Breakpoint size", enum: ["small", "large"] } };
  return {};
}

/** The position schema for a child of [parent] (the root has none). */
export function positionSchemaFor(parent: ContainerNode | null): Record<string, JsonSchema> {
  if (!parent) return {};
  return positionSchema(parent.layout, parent.props.mode ?? "fixed");
}

/** The variant [node] names on [module], if the module has it. */
export function variantOf(node: Pick<ComponentNode, "variant">, module: HmiComponentModule | undefined): ComponentVariant | undefined {
  if (!node.variant || !module?.variants) return undefined;
  return module.variants.find((variant) => variant.name === node.variant);
}

/** The style a node's variant gives it; it sits under style classes and the node's own style. */
export function variantStyle(node: Pick<ComponentNode, "variant">, module: HmiComponentModule | undefined): StyleProps | undefined {
  return variantOf(node, module)?.style;
}
