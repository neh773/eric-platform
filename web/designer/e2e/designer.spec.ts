import { expect, test } from "@playwright/test";
import { canvas, canvasNode, openStudio, selectOnCanvas, setTag, signIn, uniqueProject } from "./studio";

test.describe("studio shell", () => {
  test("browses the project, edits a schema field, and shows live values on the canvas", async ({ page }) => {
    await openStudio(page, uniqueProject("studio"));
    await expect(page.getByRole("treeitem", { name: "Overview" })).toHaveAttribute("aria-selected", "true");
    const level = canvasNode(page, "level");
    await expect(level).toContainText("62.4 m");
    const overlay = page.getByRole("application", { name: "View canvas" });

    await page.getByRole("treeitem", { name: "Pumps" }).click();
    await expect(overlay).toHaveAttribute("data-layout", "flex");
    await page.getByRole("treeitem", { name: "Overview" }).click();
    await selectOnCanvas(page, "title");

    const inspector = page.getByTestId("inspector");
    await inspector.getByRole("textbox", { name: "Text" }).fill("Holding tank");
    await expect(canvasNode(page, "title")).toHaveText("Holding tank");

    const components = canvas(page).locator(".oip-canvas-render [data-component]");
    const before = await components.count();
    await page.getByRole("button", { name: "Add label" }).click();
    await expect(components).toHaveCount(before + 1);

    await setTag(page, "plant/tank/level", 12.5);
    await expect(level).toContainText("12.5 m");

    // Design mode: clicking a real component selects it; the component itself does not react.
    const toggle = canvasNode(page, "command").locator("[role='switch']");
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await selectOnCanvas(page, "command");
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await selectOnCanvas(page, "start");
    await setTag(page, "plant/pump/command", false);
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await selectOnCanvas(page, "start");
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(inspector.getByRole("textbox", { name: "Text" })).toHaveValue("Start pump");

    await page.getByRole("button", { name: "Dark", exact: true }).click();
    const session = canvas(page).locator(".oip-session");
    await expect(session).toHaveAttribute("data-theme", "nightwatch");
    const color = await session.evaluate((element) => getComputedStyle(element).getPropertyValue("--oip-color-canvas").trim());
    expect(color).toBe("#0b0d10");
  });

  test("drags and resizes a coordinate component", async ({ page }) => {
    await openStudio(page, uniqueProject("studio"));
    await selectOnCanvas(page, "title");
    const inspector = page.getByTestId("inspector");
    const handle = page.getByRole("button", { name: "Resize Title" });
    const handleBox = await handle.boundingBox();
    if (!handleBox) throw new Error("resize handle is not visible");
    await page.mouse.move(handleBox.x + 2, handleBox.y + 2);
    await page.mouse.down();
    await page.mouse.move(handleBox.x + 2 + 40, handleBox.y + 2 + 16, { steps: 6 });
    await page.mouse.up();
    await expect(inspector.getByRole("spinbutton", { name: "Width" })).toHaveValue("340");
    await expect(inspector.getByRole("spinbutton", { name: "Height" })).toHaveValue("48");

    const title = canvasNode(page, "title");
    const box = await title.boundingBox();
    if (!box) throw new Error("title is not visible");
    await page.mouse.move(box.x + 24, box.y + 8);
    await page.mouse.down();
    await page.mouse.move(box.x + 24 + 80, box.y + 8, { steps: 8 });
    await page.mouse.up();
    await expect(inspector.getByRole("spinbutton", { name: "X" })).toHaveValue("96");
    await expect(inspector.getByRole("spinbutton", { name: "Y" })).toHaveValue("12");
    await expect(title).toHaveCSS("left", "96px");
    await expect(title).toHaveCSS("width", "340px");

    await page.keyboard.press("Shift+ArrowDown");
    await expect(inspector.getByRole("spinbutton", { name: "Y" })).toHaveValue("22");
    await expect(title).toHaveCSS("top", "22px");
  });

  test("selects containers and their nested children", async ({ page }) => {
    await openStudio(page, uniqueProject("studio"));
    await page.getByRole("button", { name: "Add split container" }).click();
    const overlay = page.getByRole("application", { name: "View canvas" });
    const split = await overlay.getAttribute("data-selected-node");
    if (!split) throw new Error("the new container is not selected");
    const inspector = page.getByTestId("inspector");
    await expect(inspector).toHaveAttribute("data-container", "split");
    await expect(inspector.getByRole("combobox", { name: "Orientation" })).toHaveValue("horizontal");

    // The second pane (a nested flex container) is select-only.
    const pane = canvasNode(page, `${split}-1`);
    const paneBox = await pane.boundingBox();
    if (!paneBox) throw new Error("second pane is not visible");
    await page.mouse.click(paneBox.x + paneBox.width / 2, paneBox.y + paneBox.height / 2);
    await expect(overlay).toHaveAttribute("data-selected-node", `${split}-1`);
    await expect(inspector).toHaveAttribute("data-container", "flex");
    await expect(page.getByRole("button", { name: /^Resize / })).toHaveCount(0);

    // The divider belongs to the split container itself, not to either pane.
    const bar = await canvasNode(page, split).locator(".oip-split-bar").boundingBox();
    if (!bar) throw new Error("split divider is not visible");
    await page.mouse.click(bar.x + bar.width / 2, bar.y + bar.height / 2);
    await expect(overlay).toHaveAttribute("data-selected-node", split);
    await inspector.getByRole("combobox", { name: "Orientation" }).selectOption("vertical");
    await expect(canvasNode(page, split)).toHaveAttribute("data-orientation", "vertical");
  });

  test("a percent-mode root view has a real size and its children can be selected", async ({ page }) => {
    const project = uniqueProject("percent");
    const csrf = await signIn(page.request);
    const label = (id: string, frame: Record<string, number>) => ({ id, kind: "component", name: id, type: "label", frame, props: { text: id } });
    const put = await page.request.put(`/api/v1/config/designer/project/${project}/View/percent`, {
      headers: { "X-CSRF-Token": csrf },
      data: {
        apiVersion: "oip/v1",
        kind: "View",
        metadata: { name: "percent", module: "designer" },
        spec: {
          path: "percent",
          title: "Percent",
          document: {
            params: {},
            width: 600,
            height: 300,
            root: {
              id: "root",
              kind: "container",
              name: "Root",
              layout: "coordinate",
              props: { mode: "percent" },
              children: [label("half", { x: 0.5, y: 0.5, width: 0.5, height: 0.5 }), label("corner", { x: 0, y: 0, width: 0.25, height: 0.2 })],
            },
          },
        },
      },
    });
    expect(put.ok(), await put.text()).toBeTruthy();
    await openStudio(page, project, csrf);
    await page.getByRole("treeitem", { name: "Percent", exact: true }).click();
    const half = canvasNode(page, "half");
    await expect.poll(async () => (await half.boundingBox())?.height ?? 0).toBeGreaterThan(140);
    const box = await half.boundingBox();
    expect(box?.width).toBeGreaterThan(290);
    await selectOnCanvas(page, "half");
    await selectOnCanvas(page, "corner");
    await expect(page.getByTestId("inspector").getByRole("spinbutton", { name: "Width" })).toHaveValue("0.25");
  });
});
