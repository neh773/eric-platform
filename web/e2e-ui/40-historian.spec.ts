import { expect, test, type Page } from "@playwright/test";
import { LEVEL_TAG, login, main, openStudio, section } from "./ui";

export async function ensureHistory(page: Page, name: string, tagPath: string, provider = "local") {
  await section(page, "Historian");
  if (await main(page).getByRole("row").filter({ hasText: name }).count()) return;
  await page.getByRole("button", { name: "Record a tag" }).click();
  await main(page).getByLabel("History name").fill(name);
  await main(page).getByLabel("Tag path").fill(tagPath);
  await main(page).getByLabel("Sample mode").selectOption("periodic");
  await main(page).getByLabel("Sample rate").fill("1000");
  const providerField = main(page).getByLabel("Storage provider");
  if ((await providerField.evaluate((e) => e.tagName)) === "SELECT") await providerField.selectOption(provider);
  else await providerField.fill(provider);
  await main(page).getByRole("button", { name: "Save", exact: true }).click();
  await expect(main(page).getByText(`Saved ${name}.`)).toBeVisible();
  await expect(main(page).getByRole("row").filter({ hasText: name })).toBeVisible();
}

/** Samples for [tagPath] in the last 10 minutes, read through Control Canvas's History query tool. */
export async function historyRows(page: Page, tagPath: string): Promise<number> {
  await openStudio(page);
  // An empty project shows only the New view form; the tools need an open view. Leave it unsaved.
  if (!(await page.getByRole("tree").count())) {
    await page.getByRole("form", { name: "New view" }).getByRole("textbox").fill("historycheck");
    await page.getByRole("form", { name: "New view" }).getByRole("button", { name: "Add view" }).click();
  }
  await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: "Tools", exact: true }).click();
  await page.getByRole("tablist", { name: "Designer tools" }).getByRole("tab", { name: "History query" }).click();
  await page.getByLabel("History tag paths").fill(tagPath);
  await page.getByLabel("History start").fill("last 10 m");
  await page.getByLabel("History end").fill("now");
  await page.getByRole("button", { name: "Run history query" }).click();
  const table = page.getByRole("table", { name: "History result" });
  await expect(table).toBeVisible();
  return Math.max(0, (await table.getByRole("row").count()) - 1);
}

test.describe("historian", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("record a device tag on the built-in provider and read its samples back", async ({ page }) => {
    await ensureHistory(page, "wetwell-level", `[default]${LEVEL_TAG}`);
    await page.waitForTimeout(5_000);
    await expect(async () => expect(await historyRows(page, `[default]${LEVEL_TAG}`)).toBeGreaterThan(0)).toPass({ timeout: 60_000 });
  });

  test("tag history entry can be edited and deleted", async ({ page }) => {
    await ensureHistory(page, "scratch-history", "[default]LiftStation/HighLevelTest");
    await main(page).getByRole("row").filter({ hasText: "scratch-history" }).getByRole("button", { name: /Edit/ }).click();
    await main(page).getByLabel("Sample rate").fill("2000");
    await main(page).getByRole("button", { name: "Save", exact: true }).click();
    await expect(main(page).getByText("Saved scratch-history.")).toBeVisible();
    await page.reload();
    await main(page).getByRole("row").filter({ hasText: "scratch-history" }).getByRole("button", { name: /Edit/ }).click();
    await expect(main(page).getByLabel("Sample rate")).toHaveValue("2000");
    page.once("dialog", (d) => d.accept());
    await main(page).getByRole("button", { name: /Delete/ }).first().click();
    const confirm = main(page).getByRole("button", { name: /^Delete$|^Confirm/ });
    if (await confirm.count()) await confirm.first().click();
    await page.reload();
    await expect(main(page).getByRole("row").filter({ hasText: "scratch-history" })).toHaveCount(0);
  });

  test("Postgres: add a database connection and a SQL history provider from the admin page", async ({ page }) => {
    const pg = { host: process.env.ERIC_UI_PG_HOST, db: process.env.ERIC_UI_PG_DB, user: process.env.ERIC_UI_PG_USER, pw: process.env.ERIC_UI_PG_PASSWORD };
    test.skip(!pg.host || !pg.db, "set ERIC_UI_PG_HOST / _PORT / _DB / _USER / _PASSWORD to test Postgres");
    await section(page, "Databases");
    const table = page.getByRole("table", { name: "Database connections" });
    if (!(await table.getByRole("row", { name: /historian-pg/ }).count())) {
      await page.getByRole("button", { name: "New connection" }).click();
      const form = page.getByRole("form", { name: "Database connection" });
      await form.getByLabel("Connection name").fill("historian-pg");
      await form.getByLabel("Database type").selectOption("postgres");
      await form.getByLabel("Host").fill(pg.host!);
      await form.getByLabel("Port").fill(process.env.ERIC_UI_PG_PORT ?? "5432");
      await form.getByLabel("Database", { exact: true }).fill(pg.db!);
      await form.getByLabel("Username").fill(pg.user ?? "");
      await form.getByLabel("Password", { exact: true }).fill(pg.pw ?? "");
      await form.getByRole("button", { name: "Create and test" }).click();
      await expect(page.getByRole("status").filter({ hasText: "Connection historian-pg was saved and is valid." })).toBeVisible({ timeout: 30_000 });
    }
    await expect(page.getByTestId("db-status-historian-pg")).toHaveText("Valid");

    await section(page, "Historian");
    if (!(await main(page).getByRole("button", { name: "Edit pg", exact: true }).count())) {
      await page.getByRole("button", { name: "New provider" }).click();
      const form = page.getByRole("form", { name: "New provider" });
      await form.getByLabel("Provider name").fill("pg");
      await form.getByLabel("Provider type").selectOption({ label: "SQL database" });
      await form.getByLabel("Connection", { exact: true }).fill("historian-pg");
      await form.getByRole("button", { name: "Save", exact: true }).click();
      await expect(main(page).getByText(/Saved pg/)).toBeVisible();
    }
    await ensureHistory(page, "wetwell-level-pg", `[default]${LEVEL_TAG}`, "pg");
    await page.waitForTimeout(5_000);
    await expect(async () => expect(await historyRows(page, `[default]${LEVEL_TAG}`)).toBeGreaterThan(0)).toPass({ timeout: 60_000 });
  });

});
