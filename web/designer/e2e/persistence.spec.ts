import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, canvas, canvasNode, openPanel, openStudio, selectOnCanvas, signIn, uniqueProject } from "./studio";

/**
 * M4.4: Designer edits are saved to the gateway config store (`designer/project/<p>/View/<name>`, and the
 * project's style classes at `designer/project/<p>/StyleClasses/style-classes`) and
 * a tag dragged from the tag browser onto a label binds it and shows live gateway values.
 */

async function putMemoryTag(request: APIRequestContext, csrf: string, path: string, value: number): Promise<void> {
  const name = path.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  const url = `/api/v1/config/tags/gateway/Tag/${name}`;
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name, module: "tags", version },
      spec: { path, type: "memory", dataType: "Double", value },
    },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test.describe("designer persistence", () => {
  test("creates a view, saves it to the gateway, and finds it after a reload", async ({ page }) => {
    const project = uniqueProject("persist");
    await page.goto(`/?project=${project}&starter=sample`);

    // Not signed in: the Studio asks for gateway credentials first.
    const form = page.getByRole("form", { name: "Sign in to the gateway" });
    await form.getByLabel("Username").fill("admin");
    await form.getByLabel("Password").fill(PASSWORD);
    await form.getByRole("button", { name: "Sign in" }).click();

    const tree = page.getByRole("navigation", { name: "Project" });
    await expect(tree.getByRole("treeitem", { name: "Overview" })).toBeVisible();

    await page.getByRole("textbox", { name: "New view name" }).fill("Line 1");
    await page.getByRole("button", { name: "Add view", exact: true }).click();
    await expect(tree.getByRole("treeitem", { name: "Line 1" })).toHaveAttribute("aria-selected", "true");
    const components = canvas(page).locator(".oip-canvas-render [data-component]");
    await expect(components).toHaveCount(0);
    await page.getByRole("button", { name: "Add label" }).click();
    await page.getByTestId("inspector").getByRole("textbox", { name: "Text" }).fill("Saved on the gateway");

    const status = page.getByRole("status").filter({ hasText: /unsaved|saved|Saving/ });
    await expect(status).toContainText("unsaved");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(status).toHaveText("All changes saved");

    // The view is a config resource now, not tab state.
    const stored = await page.request.get(`/api/v1/config/designer/project/${project}/View/line-1`);
    expect(stored.ok(), await stored.text()).toBeTruthy();
    const spec = ((await stored.json()) as { spec: { path: string; title: string } }).spec;
    expect(spec).toMatchObject({ path: "line-1", title: "Line 1" });

    await page.reload();
    await expect(tree.getByRole("treeitem", { name: "Line 1" })).toBeVisible();
    await expect(status).toHaveText("All changes saved");
    await tree.getByRole("treeitem", { name: "Line 1" }).click();
    await expect(components).toHaveCount(1);
    await expect(canvas(page).getByText("Saved on the gateway")).toBeVisible();
  });

  test("a style class edit is saved to the gateway and survives a reload", async ({ page }) => {
    const project = uniqueProject("styles");
    await openStudio(page, project);
    const status = page.getByRole("status").filter({ hasText: /unsaved|saved|Saving|conflict/i });
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(status).toHaveText("All changes saved");

    await openPanel(page, "Styles");
    const panel = page.getByTestId("style-classes-panel");
    await panel.getByRole("option", { name: "heading", exact: true }).click();
    await panel.getByRole("textbox", { name: "Color" }).fill("#ff00aa");
    await expect(status).toHaveText("Unsaved style classes");
    const title = canvasNode(page, "title").locator(".oip-label");
    await expect(title).toHaveCSS("color", "rgb(255, 0, 170)");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(status).toHaveText("All changes saved");

    const stored = await page.request.get(`/api/v1/config/designer/project/${project}/StyleClasses/style-classes`);
    expect(stored.ok(), await stored.text()).toBeTruthy();
    const classes = ((await stored.json()) as { spec: { classes: Record<string, { style: Record<string, unknown> }> } }).spec.classes;
    expect(classes.heading?.style.color).toBe("#ff00aa");

    await page.reload();
    await expect(status).toHaveText("All changes saved");
    await expect(title).toHaveCSS("color", "rgb(255, 0, 170)");
    await openPanel(page, "Styles");
    await panel.getByRole("option", { name: "heading", exact: true }).click();
    await expect(panel.getByRole("textbox", { name: "Color" })).toHaveValue("#ff00aa");
  });

  test("a stale save reports a conflict; the designer can take the gateway copy or keep its own", async ({ page }) => {
    const project = uniqueProject("conflict");
    const csrf = await openStudio(page, project);
    await page.getByRole("button", { name: "Save", exact: true }).click();
    const status = page.getByRole("status").filter({ hasText: /unsaved|saved|Saving|conflict/ });
    await expect(status).toHaveText("All changes saved");

    // Another designer saves Overview first.
    const url = `/api/v1/config/designer/project/${project}/View/overview`;
    const theirs = (await (await page.request.get(url)).json()) as { metadata: { version: number }; spec: { title: string } };
    theirs.spec.title = "Overview (theirs)";
    const put = await page.request.put(url, { headers: { "X-CSRF-Token": csrf }, data: theirs });
    expect(put.ok(), await put.text()).toBeTruthy();

    await selectOnCanvas(page, "title");
    await page.getByTestId("inspector").getByRole("textbox", { name: "Text" }).fill("Mine");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    const dialog = page.getByRole("alertdialog", { name: "Save conflict" });
    await expect(dialog).toContainText("Overview");
    await dialog.getByRole("button", { name: "Use gateway copy" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole("navigation", { name: "Project" }).getByRole("treeitem", { name: "Overview (theirs)" })).toBeVisible();
    await expect(status).toHaveText("All changes saved");

    // Second round: another save lands first again, and this time the Designer keeps its own edit.
    const again = (await (await page.request.get(url)).json()) as { spec: { title: string } };
    again.spec.title = "Overview (theirs again)";
    expect((await page.request.put(url, { headers: { "X-CSRF-Token": csrf }, data: again })).ok()).toBeTruthy();
    await selectOnCanvas(page, "title");
    await page.getByTestId("inspector").getByRole("textbox", { name: "Text" }).fill("Mine wins");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await dialog.getByRole("button", { name: "Keep my changes" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(status).toHaveText("All changes saved");
    const stored = (await (await page.request.get(url)).json()) as { spec: { title: string; document: unknown } };
    expect(stored.spec.title).toBe("Overview (theirs)");
    expect(JSON.stringify(stored.spec.document)).toContain("Mine wins");
  });

  test("drags a tag from the tag browser onto a label and shows live values", async ({ page }) => {
    const project = uniqueProject("tagdrop");
    const folder = uniqueProject("e2e");
    const csrf = await signIn(page.request);
    await putMemoryTag(page.request, csrf, `${folder}/level`, 41.5);
    await openStudio(page, project, csrf);

    await page.getByRole("textbox", { name: "New view name" }).fill("Tags");
    await page.getByRole("button", { name: "Add view", exact: true }).click();
    await page.getByRole("button", { name: "Add label" }).click();
    const label = canvas(page).locator(".oip-canvas-render [data-component='label']").first();
    const labelBox = await label.boundingBox();
    const overlay = page.getByRole("application", { name: "View canvas" });
    const overlayBox = await overlay.boundingBox();
    if (!labelBox || !overlayBox) throw new Error("the new label is not on the canvas");

    await openPanel(page, "Tags");
    const browser = page.getByRole("region", { name: "Tag browser" });
    // D-6: the root lists the tag providers.
    await browser.getByRole("treeitem", { name: "default", exact: true }).click();
    await browser.getByRole("treeitem", { name: folder }).click();
    const tag = browser.locator(`[data-tag-path='[default]${folder}/level']`);
    await expect(tag).toBeVisible();
    // The overlay receives the drop and hit-tests the label under it.
    await tag.dragTo(overlay, { targetPosition: { x: labelBox.x - overlayBox.x + 8, y: labelBox.y - overlayBox.y + 8 } });

    const preview = canvas(page).locator(".oip-canvas-render .oip-label");
    await expect(preview).toHaveText("41.5");
    const inspector = page.getByTestId("inspector");
    await expect(inspector.getByRole("button", { name: "Text: Tag binding" })).toContainText(`[default]${folder}/level`);
    await inspector.getByRole("button", { name: "Bind Text" }).click();
    await expect(inspector.getByRole("textbox", { name: "Tag path" })).toHaveValue(`[default]${folder}/level`);

    // A gateway-side change reaches the preview without any Designer action.
    await putMemoryTag(page.request, csrf, `${folder}/level`, 77.25);
    await expect(preview).toHaveText("77.25");

    // The binding is saved with the view.
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: /saved/ })).toHaveText("All changes saved");
    await page.reload();
    await page.getByRole("navigation", { name: "Project" }).getByRole("treeitem", { name: "Tags" }).click();
    await expect(canvas(page).locator(".oip-canvas-render .oip-label")).toHaveText("77.25");
  });
});
