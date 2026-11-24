import { expect, test, type Page } from "@playwright/test";
import { createTag, LEVEL_TAG, login, main, openStudio, PROJECT, section, studioSave, tagExists, VIEW } from "./ui";

const GAUGE_TAG = "GaugeLevel";

async function addComponent(page: Page, search: string, button: string) {
  await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: "Add", exact: true }).click();
  await page.getByLabel("Search components").fill(search);
  await page.getByRole("button", { name: button, exact: true }).click();
}

async function addView(page: Page, name: string) {
  const tree = page.getByRole("tree").first();
  if (await tree.getByRole("treeitem", { name: new RegExp(`^${name}$`, "i") }).count()) {
    await tree.getByRole("treeitem", { name: new RegExp(`^${name}$`, "i") }).click();
    return false;
  }
  await page.getByPlaceholder("New view name").fill(name);
  await page.getByRole("button", { name: "Add view", exact: true }).click();
  return true;
}

async function bindTag(page: Page, property: string, tagPath: string) {
  const inspector = page.getByTestId("inspector");
  await inspector.getByRole("button", { name: `Bind ${property}` }).click();
  const editor = inspector.getByRole("region", { name: /Binding for/ });
  await editor.getByRole("radio", { name: "Tag", exact: true }).click();
  await editor.getByRole("textbox").first().fill(tagPath);
  await expect(editor.getByText(/^Value /)).toBeVisible();
  await editor.getByRole("button", { name: "Close binding editor" }).click();
}

test.describe("Control Canvas", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("create a project on the admin page", async ({ page }) => {
    await section(page, "Projects");
    if (await main(page).getByRole("cell", { name: PROJECT, exact: true }).count()) return;
    await main(page).getByLabel("New project name").fill(PROJECT);
    await main(page).getByLabel("New project title").fill("Lift Station");
    await main(page).getByLabel("Sessions can run it (runnable)").check();
    await main(page).getByRole("button", { name: "Create project" }).click();
    await expect(main(page).getByText(`Created ${PROJECT}.`)).toBeVisible();
  });

  test("a new project opens empty, not with unsaved sample screens", async ({ page }) => {
    await openStudio(page);
    await expect(page.getByText(/unsaved views/)).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: /unsaved/i })).toHaveCount(0);
  });

  test("build a screen: live tag binding, a popup with detail, saved", async ({ page }) => {
    await openStudio(page);
    // Detail popup view.
    if (await addView(page, "detail")) {
      await addComponent(page, "numeric", "Add numeric");
      await bindTag(page, "Value", `[default]${LEVEL_TAG}`);
    }
    if (await addView(page, VIEW)) {
      await addComponent(page, "label", "Add label");
      await page.getByTestId("inspector").getByRole("textbox", { name: "Text", exact: true }).fill("Wet well");
      await addComponent(page, "numeric", "Add numeric");
      await bindTag(page, "Value", `[default]${LEVEL_TAG}`);
      await addComponent(page, "button", "Add button");
      const inspector = page.getByTestId("inspector");
      await inspector.getByRole("textbox", { name: "Text", exact: true }).fill("Details");
      await inspector.getByRole("button", { name: "onClick: no actions" }).click();
      const actions = inspector.getByRole("region", { name: "Actions for onClick" });
      await actions.getByRole("combobox", { name: "New onClick action type" }).selectOption({ label: "Popup" });
      await actions.getByRole("button", { name: "+ Add action" }).click();
      const action = actions.getByRole("listitem", { name: "onClick action 1: Popup" });
      await action.getByLabel("Popup view").fill("detail");
      await action.getByLabel("Popup view").press("Tab");
      await action.getByLabel("Popup title").fill("Wet well detail");
    }
    await studioSave(page);
    // Reopen: everything is still there.
    await page.reload();
    await expect(page.getByRole("tree").first().getByText(VIEW, { exact: true }).first()).toBeVisible();
  });

  test("the saved screen runs at /hmi with live values and the popup opens", async ({ page }) => {
    await page.goto(`/hmi/${PROJECT}/${VIEW}`);
    await expect(page.getByText("Wet well", { exact: true })).toBeVisible();
    await expect(page.getByText(/^\d+(\.\d+)?$/).first()).toBeVisible();
    await page.getByRole("button", { name: "Details" }).click();
    await expect(page.getByRole("dialog", { name: /Wet well detail/ })).toBeVisible();
  });

  test("a linear gauge bound to a nonzero memory tag shows its value at /hmi", async ({ page }) => {
    if (!(await tagExists(page, GAUGE_TAG))) {
      await createTag(page, GAUGE_TAG, "memory", "Double", async (form) => {
        await form.getByLabel("Initial value").fill("42.5");
      });
    }
    await openStudio(page);
    if (await addView(page, "gauge")) {
      await addComponent(page, "linear", "Add linear gauge");
      await bindTag(page, "Value", `[default]${GAUGE_TAG}`);
    }
    await studioSave(page);
    await page.goto(`/hmi/${PROJECT}/gauge`);
    await expect(page.getByRole("meter").first()).toHaveAttribute("aria-valuetext", /^42\.5/, { timeout: 30_000 });
  });

  test("/hmi without a project lists the projects, or opens the only one", async ({ page }) => {
    await page.goto("/hmi");
    // One project: /hmi opens it. Several: a list of links. Either way the user reaches the lift station.
    // With no pages set up yet the project opens on its default view, which may be the popup view, so check the project, not a label.
    await expect(async () => {
      const links = await page.getByRole("link", { name: /Lift Station|liftstation/ }).count();
      expect(links > 0 || /\/hmi\/liftstation(\/|$)/.test(page.url())).toBe(true);
    }).toPass({ timeout: 15_000 });
  });
});
