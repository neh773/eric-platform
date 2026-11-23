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
    test.skip(!process.env.ERIC_UI_PG_URL, "set ERIC_UI_PG_URL / ERIC_UI_PG_USER / ERIC_UI_PG_PASSWORD to test Postgres");
    await section(page, "Databases");
    await main(page).getByRole("button", { name: /New (database )?connection/ }).click();
    await main(page).getByLabel("Name", { exact: true }).fill("historian-pg");
    const type = main(page).getByLabel(/Type|Driver/).first();
    if (await type.count()) await type.selectOption({ label: /PostgreSQL/i as unknown as string }).catch(() => type.selectOption("postgresql"));
    await main(page).getByLabel(/URL|Connect URL/).first().fill(process.env.ERIC_UI_PG_URL!);
    await main(page).getByLabel("Username").fill(process.env.ERIC_UI_PG_USER ?? "");
    await main(page).getByLabel("Password").fill(process.env.ERIC_UI_PG_PASSWORD ?? "");
    await main(page).getByRole("button", { name: /^Save/ }).first().click();
    await expect(main(page).getByRole("row").filter({ hasText: "historian-pg" })).toContainText(/Valid|Connected|OK/i, { timeout: 30_000 });

    await section(page, "Historian");
    await page.getByRole("button", { name: "New provider" }).click();
    await main(page).getByLabel("Name", { exact: true }).fill("pg");
    await main(page).getByLabel("Type").selectOption({ label: "SQL database" });
    const conn = main(page).getByLabel(/Connection|Database/).first();
    if ((await conn.evaluate((e) => e.tagName)) === "SELECT") await conn.selectOption("historian-pg");
    else await conn.fill("historian-pg");
    await main(page).getByRole("button", { name: "Save", exact: true }).click();
    await ensureHistory(page, "wetwell-level-pg", `[default]${LEVEL_TAG}`, "pg");
  });
});
