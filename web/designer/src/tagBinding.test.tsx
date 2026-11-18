import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { allowedComponentTypes, componentModules, componentRegistry } from "@oip/components";
import { createSession, findNode, parseView, type ComponentNode, type ViewDocument } from "@oip/hmi-runtime";
import { Canvas, selectedComponent } from "./Canvas";
import { Inspector } from "./Inspector";
import { updateNode } from "./project";
import { createTagRuntime, seedStudio, type TagRuntime } from "./runtime";
import { DEFAULT_TAG_PROP, TAG_DRAG_TYPE, bindDroppedTag, dropProperty, encodeDraggedTag, tagBindingForProp, type DraggedTag } from "./tagDrop";
import { TagSourcesContext, localBrowse, type TagSources } from "./tagSource";

/** D-6: drag a tag onto a property row, the default property per component type, and the "Browse…" picker. */

const registry = componentRegistry();

function fixture(): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Drop", path: "drop" },
      spec: {
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
              id: "title",
              kind: "component",
              name: "Title",
              type: "label",
              frame: { x: 0, y: 0, width: 200, height: 30 },
              props: { text: "North tank", align: "start" },
              bindings: { align: { kind: "expression", expression: "'start'", transforms: [{ kind: "format", type: "numeric", mode: "percent" }] } },
            },
            {
              id: "level",
              kind: "component",
              name: "Level",
              type: "numeric",
              frame: { x: 0, y: 40, width: 200, height: 30 },
              props: { value: 7, decimals: 1, prefix: "", suffix: " m", announce: false },
            },
          ],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

function Harness({ runtime, initialSelected, sources, state }: { runtime: TagRuntime; initialSelected: string; sources?: TagSources; state: { view?: ViewDocument } }) {
  const [view, setView] = useState(fixture);
  const [selectedId, setSelectedId] = useState<string | null>(initialSelected);
  state.view = view;
  return (
    <TagSourcesContext.Provider value={sources ?? {}}>
      <Inspector view={view} selected={selectedComponent(view, selectedId)} tags={runtime.server} onView={setView} />
      <Canvas
        view={view}
        views={[view]}
        registry={registry}
        transport={runtime.transport}
        session={createSession({ id: "d6", locale: "en-US", theme: "daylight" })}
        selectedId={selectedId}
        onSelect={setSelectedId}
        onFrame={(id, frame) => setView(updateNode(view, id, { frame }))}
      />
    </TagSourcesContext.Provider>
  );
}

/** happy-dom drops dataTransfer from DragEvent init, so it is set on the event. */
function dropTag(target: Element, tag: DraggedTag) {
  const data = new Map<string, string>([
    [TAG_DRAG_TYPE, encodeDraggedTag(tag)],
    ["text/plain", tag.path],
  ]);
  const dataTransfer = { types: [TAG_DRAG_TYPE, "text/plain"], getData: (type: string) => data.get(type) ?? "", dropEffect: "none" };
  for (const type of ["dragover", "drop"]) {
    const event = new Event(type, { bubbles: true, cancelable: true });
    Object.defineProperty(event, "dataTransfer", { value: dataTransfer });
    fireEvent(target, event);
  }
}

const node = (state: { view?: ViewDocument }, id: string) => findNode(state.view!.spec.root, id) as ComponentNode;

describe("drag a tag onto a property", () => {
  it("binds that property, opens its binding editor, and the canvas shows the live value at once", async () => {
    const runtime = createTagRuntime(seedStudio);
    const state: { view?: ViewDocument } = {};
    render(<Harness runtime={runtime} initialSelected="level" state={state} />);
    const canvas = screen.getByTestId("canvas");
    const value = () => canvas.querySelector("[data-node-id='level'] [data-testid='numeric-value']");
    await waitFor(() => expect(value()).toHaveTextContent("7.0 m"));

    const row = document.querySelector(".oip-prop[data-prop='value']")!;
    dropTag(row, { path: "plant/tank/level", dataType: "double" });
    expect(node(state, "level").bindings?.value).toEqual({ kind: "tag", mode: "direct", path: "plant/tank/level" });
    const editor = screen.getByRole("region", { name: "Binding for value" });
    expect(within(editor).getByRole("textbox", { name: "Tag path" })).toHaveValue("plant/tank/level");
    await waitFor(() => expect(value()).toHaveTextContent("62.4 m"));

    // The bound row shows the value and its quality.
    const bound = screen.getByRole("button", { name: "Value: Tag binding" });
    await waitFor(() => expect(bound).toHaveTextContent("62.4"));
    expect(bound.querySelector(".oip-quality-dot")).toHaveAttribute("data-quality", "good");
    act(() => runtime.server.set("plant/tank/level", 9, "bad", "bad_not_connected"));
    await waitFor(() => expect(bound.querySelector(".oip-quality-dot")).toHaveAttribute("data-quality", "bad"), { timeout: 2000 });
    runtime.server.close();
  });

  it("binds a numeric tag dropped on a text property with a number format, and keeps the transforms of a bound property", () => {
    const runtime = createTagRuntime(seedStudio);
    const state: { view?: ViewDocument } = {};
    render(<Harness runtime={runtime} initialSelected="title" state={state} />);
    dropTag(document.querySelector(".oip-prop[data-prop='text']")!, { path: "[default]tank/level", dataType: "double" });
    expect(node(state, "title").bindings?.text).toEqual({
      kind: "tag",
      mode: "direct",
      path: "[default]tank/level",
      transforms: [{ kind: "format", type: "numeric", mode: "number" }],
    });
    // align had an expression binding with a transform: the tag replaces the expression, the transform stays.
    dropTag(document.querySelector(".oip-prop[data-prop='align']")!, { path: "plant/tank/note", dataType: "string" });
    expect(node(state, "title").bindings?.align).toEqual({
      kind: "tag",
      mode: "direct",
      path: "plant/tank/note",
      transforms: [{ kind: "format", type: "numeric", mode: "percent" }],
    });
    expect(() => parseView(state.view, { allowedTypes: allowedComponentTypes() })).not.toThrow();
    runtime.server.close();
  });

  it("ignores drags that carry no tag", () => {
    const runtime = createTagRuntime(seedStudio);
    const state: { view?: ViewDocument } = {};
    render(<Harness runtime={runtime} initialSelected="level" state={state} />);
    const row = document.querySelector(".oip-prop[data-prop='value']")!;
    const event = new Event("drop", { bubbles: true, cancelable: true });
    Object.defineProperty(event, "dataTransfer", { value: { types: ["text/plain"], getData: () => "plain text" } });
    fireEvent(row, event);
    expect(node(state, "level").bindings).toBeUndefined();
    runtime.server.close();
  });

  it("turns an indirect tag binding into a direct one on the dropped path", () => {
    expect(
      tagBindingForProp({ path: "[default]b" }, undefined, {
        kind: "tag",
        mode: "indirect",
        path: "x/{a}",
        references: { a: { source: "param", name: "a" } },
        bidirectional: true,
      }),
    ).toEqual({ kind: "tag", mode: "direct", path: "[default]b", bidirectional: true });
  });
});

