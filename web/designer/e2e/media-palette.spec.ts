import { expect, test } from "@playwright/test";
import { canvas, openPanel, openStudio, signIn, uniqueProject } from "./studio";

/**
 * HMP-031, HMP-033, HMP-035, HMP-042: the drawing, PDF viewer, audio and video player are in the Display group of
 * the Studio palette, are added at their default size, and their properties are edited in the Inspector and saved.
 */

const ADDS = [
  { button: "Add drawing", type: "drawing", width: 160, height: 160 },
  { button: "Add pdf viewer", type: "pdf-viewer", width: 420, height: 540 },
  { button: "Add audio", type: "audio", width: 300, height: 56 },
  { button: "Add video player", type: "video-player", width: 480, height: 270 },
];

test("adds the media components from the palette and edits them in the Inspector", async ({ page }) => {
  const project = uniqueProject("media");
  const csrf = await signIn(page.request);
  await openStudio(page, project, csrf);
  await page.getByRole("textbox", { name: "New view name" }).fill("Media");
  await page.getByRole("button", { name: "Add view", exact: true }).click();
  await openPanel(page, "Add");

  const palette = page.getByRole("group", { name: "Component palette" });
  const display = palette.getByRole("region", { name: "Display components" });
  const overlay = page.getByRole("application", { name: "View canvas" });
  const inspector = page.getByTestId("inspector");
  const ids: Record<string, string> = {};
  for (const add of ADDS) {
    await display.getByRole("button", { name: add.button, exact: true }).click();
    await expect(canvas(page).locator(`.oip-canvas-render [data-component='${add.type}']`)).toHaveCount(1);
    const id = await overlay.getAttribute("data-selected-node");
    expect(id).toMatch(new RegExp(`^${add.type}-`));
    ids[add.type] = id!;
    const box = await canvas(page).locator(`.oip-canvas-render [data-node-id='${id}']`).boundingBox();
    expect(Math.round(box!.width)).toBe(add.width);
    expect(Math.round(box!.height)).toBe(add.height);

    if (add.type === "drawing") {
      // The element overrides are JSON; invalid text is refused and the drawing keeps its shapes.
      const overrides = inspector.getByRole("textbox", { name: "Element overrides (by id)", exact: true });
      await overrides.fill("{ level:");
      await overrides.blur();
      await expect(inspector.getByRole("alert")).toHaveText("Must be JSON");
      await overrides.fill('{"level": {"fill": "#c03030", "height": 20}}');
      await overrides.blur();
      const level = canvas(page).locator(`.oip-canvas-render [data-node-id='${id}'] rect[id$='-level']`);
      await expect(level).toHaveAttribute("fill", "#c03030");
      await expect(level).toHaveAttribute("height", "20");
    }
    if (add.type === "pdf-viewer") {
      const node = canvas(page).locator(`.oip-canvas-render [data-node-id='${id}']`);
      await expect(node.locator(".oip-pdf-viewer")).toHaveAttribute("data-empty", "true");
      await inspector.getByRole("textbox", { name: "Source (path or http(s) URL)", exact: true }).fill("javascript:alert(1)");
      await expect(node.locator("iframe")).toHaveCount(0);
      await inspector.getByRole("textbox", { name: "Source (path or http(s) URL)", exact: true }).fill("/docs/manual.pdf");
      await inspector.getByRole("spinbutton", { name: "Page", exact: true }).fill("3");
      await expect(node.locator("iframe")).toHaveAttribute("src", "/docs/manual.pdf#page=3");
    }
    if (add.type === "audio") {
      await inspector.getByRole("textbox", { name: "Source (path or http(s) URL)", exact: true }).fill("https://media.test/horn.wav");
      await inspector.getByRole("checkbox", { name: "Loop", exact: true }).check();
      await expect(canvas(page).locator(`.oip-canvas-render [data-node-id='${id}'] audio`)).toHaveAttribute("loop", "");
    }
    if (add.type === "video-player") {
      await inspector.getByRole("textbox", { name: "Source (path or http(s) URL)", exact: true }).fill("https://media.test/line.webm");
      await inspector.getByRole("combobox", { name: "Fit", exact: true }).selectOption("cover");
      await expect(canvas(page).locator(`.oip-canvas-render [data-node-id='${id}'] video`)).toHaveAttribute("data-fit", "cover");
    }
  }

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /saved/ })).toHaveText("All changes saved");
  const stored = (await (await page.request.get(`/api/v1/config/designer/project/${project}/View/media`)).json()) as {
    spec: { document: { root: { children: Array<{ id: string; type: string; props: Record<string, unknown> }> } } };
  };
  const byId = new Map(stored.spec.document.root.children.map((child) => [child.id, child]));
  expect(byId.get(ids.drawing!)?.props.elements).toEqual({ level: { fill: "#c03030", height: 20 } });
  expect(byId.get(ids["pdf-viewer"]!)?.props).toMatchObject({ source: "/docs/manual.pdf", page: 3 });
  expect(byId.get(ids.audio!)?.props).toMatchObject({ source: "https://media.test/horn.wav", loop: true });
  expect(byId.get(ids["video-player"]!)?.props).toMatchObject({ source: "https://media.test/line.webm", fit: "cover" });
});
