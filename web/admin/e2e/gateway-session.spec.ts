import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * C-4 (M4.1): the gateway serves the HMI bundle and its session socket. A saved view opened from the gateway's own
 * `/studio` shows a gateway tag, and a change made on the gateway reaches the page over `/api/v1/ws` as the
 * signed-in user. -
 */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const metadata = { ...(resource.metadata as Record<string, unknown>), version };
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function memoryTag(request: APIRequestContext, csrf: string, path: string, value: number) {
  const name = path.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  await put(request, csrf, `/api/v1/config/tags/gateway/Tag/${name}`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name, module: "tags" },
    spec: { path, type: "memory", dataType: "Double", value },
  });
}

test("the gateway hosts the HMI and pushes tag changes over its session socket", async ({ page }) => {
  const folder = stamp("c4");
  const project = stamp("hosted");
  const tagPath = `[default]${folder}/level`;
  const csrf = await signIn(page.request);
  await memoryTag(page.request, csrf, `${folder}/level`, 41.5);
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/tags`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "tags", module: "designer" },
    spec: {
      path: "tags",
      title: "Tags",
      document: {
        params: {},
        width: 320,
        height: 120,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "coordinate",
          props: { mode: "fixed" },
          children: [
            {
              id: "level",
              kind: "component",
              name: "Level",
              type: "label",
              frame: { x: 0, y: 0, width: 200, height: 24 },
              props: { text: "" },
              bindings: { text: { kind: "tag", mode: "direct", path: tagPath, transforms: [{ kind: "format", type: "numeric", mode: "number" }] } },
            },
          ],
        },
      },
    },
  });

  // The bundle is served by the gateway itself.
  const shell = await page.request.get("/hmi/");
  expect(shell.ok()).toBeTruthy();
  expect(shell.headers()["content-type"]).toContain("text/html");

  const socket = page.waitForEvent("websocket", (ws) => ws.url().endsWith("/api/v1/ws"));
  await page.goto(`/studio?project=${project}`);
  const ws = await socket;
  // The Studio canvas renders the view live (design mode).
  const preview = page.getByTestId("canvas").locator(".oip-canvas-render .oip-label");
  await expect(preview).toHaveText("41.5");

  const pushed = ws.waitForEvent("framereceived", (frame) => String(frame.payload).includes("77.25"));
  await memoryTag(page.request, csrf, `${folder}/level`, 77.25);
  await pushed;
  await expect(preview).toHaveText("77.25");
});

test("the session socket refuses an anonymous upgrade", async ({ page }) => {
  await page.goto("/hmi/");
  const outcome = await page.evaluate(
    () =>
      new Promise<string>((resolve) => {
        const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/v1/ws`);
        ws.addEventListener("open", () => resolve("open"));
        ws.addEventListener("close", () => resolve("closed"));
      }),
  );
  expect(outcome).toBe("closed");
});
