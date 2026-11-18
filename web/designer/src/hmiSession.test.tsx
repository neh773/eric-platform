import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { parseView, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";
import { fakeGateway } from "./fakeGateway.testutil";
import { GatewayClient, type FetchLike } from "./gatewayClient";
import { PAGE_CONFIG_KIND, PAGE_CONFIG_RESOURCE } from "./hmiProject";
import { saveView } from "./persistence";
import { createTagRuntime, seedStudio } from "./runtime";
import { HmiApp } from "./sessionApp";

/** H-1: /hmi opens a project saved on the gateway, follows saved changes, and falls back to the sample offline. */

const fast = { pollMs: 10, retryMs: 20, waitMs: 200 };

function labelView(path: string, name: string, children: unknown[]): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name, path },
      spec: { params: {}, width: 320, height: 120, root: { id: `${path}-root`, kind: "container", name, layout: "flex", props: { direction: "column" }, children } },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

const home = labelView("home", "Home", [
  {
    id: "level",
    kind: "component",
    name: "Level",
    type: "label",
    props: { text: "" },
    bindings: { text: { kind: "tag", mode: "direct", path: "plant/tank/level", transforms: [{ kind: "format", type: "numeric", mode: "number" }] } },
  },
  {
    id: "to-pumps",
    kind: "component",
    name: "Go",
    type: "button",
    props: { text: "Pumps", enabled: true },
    events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }],
  },
]);
const pumps = labelView("pumps", "Pumps", [{ id: "pumps-title", kind: "component", name: "Title", type: "label", props: { text: "Pump list" } }]);

/** The fake config API, plus sign-in state, the project list, and an off switch for the whole gateway. */
function gatewayWith(options: { signedIn?: boolean; status?: number } = {}) {
  const gw = fakeGateway();
  const state = { signedIn: options.signedIn ?? true, down: false, status: options.status ?? 200 };
  const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
  const fetcher: FetchLike = async (url, init) => {
    if (state.down) throw new TypeError("Failed to fetch");
    if (url === "/api/v1/auth/login") {
      const body = JSON.parse(String(init?.body)) as { password: string };
      if (body.password !== "right") return json(401, { title: "Unauthorized" });
      state.signedIn = true;
      return json(200, { username: "op", csrfToken: "csrf-2" });
    }
    if (!state.signedIn) return json(401, { title: "Unauthorized" });
    if (url === "/api/v1/auth/logout") {
      gw.calls.push({ method: "POST", url, headers: (init?.headers ?? {}) as Record<string, string> });
      state.signedIn = false;
      return new Response(null, { status: 204 });
    }
    if (state.status !== 200 && url.startsWith("/api/v1/config")) return json(state.status, { title: "Forbidden" });
    if (state.status !== 200 && url.startsWith("/api/v1/hmi")) return json(state.status, { title: "Forbidden" });
    if (url === "/api/v1/hmi/projects") {
      const names = new Set([...gw.store.keys()].map((key) => key.split("/")[6]!));
      return json(200, { projects: [...names].sort() });
    }
    if (url.startsWith("/api/v1/hmi/projects/")) {
      // The session route (review-4 F5, F12): the project's resources and a signature; with `after`, the answer waits
      // for a change (here by checking the store every few ms) and is 304 when the wait ends without one.
      const [path, search = ""] = url.slice("/api/v1/hmi/projects/".length).split("?");
      const project = decodeURIComponent(path!);
      const query = new URLSearchParams(search);
      const read = () => {
        const prefix = `/api/v1/config/designer/project/${project}/`;
        const views = [...gw.store.entries()].filter(([key]) => key.startsWith(`${prefix}View/`)).map(([, value]) => value);
        const pageConfig = gw.store.get(`${prefix}PageConfig/page-config`) ?? null;
        const styleClasses = gw.store.get(`${prefix}StyleClasses/style-classes`) ?? null;
        if (views.length === 0 && !pageConfig) return null;
        const body = { name: project, launchable: true, enabled: true, runnable: true, views, pageConfig, styleClasses };
        return { ...body, signature: JSON.stringify(body) };
      };
      gw.calls.push({ method: "GET", url, headers: (init?.headers ?? {}) as Record<string, string> });
      const after = query.get("after");
      const until = Date.now() + Number(query.get("waitMs") ?? 0);
      let current = read();
      while (after !== null && current?.signature === after && Date.now() < until && !init?.signal?.aborted) {
        await new Promise((resolve) => setTimeout(resolve, 5));
        current = read();
      }
      if (!current) return json(404, { title: "Not Found" });
      if (after !== null && current.signature === after) return new Response(null, { status: 304 });
      return json(200, current);
    }
    return gw.fetcher(url, init);
  };
  return { gw, state, client: new GatewayClient(fetcher), calls: gw.calls };
}

