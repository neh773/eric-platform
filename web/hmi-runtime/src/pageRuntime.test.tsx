import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import type { HmiComponentProps } from "./types";
import { createLinkedTransports, MemoryTagServer } from "./tags";
import { createSession } from "./session";
import { parsePage } from "./pages";
import { parseView } from "./model";
import { PageSession } from "./PageSession";
import { ViewRenderer } from "./ViewRenderer";
import type { NamedQuerySource } from "./bindings";
import type { HmiProject, ViewDocument } from "./types";

/** Popups, docks, embedded views, events, and pages.
 */

const registry = {
  get(type: string) {
    if (type !== "label" && type !== "button" && type !== "numeric") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" as const },
      Component: ({ props, onChange }: HmiComponentProps) =>
        type === "button" ? (
          <button type="button" onClick={() => onChange("value", props.writeValue ?? "updated")}>
            {String(props.text ?? "Go")}
          </button>
        ) : (
          <span>{String(props.text ?? props.value ?? "")}{String(props.suffix ?? "")}</span>
        ),
    };
  },
  list: () => [],
};

function flexView(path: string, children: unknown[], extra: Record<string, unknown> = {}): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: {
      width: 400,
      height: 240,
      params: {},
      ...extra,
      root: {
        id: `${path}-root`,
        kind: "container",
        name: path,
        layout: "flex",
        props: { direction: "column" },
        children,
      },
    },
  });
}

