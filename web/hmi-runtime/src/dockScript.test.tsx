import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ScriptHost } from "./bindings";
import { parseView } from "./model";
import { parsePage } from "./pages";
import { PageSession } from "./PageSession";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import type { ComponentRegistry, HmiComponentProps, HmiProject, ViewDocument } from "./types";

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "button" && type !== "label") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props }: HmiComponentProps) => <button type="button">{String(props.text ?? "")}</button>,
    };
  },
  list: () => [],
};

function view(path: string, children: unknown[]): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: { width: 300, height: 200, params: {}, custom: {}, root: { id: `${path}-root`, kind: "container", name: "Root", layout: "flex", props: {}, children } },
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

describe("session dock scripts", () => {
  it("opens a closed dock when the script asks", async () => {
    const host: ScriptHost = {
      transform: (_hook, value) => value,
      action: () => Promise.resolve({ dock: [{ kind: "dock", mode: "open", id: "side" }] }),
    };
    const main = view("main", [
      { id: "open", kind: "component", name: "open", type: "button", props: { text: "Open side" }, events: [{ event: "onClick", actions: [{ kind: "script", hook: "open" }] }] },
    ]);
    const side = view("side", [{ id: "label", kind: "component", name: "label", type: "label", props: { text: "Dock body" } }]);
    const project: HmiProject = {
      name: "p",
      views: [main, side],
      pages: [
        parsePage({
          apiVersion: "oip.hmi/v1",
          kind: "Page",
          metadata: { name: "Main" },
          spec: { url: "/", primaryView: "main", docks: [{ id: "side", edge: "left", view: "side", size: 120, display: "onDemand", content: "push", handle: "show" }] },
        }),
      ],
    };
    render(<PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location="/" scriptHost={host} viewportWidth={1200} />);
    await act(async () => {});
    expect(screen.queryByLabelText("side dock")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Open side" }));
    await act(async () => {});
    expect(screen.getByLabelText("side dock")).toHaveAttribute("data-open", "yes");
  });
});
