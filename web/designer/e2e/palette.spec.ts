import { expect, test } from "@playwright/test";
import { canvas, openPanel, openStudio, signIn, uniqueProject } from "./studio";

/**
 * P4-05 (HMP-002): the Studio palette is grouped into chart, container, display, input and navigation, with search.
 * One component from each group is dragged onto a view, lands where it was dropped, and is saved with the view.
 */

const DROPS = [
  { group: "Chart components", button: "Add sparkline", type: "sparkline", x: 20, y: 20 },
  { group: "Container components", button: "Add flex repeater", type: "flex-repeater", x: 260, y: 20 },
  { group: "Display components", button: "Add indicator", type: "indicator", x: 20, y: 150 },
  { group: "Input components", button: "Add numeric entry field", type: "numeric-entry", x: 260, y: 150 },
  { group: "Navigation components", button: "Add link", type: "link", x: 480, y: 260 },
];

test("drags a component from each palette group onto a view", async ({ page }) => {
  const project = uniqueProject("palette");
  const csrf = await signIn(page.request);
  await openStudio(page, project, csrf);
  await page.getByRole("textbox", { name: "New view name" }).fill("Palette");
  await page.getByRole("button", { name: "Add view", exact: true }).click();
  await openPanel(page, "Add");

  const palette = page.getByRole("group", { name: "Component palette" });
  const sections = palette.getByRole("region");
  await expect(sections).toHaveCount(5);
  expect(await sections.evaluateAll((nodes) => nodes.map((node) => node.getAttribute("aria-label")))).toEqual(DROPS.map((drop) => drop.group));

  // Search narrows the groups; clearing it brings them back.
  const search = palette.getByRole("searchbox", { name: "Search components" });
  await search.fill("menu");
  await expect(sections).toHaveCount(1);
  await expect(palette.getByRole("region", { name: "Navigation components" }).getByRole("button")).toHaveText(["Add horizontal menu", "Add menu tree"]);
  await search.fill("");
  await expect(sections).toHaveCount(5);

  const overlay = page.getByRole("application", { name: "View canvas" });
  for (const drop of DROPS) {
    const entry = palette.getByRole("region", { name: drop.group }).getByRole("button", { name: drop.button, exact: true });
    await entry.dragTo(overlay, { targetPosition: { x: drop.x, y: drop.y } });
    const node = canvas(page).locator(`.oip-canvas-render [data-component='${drop.type}']`);
    await expect(node).toHaveCount(1);
    // The new component is selected and sits at the drop point.
    const id = await overlay.getAttribute("data-selected-node");
    expect(id).toMatch(new RegExp(`^${drop.type}-`));
    const nodeBox = await canvas(page).locator(`.oip-canvas-render [data-node-id='${id}']`).boundingBox();
    const overlayBox = await overlay.boundingBox();
    expect(Math.abs(nodeBox!.x - overlayBox!.x - drop.x)).toBeLessThanOrEqual(2);
    expect(Math.abs(nodeBox!.y - overlayBox!.y - drop.y)).toBeLessThanOrEqual(2);
  }

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /saved/ })).toHaveText("All changes saved");
  const stored = (await (await page.request.get(`/api/v1/config/designer/project/${project}/View/palette`)).json()) as {
    spec: { document: { root: { children: Array<{ type: string; frame: { x: number; y: number } }> } } };
  };
  const children = stored.spec.document.root.children;
  for (const drop of DROPS) {
    expect(children.find((child) => child.type === drop.type)?.frame).toMatchObject({ x: drop.x, y: drop.y });
  }
});
