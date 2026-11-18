import { describe, expect, it } from "vitest";
import { parseView, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";
import type { ConfigResource } from "./gatewayClient";
import { toResource, type StyleClassesSpec, type ViewSpec } from "./persistence";
import { buildHmiProject, chooseDefaultProject, derivedPages, hmiUrl, pagesFromConfig, parseHmiUrl, type PageConfigSpec } from "./hmiProject";

/** H-1: what /hmi loads, from which URL, and how stored resources become session pages. */

function view(path: string, name = path): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name, path },
      spec: { params: {}, width: 320, height: 120, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children: [] } },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

function stored(doc: ViewDocument, version = 1): ConfigResource<ViewSpec> {
  return toResource(doc, { name: doc.metadata.path, version });
}

function pageConfig(spec: PageConfigSpec, version = 1): ConfigResource<PageConfigSpec> {
  return { apiVersion: "oip/v1", kind: "PageConfig", metadata: { name: "page-config", module: "designer", version }, spec };
}

function styles(classes: Record<string, unknown>, version = 1): ConfigResource<StyleClassesSpec> {
  return { apiVersion: "oip/v1", kind: "StyleClasses", metadata: { name: "style-classes", module: "designer", version }, spec: { classes } as StyleClassesSpec };
}

describe("parseHmiUrl", () => {
  it("reads the project and the page path", () => {
    expect(parseHmiUrl("/hmi/plant", "")).toEqual({ kind: "project", project: "plant", location: "/" });
    expect(parseHmiUrl("/hmi/plant/", "")).toEqual({ kind: "project", project: "plant", location: "/" });
    expect(parseHmiUrl("/hmi/plant/pumps/", "")).toEqual({ kind: "project", project: "plant", location: "/pumps" });
    expect(parseHmiUrl("/hmi/plant/line/3", "?mode=auto")).toEqual({ kind: "project", project: "plant", location: "/line/3?mode=auto" });
  });

  it("leaves the project open when the URL does not name one", () => {
    expect(parseHmiUrl("/hmi", "")).toEqual({ kind: "default", search: "" });
    expect(parseHmiUrl("/hmi/", "?theme=nightwatch")).toEqual({ kind: "default", search: "?theme=nightwatch" });
  });

  it("reads the earlier ?project= form as the project", () => {
    expect(parseHmiUrl("/hmi/", "?project=plant&theme=nightwatch")).toEqual({ kind: "project", project: "plant", location: "/?theme=nightwatch" });
  });

  it("keeps the built-in sample under a name no project can have", () => {
    expect(parseHmiUrl("/hmi/_sample/", "")).toEqual({ kind: "sample", location: "/" });
    expect(parseHmiUrl("/hmi/_sample/pumps", "?project=x")).toEqual({ kind: "sample", location: "/pumps?project=x" });
  });

  it("builds the browser URL back", () => {
    expect(hmiUrl("plant", "/")).toBe("/hmi/plant/");
    expect(hmiUrl("plant", "/pumps?line=3")).toBe("/hmi/plant/pumps?line=3");
  });
});

describe("chooseDefaultProject", () => {
  it("prefers the project named default, else the only project", () => {
    expect(chooseDefaultProject(["a", "default", "z"])).toBe("default");
    expect(chooseDefaultProject(["plant"])).toBe("plant");
    expect(chooseDefaultProject(["a", "b"])).toBeNull();
    expect(chooseDefaultProject([])).toBeNull();
  });
});

describe("pages", () => {
  it("derives one page per view, with a home view at /", () => {
    const pages = derivedPages([view("pumps", "Pumps"), view("home", "Home"), view("alarms")]);
    expect(pages.map((page) => [page.spec.url, page.spec.primaryView])).toEqual([
      ["/", "home"],
      ["/alarms", "alarms"],
      ["/home", "home"],
      ["/pumps", "pumps"],
    ]);
    expect(pages[0]!.spec.title).toBe("Home");
    // Without a home, main or overview view, the first view by path is the home page.
    expect(derivedPages([view("b"), view("a")])[0]!.spec.primaryView).toBe("a");
    expect(derivedPages([])).toEqual([]);
  });

  it("reads a stored page configuration and reports entries it cannot use", () => {
    const result = pagesFromConfig({
      pages: [
        { url: "/", title: "Home", primaryView: "home" },
        { url: "/line/:id", primaryView: "line", docks: [{ id: "nav", edge: "left", view: "nav", size: 120 }] },
        { url: "/bad url", primaryView: "x" },
      ],
      sharedDocks: [{ id: "top", edge: "top", view: "header", size: 48 }],
      cornerPriority: "left-right",
    });
    expect(result.pages.map((page) => page.spec.url)).toEqual(["/", "/line/:id"]);
    expect(result.pages[1]!.spec.docks[0]).toMatchObject({ id: "nav", edge: "left", display: "visible", content: "push" });
    expect(result.sharedDocks?.map((dock) => dock.id)).toEqual(["top"]);
    expect(result.cornerPriority).toBe("left-right");
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0]).toContain("/bad url");
  });
});

