import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { HmiComponentProps, HmiProject, ViewDocument } from "./types";
import { createLinkedTransports, MemoryTagServer } from "./tags";
import { createSession } from "./session";
import { parsePage } from "./pages";
import { parseView, ViewModelError } from "./model";
import { PageSession } from "./PageSession";

/**
 * P4-04 (HMP-092): a view the session's user may not open is sent by path only (`deniedViews`). Wherever the session
 * uses it, as the page's primary view, a dock, a popup or an embedded view, it shows a refusal and renders nothing of
 * the view. P4-04 and P4-06: view security and drop configuration survive parsing.
 */

const registry = {
  get(type: string) {
    if (type !== "label" && type !== "button") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" as const },
      Component: ({ props }: HmiComponentProps) => (type === "button" ? <button type="button">{String(props.text)}</button> : <span>{String(props.text ?? "")}</span>),
    };
  },
  list: () => [],
};

function flexView(path: string, children: unknown[]): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: { width: 400, height: 240, params: {}, root: { id: `${path}-root`, kind: "container", name: path, layout: "flex", props: { direction: "column" }, children } },
  });
}

function page(url: string, primaryView: string) {
  return parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: url }, spec: { url, primaryView, docks: [] } });
}

function mount(project: HmiProject, location: string) {
  const server = new MemoryTagServer();
  const [transport, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  return render(<PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location={location} viewportWidth={1100} />);
}

describe("view security in a session", () => {
  const home = flexView("home", [
    { id: "title", kind: "component", name: "Title", type: "label", props: { text: "Overview" } },
    { id: "host", kind: "component", name: "Host", type: "embedded-view", props: { path: "reactor", params: {} } },
    {
      id: "open",
      kind: "component",
      name: "Open",
      type: "button",
      props: { text: "Open reactor" },
      events: [{ event: "onClick", actions: [{ kind: "popup", mode: "open", id: "reactor-popup", view: "reactor", title: "Reactor", showClose: true }] }],
    },
  ]);
  const project: HmiProject = {
    name: "plant",
    views: [home],
    deniedViews: ["reactor"],
    pages: [page("/", "home"), page("/reactor", "reactor")],
    sharedDocks: [{ id: "side", edge: "left", view: "reactor", size: 160, display: "visible", content: "push", handle: "show" }],
  };

  it("shows a refusal for a denied primary view, dock, embedded view and popup", () => {
    mount(project, "/");
    expect(screen.getByText("Overview")).toBeInTheDocument();
    // Embedded view and dock.
    const refusals = document.querySelectorAll("[data-view-refused='reactor']");
    expect(refusals.length).toBe(2);
    expect(screen.getByRole("region", { name: "side dock" })).toHaveTextContent('You do not have access to view "reactor".');
    expect(document.querySelector("[data-embedded-view='reactor']")).toBeNull();
    // Popup.
    fireEvent.click(screen.getByRole("button", { name: "Open reactor" }));
    expect(screen.getByRole("dialog")).toHaveTextContent('You do not have access to view "reactor".');
  });

  it("refuses a page whose primary view is denied, and says unknown for a view that does not exist", () => {
    const { unmount } = mount(project, "/reactor");
    expect(document.querySelector(".oip-page-center [data-view-refused='reactor']")).toHaveTextContent('You do not have access to view "reactor".');
    unmount();
    mount({ ...project, deniedViews: [] }, "/reactor");
    expect(document.querySelector(".oip-page-center")).toHaveTextContent("Unknown view: reactor");
    expect(document.querySelector("[data-view-refused]")).toBeNull();
  });
});

describe("view security and drop configuration in the view model", () => {
  const base = {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Tank", path: "tank" },
    spec: { width: 400, height: 240, params: {}, root: { id: "root", kind: "container", name: "Root", layout: "coordinate", children: [] } },
  };

  it("keeps the security roles and the drop rules", () => {
    const view = parseView(
      {
        ...base,
        spec: {
          ...base.spec,
          security: { roles: ["operator", " operator ", "supervisor"] },
          dropConfig: [
            { dataType: "Double", component: "numeric-entry", prop: "value", bidirectional: true },
            { dataType: "*", view: "tank-card", param: "tagPath" },
          ],
        },
      },
      { allowedTypes: new Set(["numeric-entry"]) },
    );
    expect(view.spec.security).toEqual({ roles: ["operator", "supervisor"] });
    expect(view.spec.dropConfig).toEqual([
      { dataType: "double", component: "numeric-entry", prop: "value", bidirectional: true },
      { dataType: "*", view: "tank-card", param: "tagPath" },
    ]);
  });

  it("refuses a bad role, a rule with an unknown component, data type or view path", () => {
    const bad = (spec: Record<string, unknown>) => () => parseView({ ...base, spec: { ...base.spec, ...spec } }, { allowedTypes: new Set(["label"]) });
    expect(bad({ security: { roles: ["bad role"] } })).toThrow(ViewModelError);
    expect(bad({ security: { roles: "operator" } })).toThrow(ViewModelError);
    expect(bad({ dropConfig: [{ dataType: "double", component: "rocket", prop: "value" }] })).toThrow(/not a component type/);
    expect(bad({ dropConfig: [{ dataType: "quaternion", component: "label", prop: "text" }] })).toThrow(/data type/);
    expect(bad({ dropConfig: [{ dataType: "double", view: "Not A Path", param: "p" }] })).toThrow(/view path/);
    expect(bad({ dropConfig: {} })).toThrow(/must be a list/);
  });
});
