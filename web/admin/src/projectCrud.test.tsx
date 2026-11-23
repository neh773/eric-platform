import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HomePage } from "./homePage";
import { ProjectsPage, parentChoices, projectState } from "./projectsPage";
import type { ProjectItem } from "./statusApi";
import { adminSession, fakeGateway, json, readerSession, sectionById } from "./statusTestKit";

/**
 * PRO-004 project CRUD on the admin Projects page, PRO-002 parent choices, and the GAT-023 home page.
 */

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function item(name: string, extra: Partial<ProjectItem> = {}): ProjectItem {
  return {
    name,
    title: "",
    description: "",
    parent: null,
    enabled: true,
    inheritable: false,
    runnable: true,
    launchable: true,
    publicHmi: false,
    resources: 1,
    version: 1,
    children: [],
    inheritanceChain: [name],
    ...extra,
  };
}

/** An in-memory project list that answers the project routes the page uses. */
function projectGateway(initial: ProjectItem[]) {
  const items = new Map(initial.map((entry) => [entry.name, entry]));
  const kit = fakeGateway(({ method, url, body }) => {
    const payload = typeof body === "string" ? (JSON.parse(body) as Record<string, unknown>) : {};
    if (method === "GET" && url === "/api/v1/projects") {
      return json(200, { projects: [...items.keys()], items: [...items.values()] });
    }
    if (method === "POST" && url === "/api/v1/projects") {
      const name = String(payload.name);
      if (items.has(name)) return json(409, { detail: `project '${name}' already exists` });
      const parent = (payload.parent as string) || null;
      const created = item(name, { ...(payload as Partial<ProjectItem>), parent, inheritanceChain: parent ? [name, parent] : [name] });
      items.set(name, created);
      return json(201, created);
    }
    const match = /^\/api\/v1\/projects\/([^/]+)(\/rename|\/copy)?$/.exec(url);
    if (!match) return undefined;
    const name = decodeURIComponent(match[1]!);
    const current = items.get(name);
    if (!current) return json(404, { detail: `project '${name}' not found` });
    if (method === "PATCH") {
      const next = { ...current, ...(payload as Partial<ProjectItem>), version: current.version + 1 };
      next.launchable = next.enabled && next.runnable;
      items.set(name, next);
      return json(200, next);
    }
    if (method === "DELETE") {
      if (current.children.length > 0) return json(409, { detail: `project '${name}' is the parent of ${current.children.join(", ")}` });
      items.delete(name);
      return new Response(null, { status: 204 });
    }
    if (method === "POST" && match[2] === "/rename") {
      const to = String(payload.name);
      items.delete(name);
      items.set(to, { ...current, name: to });
      return json(200, items.get(to));
    }
    if (method === "POST" && match[2] === "/copy") {
      const to = String(payload.name);
      items.set(to, { ...current, name: to });
      return json(201, items.get(to));
    }
    return undefined;
  });
  return { ...kit, items };
}

async function openPage(initial: ProjectItem[], session = adminSession) {
  const kit = projectGateway(initial);
  await kit.signIn();
  render(<ProjectsPage section={sectionById("projects")} client={kit.client} session={session} />);
  await screen.findByRole("link", { name: `Export ${initial[0]!.name}` });
  return kit;
}

function patches(kit: ReturnType<typeof projectGateway>) {
  return kit.calls.filter((call) => call.method === "PATCH").map((call) => [call.url, JSON.parse(String(call.body))]);
}

describe("project helpers", () => {
  it("offers only inheritable projects that would not close a cycle as parents", () => {
    const items = [
      item("base", { inheritable: true }),
      item("area", { inheritable: true, parent: "base", inheritanceChain: ["area", "base"] }),
      item("plain"),
    ];
    expect(parentChoices(items, null)).toEqual(["base", "area"]);
    expect(parentChoices(items, "base")).toEqual([]);
    expect(parentChoices(items, "plain")).toEqual(["base", "area"]);
    expect(projectState(item("x", { enabled: false, inheritable: true, runnable: false }))).toBe("Disabled, inheritable, not runnable");
  });
});

