import { describe, expect, it } from "vitest";
import { parseView, ViewModelError } from "./model";

/** Events, custom properties, and the read-only binding kinds.
 */

const shell = {
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Events", path: "events" },
  spec: {
    width: 200,
    height: 80,
    params: {},
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "flex",
      props: { direction: "column" },
      children: [] as unknown[],
    },
  },
};

describe("view model extensions", () => {
  it("refuses a url navigation action that is not a page path or an http(s) URL (review-4 F3)", () => {
    const withUrl = (url: string) => ({
      ...shell,
      spec: {
        ...shell.spec,
        root: {
          ...shell.spec.root,
          children: [{ id: "go", kind: "component", name: "Go", type: "button", props: {}, events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "url", url }] }] }],
        },
      },
    });
    for (const url of ["javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,x", "vbscript:msgbox(1)", "//evil.example/", "/\\evil.example", "ftp://host/file"]) {
      expect(() => parseView(withUrl(url)), url).toThrow(ViewModelError);
    }
    for (const url of ["https://example.com/a", "http://example.com/", "/pumps?line=3"]) {
      expect(() => parseView(withUrl(url)), url).not.toThrow();
    }
  });

  it("keeps custom properties and component events", () => {
    const view = parseView({
      ...shell,
      spec: {
        ...shell.spec,
        custom: { area: { value: "north" } },
        root: {
          ...shell.spec.root,
          children: [
            {
              id: "go",
              kind: "component",
              name: "Go",
              type: "button",
              props: { text: "Go" },
              events: [{ event: "onClick", actions: [{ kind: "tag-write", path: "plant/pump/command", value: true }] }],
            },
          ],
        },
      },
    });
    expect(view.spec.custom.area?.value).toBe("north");
    const button = view.spec.root.children[0];
    expect(button?.kind === "component" && button.events?.[0]?.actions[0]?.kind).toBe("tag-write");
  });

  it("rejects a bidirectional expression or query and an unknown binding kind", () => {
    const withBinding = (binding: unknown) => ({
      ...shell,
      spec: {
        ...shell.spec,
        root: {
          ...shell.spec.root,
          children: [{ id: "label", kind: "component", name: "Label", type: "label", props: { text: "" }, bindings: { text: binding } }],
        },
      },
    });
    expect(() => parseView(withBinding({ kind: "expression", expression: "1", bidirectional: true }))).toThrow(/expression bindings are read-only/);
    expect(() => parseView(withBinding({ kind: "query", path: "wells", bidirectional: true }))).toThrow(/query bindings are read-only/);
    expect(() => parseView(withBinding({ kind: "http", path: "/x" }))).toThrow(ViewModelError);
  });

  it("parses a tab container without frames on its children", () => {
    const view = parseView({
      ...shell,
      spec: {
        ...shell.spec,
        root: {
          id: "tabs",
          kind: "container",
          name: "Tabs",
          layout: "tab",
          props: { currentTab: 1, tabs: [{ label: "One" }, { label: "Two" }] },
          children: [
            { id: "one", kind: "component", name: "One", type: "label", tabLabel: "One", props: { text: "first" } },
            { id: "two", kind: "component", name: "Two", type: "label", props: { text: "second" } },
          ],
        },
      },
    });
    expect(view.spec.root.layout).toBe("tab");
    expect(view.spec.root.props.currentTab).toBe(1);
    expect(view.spec.root.children[0]?.tabLabel).toBe("One");
  });
});
