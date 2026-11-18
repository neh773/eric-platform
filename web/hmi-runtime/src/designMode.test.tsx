import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createLinkedTransports, createSession, MemoryTagServer, type ComponentRegistry, type HmiComponentProps, type ScriptHost } from "./index";
import { parseView } from "./model";
import { componentTree, ViewRenderer } from "./ViewRenderer";

/** Design mode: the Designer canvas renders the real view with live bindings, but nothing on it acts. */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "label" && type !== "button") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props, onChange }: HmiComponentProps) =>
        type === "button" ? (
          <button type="button" onClick={() => onChange("value", 7)}>
            {String(props.text ?? "")}
          </button>
        ) : (
          <span data-testid="label">{String(props.text ?? "")}</span>
        ),
    };
  },
  list: () => [],
};

const view = parseView({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Home", path: "home" },
  spec: {
    params: {},
    width: 400,
    height: 200,
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        {
          id: "level",
          kind: "component",
          name: "Level",
          type: "label",
          frame: { x: 0, y: 0, width: 100, height: 20 },
          props: { text: "" },
          bindings: { text: { kind: "tag", mode: "direct", path: "plant/level" } },
        },
        {
          id: "go",
          kind: "component",
          name: "Go",
          type: "button",
          frame: { x: 0, y: 40, width: 100, height: 20 },
          props: { text: "Go", value: 0 },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/setpoint", bidirectional: true } },
          events: [
            {
              event: "onClick",
              actions: [
                { kind: "tag-write", path: "plant/clicked", value: 1 },
                { kind: "navigation", mode: "page", page: "/pumps" },
                { kind: "popup", mode: "open", id: "detail", view: "home", title: "Detail" },
              ],
            },
          ],
        },
      ],
    },
  },
});

function mount(designMode: boolean) {
  const server = new MemoryTagServer();
  server.set("plant/level", "62.4 m");
  server.set("plant/setpoint", 0);
  server.set("plant/clicked", 0);
  const [client, side] = createLinkedTransports();
  server.connect(side);
  const onNavigate = vi.fn();
  const result = render(
    <ViewRenderer
      view={view}
      views={[view]}
      registry={registry}
      transport={client}
      session={createSession({ id: "design", locale: "en-US" })}
      onNavigate={onNavigate}
      designMode={designMode}
    />,
  );
  return { server, onNavigate, ...result };
}

describe("design mode", () => {
  it("keeps bindings live but suppresses actions, navigation, popups and writes", async () => {
    const { server, onNavigate, container } = mount(true);
    await waitFor(() => expect(screen.getByTestId("label")).toHaveTextContent("62.4 m"));
    act(() => server.set("plant/level", "12.5 m"));
    await waitFor(() => expect(screen.getByTestId("label")).toHaveTextContent("12.5 m"));

    const session = container.querySelector(".oip-session") as HTMLElement;
    expect(session).toHaveAttribute("data-design-mode", "true");
    expect(session.inert).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "Go", hidden: true }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(onNavigate).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog", { hidden: true })).toBeNull();
    expect(server.get("plant/clicked")?.value).toBe(0);
    expect(server.get("plant/setpoint")?.value).toBe(0);
    server.close();
  });

  it("runs the same actions outside design mode", async () => {
    const { server, onNavigate, container } = mount(false);
    await waitFor(() => expect(screen.getByTestId("label")).toHaveTextContent("62.4 m"));
    expect(container.querySelector(".oip-session")).not.toHaveAttribute("data-design-mode");
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    await waitFor(() => expect(onNavigate).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(server.get("plant/clicked")?.value).toBe(1));
    await waitFor(() => expect(server.get("plant/setpoint")?.value).toBe(7));
    expect(await screen.findByRole("dialog", { name: "Detail" })).toBeInTheDocument();
    server.close();
  });
  it("runs script transforms but never an event script, startup and shutdown included (D-5a)", async () => {
    const scripted = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Scripted", path: "scripted" },
      spec: {
        params: {},
        width: 400,
        height: 200,
        events: [
          { event: "onStartup", actions: [{ kind: "script", hook: "started" }] },
          { event: "onShutdown", actions: [{ kind: "script", hook: "stopped" }] },
        ],
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "coordinate",
          props: { mode: "fixed" },
          children: [
            {
              id: "level",
              kind: "component",
              name: "Level",
              type: "label",
              frame: { x: 0, y: 0, width: 100, height: 20 },
              props: { text: "" },
              bindings: { text: { kind: "expression", expression: "'raw'", transforms: [{ kind: "script", hook: "shout" }] } },
            },
            {
              id: "go",
              kind: "component",
              name: "Go",
              type: "button",
              frame: { x: 0, y: 40, width: 100, height: 20 },
              props: { text: "Go" },
              events: [{ event: "onClick", actions: [{ kind: "script", hook: "clicked" }] }],
            },
          ],
        },
      },
    });
    const run = (designMode: boolean) => {
      const action = vi.fn(() => undefined);
      const transform = vi.fn((_hook: string, value: unknown) => String(value).toUpperCase());
      const host: ScriptHost = { transform, action };
      const [client, side] = createLinkedTransports();
      const server = new MemoryTagServer();
      server.connect(side);
      const result = render(
        <ViewRenderer
          view={scripted}
          views={[scripted]}
          registry={registry}
          transport={client}
          session={createSession({ id: "design", locale: "en-US" })}
          scriptHost={host}
          designMode={designMode}
        />,
      );
      return { action, transform, server, ...result };
    };

    const design = run(true);
    await waitFor(() => expect(screen.getByTestId("label")).toHaveTextContent("RAW"));
    expect(design.transform).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Go", hidden: true }));
    design.unmount();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(design.action).not.toHaveBeenCalled();
    design.server.close();

    // The same view in a session runs its startup and shutdown scripts, with the saved view tree.
    const live = run(false);
    const tree = componentTree(scripted);
    await waitFor(() => expect(live.action).toHaveBeenCalledWith("started", "onStartup", "root", "scripted", {}, {}, undefined, tree));
    live.unmount();
    expect(live.action).toHaveBeenCalledWith("stopped", "onShutdown", "root", "scripted", {}, {}, undefined, tree);
    live.server.close();
  });
});
