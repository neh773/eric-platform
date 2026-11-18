import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * P2-15 (TAG-002) on the real gateway: a label bound to a tag whose quality is not Good shows an overlay naming the
 * quality level (uncertain, bad, error) with the gateway's subcode, and the overlay clears when the tag is Good.
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

function label(id: string, path: string) {
  return {
    id,
    kind: "component",
    name: id,
    type: "label",
    props: { text: "" },
    flex: { grow: 0, shrink: 0, basis: "40px", display: true },
    bindings: { text: { kind: "tag", mode: "direct", path } },
  };
}

test("a bound label shows the uncertain, bad and error overlays, and each clears when the tag is Good", async ({ page }) => {
  const run = stamp("tq");
  const project = `${run}-plant`;
  const csrf = await signIn(page.request);
  const tagUrl = (name: string) => `/api/v1/config/tags/gateway/Tag/${run}-${name}`;
  const tag = (name: string, spec: Record<string, unknown>) =>
    put(page.request, csrf, tagUrl(name), { apiVersion: "oip/v1", kind: "Tag", metadata: { name: `${run}-${name}`, module: "tags" }, spec: { path: `${run}/${name}`, ...spec } });
  // A device tag whose device is absent, kept Uncertain on comms loss; one that goes Bad; an expression that cannot evaluate.
  await tag("uncertain", { type: "opc", dataType: "Double", opcServer: `${run}-absent`, opcItemPath: "40001", commsLoss: "uncertainLastKnown" });
  await tag("bad", { type: "opc", dataType: "Double", opcServer: `${run}-absent`, opcItemPath: "40002" });
  await tag("error", { type: "expression", dataType: "Double", expression: "1 / 0" });
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
          children: ["uncertain", "bad", "error"].map((name) => label(name, `[default]${run}/${name}`)),
        },
      },
    },
  });

  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  const expected = [
    { name: "uncertain", quality: "uncertain", level: "uncertain", text: "Uncertain: last known value" },
    { name: "bad", quality: "bad", level: "bad", text: "Not connected" },
    { name: "error", quality: "bad", level: "error", text: "Error: expression eval" },
  ];
  for (const e of expected) {
    const overlay = page.locator(`.oip-node[data-node-id='${e.name}'] > .oip-quality-overlay`);
    await expect(overlay, e.name).toBeVisible({ timeout: 10_000 });
    await expect(overlay).toHaveAttribute("data-quality", e.quality);
    await expect(overlay).toHaveAttribute("data-level", e.level);
    await expect(overlay.getByRole("status")).toHaveText(e.text);
  }
  // Each overlay looks different: the uncertain outline is dashed, the bad and error ones solid.
  const borderStyle = (name: string) => page.locator(`.oip-node[data-node-id='${name}'] > .oip-quality-overlay`).evaluate((el) => getComputedStyle(el).borderTopStyle);
  expect(await borderStyle("uncertain")).toBe("dashed");
  expect(await borderStyle("bad")).toBe("solid");
  expect(await borderStyle("error")).toBe("solid");

  // Each tag becomes Good: its overlay clears and the value shows.
  for (const [index, name] of ["uncertain", "bad", "error"].entries()) {
    await tag(name, { type: "memory", dataType: "String", value: `ok ${index}` });
    const node = page.locator(`.oip-node[data-node-id='${name}']`);
    await expect(node.locator(".oip-quality-overlay")).toHaveCount(0, { timeout: 10_000 });
    await expect(node).toContainText(`ok ${index}`);
    await expect(node).toHaveAttribute("data-quality", "good");
  }
});