describe("default property for a tag dropped on a component", () => {
  it("names every registered component type explicitly, with a property its schema has", () => {
    for (const module of componentModules) {
      expect(Object.prototype.hasOwnProperty.call(DEFAULT_TAG_PROP, module.type), `${module.type} has a default tag property entry`).toBe(true);
      const prop = DEFAULT_TAG_PROP[module.type];
      if (prop !== null) expect(Object.keys(module.schema.properties ?? {}), module.type).toContain(prop);
    }
  });

  it.each([
    ["label", "text"],
    ["numeric", "value"],
    ["button", "text"],
    ["vessel", "level"],
    ["cylindrical-tank", "level"],
    ["pump", "state"],
    ["sparkline", "points"],
    ["table", null],
    ["embedded-view", null],
  ])("%s binds %s", (type, prop) => {
    expect(dropProperty(registry.get(type)?.schema, type)).toBe(prop);
  });

  it("falls back to value, then text, for a type with no entry", () => {
    expect(dropProperty({ type: "object", properties: { text: { type: "string" }, value: { type: "number" } } }, "custom-x")).toBe("value");
    expect(dropProperty({ type: "object", properties: { text: { type: "string" } } }, "custom-x")).toBe("text");
    expect(dropProperty({ type: "object", properties: { other: { type: "string" } } }, "custom-x")).toBeNull();
  });

  it("does not bind a component type with no default property", () => {
    const view = parseView(
      {
        apiVersion: "oip.hmi/v1",
        kind: "View",
        metadata: { name: "T", path: "t" },
        spec: {
          width: 100,
          height: 100,
          root: { id: "root", kind: "container", name: "Root", layout: "coordinate", props: { mode: "fixed" }, children: [{ id: "t", kind: "component", name: "T", type: "table", frame: { x: 0, y: 0, width: 50, height: 50 }, props: {} }] },
        },
      },
      { allowedTypes: allowedComponentTypes() },
    );
    expect(bindDroppedTag(view, "t", { path: "[default]a" }, (type) => registry.get(type)?.schema)).toBeNull();
  });
});

describe("tag picker in the binding editor", () => {
  it("opens from Browse…, picks a tag with the keyboard, and returns focus", async () => {
    const runtime = createTagRuntime(seedStudio);
    const state: { view?: ViewDocument } = {};
    render(<Harness runtime={runtime} initialSelected="level" state={state} sources={{ local: localBrowse(runtime.server) }} />);
    fireEvent.click(screen.getByRole("button", { name: "Bind Value" }));
    const editor = screen.getByRole("region", { name: "Binding for value" });
    fireEvent.click(within(editor).getByRole("radio", { name: "Tag" }));
    const browse = within(editor).getByRole("button", { name: "Browse…" });
    fireEvent.click(browse);
    const dialog = screen.getByRole("dialog", { name: "Pick a tag" });
    expect(within(dialog).getByLabelText("Filter tags")).toHaveFocus();
    fireEvent.click(await within(dialog).findByRole("treeitem", { name: /plant/ }));
    fireEvent.click(await within(dialog).findByRole("treeitem", { name: /tank/ }));
    const level = await within(dialog).findByRole("treeitem", { name: /level/ });
    expect(level).not.toHaveAttribute("draggable", "true");
    fireEvent.keyDown(level, { key: "Enter" });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(node(state, "level").bindings?.value).toEqual({ kind: "tag", mode: "direct", path: "plant/tank/level" });
    expect(browse).toHaveFocus();

    fireEvent.click(browse);
    fireEvent.keyDown(screen.getByRole("dialog", { name: "Pick a tag" }), { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    runtime.server.close();
  });

  it("has no Browse… button when there is no tag source", () => {
    const runtime = createTagRuntime(seedStudio);
    const state: { view?: ViewDocument } = {};
    render(<Harness runtime={runtime} initialSelected="level" state={state} />);
    fireEvent.click(screen.getByRole("button", { name: "Bind Value" }));
    fireEvent.click(screen.getByRole("radio", { name: "Tag" }));
    expect(screen.queryByRole("button", { name: "Browse…" })).toBeNull();
    runtime.server.close();
  });
});
