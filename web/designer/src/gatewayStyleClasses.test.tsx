import { render, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { StyleClassSet } from "@oip/hmi-runtime";
import { fakeGateway } from "./fakeGateway.testutil";
import { GatewayClient, type FetchLike } from "./gatewayClient";
import { useGatewayStyleClasses } from "./gatewayStyleClasses";
import { STYLE_CLASSES_RESOURCE, saveStyleClasses } from "./persistence";
import { createTagRuntime, seedStudio } from "./runtime";
import { HmiApp } from "./sessionApp";

/** Running sessions follow the style classes saved on the gateway (C-3). */

const builtIn: StyleClassSet = { heading: { style: { color: "red" } } };
const url = `/api/v1/config/designer/project/demo/StyleClasses/${STYLE_CLASSES_RESOURCE}`;
const fast = { periodMs: 10, retryMs: 10 };

async function save(client: GatewayClient, classes: StyleClassSet, version: number) {
  return saveStyleClasses(client, "demo", classes, { name: STYLE_CLASSES_RESOURCE, version });
}

describe("gateway style classes for sessions", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState({}, "", "/");
  });

  it("starts from the stored classes", async () => {
    const gw = fakeGateway();
    await save(gw.client, { heading: { style: { color: "blue" } } }, 0);
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(gw.client, "demo", builtIn, fast));
    await waitFor(() => expect(result.current).toEqual({ styleClasses: { heading: { style: { color: "blue" } } }, status: "gateway" }));
    expect(gw.calls[1]).toMatchObject({ method: "GET", url });
    unmount();
  });

  it("keeps the built-in classes while nothing is stored", async () => {
    const gw = fakeGateway();
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(gw.client, "demo", builtIn, fast));
    await waitFor(() => expect(result.current.status).toBe("none"));
    expect(result.current.styleClasses).toBe(builtIn);
    unmount();
  });

  it("follows each save, and only re-renders when the stored version changes", async () => {
    const gw = fakeGateway();
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(gw.client, "demo", builtIn, fast));
    await waitFor(() => expect(result.current.status).toBe("none"));
    const first = await save(gw.client, { heading: { style: { color: "blue" } } }, 0);
    await waitFor(() => expect(result.current.styleClasses).toEqual({ heading: { style: { color: "blue" } } }));
    const shown = result.current.styleClasses;
    // Unchanged polls keep the same object, so the session does not re-render.
    const polls = gw.calls.length;
    await waitFor(() => expect(gw.calls.length).toBeGreaterThan(polls + 3));
    expect(result.current.styleClasses).toBe(shown);
    await save(gw.client, {}, first.version);
    await waitFor(() => expect(result.current.styleClasses).toEqual({}));
    // Deleted on the gateway: back to the built-in classes.
    gw.store.delete(url);
    await waitFor(() => expect(result.current).toEqual({ styleClasses: builtIn, status: "none" }));
    unmount();
  });

  it("keeps the last good set when the stored copy does not parse", async () => {
    const gw = fakeGateway();
    await save(gw.client, { heading: { style: { color: "blue" } } }, 0);
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(gw.client, "demo", builtIn, fast));
    await waitFor(() => expect(result.current.status).toBe("gateway"));
    const stored = gw.store.get(url)!;
    gw.store.set(url, { ...stored, metadata: { ...stored.metadata, version: 9 }, spec: { classes: { "bad name": {} } } });
    const polls = gw.calls.length;
    await waitFor(() => expect(gw.calls.length).toBeGreaterThan(polls + 2));
    expect(result.current).toEqual({ styleClasses: { heading: { style: { color: "blue" } } }, status: "gateway" });
    unmount();
  });

  it("shows nothing from the project to a caller who may not read it", async () => {
    let status = 200;
    const gw = fakeGateway();
    await save(gw.client, { heading: { style: { color: "blue" } } }, 0);
    const fetcher: FetchLike = async (input, init) => {
      if (status !== 200) {
        return new Response(JSON.stringify({ title: "Forbidden", detail: "gateway.read denied" }), {
          status,
          headers: { "content-type": "application/problem+json" },
        });
      }
      return gw.fetcher(input, init);
    };
    const client = new GatewayClient(fetcher);
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(client, "demo", builtIn, fast));
    await waitFor(() => expect(result.current.status).toBe("gateway"));
    status = 403;
    await waitFor(() => expect(result.current).toEqual({ styleClasses: builtIn, status: "denied" }));
    status = 401;
    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(result.current).toEqual({ styleClasses: builtIn, status: "denied" });
    // Allowed again (for example after signing in): the stored classes come back.
    status = 200;
    await waitFor(() => expect(result.current.status).toBe("gateway"));
    unmount();
  });

  it("keeps what it shows while no gateway answers", async () => {
    let up = true;
    const gw = fakeGateway();
    await save(gw.client, { heading: { style: { color: "blue" } } }, 0);
    const fetcher: FetchLike = async (input, init) => {
      if (!up) throw new TypeError("connection refused");
      return gw.fetcher(input, init);
    };
    const client = new GatewayClient(fetcher);
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(client, "demo", builtIn, fast));
    await waitFor(() => expect(result.current.status).toBe("gateway"));
    up = false;
    await waitFor(() => expect(result.current.status).toBe("offline"));
    expect(result.current.styleClasses).toEqual({ heading: { style: { color: "blue" } } });
    const shown = result.current.styleClasses;
    // Back with nothing changed: the status recovers and the classes stay the same object.
    up = true;
    await waitFor(() => expect(result.current.status).toBe("gateway"));
    expect(result.current.styleClasses).toBe(shown);
    unmount();
  });

  it("does not ask the gateway for an invalid project name", async () => {
    const gw = fakeGateway();
    const { result, unmount } = renderHook(() => useGatewayStyleClasses(gw.client, "Not Valid", builtIn, fast));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(gw.calls).toHaveLength(0);
    expect(result.current).toEqual({ styleClasses: builtIn, status: "pending" });
    unmount();
  });

  it("restyles a running /hmi session when the project's classes are saved", async () => {
    const gw = fakeGateway();
    vi.stubGlobal("fetch", (input: string, init?: RequestInit) => gw.fetcher(input, init));
    window.history.replaceState({}, "", "/hmi/_sample/?project=demo");
    const runtime = createTagRuntime(seedStudio);
    const view = render(<HmiApp runtime={runtime} />);
    const title = () => view.container.querySelector<HTMLElement>("[data-node-id='home-title']");
    const host = () => view.container.querySelector<HTMLElement>(".oip-hmi-host");
    await waitFor(() => expect(host()?.dataset.styleClasses).toBe("none"));
    expect(host()?.dataset.project).toBe("demo");
    expect(title()?.style.color).toBe("var(--oip-color-accent)");

    await save(gw.client, { heading: { style: { color: "#123456", fontSize: 28 } } }, 0);
    // The session polls every couple of seconds.
    await waitFor(() => expect(title()?.style.color).toBe("#123456"), { timeout: 5000 });
    expect(title()?.style.fontSize).toBe("28px");
    expect(gw.calls.every((call) => call.method === "GET" || call.method === "PUT")).toBe(true);
    // The session's polls are background reads (they do not keep the login alive).
    expect(gw.calls.filter((call) => call.method === "GET" && call.url.includes("/StyleClasses/")).every((call) => call.headers["X-OIP-Background"] === "1")).toBe(true);
    view.unmount();
  }, 15_000);
});