describe("projects page CRUD", () => {
  it("shows each project's title, parent and state", async () => {
    await openPage([item("base", { inheritable: true, title: "Base", children: ["plant"] }), item("plant", { parent: "base", enabled: false })]);
    const row = screen.getByRole("row", { name: /^plant/ });
    expect(within(row).getByText("base")).toBeVisible();
    expect(screen.getByTestId("state-plant")).toHaveTextContent("Disabled");
    expect(screen.getByTestId("state-base")).toHaveTextContent("Enabled, inheritable");
  });

  it("creates a project with a parent, title and description", async () => {
    const kit = await openPage([item("base", { inheritable: true })]);
    const form = screen.getByRole("form", { name: "Create project" });
    fireEvent.change(within(form).getByLabelText("New project name"), { target: { value: "plant" } });
    fireEvent.change(within(form).getByLabelText("New project title"), { target: { value: "Plant floor" } });
    fireEvent.change(within(form).getByLabelText("New project description"), { target: { value: "Line one" } });
    fireEvent.change(within(form).getByLabelText("New project parent"), { target: { value: "base" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create project" }));
    await screen.findByRole("link", { name: "Export plant" });
    const post = kit.calls.find((call) => call.method === "POST" && call.url === "/api/v1/projects")!;
    expect(JSON.parse(String(post.body))).toEqual({
      name: "plant",
      title: "Plant floor",
      description: "Line one",
      parent: "base",
      inheritable: false,
      runnable: true,
    });
    expect(post.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(screen.getByTestId("project-notice")).toHaveTextContent("Created plant.");
  });

  it("refuses an invalid new name before calling the API", async () => {
    const kit = await openPage([item("base")]);
    const form = screen.getByRole("form", { name: "Create project" });
    fireEvent.change(within(form).getByLabelText("New project name"), { target: { value: "Bad Name" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create project" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("lowercase");
    expect(kit.calls.some((call) => call.method === "POST")).toBe(false);
  });

  it("disables and enables a project", async () => {
    const kit = await openPage([item("plant")]);
    fireEvent.click(screen.getByRole("button", { name: "Disable plant" }));
    await waitFor(() => expect(screen.getByTestId("state-plant")).toHaveTextContent("Disabled"));
    fireEvent.click(screen.getByRole("button", { name: "Enable plant" }));
    await waitFor(() => expect(screen.getByTestId("state-plant")).toHaveTextContent("Enabled"));
    expect(patches(kit)).toEqual([
      ["/api/v1/projects/plant", { enabled: false }],
      ["/api/v1/projects/plant", { enabled: true }],
    ]);
  });

  it("edits settings, renames, copies and deletes", async () => {
    const kit = await openPage([item("base", { inheritable: true }), item("plant")]);
    fireEvent.click(screen.getByRole("button", { name: "Manage plant" }));
    const settings = screen.getByRole("form", { name: "Settings of plant" });
    fireEvent.change(within(settings).getByLabelText("Title"), { target: { value: "Plant A" } });
    fireEvent.change(within(settings).getByLabelText("Parent project"), { target: { value: "base" } });
    fireEvent.click(within(settings).getByRole("button", { name: "Save settings" }));
    await screen.findByText("Saved plant.");
    expect(patches(kit).at(-1)).toEqual([
      "/api/v1/projects/plant",
      { title: "Plant A", description: "", parent: "base", inheritable: false, runnable: true, publicHmi: false },
    ]);

    const rename = screen.getByRole("form", { name: "Rename plant" });
    fireEvent.change(within(rename).getByLabelText("Rename to"), { target: { value: "line-a" } });
    fireEvent.click(within(rename).getByRole("button", { name: "Rename" }));
    await screen.findByText("Renamed plant to line-a.");
    await screen.findByRole("link", { name: "Export line-a" });
    expect(screen.queryByRole("link", { name: "Export plant" })).toBeNull();

    const copy = screen.getByRole("form", { name: "Copy line-a" });
    fireEvent.change(within(copy).getByLabelText("Copy to"), { target: { value: "line-b" } });
    fireEvent.click(within(copy).getByRole("button", { name: "Copy" }));
    await screen.findByRole("link", { name: "Export line-b" });

    fireEvent.click(screen.getByRole("button", { name: "Delete line-a" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete" }));
    await screen.findByText("Deleted line-a.");
    await waitFor(() => expect(screen.queryByRole("link", { name: "Export line-a" })).toBeNull());
    expect(kit.calls.find((call) => call.method === "DELETE")?.url).toBe("/api/v1/projects/line-a");
    expect([...kit.items.keys()].sort()).toEqual(["base", "line-b"]);
  });

  it("shows the refusal when a parent in use is deleted", async () => {
    await openPage([item("base", { inheritable: true, children: ["plant"] }), item("plant", { parent: "base" })]);
    fireEvent.click(screen.getByRole("button", { name: "Manage base" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete base" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("parent of plant");
    expect(screen.getByRole("link", { name: "Export base" })).toBeVisible();
  });

  it("gives a read-only session no project controls", async () => {
    await openPage([item("plant")], readerSession);
    expect(screen.queryByRole("button", { name: "Disable plant" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Manage plant" })).toBeNull();
    expect(screen.queryByRole("form", { name: "Create project" })).toBeNull();
  });
});

function homeDocument(setup: { complete: boolean } | null) {
  return {
    systemName: "north",
    user: "admin",
    links: [
      { id: "admin", label: "Gateway admin", href: "/admin" },
      { id: "designer", label: "Designer", href: "/studio" },
      { id: "hmi", label: "HMI sessions", href: "/hmi/" },
    ],
    projects: [{ name: "plant", title: "Plant floor", description: "Line one", hmi: "/hmi/plant/", designer: "/studio?project=plant" }],
    projectsHidden: false,
    setup: setup && {
      complete: setup.complete,
      steps: [
        { id: "system-name", label: "Name this gateway", done: true, href: "/admin/gateway-settings" },
        { id: "project", label: "Create a project", done: false, href: "/admin/projects" },
      ],
    },
  };
}

describe("home page", () => {
  it("shows the first-run steps until setup is finished, then the launch links", async () => {
    const state = { complete: false };
    const kit = fakeGateway(({ method, url, body }) => {
      if (url === "/api/v1/home" && method === "GET") return json(200, homeDocument(state));
      if (url === "/api/v1/hmi/sessions") return json(200, { sessions: [] });
      if (url === "/api/v1/home/setup" && method === "PUT") {
        state.complete = (JSON.parse(String(body)) as { complete: boolean }).complete;
        return json(200, homeDocument(state));
      }
      return undefined;
    });
    await kit.signIn();
    render(<HomePage section={sectionById("home")} client={kit.client} session={adminSession} />);
    const steps = await screen.findByTestId("first-run");
    expect(within(steps).getByText("Name this gateway").closest("li")).toHaveAttribute("data-done", "yes");
    expect(within(steps).getByText("Create a project").closest("li")).toHaveAttribute("data-done", "no");
    // Launch links show during setup too, so a new user can open Control Canvas at once.
    expect(await screen.findByTestId("launch")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Finish setup" }));
    await waitFor(() => expect(screen.queryByTestId("first-run")).toBeNull());
    const launch = await screen.findByTestId("launch");
    expect(within(launch).getByRole("link", { name: "Designer" })).toHaveAttribute("href", "/studio");
    expect(within(launch).getByRole("link", { name: "HMI sessions" })).toHaveAttribute("href", "/hmi/");
    expect(within(launch).getByRole("link", { name: "Open plant session" })).toHaveAttribute("href", "/hmi/plant/");
    expect(within(launch).getByRole("link", { name: "Edit plant in Control Canvas" })).toHaveAttribute("href", "/studio?project=plant");
    expect(screen.queryByTestId("first-run")).toBeNull();
    const put = kit.calls.find((call) => call.method === "PUT")!;
    expect(put.headers.get("X-CSRF-Token")).toBe("csrf-1");
  });

  it("shows what the gateway allows a reader: links and projects, no setup", async () => {
    const reader = {
      ...homeDocument(null),
      links: [{ id: "hmi", label: "HMI sessions", href: "/hmi/" }],
      projects: [{ name: "plant", title: "Plant floor", description: "", hmi: "/hmi/plant/", designer: null }],
    };
    const kit = fakeGateway(({ url }) => {
      if (url === "/api/v1/home") return json(200, reader);
      if (url === "/api/v1/hmi/sessions") return json(200, { sessions: [] });
      return undefined;
    });
    render(<HomePage section={sectionById("home")} client={kit.client} session={readerSession} />);
    const launch = await screen.findByTestId("launch");
    expect(within(launch).queryByRole("link", { name: "Designer" })).toBeNull();
    expect(within(launch).queryByRole("link", { name: /in Control Canvas/ })).toBeNull();
    expect(within(launch).getByRole("link", { name: "Open plant session" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Finish setup" })).toBeNull();
  });
});
