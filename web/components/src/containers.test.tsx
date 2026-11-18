import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createLinkedTransports, createSession, parseView, ViewRenderer, type ViewDocument } from "@oip/hmi-runtime";
import { componentRegistry } from "./registry";
import {
  clampPane,
  containerModules,
  listError,
  readAccordionItems,
  readList,
  readSlides,
  repeaterInstances,
  stepPane,
  toggleAccordion,
} from "./containers";
import { flexRepeaterSchema } from "./containers";

/** C-15 embedding components rendered through a real session, so each embedded view resolves its params. */

const registry = componentRegistry();

/** A small view whose label shows its `name` param and its automatic `index`. */
const card: ViewDocument = parseView({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Card", path: "card" },
  spec: {
    width: 120,
    height: 40,
    params: { name: { value: "default" }, index: { value: -1 } },
    root: {
      id: "card-root",
      kind: "container",
      name: "Card",
      layout: "flex",
      props: { direction: "row" },
      children: [
        {
          id: "card-label",
          kind: "component",
          name: "Label",
          type: "label",
          props: { text: "" },
          bindings: { text: { kind: "expression", expression: "{view.params.name} + ' #' + {view.params.index}" } },
        },
      ],
    },
  },
});

const other: ViewDocument = parseView({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Other", path: "other" },
  spec: {
    width: 120,
    height: 40,
    params: { text: { value: "other" } },
    root: {
      id: "other-root",
      kind: "container",
      name: "Other",
      layout: "flex",
      props: {},
      children: [
        { id: "other-label", kind: "component", name: "L", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "param", name: "text" } } } },
      ],
    },
  },
});

function host(type: string, props: Record<string, unknown>): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Host", path: "host" },
    spec: {
      width: 600,
      height: 400,
      params: {},
      root: {
        id: "host-root",
        kind: "container",
        name: "Host",
        layout: "flex",
        props: { direction: "column" },
        children: [{ id: "widget", kind: "component", name: "Widget", type, props }],
      },
    },
  });
}

function session(view: ViewDocument, extra: ViewDocument[] = []) {
  const [transport] = createLinkedTransports();
  return (
    <ViewRenderer
      view={view}
      views={[card, other, view, ...extra]}
      registry={registry}
      transport={transport}
      session={createSession({ id: "s", theme: "daylight", locale: "en-US" })}
      viewportWidth={800}
    />
  );
}

function labels(): string[] {
  return [...document.querySelectorAll("[data-component='flex-repeater'] [data-component='label']")].map((el) => el.textContent ?? "");
}

