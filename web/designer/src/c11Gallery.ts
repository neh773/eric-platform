import { parsePage, parseView, type PageDocument, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";

/** Session page at /hmi/components with every C-11 batch 1 component, used by designer/e2e/c11-components.spec.ts.
 * Writable controls are bound bidirectionally to `c11/*` tags on the in-memory tag server.
 */

const bind = (path: string, bidirectional = true) => ({ value: { kind: "tag", mode: "direct", path, bidirectional } });

function component(id: string, type: string, props: Record<string, unknown>, extra: Record<string, unknown> = {}, basis: number | string = "auto") {
  return { id, kind: "component", name: id, type, props, flex: { grow: 0, shrink: 0, basis, display: true }, ...extra };
}

export const c11GalleryView: ViewDocument = parseView(
  {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "C-11 components", path: "c11-gallery" },
    spec: {
      params: {},
      width: 960,
      height: 1400,
      root: {
        id: "c11-root",
        kind: "container",
        name: "C-11 components",
        layout: "flex",
        props: { direction: "column", alignItems: "stretch", wrap: "nowrap" },
        children: [
          component("c11-gauge", "simple-gauge", { label: "Tank level", unit: "m", min: 0, max: 100, decimals: 1 }, { bindings: bind("c11/level", false) }, 160),
          component("c11-progress", "progress-bar", { label: "Batch progress", min: 0, max: 100 }, { bindings: bind("c11/level", false) }),
          component("c11-icon", "icon", { name: "pump", color: "ok", label: "Pump icon" }, {}, 48),
          component("c11-sparkline", "sparkline", { label: "Flow history", points: "3, 5, 4, 8, 6, 9" }, {}, 60),
          component("c11-datetime-input", "datetime-input", { label: "Batch start" }, { bindings: bind("c11/start") }),
          component("c11-picker", "datetime-picker", { label: "Due date" }, { bindings: bind("c11/due") }),
          component("c11-password", "password-field", { label: "Operator PIN" }, { bindings: bind("c11/pin") }),
          component("c11-radio", "radio-group", { label: "Recipe", options: "r1:Recipe 1, r2:Recipe 2, r3:Recipe 3", orientation: "row" }, { bindings: bind("c11/recipe") }),
          component("c11-textarea", "text-area", { label: "Shift notes", rows: 3 }, { bindings: bind("c11/notes") }),
          component("c11-oneshot", "one-shot-button", { text: "Reset counter", writingText: "Resetting", writeValue: 1, timeoutMs: 10000 }, { bindings: bind("c11/reset") }, 40),
          component("c11-multistate", "multi-state-button", { label: "Pump mode", states: "0:Off, 1:Hand, 2:Auto" }, { bindings: bind("c11/mode") }, 40),
          component("c11-link", "link", { text: "Go to pumps", url: "/pumps" }, {
            events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }],
          }),
        ],
      },
    },
  },
  { allowedTypes: allowedComponentTypes() },
);

export const c11GalleryPage: PageDocument = parsePage({
  apiVersion: "oip.hmi/v1",
  kind: "Page",
  metadata: { name: "C-11 components" },
  spec: { url: "/components", title: "Components", primaryView: "c11-gallery", docks: [] },
});
