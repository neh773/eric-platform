import { expect, test, type Page } from "@playwright/test";
import { canvas, canvasNode, openStudio, signIn, uniqueProject } from "./studio";

/** D-2: the outline beside the live canvas, nested selection, and reparenting by drag. */

const label = (id: string, extra: Record<string, unknown>) => ({ id, kind: "component", name: id, type: "label", props: { text: id }, ...extra });
const flex = { grow: 0, shrink: 1, basis: "120px", display: true };

/** A view with a flex group (one label), a tab container whose second panel holds a label, and a loose label. */
async function openNested(page: Page) {
  const project = uniqueProject("outline");
  const csrf = await signIn(page.request);
  const put = await page.request.put(`/api/v1/config/designer/project/${project}/View/nested`, {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "View",
      metadata: { name: "nested", module: "designer" },
      spec: {
        path: "nested",
        title: "Nested",
        document: {
          params: {},
          width: 640,
          height: 360,
          root: {
            id: "root",
            kind: "container",
            name: "Root",
            layout: "coordinate",
            props: { mode: "fixed" },
            children: [
              label("loose", { name: "Loose", frame: { x: 24, y: 24, width: 160, height: 36 } }),
              {
                id: "group",
                kind: "container",
                name: "Group",
                layout: "flex",
                props: { direction: "row" },
                frame: { x: 24, y: 100, width: 300, height: 90 },
                children: [label("inner", { name: "Inner", flex })],
              },
              {
                id: "tabs",
                kind: "container",
                name: "Tabs",
                layout: "tab",
                props: { tabs: [{ label: "First" }, { label: "Second" }] },
                frame: { x: 360, y: 100, width: 240, height: 160 },
                children: [label("first", { name: "First label" }), label("deep", { name: "Deep" })],
              },
            ],
          },
        },
      },
    },
  });
  expect(put.ok(), await put.text()).toBeTruthy();
  await openStudio(page, project, csrf);
  await page.getByRole("navigation", { name: "Project" }).getByRole("treeitem", { name: "Nested", exact: true }).click();
  const outline = page.getByRole("tree", { name: "Nested outline" });
  await expect(outline).toBeVisible();
  return { outline, overlay: page.getByRole("application", { name: "View canvas" }) };
}

const row = (outline: ReturnType<Page["getByRole"]>, name: string) =>
  outline.getByRole("treeitem", { name, exact: true }).locator(":scope > .oip-outline-row");

async function centre(page: Page, id: string) {
  const box = await canvasNode(page, id).boundingBox();
  if (!box) throw new Error(`${id} is not on the canvas`);
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

test.describe("outline", () => {
  test("selects through the outline and the keyboard, and shows a hidden tab's child", async ({ page }) => {
    const { outline, overlay } = await openNested(page);
    await row(outline, "Inner").click();
    await expect(overlay).toHaveAttribute("data-selected-node", "inner");
    await expect(canvas(page).getByTestId("canvas-selection")).toHaveAttribute("data-selected-node", "inner");
    await expect(outline.getByRole("treeitem", { name: "Inner", exact: true })).toHaveAttribute("aria-selected", "true");

    // Keyboard: the outline is a tree; arrows move focus and Enter selects.
    await page.keyboard.press("ArrowUp");
    await expect(outline.getByRole("treeitem", { name: "Group", exact: true })).toBeFocused();
    await page.keyboard.press("ArrowLeft");
    await expect(outline.getByRole("treeitem", { name: "Group", exact: true })).toHaveAttribute("aria-expanded", "false");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(overlay).toHaveAttribute("data-selected-node", "group");

    // A child of the inactive tab: selecting it shows its panel on the canvas, and nothing needs saving.
    await expect(canvasNode(page, "deep")).toBeHidden();
    const status = page.locator(".oip-save-status");
    const before = await status.textContent();
    await row(outline, "Deep").click();
    await expect(canvasNode(page, "deep")).toBeVisible();
    await expect(canvasNode(page, "tabs")).toHaveAttribute("data-active-tab", "1");
    await expect(canvas(page).getByTestId("canvas-selection")).toHaveAttribute("data-selected-node", "deep");
    await expect(status).toHaveText(before ?? "");
  });

  test("clicks a nested node on the canvas and walks up to its parent", async ({ page }) => {
    const { outline, overlay } = await openNested(page);
    const inner = await centre(page, "inner");
    await page.mouse.click(inner.x, inner.y);
    await expect(overlay).toHaveAttribute("data-selected-node", "inner");
    await expect(outline.getByRole("treeitem", { name: "Inner", exact: true })).toHaveAttribute("aria-selected", "true");

    const path = page.getByTestId("inspector").getByRole("navigation", { name: "Selection path" });
    await expect(path.getByRole("button")).toHaveText(["Root", "Group"]);
    await path.getByRole("button", { name: "Group" }).click();
    await expect(overlay).toHaveAttribute("data-selected-node", "group");
    await expect(page.getByTestId("inspector")).toHaveAttribute("data-container", "flex");

    await page.mouse.click(inner.x, inner.y);
    await expect(overlay).toHaveAttribute("data-selected-node", "inner");
    await page.keyboard.down("Alt");
    await page.mouse.click(inner.x, inner.y);
    await page.keyboard.up("Alt");
    await expect(overlay).toHaveAttribute("data-selected-node", "group");

    await page.mouse.click(inner.x + 40, inner.y + 30);
    await page.mouse.click(inner.x, inner.y);
    await expect(overlay).toHaveAttribute("data-selected-node", "inner");
    await page.mouse.dblclick(inner.x, inner.y);
    await expect(overlay).toHaveAttribute("data-selected-node", "group");
    await expect(outline.getByRole("treeitem", { name: "Group", exact: true })).toHaveAttribute("aria-selected", "true");

    await page.keyboard.press("Escape");
    await expect(overlay).toHaveAttribute("data-selected-node", "root");
    await page.keyboard.press("Escape");
    await expect(overlay).not.toHaveAttribute("data-selected-node");
  });

  test("reparents a node by dragging it in the outline", async ({ page }) => {
    const { outline } = await openNested(page);
    const group = row(outline, "Group");
    const box = await group.boundingBox();
    if (!box) throw new Error("group row is not visible");
    await row(outline, "Loose").dragTo(group, { targetPosition: { x: 40, y: box.height / 2 } });

    await expect(outline.getByRole("treeitem", { name: "Loose", exact: true })).toHaveAttribute("aria-level", "3");
    await expect(canvasNode(page, "group").locator("[data-node-id='loose']")).toHaveCount(1);
    const path = page.getByTestId("inspector").getByRole("navigation", { name: "Selection path" });
    await expect(path.getByRole("button")).toHaveText(["Root", "Group"]);
    // The coordinate frame became a flex basis of the same width.
    await expect(page.getByTestId("inspector").getByRole("textbox", { name: "Basis" })).toHaveValue("160px");
    await expect(page.locator(".oip-save-status")).toHaveAttribute("data-save-state", "dirty");

    // And back out with the keyboard: Alt+Left moves it out of the group.
    await outline.getByRole("treeitem", { name: "Loose", exact: true }).focus();
    await page.keyboard.press("Alt+ArrowLeft");
    await expect(outline.getByRole("treeitem", { name: "Loose", exact: true })).toHaveAttribute("aria-level", "2");
    await expect(canvasNode(page, "group").locator("[data-node-id='loose']")).toHaveCount(0);
    await expect(page.getByTestId("inspector").getByRole("spinbutton", { name: "Width" })).toHaveValue("160");
  });
});
