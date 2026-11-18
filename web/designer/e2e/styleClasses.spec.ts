import { expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { canvasNode, openPanel, openStudio, selectOnCanvas, signIn, uniqueProject } from "./studio";

/** M4.2 acceptance: style classes saved from the Designer restyle running sessions, under both themes.
 * A session follows the classes stored on the gateway for its `?project=` (gatewayStyleClasses.ts), so a save
 * reaches sessions in any browser; unsaved Designer edits stay in the Designer (C-3).
 */

const daylight = { accent: "rgb(10, 102, 224)", alarm: "rgb(215, 15, 31)", ink: "rgb(11, 13, 16)" };
const nightwatch = { accent: "rgb(61, 139, 255)", alarm: "rgb(255, 69, 58)", ink: "rgb(242, 244, 247)" };

/** The visible text of the session's "Home" label, which references the "heading" class. */
function homeTitle(page: Page) {
  return page.locator(".oip-label", { hasText: /^Home$/ });
}

/** A browser context separate from the Designer's: its own cookies, as on another machine. */
async function otherBrowser(browser: Browser, signedIn: boolean): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  if (signedIn) await signIn(context.request);
  return context;
}

async function openSession(page: Page, project: string, theme: "daylight" | "nightwatch", source = "none") {
  await page.goto(theme === "daylight" ? `/hmi/_sample/?theme=daylight&project=${project}` : `/hmi/_sample/?theme=nightwatch&project=${project}`);
  await expect(page.locator(".oip-session").first()).toHaveAttribute("data-theme", theme);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-style-classes", source);
  await expect(homeTitle(page)).toBeVisible();
}

async function save(designer: Page) {
  await designer.getByRole("button", { name: "Save", exact: true }).click();
  await expect(designer.getByRole("status").filter({ hasText: "All changes saved" })).toBeVisible();
}