describe("flex repeater", () => {
  it("builds instance params, keeping style and position out of them", () => {
    const instances = repeaterInstances([
      { name: "A", instanceStyle: { color: "red" }, instancePosition: { grow: 1, basis: 80 } },
      { name: "B", index: 9 },
      "plain",
    ]);
    expect(instances.map((instance) => instance.params)).toEqual([
      { index: 0, name: "A" },
      { index: 9, name: "B" },
      { index: 2, value: "plain" },
    ]);
    expect(instances[0]?.position).toEqual({ grow: 1, basis: "80px" });
    expect(instances[0]?.style).toEqual({ color: "red" });
    expect(readList('[{"a":1}]')).toEqual([{ a: 1 }]);
    expect(readList("not json")).toEqual([]);
    expect(readList('{"a":1}')).toEqual([]);
    expect(readList(null)).toEqual([]);
  });

  it("renders N instances that follow instances, each param reaching its view", () => {
    const first = host("flex-repeater", { path: "card", instances: [{ name: "Pump 1" }, { name: "Pump 2" }, { name: "Pump 3" }] });
    const { rerender } = render(session(first));
    const list = screen.getByRole("list", { name: "Repeated views" });
    expect(list).toHaveAttribute("data-instance-count", "3");
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    expect(labels()).toEqual(["Pump 1 #0", "Pump 2 #1", "Pump 3 #2"]);

    rerender(session(host("flex-repeater", { path: "card", instances: [{ name: "Pump 1" }, { name: "Tank 7" }, { name: "Pump 3" }, { name: "Valve 4" }, { name: "Pump 5" }] })));
    expect(within(list).getAllByRole("listitem")).toHaveLength(5);
    expect(labels()).toEqual(["Pump 1 #0", "Tank 7 #1", "Pump 3 #2", "Valve 4 #3", "Pump 5 #4"]);

    rerender(session(host("flex-repeater", { path: "card", instances: '[{"name":"Only"}]' })));
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    expect(labels()).toEqual(["Only #0"]);

    rerender(session(host("flex-repeater", { path: "card", instances: [] })));
    expect(list).toHaveAttribute("data-instance-count", "0");
    expect(within(list).queryAllByRole("listitem")).toHaveLength(0);
  });

  it("gives each instance its own DOM ids and lays instances out with the flex props", () => {
    render(session(host("flex-repeater", { path: "card", direction: "column", wrap: "wrap", elementPosition: { grow: 1, shrink: 0, basis: "50px" }, instances: [{ name: "a" }, { name: "b" }] })));
    const list = screen.getByRole("list", { name: "Repeated views" });
    expect(list.style.flexDirection).toBe("column");
    expect(list.style.flexWrap).toBe("wrap");
    const items = within(list).getAllByRole("listitem");
    expect(items[0]?.style.flexGrow).toBe("1");
    expect(items[0]?.style.flexBasis).toBe("50px");
    expect(document.querySelector("[data-node-id='widget-0__card-label']")).not.toBeNull();
    expect(document.querySelector("[data-node-id='widget-1__card-label']")).not.toBeNull();
  });

  it("shows an error for an unknown view or a view that embeds itself", () => {
    render(session(host("flex-repeater", { path: "missing", instances: [{}] })));
    expect(screen.getByRole("alert")).toHaveTextContent("Unknown view: missing");
  });

  it("refuses a repeater that repeats its own view, once per instance and with no nested repeater", () => {
    render(session(host("flex-repeater", { path: "host", instances: [{}, {}, {}] })));
    const alerts = screen.getAllByRole("alert");
    expect(alerts).toHaveLength(3);
    for (const alert of alerts) expect(alert).toHaveTextContent("View embeds itself: host");
    expect(document.querySelectorAll("[data-component='flex-repeater']")).toHaveLength(1);
    expect(screen.getAllByRole("list")).toHaveLength(1);
  });

  it("refuses a cycle through another view (A embeds B, B embeds A)", () => {
    const a = host("flex-repeater", { path: "b-view", instances: [{}, {}] });
    const b: ViewDocument = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "B", path: "b-view" },
      spec: {
        width: 200,
        height: 100,
        params: {},
        root: {
          id: "b-root",
          kind: "container",
          name: "B",
          layout: "flex",
          props: {},
          children: [{ id: "b-embed", kind: "component", name: "Embed", type: "embedded-view", props: { path: "host" } }],
        },
      },
    });
    render(session(a, [b]));
    // Two B instances, each refusing to embed A again.
    expect(document.querySelectorAll("[data-embedded-view='b-view']")).toHaveLength(2);
    const alerts = screen.getAllByRole("alert");
    expect(alerts).toHaveLength(2);
    for (const alert of alerts) expect(alert).toHaveTextContent("View embeds itself: host");
    expect(document.querySelectorAll("[data-component='flex-repeater']")).toHaveLength(1);
  });

  it("shows an inline error for malformed instances JSON", () => {
    render(session(host("flex-repeater", { path: "card", instances: "[{oops" })));
    expect(screen.getByRole("alert")).toHaveTextContent("Instances is not valid JSON");
    expect(screen.getByRole("list", { name: "Repeated views" })).toHaveAttribute("data-instance-count", "0");
    expect(listError('{"a":1}')).toBe("is not a JSON array");
    expect(listError("[]")).toBeNull();
    expect(listError("")).toBeNull();
    expect(listError([1])).toBeNull();
  });
});

