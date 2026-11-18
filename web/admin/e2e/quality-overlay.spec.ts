import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * P4-08 (HMP-111) on the real gateway: a component bound to a tag in comms loss (a device tag whose source is not
 * connected) shows the quality overlay in a `/hmi` session; a Good tag shows none, and the overlay clears when the tag
 * is Good again.
 */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata: { ...(resource.metadata as object), version } } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

function numeric(id: string, path: string) {
  return {
    id,
    kind: "component",
    name: id,
    type: "numeric",
    props: { value: 0, decimals: 1 },
    flex: { grow: 0, shrink: 0, basis: "48px", display: true },
    bindings: { value: { kind: "tag", mode: "direct", path } },
  };
}

test("a tag in comms loss shows the quality overlay in a session, and it clears when the tag is Good", async ({ page }) => {
  const run = stamp("qo");
  const project = `${run}-plant`;
  const csrf = await signIn(page.request);
  const local = `${run}-local`;
  const remote = `${run}-remote`;
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${local}`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: local, module: "tags" },
    spec: { path: `${run}/local`, type: "memory", dataType: "Double", value: 40 },
  });
  // A device tag whose source is not configured on this gateway: Bad, not connected.
  const remoteTag = { apiVersion: "oip/v1", kind: "Tag", metadata: { name: remote, module: "tags" } };
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${remote}`, {
    ...remoteTag,
    spec: { path: `${run}/remote`, type: "opc", dataType: "Double", opcServer: `${run}-absent-device`, opcItemPath: "40001" },
  });
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: {
        params: {},
        width: 400,
        height: 200,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "flex",
          props: { direction: "column" },
          children: [numeric("local", `[default]${run}/local`), numeric("remote", `[default]${run}/remote`)],
        },
      },
    },
  });

  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  const localNode = page.locator(".oip-node[data-node-id='local']");
  const remoteNode = page.locator(".oip-node[data-node-id='remote']");
  await expect(localNode).toContainText("40.0");
  await expect(localNode.locator(".oip-quality-overlay")).toHaveCount(0);

  const overlay = remoteNode.locator(".oip-quality-overlay");
  await expect(overlay).toBeVisible();
  await expect(overlay).toHaveAttribute("data-quality", "bad");
  await expect(overlay).toHaveAttribute("data-code", "bad_not_connected");
  await expect(overlay.getByRole("status")).toHaveText("Not connected");
  await expect(remoteNode).toHaveAttribute("data-quality", "bad");
  // The overlay covers the whole component.
  const [nodeBox, overlayBox] = await Promise.all([remoteNode.boundingBox(), overlay.boundingBox()]);
  expect(overlayBox!.width).toBeCloseTo(nodeBox!.width, 0);
  expect(overlayBox!.height).toBeCloseTo(nodeBox!.height, 0);

  // The tag becomes Good: the overlay clears and the value shows.
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${remote}`, {
    ...remoteTag,
    spec: { path: `${run}/remote`, type: "memory", dataType: "Double", value: 7 },
  });
  await expect(remoteNode.locator(".oip-quality-overlay")).toHaveCount(0, { timeout: 10_000 });
  await expect(remoteNode).toContainText("7.0");
});
