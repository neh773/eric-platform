import path from "node:path";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { canvas, openPanel, openStudio, signIn, uniqueProject } from "./studio";

/**
 * D-6: the Studio's Tags side tab against a real gateway. Browse providers and folders with live values for the rows
 * in view, drag a tag onto one property row in the Inspector, see the canvas show its value, and see a gateway-side
 * change reach the tag row, the bound property row and the canvas. Pick a tag with the keyboard from the binding
 * editor, and fall back to the Studio's local tags when no gateway answers the browse.
 *
 * Set OIP_E2E_SHOTS to a folder to keep screenshots of each step.
 */

const shots = process.env.OIP_E2E_SHOTS;

async function shot(page: Page, name: string): Promise<void> {
  if (shots) await page.screenshot({ path: path.join(shots, `${name}.png`) });
}

async function putMemoryTag(request: APIRequestContext, csrf: string, tagPath: string, value: number): Promise<void> {
  const name = tagPath.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  const url = `/api/v1/config/tags/gateway/Tag/${name}`;
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name, module: "tags", version },
      spec: { path: tagPath, type: "memory", dataType: "Double", value },
    },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function newView(page: Page, name: string): Promise<void> {
  await page.getByRole("textbox", { name: "New view name" }).fill(name);
  await page.getByRole("button", { name: "Add view", exact: true }).click();
}

