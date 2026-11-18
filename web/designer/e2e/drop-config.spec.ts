import { expect, test, type APIRequestContext } from "@playwright/test";
import { canvas, openPanel, openStudio, signIn, uniqueProject } from "./studio";

/**
 * P4-06 (HMP-073, HMP-110): a view's custom properties and drop configuration are edited in the Studio and saved
 * with the view. A tag dropped on an empty part of the canvas becomes the configured component, bound to the tag.
 */

async function putMemoryTag(request: APIRequestContext, csrf: string, path: string, value: number) {
  const name = path.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  const url = `/api/v1/config/tags/gateway/Tag/${name}`;
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name, module: "tags", version }, spec: { path, type: "memory", dataType: "Double", value } },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("edits custom properties and drop configuration, and a dropped tag becomes the configured component", async ({ page }) => {
  const project = uniqueProject("dropcfg");
  const folder = uniqueProject("e2e");
  const csrf = await signIn(page.request);
  await putMemoryTag(page.request, csrf, `${folder}/setpoint`, 37.5);
  await openStudio(page, project, csrf);
  await page.getByRole("textbox", { name: "New view name" }).fill("Tank");
  await page.getByRole("button", { name: "Add view", exact: true }).click();

  // Nothing is selected: the Inspector shows the view's own properties.
  const inspector = page.getByTestId("inspector");
  const custom = inspector.getByRole("region", { name: "Custom properties" });
  await custom.getByLabel("Name").fill("area");
  await custom.getByLabel("Value").fill("North");
  await custom.getByRole("button", { name: "Add custom property" }).click();
  await custom.getByLabel("Name").fill("limit");
  await custom.getByLabel("Value").fill("80");
  await custom.getByRole("button", { name: "Add custom property" }).click();
  await expect(custom.getByRole("textbox", { name: "Custom property limit" })).toHaveValue("80");

  const drop = inspector.getByRole("region", { name: "Drop configuration" });
  await drop.getByRole("button", { name: "Add drop rule" }).click();
  const rule = drop.getByRole("group", { name: "Drop rule 1" });
  await rule.getByLabel("Data type", { exact: true }).selectOption("double");
  await rule.getByLabel("Component", { exact: true }).selectOption("numeric-entry");
  await expect(rule.getByLabel("Bound property")).toHaveValue("value");
  await rule.getByLabel("Bidirectional").check();

  // Drop the tag on an empty part of the canvas.
  await openPanel(page, "Tags");
  const browser = page.getByRole("region", { name: "Tag browser" });
  await browser.getByRole("treeitem", { name: "default", exact: true }).click();
  await browser.getByRole("treeitem", { name: folder }).click();
  const tag = browser.locator(`[data-tag-path='[default]${folder}/setpoint']`);
  await expect(tag).toBeVisible();
  const overlay = page.getByRole("application", { name: "View canvas" });
  await tag.dragTo(overlay, { targetPosition: { x: 300, y: 200 } });

  const entry = canvas(page).locator(".oip-canvas-render [data-component='numeric-entry']");
  await expect(entry).toHaveCount(1);
  await expect(entry.getByRole("textbox")).toHaveValue("37.50");
  await expect(inspector.getByRole("button", { name: "Value: Tag binding" })).toContainText(`[default]${folder}/setpoint`);

  // Saved with the view, and read back after a reload.
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /saved/ })).toHaveText("All changes saved");
  const stored = (await (await page.request.get(`/api/v1/config/designer/project/${project}/View/tank`)).json()) as {
    spec: { document: { custom: unknown; dropConfig: unknown; root: { children: Array<{ type: string; frame: { x: number; y: number }; bindings: unknown }> } } };
  };
  expect(stored.spec.document.custom).toEqual({ area: { value: "North" }, limit: { value: 80 } });
  expect(stored.spec.document.dropConfig).toEqual([{ dataType: "double", component: "numeric-entry", prop: "value", bidirectional: true }]);
  const made = stored.spec.document.root.children.find((child) => child.type === "numeric-entry")!;
  expect(made.frame).toMatchObject({ x: 300, y: 200 });
  expect(made.bindings).toEqual({ value: { kind: "tag", mode: "direct", path: `[default]${folder}/setpoint`, bidirectional: true } });

  await page.reload();
  await page.getByRole("navigation", { name: "Project" }).getByRole("treeitem", { name: "Tank" }).click();
  await expect(inspector.getByRole("region", { name: "Custom properties" }).getByRole("textbox", { name: "Custom property area" })).toHaveValue("North");
  await expect(inspector.getByRole("group", { name: "Drop rule 1" }).getByLabel("Component", { exact: true })).toHaveValue("numeric-entry");
});
