import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { parsePage } from "./pages";
import { parseView } from "./model";
import { PageSession } from "./PageSession";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import type { ComponentRegistry, HmiComponentProps, HmiProject, StyleClassSet, StyleProps, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** Style classes as rendered by a session.
 */

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "label") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props }: HmiComponentProps) => <span>{String(props.text ?? "")}</span>,
    };
  },
  list: () => [],
};

function viewWith(styleClasses: string[] | undefined, style?: StyleProps, rootClasses?: string[]): ViewDocument {
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
        layout: "flex",
        props: { direction: "row" },
        ...(rootClasses ? { styleClasses: rootClasses } : {}),
        children: [
          {
            id: "title",
            kind: "component",
            name: "Title",
            type: "label",
            flex: { grow: 0, shrink: 1, basis: "auto", display: true },
            props: { text: "Tank" },
            ...(styleClasses ? { styleClasses } : {}),
            ...(style ? { style } : {}),
          },
        ],
      },
    },
  });
}

const classes: StyleClassSet = {
  alert: { style: { color: "#a33b32", fontWeight: 700 } },
  calm: { style: { color: "#1f7a4d" } },
  themed: { style: { color: "var(--oip-color-alarm)" } },
  panel: { style: { background: "#eeeeee", padding: 6 } },
};

let server: MemoryTagServer;
let transport: StringTransport;

beforeEach(() => {
  server = new MemoryTagServer();
  const [client, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  transport = client;
});

afterEach(() => server.close());

function renderer(view: ViewDocument, styleClasses: StyleClassSet | undefined, theme = "daylight") {
  return (
    <ViewRenderer
      view={view}
      registry={registry}
      transport={transport}
      session={createSession({ id: "s", theme, locale: "en-US" })}
      styleClasses={styleClasses}
    />
  );
}

function node(id = "title"): HTMLElement {
  return document.querySelector(`[data-node-id='${id}']`) as HTMLElement;
}

describe("ViewRenderer style classes", () => {
  it("applies a referenced class to the component", () => {
    render(renderer(viewWith(["alert"]), classes));
    expect(node().style.color).toBe("#a33b32");
    expect(node().style.fontWeight).toBe("700");
  });

  it("applies a referenced class to a container", () => {
    render(renderer(viewWith(undefined, undefined, ["panel"]), classes));
    expect(node("root").style.background).toBe("#eeeeee");
    expect(node("root").style.padding).toBe("6px");
  });

  it("composes several classes alphabetically, whatever the listed order", () => {
    render(renderer(viewWith(["calm", "alert"]), classes));
    expect(node().style.color).toBe("#1f7a4d");
    expect(node().style.fontWeight).toBe("700");
  });

  it("lets inline style win over the class", () => {
    render(renderer(viewWith(["alert"], { color: "#00ff00" }), classes));
    expect(node().style.color).toBe("#00ff00");
    expect(node().style.fontWeight).toBe("700");
  });

  it("renders without the class when it is missing", () => {
    render(renderer(viewWith(["ghost"]), classes));
    expect(node().style.color).toBe("");
  });

  it("keeps a theme variable so each session theme supplies its own value", () => {
    const { unmount } = render(renderer(viewWith(["themed"]), classes, "daylight"));
    expect(node().style.color).toBe("var(--oip-color-alarm)");
    const daylight = (node().closest(".oip-session") as HTMLElement).style.getPropertyValue("--oip-color-alarm");
    unmount();
    render(renderer(viewWith(["themed"]), classes, "nightwatch"));
    expect(node().style.color).toBe("var(--oip-color-alarm)");
    const nightwatch = (node().closest(".oip-session") as HTMLElement).style.getPropertyValue("--oip-color-alarm");
    expect(daylight).toBe("#d70f1f");
    expect(nightwatch).toBe("#ff453a");
  });

  it("re-renders with an edited class set without remounting", () => {
    const view = viewWith(["alert"]);
    const { rerender } = render(renderer(view, classes));
    const before = node();
    rerender(renderer(view, { ...classes, alert: { style: { color: "#0000ff" } } }));
    expect(node()).toBe(before);
    expect(node().style.color).toBe("#0000ff");
    expect(node().style.fontWeight).toBe("");
    rerender(renderer(view, { calm: classes.calm! }));
    expect(node().style.color).toBe("");
  });
});

describe("PageSession style classes", () => {
  function project(styleClasses: StyleClassSet | undefined): HmiProject {
    return {
      name: "P",
      views: [viewWith(["alert"])],
      pages: [
        parsePage({
          apiVersion: "oip.hmi/v1",
          kind: "Page",
          metadata: { name: "Home" },
          spec: { url: "/", title: "Home", primaryView: "overview", docks: [] },
        }),
      ],
      styleClasses,
    };
  }

  it("applies the project's classes and follows a new set", () => {
    const session = createSession({ id: "s" });
    const page = (styleClasses: StyleClassSet | undefined) => (
      <PageSession project={project(styleClasses)} registry={registry} transport={transport} session={session} location="/" viewportWidth={800} />
    );
    const { rerender } = render(page(classes));
    expect(node().style.color).toBe("#a33b32");
    rerender(page({ alert: { style: { color: "#123456" } } }));
    expect(node().style.color).toBe("#123456");
  });
});