async function saveProject(client: GatewayClient, project: string, views: ViewDocument[]) {
  for (const view of views) await saveView(client, project, view, { name: view.metadata.path, version: 0 });
}

function open(path: string, client: GatewayClient) {
  window.history.replaceState({}, "", path);
  const runtime = createTagRuntime(seedStudio);
  return { runtime, ...render(<HmiApp runtime={runtime} client={client} {...fast} />) };
}

function state(container: HTMLElement): string | undefined {
  return container.querySelector<HTMLElement>("[data-hmi-state]")?.dataset.hmiState;
}

describe("HmiApp with a saved project", () => {
  afterEach(() => window.history.replaceState({}, "", "/"));

  it("runs the saved pages with live tag values, and navigates by page URL", async () => {
    const { client } = gatewayWith();
    await saveProject(client, "plant", [home, pumps]);
    const view = open("/hmi/plant/", client);
    await waitFor(() => expect(view.container.querySelector("[data-node-id='level']")?.textContent).toBe("62.4"));
    const host = view.container.querySelector<HTMLElement>(".oip-hmi-host")!;
    expect(host.dataset.hmiSource).toBe("gateway");
    expect(host.dataset.project).toBe("plant");
    expect(host.dataset.pagesFrom).toBe("views");

    act(() => view.runtime.server.set("plant/tank/level", 70));
    await waitFor(() => expect(view.container.querySelector("[data-node-id='level']")?.textContent).toBe("70"));

    fireEvent.click(view.getByRole("button", { name: "Pumps" }));
    await waitFor(() => expect(view.getByText("Pump list")).toBeTruthy());
    expect(window.location.pathname).toBe("/hmi/plant/pumps");

    // Back in the browser: the URL decides the page.
    window.history.replaceState({}, "", "/hmi/plant/");
    act(() => window.dispatchEvent(new PopStateEvent("popstate")));
    await waitFor(() => expect(view.container.querySelector("[data-node-id='level']")).toBeTruthy());
    view.unmount();
  });

  it("opens a page from its URL and follows a saved change", async () => {
    const { client, calls } = gatewayWith();
    await saveProject(client, "plant", [home, pumps]);
    const view = open("/hmi/plant/pumps", client);
    await waitFor(() => expect(view.getByText("Pump list")).toBeTruthy());

    const edited = labelView("pumps", "Pumps", [{ id: "pumps-title", kind: "component", name: "Title", type: "label", props: { text: "Pumps, edited" } }]);
    await saveView(client, "plant", edited, { name: "pumps", version: 1 });
    await waitFor(() => expect(view.getByText("Pumps, edited")).toBeTruthy());
    // Every project read is a background read on the session route, so it does not keep the login alive; after the
    // first, each one waits at the gateway for a change (F12) instead of polling the config API.
    const reads = calls.filter((call) => call.method === "GET" && call.url.startsWith("/api/v1/hmi/projects/plant"));
    expect(reads.length).toBeGreaterThan(1);
    expect(reads.every((call) => call.headers["X-OIP-Background"] === "1")).toBe(true);
    expect(reads.slice(1).every((call) => call.url.includes("after=") && call.url.includes("waitMs=200"))).toBe(true);
    expect(calls.some((call) => call.method === "GET" && call.url.startsWith("/api/v1/config/"))).toBe(false);
    view.unmount();
  });

  it("takes its pages from the saved page configuration", async () => {
    const { client } = gatewayWith();
    await saveProject(client, "plant", [home, pumps]);
    await client.putResource("project/plant", {
      apiVersion: "oip/v1",
      kind: PAGE_CONFIG_KIND,
      metadata: { name: PAGE_CONFIG_RESOURCE, module: "designer", version: 0 },
      spec: { pages: [{ url: "/", primaryView: "pumps", title: "Start" }], sharedDocks: [{ id: "nav", edge: "left", view: "home", size: 150 }] },
    });
    const view = open("/hmi/plant/", client);
    await waitFor(() => expect(view.getByText("Pump list")).toBeTruthy());
    expect(view.container.querySelector<HTMLElement>(".oip-hmi-host")?.dataset.pagesFrom).toBe("config");
    // The shared dock shows the home view beside the page.
    await waitFor(() => expect(view.container.querySelector("[data-dock-id='nav'] [data-node-id$='__level']")?.textContent).toBe("62.4"));
    view.unmount();
  });

  it("says so when the project or the page does not exist", async () => {
    const { client } = gatewayWith();
    await saveProject(client, "plant", [home, pumps]);
    const missing = open("/hmi/nowhere/", client);
    await waitFor(() => expect(state(missing.container)).toBe("missing"));
    expect(missing.getByRole("alert").textContent).toContain('Project "nowhere" was not found');
    missing.unmount();

    const noPage = open("/hmi/plant/valves", client);
    await waitFor(() => expect(state(noPage.container)).toBe("no-page"));
    expect(noPage.getByRole("alert").textContent).toContain('no page at "/valves"');
    fireEvent.click(noPage.getByRole("link", { name: "Pumps" }));
    await waitFor(() => expect(noPage.getByText("Pump list")).toBeTruthy());
    expect(window.location.pathname).toBe("/hmi/plant/pumps");
    noPage.unmount();

    const invalid = open("/hmi/Not%20Valid/", client);
    await waitFor(() => expect(state(invalid.container)).toBe("invalid"));
    invalid.unmount();
  });

  it("binds the signed-in user into the session's auth properties (HMP-084)", async () => {
    const { client } = gatewayWith();
    const who = labelView("home", "Home", [
      { id: "user", kind: "component", name: "User", type: "label", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "session", path: "auth.userName" } } } },
    ]);
    await saveProject(client, "plant", [who]);
    const view = open("/hmi/plant/", client);
    await waitFor(() => expect(view.container.querySelector("[data-node-id='user']")?.textContent).toBe("admin"));
    view.unmount();
  });

  it("asks a signed-out user to sign in, then opens the project", async () => {
    const { client } = gatewayWith({ signedIn: true });
    await saveProject(client, "plant", [home]);
    const gateway = gatewayWith({ signedIn: false });
    for (const [key, value] of (await storeOf(client)).entries()) gateway.gw.store.set(key, value);
    const view = open("/hmi/plant/", gateway.client);
    await waitFor(() => expect(state(view.container)).toBe("login"));
    expect(view.container.querySelector("[data-node-id='level']")).toBeNull();

    fireEvent.change(view.getByLabelText("Username"), { target: { value: "op" } });
    fireEvent.change(view.getByLabelText("Password"), { target: { value: "wrong" } });
    fireEvent.submit(view.getByRole("form", { name: "Sign in to the gateway" }));
    await waitFor(() => expect(view.getByRole("alert").textContent).toBe("Wrong username or password."));
    fireEvent.change(view.getByLabelText("Password"), { target: { value: "right" } });
    fireEvent.submit(view.getByRole("form", { name: "Sign in to the gateway" }));
    await waitFor(() => expect(view.container.querySelector("[data-node-id='level']")?.textContent).toBe("62.4"));

    // Signed out on the gateway (logout or timeout): back to the sign-in form, and the project leaves the screen.
    gateway.state.signedIn = false;
    await waitFor(() => expect(state(view.container)).toBe("login"));
    expect(view.container.querySelector("[data-node-id='level']")).toBeNull();
    view.unmount();
  });

  it("a logout action signs the user out and returns the session to sign-in (P4-11, HMP-117)", async () => {
    const { client, state: gatewayState, calls } = gatewayWith();
    const leave = labelView("home", "Home", [
      { id: "out", kind: "component", name: "Out", type: "button", props: { text: "Sign out", enabled: true }, events: [{ event: "onClick", actions: [{ kind: "logout" }] }] },
    ]);
    await saveProject(client, "plant", [leave]);
    const view = open("/hmi/plant/", client);
    await waitFor(() => expect(view.getByRole("button", { name: "Sign out" })).toBeTruthy());
    fireEvent.click(view.getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(state(view.container)).toBe("login"));
    expect(gatewayState.signedIn).toBe(false);
    expect(calls.some((call) => call.method === "POST" && call.url === "/api/v1/auth/logout")).toBe(true);
    expect(view.queryByRole("button", { name: "Sign out" })).toBeNull();
    view.unmount();
  });

  it("never draws the previous login's project after signing in again", async () => {
    const gateway = gatewayWith();
    await saveProject(gateway.client, "plant", [home]);
    const view = open("/hmi/plant/", gateway.client);
    await waitFor(() => expect(view.container.querySelector("[data-node-id='level']")).toBeTruthy());
    gateway.state.signedIn = false;
    await waitFor(() => expect(state(view.container)).toBe("login"));

    // While signed out, the project changes on the gateway; the next sign-in must show only the new copy.
    gateway.state.signedIn = true;
    await saveView(gateway.client, "plant", labelView("home", "Home", [{ id: "fresh", kind: "component", name: "Fresh", type: "label", props: { text: "Fresh copy" } }]), { name: "home", version: 1 });
    gateway.state.signedIn = false;
    const watch = watchAdded(view.container, "[data-node-id='level']");
    fireEvent.change(view.getByLabelText("Username"), { target: { value: "op" } });
    fireEvent.change(view.getByLabelText("Password"), { target: { value: "right" } });
    fireEvent.submit(view.getByRole("form", { name: "Sign in to the gateway" }));
    await waitFor(() => expect(view.getByText("Fresh copy")).toBeTruthy());
    expect(watch.seen()).toBe(false);
    view.unmount();
  });

  it("never draws the previous project when the URL switches project", async () => {
    const { client } = gatewayWith();
    await saveProject(client, "plant", [home, pumps]);
    await saveProject(client, "other", [labelView("home", "Other", [{ id: "other-title", kind: "component", name: "Title", type: "label", props: { text: "Other project" } }])]);
    const view = open("/hmi/plant/pumps", client);
    await waitFor(() => expect(view.getByText("Pump list")).toBeTruthy());

    // plant's home page has the level label; drawing plant at the other project's "/" would add it.
    const watch = watchAdded(view.container, "[data-node-id='level']");
    window.history.pushState({}, "", "/hmi/other/");
    act(() => window.dispatchEvent(new PopStateEvent("popstate")));
    await waitFor(() => expect(view.getByText("Other project")).toBeTruthy());
    expect(watch.seen()).toBe(false);
    expect(view.container.querySelector<HTMLElement>(".oip-hmi-host")?.dataset.project).toBe("other");
    view.unmount();
  });

  it("shows nothing from a project the user may not read", async () => {
    const gateway = gatewayWith({ status: 403 });
    const view = open("/hmi/plant/", gateway.client);
    await waitFor(() => expect(state(view.container)).toBe("denied"));
    view.unmount();
  });

  it("opens the only project at /hmi/, and lists them when there are several", async () => {
    const { client } = gatewayWith();
    await saveProject(client, "plant", [home]);
    const one = open("/hmi/?theme=nightwatch", client);
    await waitFor(() => expect(one.container.querySelector("[data-node-id='level']")).toBeTruthy());
    expect(`${window.location.pathname}${window.location.search}`).toBe("/hmi/plant/?theme=nightwatch");
    one.unmount();

    await saveProject(client, "line-2", [pumps]);
    const several = open("/hmi/", client);
    await waitFor(() => expect(state(several.container)).toBe("choose"));
    expect(several.getByRole("link", { name: "line-2" }).getAttribute("href")).toBe("/hmi/line-2/");
    several.unmount();

    // A project named default wins.
    await saveProject(client, "default", [pumps]);
    const preferred = open("/hmi/", client);
    await waitFor(() => expect(preferred.getByText("Pump list")).toBeTruthy());
    expect(window.location.pathname).toBe("/hmi/default/");
    preferred.unmount();
  });

  it("reads the earlier ?project= URL as the project", async () => {
    const { client } = gatewayWith();
    await saveProject(client, "plant", [home]);
    const view = open("/hmi/?project=plant", client);
    await waitFor(() => expect(view.container.querySelector("[data-node-id='level']")).toBeTruthy());
    expect(window.location.pathname).toBe("/hmi/plant/");
    view.unmount();
  });
});