test.describe("designer tag browser", () => {
  test("browses live tags, binds one by dropping it on a property, and follows gateway changes", async ({ page }) => {
    const folder = uniqueProject("d6");
    const csrf = await signIn(page.request);
    await putMemoryTag(page.request, csrf, `${folder}/level`, 41.5);
    await putMemoryTag(page.request, csrf, `${folder}/flow`, 3.5);
    await openStudio(page, uniqueProject("tagbrowse"), csrf);
    await newView(page, "Tags");
    await page.getByRole("button", { name: "Add numeric", exact: true }).click();
    const inspector = page.getByTestId("inspector");
    const valueRow = inspector.locator(".oip-prop[data-prop='value']");
    await expect(valueRow).toBeVisible();

    // The Tags tab is hidden: none of its rows are watched.
    const tree = page.locator("section[aria-label='Tag browser'] [role='tree']");
    await expect(tree).toHaveAttribute("data-live-count", "0");

    await openPanel(page, "Tags");
    const browser = page.getByRole("region", { name: "Tag browser" });
    await browser.getByRole("treeitem", { name: "default", exact: true }).click();
    await browser.getByRole("treeitem", { name: folder }).click();
    const level = browser.locator(`[data-tag-path='[default]${folder}/level']`);
    await expect(level).toContainText("double");
    await expect(level.locator(".oip-tagtree-value output")).toHaveText("41.5");
    await expect(level.locator(".oip-quality-dot")).toHaveAttribute("data-quality", "good");
    await expect(level).toHaveAttribute("data-live", "yes");

    // The filter narrows the loaded rows.
    await browser.getByLabel("Filter tags").fill("flo");
    await expect(browser.locator(`[data-tag-path='[default]${folder}/flow']`)).toBeVisible();
    await expect(level).toHaveCount(0);
    await browser.getByLabel("Filter tags").fill("");
    await expect(level).toBeVisible();
    await shot(page, "01-tag-browser-live");

    // Collapsing the folder stops watching its rows.
    await browser.getByRole("treeitem", { name: folder }).click();
    await expect(level).toHaveCount(0);
    await expect(tree).toHaveAttribute("data-live-count", "0");
    await browser.getByRole("treeitem", { name: folder }).click();
    await expect(level).toHaveAttribute("data-live", "yes");

    // Drop the tag on the numeric's Value row: a tag binding on that property, its editor open, the canvas live.
    await level.dragTo(valueRow);
    const editor = inspector.getByRole("region", { name: "Binding for value" });
    await expect(editor.getByRole("textbox", { name: "Tag path" })).toHaveValue(`[default]${folder}/level`);
    const numeric = canvas(page).locator(".oip-canvas-render [data-testid='numeric-value']").first();
    await expect(numeric).toHaveText("41.5");
    await shot(page, "02-dropped-on-value-prop");

    await editor.getByRole("button", { name: "Close binding editor" }).click();
    const bound = inspector.getByRole("button", { name: "Value: Tag binding" });
    await expect(bound).toContainText("41.5");
    await expect(bound.locator(".oip-quality-dot")).toHaveAttribute("data-quality", "good");

    // A gateway-side change reaches the canvas, the bound property row, and the tag row.
    await putMemoryTag(page.request, csrf, `${folder}/level`, 63.5);
    await expect(numeric).toHaveText("63.5");
    await expect(bound).toContainText("63.5");
    await expect(level.locator(".oip-tagtree-value output")).toHaveText("63.5");
    await shot(page, "03-live-change");
  });

  test("picks a tag path with the keyboard from the binding editor", async ({ page }) => {
    const folder = uniqueProject("d6k");
    const csrf = await signIn(page.request);
    await putMemoryTag(page.request, csrf, `${folder}/speed`, 12.5);
    await openStudio(page, uniqueProject("tagpick"), csrf);
    await newView(page, "Pick");
    await page.getByRole("button", { name: "Add numeric", exact: true }).click();
    const inspector = page.getByTestId("inspector");
    await inspector.getByRole("button", { name: "Bind Value" }).click();
    const editor = inspector.getByRole("region", { name: "Binding for value" });
    await editor.getByRole("radio", { name: "Tag", exact: true }).click();
    await editor.getByRole("button", { name: "Browse…" }).focus();
    await page.keyboard.press("Enter");

    const dialog = page.getByRole("dialog", { name: "Pick a tag" });
    await expect(dialog.getByLabel("Filter tags")).toBeFocused();
    const provider = dialog.getByRole("treeitem", { name: "default", exact: true });
    await provider.focus();
    await page.keyboard.press("Enter");
    const folderRow = dialog.getByRole("treeitem", { name: folder });
    await folderRow.focus();
    await page.keyboard.press("Enter");
    const speed = dialog.locator(`[data-tag-path='[default]${folder}/speed']`);
    await expect(speed).toContainText("12.5");
    await shot(page, "04-picker");
    await speed.focus();
    await page.keyboard.press("Enter");

    await expect(dialog).toHaveCount(0);
    await expect(editor.getByRole("textbox", { name: "Tag path" })).toHaveValue(`[default]${folder}/speed`);
    await expect(editor.getByRole("button", { name: "Browse…" })).toBeFocused();
    await expect(canvas(page).locator(".oip-canvas-render [data-testid='numeric-value']").first()).toHaveText("12.5");
  });

  test("shows the Studio's local tags when no gateway answers the browse, and recovers on retry", async ({ page }) => {
    const csrf = await signIn(page.request);
    let down = true;
    // A proxy with no gateway behind it answers 502 with a non-JSON body.
    await page.route(/\/api\/v1\/tags\/(providers|browse)/, (route) =>
      down ? route.fulfill({ status: 502, contentType: "text/plain", body: "Bad gateway" }) : route.fallback(),
    );
    await openStudio(page, uniqueProject("tagoffline"), csrf);
    await openPanel(page, "Tags");
    const browser = page.getByRole("region", { name: "Tag browser" });
    await expect(browser.getByTestId("tags-offline")).toHaveText(/Gateway not reachable\. Showing the Studio's local tags\./);
    await browser.getByRole("treeitem", { name: "plant" }).click();
    await browser.getByRole("treeitem", { name: "tank" }).click();
    await expect(browser.locator("[data-tag-path='plant/tank/level'] .oip-tagtree-value output")).toHaveText("62.4");
    await shot(page, "05-offline-local-tags");

    down = false;
    await browser.getByTestId("tags-offline").getByRole("button", { name: "Retry" }).click();
    await expect(browser.getByRole("treeitem", { name: "default", exact: true })).toBeVisible();
    await expect(browser.getByTestId("tags-offline")).toHaveCount(0);
  });
});
