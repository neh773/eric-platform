import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/**
 * Named query editor (FEATURES DAT-031) on the real gateway: create a query on a SQLite connection, detect its
 * parameters from the SQL, save, test-run it, change its settings, and delete it.
 */
async function api(page: Page, method: "POST" | "DELETE", url: string, data?: unknown) {
  const token = await csrf(page);
  const res = await page.request.fetch(url, {
    method,
    data,
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("creates, tests, changes and deletes a named query", async ({ page }) => {
  await login(page);
  const connection = stamp("nqdb");
  const query = `${connection}-triple`;
  await api(page, "POST", "/api/v1/db/connections", { name: connection, driver: "sqlite", url: `jdbc:sqlite:e2e/${connection}.db`, description: "e2e" });

  try {
    await openSection(page, "Databases");
    await page.getByRole("button", { name: "New named query", exact: true }).click();
    const editor = page.getByRole("form", { name: "Named query editor" });
    await editor.getByLabel("Query name", { exact: true }).fill(query);
    await editor.getByLabel("Query connection", { exact: true }).selectOption(connection);
    await editor.getByLabel("Query SQL", { exact: true }).fill("SELECT :n * 3 AS tripled");
    await editor.getByRole("button", { name: "Detect parameters from SQL", exact: true }).click();
    await editor.getByLabel("Query parameter 1 type", { exact: true }).selectOption("integer");
    await editor.getByRole("button", { name: "Save query", exact: true }).click();
    await expect(editor).toContainText(`Saved ${query}.`);
    await expect(page.getByRole("table", { name: "Named queries" })).toContainText(query);

    await editor.getByRole("button", { name: "Test run", exact: true }).click();
    await page.getByLabel("n (integer)", { exact: true }).fill("14");
    await page.getByRole("button", { name: `Run ${query}`, exact: true }).click();
    await expect(page.getByRole("table", { name: "Named query result" })).toContainText("42");

    // Settings: cache the results and limit the rows; the test run waits for the save.
    await editor.getByLabel("Cache results", { exact: true }).check();
    await editor.getByLabel("Cache time to live (ms)", { exact: true }).fill("5000");
    await editor.getByLabel("Maximum rows", { exact: true }).fill("100");
    await expect(editor.getByRole("button", { name: "Test run", exact: true })).toBeDisabled();
    await editor.getByRole("button", { name: "Save query", exact: true }).click();
    await expect(editor).toContainText(`Saved ${query}.`);
    await expect(editor.getByRole("button", { name: "Test run", exact: true })).toBeEnabled();

    await editor.getByRole("button", { name: "Delete query", exact: true }).click();
    await expect(page.getByRole("form", { name: "Named query editor" })).toHaveCount(0);
    await expect(page.getByRole("table", { name: "Named queries" })).not.toContainText(query);
  } finally {
    await api(page, "DELETE", `/api/v1/db/queries/${query}`).catch(() => undefined);
    await api(page, "DELETE", `/api/v1/db/connections/${connection}`).catch(() => undefined);
  }
});