describe("HmiApp built-in sample", () => {
  afterEach(() => window.history.replaceState({}, "", "/"));

  it("shows no sample values under a project URL when no gateway answers, and the saved project once it does (review-4 F7)", async () => {
    const gateway = gatewayWith();
    await saveProject(gateway.client, "plant", [home, pumps]);
    gateway.state.down = true;
    const view = open("/hmi/plant/pumps", gateway.client);
    await waitFor(() => expect(state(view.container)).toBe("gateway-offline"));
    expect(view.getByRole("alert").textContent).toContain('No gateway answered');
    expect(view.getByRole("alert").textContent).toContain('project "plant" cannot be shown');
    // Nothing of the built-in sample: no simulated plant values, no sample pages.
    expect(view.container.querySelector(".oip-hmi-host")).toBeNull();
    expect(view.queryByText("Pump list")).toBeNull();
    expect(view.container.textContent).not.toContain("62.4");
    expect(window.location.pathname).toBe("/hmi/plant/pumps");

    gateway.state.down = false;
    await waitFor(() => expect(view.container.querySelector<HTMLElement>(".oip-hmi-host")?.dataset.hmiSource).toBe("gateway"));
    expect(view.getByText("Pump list")).toBeTruthy();
    view.unmount();
  });

  it("runs the labelled sample at /hmi/_sample/ without asking the gateway for a project", async () => {
    const gateway = gatewayWith({ signedIn: false });
    const view = open("/hmi/_sample/", gateway.client);
    await waitFor(() => expect(view.getByRole("note").textContent).toContain("Built-in sample project"));
    expect(view.container.querySelector<HTMLElement>(".oip-hmi-host")?.dataset.hmiSource).toBe("sample");
    await waitFor(() => expect(view.getByTestId("numeric-value").textContent).toContain("62.4"));
    fireEvent.click(view.getByRole("button", { name: "Pumps" }));
    await waitFor(() => expect(window.location.pathname).toBe("/hmi/_sample/pumps"));
    expect(gateway.calls).toHaveLength(0);
    view.unmount();
  });
});

/** Records whether a node matching [selector] is ever added under [root] (even if removed again right after). */
function watchAdded(root: HTMLElement, selector: string) {
  let seen = false;
  const check = (records: MutationRecord[]) => {
    for (const record of records) {
      for (const node of Array.from(record.addedNodes)) {
        if (node instanceof Element && (node.matches(selector) || node.querySelector(selector))) seen = true;
      }
    }
  };
  const observer = new MutationObserver(check);
  observer.observe(root, { childList: true, subtree: true });
  return {
    seen: () => {
      check(observer.takeRecords());
      observer.disconnect();
      return seen;
    },
  };
}

async function storeOf(client: GatewayClient) {
  // The first fake's store, read back through its API (the same keys the second fake serves).
  const items = await client.listResources("designer", "project/plant", "View");
  const map = new Map<string, Record<string, unknown> & { metadata: { version: number } }>();
  for (const item of items) map.set(`/api/v1/config/designer/project/plant/View/${item.metadata.name}`, item as never);
  return map;
}
