import { act, cleanup, render, renderHook, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { GatewayClient, type ConfigResource, type FetchLike, type ProjectInfo } from "./gatewayClient";
import { ProjectUnavailable, loadHmiProject } from "./hmiProject";
import { blankView, loadProjectViews, toResource, type ViewSpec } from "./persistence";
import { ProjectTree } from "./ProjectTree";
import { ProjectStatusLine, useProjectStatus } from "./projectStatus";
import { useSavedProject } from "./useSavedProject";

/**
 * PRO-002 in the Designer and the HMI: inherited views are marked and read-only until overridden, reads go through
 * `?inherited=true`, and a disabled project does not run (PRO-004).
 */

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" } });

function resource(path: string, version: number, inheritedFrom?: string): ConfigResource<ViewSpec> {
  const stored = toResource(blankView(path, path.toUpperCase()), { name: path, version });
  return inheritedFrom ? { ...stored, inheritedFrom } : stored;
}

function project(info: Partial<ProjectInfo> = {}): ProjectInfo {
  return {
    name: "plant",
    title: "",
    description: "",
    parent: "base",
    enabled: true,
    inheritable: false,
    runnable: true,
    launchable: true,
    inheritanceChain: ["plant", "base"],
    ...info,
  };
}

/** A gateway where `plant` stores `local` and inherits `home` from `base`. */
function inheritingGateway(info: { current: ProjectInfo | null } = { current: project() }) {
  const calls: Array<{ method: string; url: string; body?: unknown }> = [];
  const fetcher: FetchLike = async (url, init) => {
    const method = init?.method ?? "GET";
    calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (url === "/api/v1/auth/session") return json(200, { username: "admin", csrfToken: "csrf-1" });
    if (url === "/api/v1/projects/plant") return info.current ? json(200, info.current) : json(404, { title: "Not Found" });
    if (url === "/api/v1/config/designer/project/plant/View?inherited=true") {
      return json(200, { items: [resource("home", 3, "base"), resource("local", 2)] });
    }
    if (url.startsWith("/api/v1/config/designer/project/plant/") && method === "GET") return json(404, { title: "Not Found" });
    // The session route (review-4 F5): the gateway resolves inheritance; resources only for a launchable project.
    if (url.startsWith("/api/v1/hmi/projects/plant")) {
      const current = info.current;
      if (!current) return json(200, { name: "plant", launchable: true, enabled: true, runnable: true, signature: "s1", views: [resource("home", 3, "base"), resource("local", 2)], pageConfig: null, styleClasses: null });
      if (!current.launchable) return json(200, { name: "plant", launchable: false, enabled: current.enabled, runnable: current.runnable });
      return json(200, { name: "plant", launchable: true, enabled: true, runnable: true, signature: "s1", views: [resource("home", 3, "base"), resource("local", 2)], pageConfig: null, styleClasses: null });
    }
    if (method === "PUT") {
      const body = JSON.parse(String(init?.body)) as ConfigResource<ViewSpec>;
      return json(201, { ...body, metadata: { ...body.metadata, version: body.metadata.version + 1 } });
    }
    return json(404, { title: "Not Found" });
  };
  return { calls, client: new GatewayClient(fetcher) };
}

afterEach(() => cleanup());

describe("inherited views in the Designer", () => {
  it("loads inherited views through ?inherited=true and marks them", async () => {
    const { client, calls } = inheritingGateway();
    const loaded = await loadProjectViews(client, "plant");
    expect(calls[0]!.url).toBe("/api/v1/config/designer/project/plant/View?inherited=true");
    expect(loaded.views.map((entry) => [entry.view.metadata.path, entry.inheritedFrom ?? null, entry.ref.version])).toEqual([
      // The inherited view is not stored in plant: an override creates plant's copy at version 0.
      ["home", "base", 0],
      ["local", null, 2],
    ]);
  });

  it("keeps an inherited view read-only until it is overridden, then saves it into the child", async () => {
    const { client, calls } = inheritingGateway();
    await client.session();
    const loaded = await loadProjectViews(client, "plant");
    const { result } = renderHook(() => useSavedProject({ name: "plant", views: loaded.views }, client, "plant"));
    expect([...result.current.inherited]).toEqual([["home", "base"]]);

    const home = result.current.project.views.find((view) => view.metadata.path === "home")!;
    const edited = { ...home, spec: { ...home.spec, width: 999 } };
    act(() => result.current.replaceView(edited));
    expect(result.current.dirty.has("home")).toBe(false);
    expect(result.current.project.views.find((view) => view.metadata.path === "home")!.spec.width).not.toBe(999);

    act(() => result.current.overrideView("home"));
    expect(result.current.inherited.has("home")).toBe(false);
    expect(result.current.dirty.has("home")).toBe(true);
    act(() => result.current.replaceView(edited));
    await act(async () => {
      await result.current.save();
    });
    const put = calls.find((call) => call.method === "PUT")!;
    expect(put.url).toBe("/api/v1/config/designer/project/plant/View/home");
    expect((put.body as ConfigResource<ViewSpec>).metadata.version).toBe(0);
    expect((put.body as ConfigResource<ViewSpec>).spec.document.width).toBe(999);
    expect(result.current.saveState).toEqual({ kind: "saved", count: 1 });
  });

  it("marks inherited views in the project tree", () => {
    const views = [blankView("home", "Home"), blankView("local", "Local")];
    render(<ProjectTree project={{ name: "plant", views }} activePath="local" onOpen={() => {}} inherited={new Map([["home", "base"]])} />);
    const home = screen.getByRole("treeitem", { name: /^Home/ });
    expect(home).toHaveAttribute("data-inherited-from", "base");
    expect(home).toHaveTextContent("Home (inherited from base)");
    const local = screen.getByRole("treeitem", { name: "Local" });
    expect(local).not.toHaveAttribute("data-inherited-from");
  });

  it("shows the parent and follows a disable or delete made elsewhere", async () => {
    const info = { current: project({ title: "Plant floor" }) as ProjectInfo | null };
    const { client } = inheritingGateway(info);
    const { result } = renderHook(() => useProjectStatus(client, "plant", 10));
    await waitFor(() => expect(result.current.kind).toBe("present"));
    const line = render(<ProjectStatusLine status={result.current} />);
    expect(line.getByTestId("project-status")).toHaveTextContent("Plant floor · inherits from base");
    line.unmount();

    info.current = project({ enabled: false, launchable: false });
    await waitFor(() => expect(result.current.kind === "present" && !result.current.info.enabled).toBe(true));
    const disabled = render(<ProjectStatusLine status={result.current} />);
    expect(disabled.getByTestId("project-status")).toHaveTextContent("disabled");
    disabled.unmount();

    info.current = null;
    await waitFor(() => expect(result.current.kind).toBe("absent"));
    render(<ProjectStatusLine status={result.current} />);
    expect(screen.getByRole("alert")).toHaveTextContent("renamed or deleted");
  });
});

describe("inherited reads in the HMI", () => {
  it("reads the resolved project from the session route, not the config API (review-4 F5)", async () => {
    const { client, calls } = inheritingGateway();
    const loaded = await loadHmiProject(client, "plant");
    if (!loaded || loaded === "unchanged") throw new Error("expected a project");
    expect(loaded.project.views.map((view) => view.metadata.path)).toEqual(["home", "local"]);
    expect(loaded.signature).toBe("s1");
    expect(calls.map((call) => call.url)).toEqual(["/api/v1/hmi/projects/plant"]);
  });

  it("refuses to run a disabled or inheritance-only project", async () => {
    const info = { current: project({ enabled: false, launchable: false }) as ProjectInfo | null };
    const { client } = inheritingGateway(info);
    const disabled = await loadHmiProject(client, "plant").catch((error: unknown) => error);
    expect(disabled).toBeInstanceOf(ProjectUnavailable);
    expect((disabled as ProjectUnavailable).reason).toBe("disabled");
    info.current = project({ runnable: false, launchable: false });
    const parentOnly = await loadHmiProject(client, "plant").catch((error: unknown) => error);
    expect((parentOnly as ProjectUnavailable).reason).toBe("not-runnable");
    // A gateway without project settings (404) still runs the project.
    info.current = null;
    expect(await loadHmiProject(client, "plant")).not.toBeNull();
  });
});
