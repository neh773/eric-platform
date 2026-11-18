import { parsePage, parseView, type PageDocument, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";

/** Session pages for the C-18 symbols and navigation components, used by designer/e2e/c18-symbols.spec.ts.
 * /hmi/symbols shows every symbol (pump state and levels bound to `c18/*` tags on the in-memory tag server)
 * and the three navigation components. Menu leaves go to /hmi/symbols, /hmi/symbols-area or /hmi/pumps.
 */

const bind = (path: string, prop = "value") => ({ [prop]: { kind: "tag", mode: "direct", path, bidirectional: false } });

function component(id: string, type: string, props: Record<string, unknown>, extra: Record<string, unknown> = {}, basis: number | string = "auto") {
  return { id, kind: "component", name: id, type, props, flex: { grow: 0, shrink: 0, basis, display: true }, ...extra };
}

function row(id: string, children: unknown[], basis: number | string = "auto") {
  return {
    id,
    kind: "container",
    name: id,
    layout: "flex",
    props: { direction: "row", alignItems: "stretch", wrap: "wrap" },
    flex: { grow: 0, shrink: 0, basis, display: true },
    children,
  };
}

/** Each leaf navigates to its own page through the component navigate hook; the menus need no onClick action. */
const menuItems = "Overview = /symbols, Areas > Area 1 = /symbols-area, Areas > Area 2 = /pumps";

export const c18GalleryView: ViewDocument = parseView(
  {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "C-18 symbols", path: "c18-gallery" },
    spec: {
      params: {},
      width: 960,
      height: 900,
      root: {
        id: "c18-root",
        kind: "container",
        name: "C-18 symbols",
        layout: "flex",
        props: { direction: "column", alignItems: "stretch", wrap: "nowrap" },
        children: [
          component("c18-menu", "horizontal-menu", { label: "Symbols menu", items: menuItems, selected: "0" }),
          row(
            "c18-symbols",
            [
              component("c18-pump", "pump", { label: "P-101" }, { bindings: bind("c18/pumpState", "state") }, 110),
              component("c18-valve", "valve", { label: "XV-201" }, { bindings: bind("c18/valveState", "state") }, 110),
              component("c18-motor", "motor", { label: "M-101" }, { bindings: bind("c18/pumpState", "state") }, 110),
              component("c18-vessel", "vessel", { label: "V-301", alarmHigh: 90, alarmLow: 10 }, { bindings: bind("c18/level", "level") }, 110),
              component("c18-sensor", "sensor", { label: "PT-401", tag: "PT", unit: "bar", decimals: 2 }, { bindings: bind("c18/pressure") }, 110),
              component("c18-tank", "cylindrical-tank", { label: "T-501", alarmHigh: 90, alarmLow: 10 }, { bindings: bind("c18/level", "level") }, 110),
            ],
            120,
          ),
          row("c18-nav", [
            component("c18-menutree", "menu-tree", { label: "Site menu", items: menuItems }, {}, 240),
            component("c18-tree", "tree", { label: "Asset tree", items: "Plant > Area 1 > P-101, Plant > Area 1 > T-501, Plant > Area 2, Utilities", expanded: "0" }, {}, 260),
          ], 220),
        ],
      },
    },
  },
  { allowedTypes: allowedComponentTypes() },
);

export const c18AreaView: ViewDocument = parseView(
  {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "C-18 area", path: "c18-area" },
    spec: {
      params: {},
      width: 960,
      height: 300,
      root: {
        id: "c18-area-root",
        kind: "container",
        name: "C-18 area",
        layout: "flex",
        props: { direction: "column", alignItems: "stretch", wrap: "nowrap" },
        children: [
          component("c18-area-menu", "horizontal-menu", { label: "Symbols menu", items: menuItems, selected: "1/0" }),
          component("c18-area-tank", "cylindrical-tank", { label: "T-502" }, { bindings: bind("c18/level", "level") }, 140),
        ],
      },
    },
  },
  { allowedTypes: allowedComponentTypes() },
);

export const c18GalleryPages: PageDocument[] = [
  parsePage({
    apiVersion: "oip.hmi/v1",
    kind: "Page",
    metadata: { name: "C-18 symbols" },
    spec: { url: "/symbols", title: "Symbols", primaryView: "c18-gallery", docks: [] },
  }),
  parsePage({
    apiVersion: "oip.hmi/v1",
    kind: "Page",
    metadata: { name: "C-18 area" },
    spec: { url: "/symbols-area", title: "Area", primaryView: "c18-area", docks: [] },
  }),
];
