import { describe, expect, it } from "vitest";
import { ViewModelError } from "./model";
import { docksFor, fillPageUrl, matchPage, parsePage, resolveDock } from "./pages";
import type { HmiProject } from "./types";

/** Page URLs and docks.
 */

function page(url: string, docks: unknown[] = [], extra: Record<string, unknown> = {}) {
  return parsePage({
    apiVersion: "oip.hmi/v1",
    kind: "Page",
    metadata: { name: url },
    spec: { url, primaryView: "home", docks, ...extra },
  });
}

describe("pages", () => {
  it("maps a :param segment to the raw decoded string and keeps the query on the page", () => {
    const matched = matchPage([page("/tower/:towerNumber")], "/tower/2?line=3");
    expect(matched?.params).toEqual({ towerNumber: "2" });
    expect(matched?.urlParams).toEqual({ line: "3" });
    expect(matched?.path).toBe("/tower/2");
  });

  it("prefers a static segment over a parameter and rejects a bad url", () => {
    const matched = matchPage([page("/:id"), page("/pumps")], "/pumps");
    expect(matched?.page.spec.url).toBe("/pumps");
    expect(matched?.params).toEqual({});
    expect(() => page("pumps")).toThrow(ViewModelError);
    expect(() => page("/bad segment")).toThrow(/invalid page url segment/);
    expect(() => fillPageUrl("/tower/:towerNumber", { towerNumber: "a/b" })).toThrow(/invalid character/);
  });

  it("allows one dock per edge and lets a page dock replace the shared dock on that edge", () => {
    expect(() => page("/", [{ id: "a", edge: "left", view: "nav", size: 10 }, { id: "b", edge: "left", view: "nav", size: 12 }])).toThrow(/more than one left/);
    const project: Pick<HmiProject, "sharedDocks" | "cornerPriority"> = {
      sharedDocks: [{ id: "shared", edge: "left", view: "shared", size: 100, display: "visible", content: "push", handle: "hide" }],
      cornerPriority: "left-right",
    };
    const resolved = docksFor(page("/", [{ id: "page", edge: "left", view: "page", size: 40, display: "onDemand", content: "cover", handle: "show" }]), project);
    expect(resolved.docks.map((dock) => dock.id)).toEqual(["page"]);
    expect(docksFor(page("/"), project).corner).toBe("left-right");
    expect(docksFor(page("/"), {}).corner).toBe("top-bottom");
  });

  it("resolves display, content, handle, and modal the way the dock page describes", () => {
    const visible = { id: "nav", edge: "left" as const, view: "nav", size: 80, display: "visible" as const, content: "push" as const, handle: "autoHide" as const };
    expect(resolveDock(visible, 1200, undefined)).toMatchObject({ open: true, content: "push", showHandle: false, modal: false });
    expect(resolveDock({ ...visible, display: "onDemand", modal: true }, 1200, undefined)).toMatchObject({ open: false, showHandle: true, modal: false });
    expect(resolveDock({ ...visible, display: "onDemand", modal: true }, 1200, true)).toMatchObject({ open: true, modal: true });
    expect(resolveDock({ ...visible, display: "visible", modal: true }, 1200, undefined).modal).toBe(false);
    expect(resolveDock({ ...visible, display: "auto", content: "auto" }, 500, undefined)).toMatchObject({ open: false, content: "cover", showHandle: true });
    expect(resolveDock({ ...visible, display: "auto", content: "auto" }, 900, undefined)).toMatchObject({ open: true, content: "push", showHandle: false });
    expect(resolveDock({ ...visible, handle: "show" }, 1200, undefined).showHandle).toBe(true);
    expect(resolveDock({ ...visible, handle: "hide" }, 400, false).showHandle).toBe(false);
  });
});