test.describe("style classes", () => {
  test("a class saved in the Designer restyles running sessions in another browser, in both themes", async ({ page: designer, browser }) => {
    const project = uniqueProject("styles");
    const sessions = await otherBrowser(browser, true);
    const anonymous = await otherBrowser(browser, false);
    try {
      const day = await sessions.newPage();
      const night = await sessions.newPage();
      await openSession(day, project, "daylight");
      await openSession(night, project, "nightwatch");
      // Not signed in: the session may not read the project and keeps its built-in classes.
      const outsider = await anonymous.newPage();
      await openSession(outsider, project, "daylight", "denied");

      // Before any save: the built-in "heading" class uses the theme accent color.
      await expect(homeTitle(day)).toHaveCSS("color", daylight.accent);
      await expect(homeTitle(night)).toHaveCSS("color", nightwatch.accent);
      await expect(homeTitle(day)).toHaveCSS("font-size", "20px");

      await openStudio(designer, project);
      await openPanel(designer, "Styles");
      const panel = designer.getByTestId("style-classes-panel");
      await panel.getByRole("option", { name: "heading", exact: true }).click();
      await panel.getByRole("textbox", { name: "Color" }).fill("var(--oip-color-alarm)");
      await panel.getByRole("spinbutton", { name: "Font size" }).fill("28");
      // The Designer preview shows the edit at once; sessions wait for the save.
      await expect(canvasNode(designer, "title").locator(".oip-label")).toHaveCSS("color", nightwatch.alarm);
      await expect(homeTitle(day)).toHaveCSS("color", daylight.accent);
      await save(designer);

      // Same class, each session resolves the theme variable against its own theme.
      await expect(day.locator(".oip-hmi-host")).toHaveAttribute("data-style-classes", "gateway");
      await expect(homeTitle(day)).toHaveCSS("color", daylight.alarm);
      await expect(homeTitle(night)).toHaveCSS("color", nightwatch.alarm);
      await expect(homeTitle(day)).toHaveCSS("font-size", "28px");
      await expect(homeTitle(night)).toHaveCSS("font-size", "28px");
      await expect(homeTitle(outsider)).toHaveCSS("color", daylight.accent);

      // Deleting the class and saving: sessions fall back to the theme defaults without a reload.
      await panel.getByRole("button", { name: "Delete heading" }).click();
      await save(designer);
      await expect(homeTitle(day)).toHaveCSS("color", daylight.ink);
      await expect(homeTitle(night)).toHaveCSS("color", nightwatch.ink);
      await expect(homeTitle(day)).toHaveCSS("font-size", "14px");
      await expect(outsider.locator(".oip-hmi-host")).toHaveAttribute("data-style-classes", "denied");
    } finally {
      await sessions.close();
      await anonymous.close();
    }
  });

  test("a session opened after a save starts with the saved classes", async ({ page: designer, browser }) => {
    const project = uniqueProject("styles");
    await openStudio(designer, project);
    await openPanel(designer, "Styles");
    const panel = designer.getByTestId("style-classes-panel");
    await panel.getByRole("option", { name: "heading", exact: true }).click();
    await panel.getByRole("textbox", { name: "Color" }).fill("#ff00aa");
    await save(designer);

    const sessions = await otherBrowser(browser, true);
    try {
      const late = await sessions.newPage();
      await openSession(late, project, "nightwatch", "gateway");
      await expect(homeTitle(late)).toHaveCSS("color", "rgb(255, 0, 170)");
    } finally {
      await sessions.close();
    }
  });

  test("the Designer preview applies classes alphabetically and flags missing names", async ({ page }) => {
    await openStudio(page, uniqueProject("styles"));
    const title = canvasNode(page, "title").locator(".oip-label");
    await expect(title).toHaveCSS("color", nightwatch.accent);

    await selectOnCanvas(page, "title");
    await openPanel(page, "Styles");
    const picker = page.getByTestId("style-class-picker");
    // "alert" sorts before "heading", so heading's color still wins.
    await picker.getByRole("checkbox", { name: "Use style class alert" }).check();
    await expect(picker.getByTestId("style-class-order")).toHaveText("Applied in order alert, heading; later names win.");
    await expect(title).toHaveCSS("color", nightwatch.accent);
    await picker.getByRole("checkbox", { name: "Use style class heading" }).uncheck();
    await expect(title).toHaveCSS("color", nightwatch.alarm);

    // Deleting a referenced class leaves a flagged reference that can be removed.
    await page.getByTestId("style-classes-panel").getByRole("button", { name: "Delete alert" }).click();
    await expect(picker.locator("[data-missing-class='alert']")).toBeVisible();
    await picker.getByRole("button", { name: "Remove missing class alert" }).click();
    await expect(picker.locator("[data-missing-class='alert']")).toHaveCount(0);
  });

  test("a new class needs a valid unique name and applies once attached", async ({ page }) => {
    await openStudio(page, uniqueProject("styles"));
    await selectOnCanvas(page, "note");
    await openPanel(page, "Styles");
    const panel = page.getByTestId("style-classes-panel");
    const name = panel.getByRole("textbox", { name: "New class name" });

    await name.fill("2 bad");
    await panel.getByRole("button", { name: "Add class" }).click();
    await expect(panel.getByRole("alert")).toContainText("Start with a letter");
    await name.fill("heading");
    await panel.getByRole("button", { name: "Add class" }).click();
    await expect(panel.getByRole("alert")).toContainText("already exists");

    await name.fill("note-highlight");
    await panel.getByRole("button", { name: "Add class" }).click();
    await expect(panel.getByRole("option", { name: "note-highlight", exact: true })).toHaveAttribute("aria-selected", "true");
    await panel.getByRole("textbox", { name: "Background" }).fill("#fff2b2");
    await page.getByTestId("style-class-picker").getByRole("checkbox", { name: "Use style class note-highlight" }).check();

    const note = canvasNode(page, "note");
    await expect(note).toHaveCSS("background-color", "rgb(255, 242, 178)");
  });
});