function mount(view: ViewDocument, views: ViewDocument[] = [], sessionPatch?: (theme: string) => void) {
  const server = new MemoryTagServer();
  const [transport, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  const session = createSession({ id: "session-1", theme: "daylight", locale: "en-US" });
  render(
    <ViewRenderer
      view={view}
      views={views}
      registry={registry}
      transport={transport}
      session={session}
      onSessionPatch={(patch) => {
        if (patch.theme) sessionPatch?.(patch.theme);
      }}
    />,
  );
  return server;
}

describe("pages, popups, and embedded views", () => {
  it("opens a popup with params, toggles it, and closes it by id", async () => {
    const detail = flexView("detail", [
      { id: "message", kind: "component", name: "Message", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "param", name: "message" } } } },
    ], { params: { message: { value: "" } } });
    const home = flexView("home", [
      {
        id: "open",
        kind: "component",
        name: "Open",
        type: "button",
        props: { text: "Open" },
        events: [{ event: "onClick", actions: [{ kind: "popup", mode: "open", id: "detail", view: "detail", title: "Area", showClose: true, params: { message: "North" } }] }],
      },
      {
        id: "toggle",
        kind: "component",
        name: "Toggle",
        type: "button",
        props: { text: "Toggle" },
        events: [{ event: "onClick", actions: [{ kind: "popup", mode: "toggle", id: "detail", view: "detail", title: "Area", showClose: true, params: { message: "North" } }] }],
      },
      {
        id: "close",
        kind: "component",
        name: "Close",
        type: "button",
        props: { text: "Close it" },
        events: [{ event: "onClick", actions: [{ kind: "popup", mode: "close", id: "detail" }] }],
      },
    ]);
    const server = mount(home, [home, detail]);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(await screen.findByRole("dialog", { name: "Area" })).toBeInTheDocument();
    expect(screen.getByText("North")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Toggle" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Close it" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    server.close();
  });

  it("opens a popup with the the session module defaults when the action omits the flags", async () => {
    const detail = flexView("detail", [
      { id: "message", kind: "component", name: "Message", type: "label", props: { text: "Inside" } },
    ]);
    const home = flexView("home", [
      {
        id: "open",
        kind: "component",
        name: "Open",
        type: "button",
        props: { text: "Open plain" },
        events: [{ event: "onClick", actions: [{ kind: "popup", mode: "open", id: "plain", view: "detail", position: { left: -30, top: 12, width: 200, height: 120 } }] }],
      },
    ]);
    const server = mount(home, [home, detail]);
    fireEvent.click(screen.getByRole("button", { name: "Open plain" }));
    const dialog = await screen.findByRole("dialog", { name: "plain" });
    expect(screen.getByRole("button", { name: "Close plain" })).toBeInTheDocument();
    expect(dialog.querySelector(".oip-popup-title")).toBeTruthy();
    expect(dialog.style.left).toBe("-30px");
    server.close();
  });

  it("shows a dock param, stretches an embedded view, and writes output params back", async () => {
    const child = flexView("child", [
      {
        id: "write",
        kind: "component",
        name: "Write",
        type: "button",
        props: { text: "Write", writeValue: "updated" },
        bindings: { value: { kind: "property", bidirectional: true, source: { scope: "param", name: "message" } } },
      },
      {
        id: "shown",
        kind: "component",
        name: "Shown",
        type: "label",
        props: { text: "" },
        bindings: { text: { kind: "property", source: { scope: "param", name: "message" } } },
      },
    ], { params: { message: { value: "start", direction: "inout" } } });
    const inputChild = flexView("input-child", [
      {
        id: "kept",
        kind: "component",
        name: "Kept",
        type: "label",
        props: { text: "" },
        bindings: { text: { kind: "property", source: { scope: "param", name: "message" } } },
      },
    ], { params: { message: { value: "start", direction: "input" } } });
    const dock = flexView("dock", [
      { id: "dock-label", kind: "component", name: "Dock", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "param", name: "side" } } } },
    ], { params: { side: { value: "" } } });
    const home = flexView("home", [
      { id: "host", kind: "component", name: "Host", type: "embedded-view", props: { path: "child", params: { message: "start" }, useDefaultViewWidth: false, useDefaultViewHeight: false } },
      { id: "echo", kind: "component", name: "Echo", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "prop", nodeId: "host", prop: "params.message" } } } },
      { id: "input-host", kind: "component", name: "Input host", type: "embedded-view", props: { path: "input-child", params: { message: "kept" } } },
    ]);
    const project: HmiProject = {
      name: "demo",
      views: [home, child, inputChild, dock],
      pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Home" }, spec: { url: "/", primaryView: "home", docks: [] } })],
      sharedDocks: [{ id: "nav", edge: "left", view: "dock", size: 160, display: "visible", content: "push", handle: "show", params: { side: "west" } }],
    };
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(
      <PageSession
        project={project}
        registry={registry}
        transport={transport}
        session={createSession({ id: "s" })}
        location="/"
        viewportWidth={1100}
      />,
    );
    expect(await screen.findByRole("region", { name: "nav dock" })).toHaveAttribute("data-edge", "left");
    expect(screen.getByText("west")).toBeInTheDocument();
    const embedded = document.querySelector("[data-embedded-view='child']") as HTMLElement;
    expect(embedded.style.width).toBe("100%");
    expect(screen.getAllByText("start").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));
    await waitFor(() => expect(screen.getAllByText("updated").length).toBeGreaterThanOrEqual(2));
    expect(screen.getByText("kept")).toBeInTheDocument();
    server.close();
  });

  it("routes a page url, swaps a view without changing the url, and ignores a write to session id", async () => {
    const pumps = flexView("pumps", [{ id: "title", kind: "component", name: "Title", type: "label", props: { text: "Pump list" } }]);
    const home = flexView("home", [
      { id: "go", kind: "component", name: "Go", type: "button", props: { text: "Pumps" }, events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }] },
      { id: "swap", kind: "component", name: "Swap", type: "button", props: { text: "Swap" }, events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "view", view: "pumps", params: { message: "x" } }] }] },
      { id: "theme", kind: "component", name: "Theme", type: "button", props: { text: "Theme", writeValue: "nightwatch" }, bindings: { value: { kind: "property", bidirectional: true, source: { scope: "session", path: "theme" } } } },
      { id: "id", kind: "component", name: "Id", type: "button", props: { text: "Id", writeValue: "nope" }, bindings: { value: { kind: "property", bidirectional: true, source: { scope: "session", path: "id" } } } },
    ]);
    const project: HmiProject = {
      name: "demo",
      views: [home, pumps],
      pages: [
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Home" }, spec: { url: "/", title: "Home", primaryView: "home", docks: [] } }),
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Pumps" }, spec: { url: "/pumps", title: "Pumps", primaryView: "pumps", docks: [] } }),
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Tower" }, spec: { url: "/tower/:towerNumber", primaryView: "pumps", docks: [] } }),
      ],
    };
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    function Harness() {
      const [location, setLocation] = useState("/");
      return (
        <PageSession
          project={project}
          registry={registry}
          transport={transport}
          session={createSession({ id: "kept", theme: "daylight" })}
          location={location}
          viewportWidth={1000}
          onLocationChange={setLocation}
        />
      );
    }
    const view = render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Theme" }));
    await waitFor(() => expect(document.querySelector("[data-theme='nightwatch']")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Id" }));
    expect(document.querySelector("[data-session-id='kept']")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Swap" }));
    expect(await screen.findByText("Pump list")).toBeInTheDocument();
    expect(document.querySelector("[data-page-url]")?.getAttribute("data-page-url")).toBe("/");
    view.unmount();
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Pumps" }));
    await waitFor(() => expect(document.querySelector("[data-page-url]")?.getAttribute("data-page-url")).toBe("/pumps"));
    expect(screen.getByText("Pump list")).toBeInTheDocument();
    server.close();
  });

  it("follows only page paths and http(s) URLs from a url navigation action (review-4 F3)", () => {
    const urls = ["https://example.com/ok", "/pumps", "javascript:alert(document.cookie)", "data:text/html,<script>alert(1)</script>", "vbscript:msgbox(1)", " JavaScript:alert(1)"];
    // Parsing refuses the unsafe URLs (see model.test.ts); a stored view that reaches the runtime anyway is mutated
    // here, so the runtime's own check is what this test exercises.
    const home = flexView(
      "home",
      urls.map((_, index) => ({ id: `u${index}`, kind: "component", name: `U${index}`, type: "button", props: { text: `U${index}` }, events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "url", url: "https://example.com/placeholder" }] }] })),
    );
    const root = home.spec.root as unknown as { children: Array<{ events: Array<{ actions: Array<{ url: string }> }> }> };
    urls.forEach((url, index) => {
      root.children[index]!.events[0]!.actions[0]!.url = url;
    });
    const project: HmiProject = {
      name: "demo",
      views: [home],
      pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Home" }, spec: { url: "/", primaryView: "home", docks: [] } })],
    };
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const opened = vi.fn();
    const assign = vi.spyOn(window.location, "assign").mockImplementation(() => undefined);
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    render(<PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location="/" viewportWidth={800} openExternal={opened} />);
    urls.forEach((_, index) => fireEvent.click(screen.getByRole("button", { name: `U${index}` })));
    expect(opened.mock.calls).toEqual([
      ["https://example.com/ok", false],
      ["/pumps", false],
    ]);
    expect(assign).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
    assign.mockRestore();
    open.mockRestore();
    server.close();
  });

  it("switches tabs from the pointer and the keyboard and runs a tag write", async () => {
    const server = new MemoryTagServer();
    server.set("plant/pump/command", false);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    const view = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Tabs", path: "tabs" },
      spec: {
        width: 200,
        height: 120,
        params: {},
        root: {
          id: "tabs",
          kind: "container",
          name: "Tabs",
          layout: "tab",
          props: { tabs: [{ label: "One" }, { label: "Two" }] },
          children: [
            {
              id: "one",
              kind: "component",
              name: "One",
              type: "button",
              props: { text: "Write tag" },
              events: [{ event: "onClick", actions: [{ kind: "tag-write", path: "plant/pump/command", value: true }] }],
            },
            { id: "two", kind: "component", name: "Two", type: "label", tabLabel: "Two", props: { text: "second" } },
          ],
        },
      },
    });
    render(<ViewRenderer view={view} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(screen.getByRole("tab", { name: "One", selected: true })).toBeInTheDocument();
    expect(screen.getByRole("tabpanel", { name: "One" })).not.toHaveAttribute("hidden");
    fireEvent.click(screen.getByRole("tab", { name: "Two" }));
    expect(screen.getByRole("tab", { name: "Two", selected: true })).toBeInTheDocument();
    expect(screen.getByText("second")).toBeVisible();
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: "One", selected: true })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Write tag" }));
    await waitFor(() => expect(server.get("plant/pump/command")?.value).toBe(true));
    server.close();
  });

  it("calls a named query and a script action", async () => {
    const calls: string[] = [];
    const source: NamedQuerySource = {
      execute: vi.fn(async () => ({ columns: ["name"], rows: [["East"]] })),
    };
    const view = flexView("home", [
      { id: "label", kind: "component", name: "Label", type: "label", props: { text: "waiting" }, bindings: { text: { kind: "query", path: "areas", returnFormat: "scalar" } } },
      { id: "run", kind: "component", name: "Run", type: "button", props: { text: "Run" }, events: [{ event: "onClick", actions: [{ kind: "script", hook: "clicked" }] }] },
    ]);
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(
      <ViewRenderer
        view={view}
        registry={registry}
        transport={transport}
        session={createSession({ id: "s" })}
        querySource={source}
        scriptHost={{
          transform: () => undefined,
          action: (hook) => {
            calls.push(hook);
          },
        }}
      />,
    );
    expect(await screen.findByText("East")).toBeInTheDocument();
    expect(source.execute).toHaveBeenCalledWith(expect.objectContaining({ path: "areas", returnFormat: "scalar" }));
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    expect(calls).toEqual(["clicked"]);
    server.close();
  });

  it("reports an unknown page", () => {
    const home = flexView("home", []);
    const project: HmiProject = {
      name: "demo",
      views: [home],
      pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Home" }, spec: { url: "/", primaryView: "home", docks: [] } })],
    };
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(
      <PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location="/missing" viewportWidth={800} />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Page not found");
    server.close();
  });

  it("places a view on a canvas", () => {
    const child = flexView("child", [{ id: "label", kind: "component", name: "L", type: "label", props: { text: "placed" } }]);
    const home = flexView("home", [
      { id: "canvas", kind: "component", name: "Canvas", type: "view-canvas", props: { instances: "child 10 20 100 40" } },
    ]);
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={home} views={[home, child]} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    expect(document.querySelector("[data-embedded-view='child']")).toBeTruthy();
    expect(screen.getByText("placed")).toBeVisible();
    server.close();
  });
});
