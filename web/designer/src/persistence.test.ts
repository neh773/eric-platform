import { describe, expect, it } from "vitest";
import { GatewayClient, GatewayUnreachable } from "./gatewayClient";
import { fakeGateway } from "./fakeGateway.testutil";
import {
  STYLE_CLASSES_KIND,
  SaveConflict,
  StyleClassesConflict,
  loadStyleClasses,
  saveStyleClasses,
  blankView,
  fromResource,
  loadProjectViews,
  resourceNameFor,
  saveView,
  toResource,
  viewNameProblem,
} from "./persistence";
import { sampleProject } from "./sampleProject";

describe("view resources", () => {
  it("round-trips a view through the stored resource shape", () => {
    const view = sampleProject.views[0]!;
    const resource = toResource(view, { name: "overview", version: 3 });
    expect(resource.metadata).toEqual({ name: "overview", module: "designer", version: 3 });
    expect(resource.spec.path).toBe(view.metadata.path);
    expect(fromResource(resource)).toEqual(view);
  });

  it("derives a DNS-label resource name and avoids names already used", () => {
    expect(resourceNameFor("Line 1", [])).toBe("line-1");
    expect(resourceNameFor("Line 1", ["line-1"])).toBe("line-1-2");
    expect(resourceNameFor("Area/Pump #4", [])).toBe("area-pump-4");
    expect(resourceNameFor("!!!", [])).toBe("view");
    expect(resourceNameFor("x".repeat(200), [])).toMatch(/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/);
  });

  it("rejects empty and duplicate view names", () => {
    expect(viewNameProblem("  ", [])).toMatch(/Enter/);
    expect(viewNameProblem("overview", ["Overview"])).toMatch(/already exists/);
    expect(viewNameProblem("Line 1", ["Overview"])).toBeNull();
    expect(blankView("line-1", "Line 1").metadata).toEqual({ name: "Line 1", path: "line-1" });
  });
});

describe("saving to the gateway", () => {
  it("creates, then updates with the stored version, sending the CSRF token", async () => {
    const { client, calls } = fakeGateway();
    await client.session();
    const view = blankView("line-1", "Line 1");
    const first = await saveView(client, "demo", view, { name: "line-1", version: 0 });
    expect(first).toEqual({ name: "line-1", version: 1 });
    const second = await saveView(client, "demo", view, first);
    expect(second.version).toBe(2);
    const put = calls.find((call) => call.method === "PUT")!;
    expect(put.url).toBe("/api/v1/config/designer/project/demo/View/line-1");
    expect(put.headers["X-CSRF-Token"]).toBe("csrf-1");
    const loaded = await loadProjectViews(client, "demo");
    expect(loaded.views.map((entry) => [entry.view.metadata.path, entry.ref.version])).toEqual([["line-1", 2]]);
  });

  it("turns a stale version into a SaveConflict carrying the gateway copy", async () => {
    const { client } = fakeGateway();
    await client.session();
    const mine = blankView("line-1", "Line 1");
    await saveView(client, "demo", mine, { name: "line-1", version: 0 });
    const theirs = { ...mine, metadata: { ...mine.metadata, name: "Theirs" } };
    await saveView(client, "demo", theirs, { name: "line-1", version: 1 });
    const error = await saveView(client, "demo", mine, { name: "line-1", version: 1 }).catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(SaveConflict);
    const conflict = error as SaveConflict;
    expect(conflict.theirs?.ref).toEqual({ name: "line-1", version: 2 });
    expect(conflict.theirs?.view.metadata.name).toBe("Theirs");
  });

  it("gives a readable error when the gateway copy in a conflict cannot be read", async () => {
    const { client, store } = fakeGateway();
    await client.session();
    const mine = blankView("line-1", "Line 1");
    await saveView(client, "demo", mine, { name: "line-1", version: 0 });
    const url = "/api/v1/config/designer/project/demo/View/line-1";
    const stored = store.get(url)!;
    store.set(url, { ...stored, metadata: { ...stored.metadata, version: 2 }, spec: { path: "Not a slug!", document: {} } });
    const error = await saveView(client, "demo", mine, { name: "line-1", version: 1 }).catch((caught: unknown) => caught);
    expect(error).not.toBeInstanceOf(SaveConflict);
    expect((error as Error).message).toMatch(/^View "Line 1" was changed on the gateway, and the gateway copy could not be read: /);
  });

  it("reports a missing gateway as unreachable, not as signed out", async () => {
    const offline = new GatewayClient(async () => new Response("proxy error", { status: 502, headers: { "content-type": "text/plain" } }));
    await expect(offline.session()).rejects.toBeInstanceOf(GatewayUnreachable);
    const refused = new GatewayClient(async () => {
      throw new TypeError("fetch failed");
    });
    await expect(refused.session()).rejects.toBeInstanceOf(GatewayUnreachable);
    const signedOut = new GatewayClient(async () => new Response("{}", { status: 401, headers: { "content-type": "application/problem+json" } }));
    await expect(signedOut.session()).resolves.toBeNull();
  });
});

describe("style class resources", () => {
  it("saves the project's style classes as one versioned resource and loads them back", async () => {
    const { client, calls } = fakeGateway();
    await client.session();
    expect(await loadStyleClasses(client, "demo")).toBeNull();
    const first = await saveStyleClasses(client, "demo", sampleProject.styleClasses!, { name: "style-classes", version: 0 });
    expect(first).toEqual({ name: "style-classes", version: 1 });
    const put = calls.find((call) => call.method === "PUT")!;
    expect(put.url).toBe(`/api/v1/config/designer/project/demo/${STYLE_CLASSES_KIND}/style-classes`);
    const edited = { ...sampleProject.styleClasses!, pump: { style: { background: "#00ff00", padding: 4 } } };
    await saveStyleClasses(client, "demo", edited, first);
    const loaded = await loadStyleClasses(client, "demo");
    expect(loaded).toEqual({ styleClasses: edited, ref: { name: "style-classes", version: 2 } });
  });

  it("turns a stale version into a StyleClassesConflict carrying the gateway copy", async () => {
    const { client } = fakeGateway();
    await client.session();
    await saveStyleClasses(client, "demo", { a: { style: { color: "red" } } }, { name: "style-classes", version: 0 });
    await saveStyleClasses(client, "demo", { a: { style: { color: "blue" } } }, { name: "style-classes", version: 1 });
    const error = await saveStyleClasses(client, "demo", { a: { style: { color: "green" } } }, { name: "style-classes", version: 1 }).catch(
      (caught: unknown) => caught,
    );
    expect(error).toBeInstanceOf(StyleClassesConflict);
    const conflict = error as StyleClassesConflict;
    expect(conflict.theirs).toEqual({ styleClasses: { a: { style: { color: "blue" } } }, ref: { name: "style-classes", version: 2 } });
  });
});
