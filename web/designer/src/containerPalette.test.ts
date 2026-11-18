import { describe, expect, it } from "vitest";
import { containerModules } from "@oip/components";
import { parseView, type ViewDocument } from "@oip/hmi-runtime";
import { appendContainer } from "./project";

/** C-15: the designer palette adds breakpoint and split containers that parse back as valid views. */

function blank(layout: "coordinate" | "flex" | "column"): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Blank", path: "blank" },
    spec: { width: 800, height: 600, params: {}, root: { id: "root", kind: "container", name: "Root", layout, props: {}, children: [] } },
  });
}

describe("container palette", () => {
  for (const layout of ["coordinate", "flex", "column"] as const) {
    it(`adds each container to a ${layout} root`, () => {
      let view = blank(layout);
      for (const module of containerModules) {
        const added = appendContainer(view, module.layout, module.title, module.defaults, module.children);
        view = added.view;
        const node = view.spec.root.children.find((child) => child.id === added.id);
        expect(node?.kind).toBe("container");
        expect(node?.kind === "container" ? node.children : []).toHaveLength(2);
      }
      const reparsed = parseView(JSON.parse(JSON.stringify(view)));
      const layouts = reparsed.spec.root.children.map((child) => (child.kind === "container" ? child.layout : child.type));
      expect(layouts).toEqual(["breakpoint", "split"]);
      const breakpoint = reparsed.spec.root.children[0];
      expect(breakpoint?.kind === "container" ? breakpoint.children.map((child) => child.size) : []).toEqual(["small", "large"]);
    });
  }
});
