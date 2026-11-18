import { parseView, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";

const types = allowedComponentTypes();

/** Fifty embedded views, each bound to embed/{index}. Used by the dev probe and the perf test. */
export function embedBench(): { shell: ViewDocument; child: ViewDocument } {
  const child = parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Embed meter", path: "embed-meter" },
      spec: {
        params: { index: { value: 0 } },
        width: 80,
        height: 32,
        root: {
          id: "embed-root",
          kind: "container",
          name: "Embed",
          layout: "flex",
          props: { direction: "row" },
          children: [
            {
              id: "value",
              kind: "component",
              name: "Value",
              type: "numeric",
              props: { value: 0, decimals: 0, prefix: "", suffix: "", announce: false },
              bindings: {
                value: {
                  kind: "tag",
                  mode: "indirect",
                  path: "embed/{index}",
                  references: { index: { source: "param", name: "index" } },
                },
              },
            },
          ],
        },
      },
    },
    { allowedTypes: types },
  );
  const children = [];
  for (let index = 0; index < 50; index += 1) {
    children.push({
      id: `e${index}`,
      kind: "component",
      name: `E${index}`,
      type: "embedded-view",
      flex: { grow: 0, shrink: 0, basis: "72px", display: true },
      props: { path: "embed-meter", params: { index }, useDefaultViewWidth: true, useDefaultViewHeight: true },
    });
  }
  const shell = parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Embed bench", path: "embed-bench" },
      spec: {
        params: {},
        width: 800,
        height: 600,
        root: {
          id: "embed-shell",
          kind: "container",
          name: "Embed shell",
          layout: "flex",
          props: { direction: "row", wrap: "wrap", justify: "flex-start", alignItems: "center", alignContent: "flex-start" },
          children,
        },
      },
    },
    { allowedTypes: types },
  );
  return { shell, child };
}
