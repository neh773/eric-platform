import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  COMPONENT_CATEGORIES,
  createLinkedTransports,
  createSession,
  MemoryTagServer,
  parseStyleObject,
  parseView,
  positionSchema,
  ViewRenderer,
  type StringTransport,
  type ViewDocument,
} from "@oip/hmi-runtime";
import { componentModules, componentRegistry } from "./registry";

/** P4-09 (HMP-115): the component model in the registry the Studio edits. */
describe("component model", () => {
  it("every registered component declares its category and position", () => {
    expect(componentModules.length).toBeGreaterThan(40);
    for (const module of componentModules) {
      expect(COMPONENT_CATEGORIES, module.type).toContain(module.category);
      expect(module.position.width, module.type).toBeGreaterThan(0);
      expect(module.position.height, module.type).toBeGreaterThan(0);
      expect(module.schema.type, module.type).toBe("object");
    }
    // Each category holds components.
    for (const category of COMPONENT_CATEGORIES) expect(componentModules.some((module) => module.category === category), category).toBe(true);
  });

  it("variants have unique names and styles inside the CSS subset", () => {
    const withVariants = componentModules.filter((module) => module.variants && module.variants.length > 0);
    expect(withVariants.map((module) => module.type).sort()).toEqual(["button", "label", "numeric"]);
    for (const module of withVariants) {
      const names = module.variants!.map((variant) => variant.name);
      expect(new Set(names).size, module.type).toBe(names.length);
      for (const variant of module.variants!) {
        expect(variant.name).toMatch(/^[a-z][a-z0-9-]*$/);
        expect(() => parseStyleObject(variant.style, `${module.type}.${variant.name}`)).not.toThrow();
      }
    }
  });

  it("gives the position schema by the parent's layout", () => {
    expect(Object.keys(positionSchema("coordinate").frame!.properties!)).toEqual(["x", "y", "width", "height", "rotate"]);
    expect(positionSchema("coordinate", "percent").frame!.properties!.x!.maximum).toBe(1);
    expect(Object.keys(positionSchema("flex").flex!.properties!)).toEqual(["grow", "shrink", "basis", "display"]);
    expect(positionSchema("tab")).toEqual({ tabLabel: { type: "string", title: "Tab label" } });
    expect(positionSchema("breakpoint").size!.enum).toEqual(["small", "large"]);
    expect(positionSchema("column")).toEqual({});
    expect(positionSchema(null)).toEqual({});
  });
});

describe("variants in a session", () => {
  let server: MemoryTagServer;
  let transport: StringTransport;
  beforeEach(() => {
    server = new MemoryTagServer();
    const [client, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    transport = client;
  });
  afterEach(() => server.close());

  function view(extra: Record<string, unknown>): ViewDocument {
    return parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "V", path: "v" },
      spec: {
        params: {},
        custom: {},
        width: 300,
        height: 200,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: {},
          children: [{ id: "title", kind: "component", name: "Title", type: "label", props: { text: "Tank" }, ...extra }],
        },
      },
    });
  }

  function show(doc: ViewDocument, styleClasses?: Record<string, { style: Record<string, unknown> }>) {
    render(
      <ViewRenderer view={doc} registry={componentRegistry()} transport={transport} session={createSession({ id: "s", locale: "en-US" })} styleClasses={styleClasses} />,
    );
    return document.querySelector("[data-node-id='title']") as HTMLElement;
  }

  it("applies the variant's style under style classes and the node's own style", () => {
    const node = show(view({ variant: "heading", styleClasses: ["muted"], style: { fontWeight: 300 } }), { muted: { style: { color: "#777777" } } });
    expect(node.dataset.variant).toBe("heading");
    expect(node.style.fontSize).toBe("20px");
    expect(node.style.color).toBe("#777777");
    expect(node.style.fontWeight).toBe("300");
  });

  it("ignores a variant the component does not have", () => {
    const node = show(view({ variant: "giant" }));
    expect(node.style.fontSize).toBe("");
  });

  it("refuses a malformed variant name in a view", () => {
    expect(() => view({ variant: "Not a name" })).toThrow(/variant is not a variant name/);
  });
});
