import { COMPONENT_CATEGORIES, type HmiComponentModule } from "@oip/hmi-runtime";
import { componentModules, type ContainerModule } from "@oip/components";

/**
 * Studio component palette (HMP-002, P4-05): the components grouped into chart, container, display, input and
 * navigation, with a search over titles and types. Every registered component type has a group here (a test checks
 * this), so a new component has to choose one.
 *
 * Doc source: FEATURES HMP-002.
 */

export const PALETTE_GROUPS = COMPONENT_CATEGORIES;
export type PaletteGroup = (typeof PALETTE_GROUPS)[number];

export const GROUP_TITLES: Readonly<Record<PaletteGroup, string>> = {
  chart: "Chart",
  container: "Container",
  display: "Display",
  input: "Input",
  navigation: "Navigation",
};

/** Each registered component's palette group: the category its registry entry declares (HMP-115, P4-09). */
export const COMPONENT_GROUP: Readonly<Record<string, PaletteGroup>> = Object.fromEntries(componentModules.map((module) => [module.type, module.category]));

/** What a palette entry adds: a registered component, or one of the containers the palette creates. */
export type PaletteItem =
  | { kind: "component"; key: string; title: string; type: string; group: PaletteGroup; module: HmiComponentModule }
  | { kind: "container"; key: string; title: string; layout: ContainerModule["layout"]; group: "container"; module: ContainerModule };

/** The group of a component: its declared category; a module registered at run time without one is shown under display. */
export function groupOf(module: Pick<HmiComponentModule, "type" | "category">): PaletteGroup {
  if (module.category) return module.category;
  return Object.prototype.hasOwnProperty.call(COMPONENT_GROUP, module.type) ? COMPONENT_GROUP[module.type]! : "display";
}

/**
 * The palette's groups, in [PALETTE_GROUPS] order, each sorted by title, keeping only entries whose title or type
 * contains every word of [query] (case-insensitive). A group with no entries left is left out.
 */
export function groupPalette(
  components: readonly HmiComponentModule[],
  containers: readonly ContainerModule[],
  query = "",
): Array<{ group: PaletteGroup; items: PaletteItem[] }> {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const items: PaletteItem[] = [
    ...components.map((module): PaletteItem => ({ kind: "component", key: `component:${module.type}`, title: module.title, type: module.type, group: groupOf(module), module })),
    ...containers.map((module): PaletteItem => ({ kind: "container", key: `container:${module.layout}`, title: module.title, layout: module.layout, group: "container", module })),
  ];
  const matches = (item: PaletteItem) => {
    const text = `${item.title} ${item.kind === "component" ? item.type : item.layout} ${GROUP_TITLES[item.group]}`.toLowerCase();
    return words.every((word) => text.includes(word));
  };
  return PALETTE_GROUPS.map((group) => ({
    group,
    items: items.filter((item) => item.group === group && matches(item)).sort((a, b) => a.title.localeCompare(b.title)),
  })).filter((entry) => entry.items.length > 0);
}

/** Drag type of a palette entry dropped on the canvas. The data is [encodePaletteDrag]'s. */
export const PALETTE_DRAG_TYPE = "application/x-oip-palette";

export type PaletteDrag = { kind: "component"; type: string } | { kind: "container"; layout: string };

export function encodePaletteDrag(item: PaletteItem): string {
  return JSON.stringify(item.kind === "component" ? { kind: "component", type: item.type } : { kind: "container", layout: item.layout });
}

export function decodePaletteDrag(raw: string): PaletteDrag | null {
  try {
    const value = JSON.parse(raw) as Record<string, unknown>;
    if (value.kind === "component" && typeof value.type === "string" && value.type) return { kind: "component", type: value.type };
    if (value.kind === "container" && typeof value.layout === "string" && value.layout) return { kind: "container", layout: value.layout };
    return null;
  } catch {
    return null;
  }
}