describe("carousel", () => {
  it("steps and clamps panes", () => {
    expect(stepPane(0, 1, 3)).toBe(1);
    expect(stepPane(2, 1, 3)).toBe(0);
    expect(stepPane(0, -1, 3)).toBe(2);
    expect(stepPane(0, 1, 0)).toBe(0);
    expect(clampPane(7, 3)).toBe(2);
    expect(clampPane(-1, 3)).toBe(0);
    expect(readSlides(["card", { viewPath: "other", viewParams: { text: "x" } }, 4])).toEqual([
      { viewPath: "card", viewParams: {} },
      { viewPath: "other", viewParams: { text: "x" } },
    ]);
  });

  it("shows one view at a time and moves with arrows and dots", () => {
    const views = [
      { viewPath: "card", viewParams: { name: "First" } },
      { viewPath: "other", viewParams: { text: "Second" } },
      { viewPath: "card", viewParams: { name: "Third" } },
    ];
    render(session(host("carousel", { label: "Line views", views })));
    const carousel = screen.getByRole("region", { name: "Line views" });
    expect(carousel).toHaveAttribute("data-active-pane", "0");
    expect(within(carousel).getByText("First #-1")).toBeInTheDocument();
    fireEvent.click(within(carousel).getByRole("button", { name: "Next view" }));
    expect(carousel).toHaveAttribute("data-active-pane", "1");
    expect(within(carousel).getByText("Second")).toBeInTheDocument();
    expect(within(carousel).queryByText("First #-1")).toBeNull();
    fireEvent.click(within(carousel).getByRole("button", { name: "Show view 3 of 3" }));
    expect(within(carousel).getByText("Third #-1")).toBeInTheDocument();
    expect(within(carousel).getByRole("button", { name: "Show view 3 of 3" })).toHaveAttribute("aria-current", "true");
    fireEvent.click(within(carousel).getByRole("button", { name: "Next view" }));
    expect(carousel).toHaveAttribute("data-active-pane", "0");
    fireEvent.click(within(carousel).getByRole("button", { name: "Previous view" }));
    expect(carousel).toHaveAttribute("data-active-pane", "2");
  });

  it("rotates on its own when autoplay is on", () => {
    vi.useFakeTimers();
    try {
      render(session(host("carousel", { views: ["card", "other"], autoplay: { enabled: true, transitionDelay: 1000, pauseOnHover: true } })));
      const carousel = screen.getByRole("region", { name: "Carousel" });
      expect(carousel).toHaveAttribute("data-active-pane", "0");
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(carousel).toHaveAttribute("data-active-pane", "1");
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(carousel).toHaveAttribute("data-active-pane", "0");
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("carousel pausing", () => {
  it("stays paused while focus is inside even after the pointer leaves", () => {
    vi.useFakeTimers();
    try {
      render(session(host("carousel", { views: ["card", "other"], autoplay: { enabled: true, transitionDelay: 1000, pauseOnHover: true } })));
      const carousel = screen.getByRole("region", { name: "Carousel" });
      const next = within(carousel).getByRole("button", { name: "Next view" });
      fireEvent.pointerEnter(carousel);
      act(() => {
        next.focus();
      });
      fireEvent.pointerLeave(carousel);
      act(() => {
        vi.advanceTimersByTime(3000);
      });
      expect(carousel).toHaveAttribute("data-active-pane", "0");
      // Focus moving to another of its own buttons keeps it paused.
      act(() => {
        within(carousel).getByRole("button", { name: "Show view 2 of 2" }).focus();
      });
      act(() => {
        vi.advanceTimersByTime(3000);
      });
      expect(carousel).toHaveAttribute("data-active-pane", "0");
      act(() => {
        (document.activeElement as HTMLElement).blur();
      });
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(carousel).toHaveAttribute("data-active-pane", "1");
    } finally {
      vi.useRealTimers();
    }
  });

  it("shows an inline error for views that are not a JSON array", () => {
    render(session(host("carousel", { views: '{"viewPath":"card"}' })));
    expect(within(screen.getByRole("region", { name: "Carousel" })).getByRole("alert")).toHaveTextContent("Views is not a JSON array");
  });
});

describe("accordion", () => {
  it("toggles single and multiple expansion", () => {
    expect(toggleAccordion([false, false, false], 1, "multiple")).toEqual([false, true, false]);
    expect(toggleAccordion([true, false, false], 1, "multiple")).toEqual([true, true, false]);
    expect(toggleAccordion([true, false, true], 1, "single")).toEqual([false, true, false]);
    expect(toggleAccordion([true, true], 0, "single")).toEqual([false, true]);
    expect(readAccordionItems([{ header: "Plain" }, { header: { content: { type: "text", text: "Nested" } }, expanded: true }, 3]).map((item) => [item.headerText, item.expanded])).toEqual([
      ["Plain", false],
      ["Nested", true],
    ]);
  });

  it("embeds each body view only while expanded", () => {
    const items = [
      { header: { content: { type: "text", text: "Pumps" } }, expanded: true, body: { viewPath: "card", viewParams: { name: "P-101" } } },
      { header: { content: { type: "text", text: "Notes" } }, expanded: false, body: { viewPath: "other", viewParams: { text: "Shift notes" } } },
    ];
    render(session(host("accordion", { label: "Areas", expansionMode: "single", items })));
    const pumps = screen.getByRole("button", { name: "Pumps" });
    const notes = screen.getByRole("button", { name: "Notes" });
    expect(pumps).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("P-101 #-1")).toBeInTheDocument();
    expect(screen.queryByText("Shift notes")).toBeNull();
    fireEvent.click(notes);
    expect(notes).toHaveAttribute("aria-expanded", "true");
    expect(pumps).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("Shift notes")).toBeInTheDocument();
    expect(screen.queryByText("P-101 #-1")).toBeNull();
  });
});

describe("accordion with skipped entries", () => {
  it("toggles the clicked item when items holds non-objects, and writes back to its raw position", () => {
    const items = [
      null,
      { header: "Alpha", expanded: false, body: { viewPath: "card", viewParams: { name: "A body" } } },
      { header: "Beta", expanded: false, body: { viewPath: "card", viewParams: { name: "B body" } } },
    ];
    render(session(host("accordion", { label: "Mixed", expansionMode: "single", items })));
    expect(readAccordionItems(items).map((item) => item.rawIndex)).toEqual([1, 2]);
    fireEvent.click(screen.getByRole("button", { name: "Beta" }));
    expect(screen.getByRole("button", { name: "Beta" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("button", { name: "Alpha" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("B body #-1")).toBeInTheDocument();
    expect(screen.queryByText("A body #-1")).toBeNull();
  });

  it("shows an inline error for malformed items JSON", () => {
    render(session(host("accordion", { items: "not json" })));
    expect(screen.getByRole("alert")).toHaveTextContent("Items is not valid JSON");
  });
});

describe("palette", () => {
  it("registers every C-15 container and embedding component", () => {
    for (const type of ["flex-repeater", "carousel", "accordion"]) {
      const module = registry.get(type);
      expect(module, type).toBeDefined();
      expect(module?.schema.type).toBe("object");
    }
    expect(containerModules.map((module) => module.layout)).toEqual(["breakpoint", "split"]);
    expect(flexRepeaterSchema.properties?.instances?.default).toBe("[]");
  });
});
