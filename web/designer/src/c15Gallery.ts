import { parsePage, parseView, type PageDocument, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";

/** Session page for the C-15 containers and embedding components, used by designer/e2e/c15-containers.spec.ts.
 * /hmi/containers holds a breakpoint container (switch at 900 px of its own width), a flex repeater whose
 * `instances` is bound to the `c15/instances` tag (JSON text on the in-memory tag server), a split container,
 * a carousel and an accordion. Every embedding component shows the small `c15-card` view.
 */

const flex = (basis: number | string = "auto", grow = 0) => ({ grow, shrink: 0, basis, display: true });

function label(id: string, text: string, extra: Record<string, unknown> = {}) {
  return { id, kind: "component", name: id, type: "label", props: { text }, ...extra };
}

function component(id: string, type: string, props: Record<string, unknown>, extra: Record<string, unknown> = {}, basis: number | string = "auto") {
  return { id, kind: "component", name: id, type, props, flex: flex(basis), ...extra };
}

export const c15CardView: ViewDocument = parseView(
  {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Gallery card", path: "c15-card" },
    spec: {
      params: { name: { value: "Unnamed" }, index: { value: 0 } },
      width: 140,
      height: 36,
      root: {
        id: "c15-card-root",
        kind: "container",
        name: "Card",
        layout: "flex",
        props: { direction: "row", alignItems: "center" },
        children: [
          label("c15-card-label", "", {
            flex: flex("auto", 1),
            bindings: { text: { kind: "property", source: { scope: "param", name: "name" } } },
          }),
        ],
      },
    },
  },
  { allowedTypes: allowedComponentTypes() },
);

export const c15GalleryView: ViewDocument = parseView(
  {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Containers gallery", path: "c15-gallery" },
    spec: {
      params: {},
      width: 960,
      height: 900,
      root: {
        id: "c15-root",
        kind: "container",
        name: "Containers gallery",
        layout: "flex",
        props: { direction: "column", alignItems: "stretch", wrap: "nowrap" },
        children: [
          {
            id: "c15-breakpoint",
            kind: "container",
            name: "Responsive header",
            layout: "breakpoint",
            props: { breakpoint: 900 },
            flex: flex(48),
            children: [
              label("c15-narrow", "Compact layout", { size: "small" }),
              label("c15-wide", "Full layout", { size: "large" }),
            ],
          },
          component(
            "c15-repeater",
            "flex-repeater",
            { label: "Pump cards", path: "c15-card", direction: "row", wrap: "wrap", instances: "[]" },
            { bindings: { instances: { kind: "tag", mode: "direct", path: "c15/instances", bidirectional: false } } },
            90,
          ),
          {
            id: "c15-split",
            kind: "container",
            name: "Detail panes",
            layout: "split",
            props: { orientation: "horizontal", split: { position: 240, size: 6, minFirst: 120, minSecond: 200 } },
            flex: flex(80),
            children: [label("c15-split-left", "Left pane"), label("c15-split-right", "Right pane")],
          },
          component(
            "c15-carousel",
            "carousel",
            {
              label: "Line carousel",
              views: [
                { viewPath: "c15-card", viewParams: { name: "Line 1" } },
                { viewPath: "c15-card", viewParams: { name: "Line 2" } },
              ],
            },
            {},
            80,
          ),
          component(
            "c15-accordion",
            "accordion",
            {
              label: "Areas",
              expansionMode: "single",
              items: [
                { expanded: true, header: { content: { type: "text", text: "North area" } }, body: { viewPath: "c15-card", viewParams: { name: "North pumps" } } },
                { expanded: false, header: { content: { type: "text", text: "South area" } }, body: { viewPath: "c15-card", viewParams: { name: "South pumps" } } },
              ],
            },
            {},
            "auto",
          ),
        ],
      },
    },
  },
  { allowedTypes: allowedComponentTypes() },
);

export const c15GalleryPage: PageDocument = parsePage({
  apiVersion: "oip.hmi/v1",
  kind: "Page",
  metadata: { name: "Containers gallery" },
  spec: { url: "/containers", title: "Containers", primaryView: "c15-gallery", docks: [] },
});