describe("buildHmiProject", () => {
  it("is null for a project with nothing saved", () => {
    expect(buildHmiProject("demo", [], null, null)).toBeNull();
  });

  it("uses the page configuration when there is one, and the views otherwise", () => {
    const views = [stored(view("home", "Home")), stored(view("pumps", "Pumps"))];
    const derived = buildHmiProject("demo", views, null, null)!;
    expect(derived.pagesFrom).toBe("views");
    expect(derived.project.pages.map((page) => page.spec.url)).toEqual(["/", "/home", "/pumps"]);

    const configured = buildHmiProject("demo", views, pageConfig({ pages: [{ url: "/", primaryView: "pumps", title: "Start" }] }), styles({ heading: { style: { color: "red" } } }))!;
    expect(configured.pagesFrom).toBe("config");
    expect(configured.project.pages.map((page) => [page.spec.url, page.spec.primaryView])).toEqual([["/", "pumps"]]);
    expect(configured.project.styleClasses).toEqual({ heading: { style: { color: "red" } } });
    expect(configured.project.views.map((entry) => entry.metadata.path)).toEqual(["home", "pumps"]);
  });

  it("changes its signature when any stored resource changes", () => {
    const base = buildHmiProject("demo", [stored(view("home"), 1)], null, null)!.signature;
    expect(buildHmiProject("demo", [stored(view("home"), 1)], null, null)!.signature).toBe(base);
    expect(buildHmiProject("demo", [stored(view("home"), 2)], null, null)!.signature).not.toBe(base);
    expect(buildHmiProject("demo", [stored(view("home"), 1)], pageConfig({ pages: [] }), null)!.signature).not.toBe(base);
    expect(buildHmiProject("demo", [stored(view("home"), 1)], null, styles({}))!.signature).not.toBe(base);
    // Deleted and saved again: the version starts over, but the modification time differs.
    const first = { ...stored(view("home"), 1), metadata: { name: "home", module: "designer", version: 1, modified: "2026-10-28T10:00:00Z" } };
    const again = { ...first, metadata: { ...first.metadata, modified: "2026-10-28T10:00:01Z" } };
    expect(buildHmiProject("demo", [again], null, null)!.signature).not.toBe(buildHmiProject("demo", [first], null, null)!.signature);
  });

  it("leaves out a view or style classes it cannot read and says so", () => {
    const broken = { ...stored(view("bad")), spec: { path: "bad", document: { root: "nope" } } } as unknown as ConfigResource<ViewSpec>;
    const result = buildHmiProject("demo", [stored(view("home")), broken], null, styles({ "1bad": {} }))!;
    expect(result.project.views.map((entry) => entry.metadata.path)).toEqual(["home"]);
    expect(result.errors.some((error) => error.startsWith("view bad"))).toBe(true);
    expect(result.errors.some((error) => error.startsWith("style classes"))).toBe(true);
  });
});

describe("views the user may not open (P4-04, HMP-092)", () => {
  it("keeps denied paths on the project, gives each a derived page, and changes the signature", () => {
    const open = buildHmiProject("demo", [stored(view("home"))], null, null)!;
    const refused = buildHmiProject("demo", [stored(view("home"))], null, null, ["reactor"])!;
    expect(refused.project.deniedViews).toEqual(["reactor"]);
    expect(refused.project.views.map((item) => item.metadata.path)).toEqual(["home"]);
    expect(refused.project.pages.map((item) => [item.spec.url, item.spec.primaryView])).toEqual([
      ["/", "home"],
      ["/home", "home"],
      ["/reactor", "reactor"],
    ]);
    expect(refused.signature).not.toBe(open.signature);
    expect(open.project.deniedViews).toBeUndefined();
  });

  it("still runs a project whose every view is denied", () => {
    const loaded = buildHmiProject("demo", [], null, null, ["home"])!;
    expect(loaded).not.toBeNull();
    expect(loaded.project.pages[0]!.spec.primaryView).toBe("home");
  });
});
