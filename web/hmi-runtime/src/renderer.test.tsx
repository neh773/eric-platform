import { render, screen } from "@testing-library/react";
import { StrictMode, useState } from "react";
import { describe, expect, it } from "vitest";
import { createSession, MemoryTagServer, createLinkedTransports, type ComponentRegistry, type HmiComponentProps, type StringTransport } from "./index";
import { parseView } from "./model";
import { ViewRenderer } from "./ViewRenderer";

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "label" && type !== "numeric") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props, onChange }: HmiComponentProps) => (
        <button type="button" onClick={() => onChange("value", 3)}>
          {String(props.text ?? props.value ?? "")}
        </button>
      ),
    };
  },
  list: () => [],
};

function viewFor(layout: "coordinate" | "flex" | "column") {
  const child = layout === "coordinate"
    ? { id: "title", kind: "component", name: "Title", type: "label", frame: { x: 0.25, y: 0.1, width: 0.5, height: 0.2, rotate: 10 }, props: { text: "Tank" }, style: { color: "#123456" } }
    : layout === "flex"
      ? { id: "title", kind: "component", name: "Title", type: "label", flex: { grow: 2, shrink: 0, basis: "40px", display: true }, props: { text: "Tank" } }
      : {
          id: "title",
          kind: "component",
          name: "Title",
          type: "label",
          columns: [{ name: "sm", span: 12, rowIndex: 0, colIndex: 0, order: 0 }, { name: "lg", span: 4, rowIndex: 0, colIndex: 2, order: 0 }],
          props: { text: "Tank" },
        };
  const hidden = layout === "flex"
    ? { id: "gone", kind: "component", name: "Gone", type: "label", flex: { grow: 0, shrink: 1, basis: "auto", display: false }, props: { text: "Hidden" } }
    : null;
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Overview", path: "overview" },
    spec: {
      width: 200,
      height: 100,
      params: {},
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout,
        props: layout === "coordinate" ? { mode: "percent", aspectRatio: "2:1" } : layout === "column" ? { gutters: { vertical: 4, horizontal: 6 } } : { direction: "row" },
        children: hidden ? [child, hidden] : [child],
      },
    },
  });
}

function Harness({ transport, layout }: { transport: StringTransport; layout: "coordinate" | "flex" | "column" }) {
  const [connected, setConnected] = useState(false);
  return (
    <div data-connected={connected ? "yes" : "no"}>
      <ViewRenderer
        view={viewFor(layout)}
        registry={registry}
        transport={transport}
        session={createSession({ id: "s", theme: "nightwatch", locale: "en-US" })}
        viewportWidth={layout === "column" ? 1000 : undefined}
        onConnectionChange={setConnected}
      />
    </div>
  );
}

describe("ViewRenderer", () => {
  it("places a percent-mode child, applies its style, and shows an unknown component", () => {
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const view = viewFor("coordinate");
    view.spec.root.children.push({
      id: "mystery",
      kind: "component",
      name: "Mystery",
      type: "nope",
      frame: { x: 0, y: 0, width: 0.2, height: 0.2 },
      props: {},
    });
    render(
      <ViewRenderer view={view} registry={registry} transport={transport} session={createSession({ id: "s", theme: "daylight" })} />,
    );
    const node = document.querySelector("[data-node-id='title']") as HTMLElement;
    expect(node.style.left).toBe("25%");
    expect(node.style.width).toBe("50%");
    expect(node.style.transform).toContain("rotate(10deg)");
    expect(node.style.color).toBe("#123456");
    expect(screen.getByRole("alert")).toHaveTextContent("Unknown component: nope");
    const root = document.querySelector("[data-layout='coordinate']") as HTMLElement;
    expect(root.style.aspectRatio).toBe("2 / 1");
    server.close();
  });

  it("removes a flex child whose display is false and grows the visible sibling", () => {
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<Harness transport={transport} layout="flex" />);
    expect(screen.queryByText("Hidden")).toBeNull();
    const node = document.querySelector("[data-node-id='title']") as HTMLElement;
    expect(node.style.flexGrow).toBe("2");
    expect(node.style.flexBasis).toBe("40px");
    server.close();
  });

  it("uses the large column placement when the viewport crosses the breakpoint", () => {
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<Harness transport={transport} layout="column" />);
    const node = document.querySelector("[data-node-id='title']") as HTMLElement;
    expect(node.style.gridColumn).toBe("3 / span 4");
    expect(document.querySelector("[data-layout='column']")).toHaveAttribute("data-breakpoint", "lg");
    server.close();
  });

  it("renders a tag change under StrictMode", async () => {
    const server = new MemoryTagServer();
    server.set("plant/tank/level", 1);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const view = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Overview", path: "overview" },
      spec: {
        width: 100,
        height: 40,
        params: {},
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction: "row" },
          children: [
            {
              id: "level",
              kind: "component",
              name: "Level",
              type: "numeric",
              props: { value: 0 },
              bindings: { value: { kind: "tag", mode: "direct", path: "plant/tank/level", bidirectional: true } },
            },
          ],
        },
      },
    });
    render(
      <StrictMode>
        <ViewRenderer view={view} registry={registry} transport={transport} session={createSession({ id: "s" })} />
      </StrictMode>,
    );
    expect(await screen.findByRole("button", { name: "1" })).toBeInTheDocument();
    server.set("plant/tank/level", 6);
    expect(await screen.findByRole("button", { name: "6" })).toBeInTheDocument();
    server.close();
  });
});
