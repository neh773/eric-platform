import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { parseView, ViewModelError } from "./model";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import type { ComponentRegistry, HmiComponentProps, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** P4-10 (HMP-127): custom and meta categories of a component in a session. */
const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "label") return undefined;
    return { type, title: type, schema: { type: "object" }, Component: ({ props }: HmiComponentProps) => <span>{String(props.text ?? "")}</span> };
  },
  list: () => [],
};

function view(source: Record<string, unknown>, target: Record<string, unknown> = {}): ViewDocument {
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
        children: [
          { id: "source", kind: "component", name: "Source", type: "label", props: { text: "Source" }, ...source },
          {
            id: "target",
            kind: "component",
            name: "Target",
            type: "label",
            props: { text: "?" },
            bindings: { text: { kind: "property", source: { scope: "prop", nodeId: "source", prop: "custom.area.name" } } },
            ...target,
          },
        ],
      },
    },
  });
}

let server: MemoryTagServer;
let transport: StringTransport;
beforeEach(() => {
  server = new MemoryTagServer();
  const [client, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  transport = client;
});
afterEach(() => server.close());

function show(doc: ViewDocument, designMode = false) {
  render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s", locale: "en-US" })} designMode={designMode} />);
  return (id: string) => document.querySelector(`[data-node-id='${id}']`) as HTMLElement | null;
}

describe("component property categories", () => {
  it("reads a component's typed custom properties through a property binding", () => {
    const node = show(view({ custom: { area: { name: "North", limits: [1, 2] }, armed: true } }));
    expect(node("target")!.textContent).toBe("North");
  });

  it("hides a component whose meta says it is not visible, and shows it faded in design mode", () => {
    const node = show(view({ meta: { visible: false } }));
    expect(node("source")).toBeNull();
    expect(node("target")).not.toBeNull();
  });

  it("shows a hidden component faded on the design canvas", () => {
    const node = show(view({ meta: { visible: false } }), true);
    expect(node("source")!.dataset.hidden).toBe("true");
    expect(node("source")!.style.opacity).toBe("0.35");
  });

  it("puts the meta tooltip on the component", () => {
    const node = show(view({ meta: { tooltip: "North tank level" } }));
    expect(node("source")!.getAttribute("title")).toBe("North tank level");
  });

  it("refuses custom names and meta keys it does not know", () => {
    expect(() => view({ custom: { "bad name": 1 } })).toThrow(ViewModelError);
    expect(() => view({ meta: { domId: "x" } })).toThrow(/meta.domId is not a meta property/);
    expect(() => view({ meta: { visible: "no" } })).toThrow(/visible must be a boolean/);
  });
});
