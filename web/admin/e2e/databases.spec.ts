import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp, withViewer } from "./gateway";

/**
 * Databases page (C-17): a connection's status and test action, a named-query test run, and the query
 * browser, which runs reads and refuses a write on a connection that does not allow writes. The
 * connection password is set through the API and must never appear on the page.
 */
const DB_PASSWORD = "E2e-Db-Password-7";

async function api(page: Page, method: "POST" | "PUT" | "DELETE", url: string, data?: unknown) {
  const token = await csrf(page);
  const res = await page.request.fetch(url, {
    method,
    data,
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("tests a connection, runs a named query, and browses read-only", async ({ page, browser }) => {
  await login(page);
  const name = stamp("e2edb");
  const query = `${name}-double`;
  await api(page, "POST", "/api/v1/db/connections", {
    name,
    driver: "sqlite",
    url: `jdbc:sqlite:e2e/${name}.db`,
    password: DB_PASSWORD,
    description: "e2e",
  });
  await api(page, "POST", "/api/v1/db/queries", {
    name: query,
    connection: name,
    queryType: "query",
    sql: "SELECT :n * 2 AS doubled",
    parameters: [{ name: "n", dataType: "integer" }],
  });

  try {
    await openSection(page, "Databases");
    const connections = page.getByRole("table", { name: "Database connections" });
    const row = connections.getByRole("row", { name: new RegExp(name) }).first();
    await expect(row).toContainText("sqlite");
    await expect(row).toContainText("Stored");
    await expect(row).toContainText("Read only");
    await expect(page.locator("body")).not.toContainText(DB_PASSWORD);
    await expect(page.locator("body")).not.toContainText("secret://");

    await page.getByRole("button", { name: `Test ${name}`, exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: `Connection ${name} is valid.` })).toBeVisible();
    await expect(page.getByTestId(`db-status-${name}`)).toHaveText("Valid");

    await page.getByRole("button", { name: `Test ${query}`, exact: true }).click();
    await page.getByLabel("n (integer)").fill("21");
    await page.getByRole("button", { name: `Run ${query}` }).click();
    const result = page.getByRole("table", { name: "Named query result" });
    await expect(result).toContainText("doubled");
    await expect(result).toContainText("42");

    const queryBrowser = page.getByRole("form", { name: "Query browser" });
    await queryBrowser.getByLabel("Connection").selectOption(name);
    await expect(queryBrowser).toContainText("Statements that change data are refused");
    await queryBrowser.getByLabel("SQL statement").fill("SELECT 7 AS seven, 'plant' AS area");
    await queryBrowser.getByRole("button", { name: "Run statement" }).click();
    const browsed = page.getByRole("table", { name: "Query browser result" });
    await expect(browsed).toContainText("seven");
    await expect(browsed).toContainText("plant");

    await queryBrowser.getByLabel("SQL statement").fill("CREATE TABLE e2e_blocked (id INTEGER)");
    await queryBrowser.getByRole("button", { name: "Run statement" }).click();
    await expect(queryBrowser.getByRole("alert")).toContainText("does not allow write statements");
    await expect(page.locator("body")).not.toContainText(DB_PASSWORD);

    await withViewer(browser, page, async (viewer) => {
      await openSection(viewer, "Databases");
      await expect(viewer.getByRole("alert")).toBeVisible();
      await expect(viewer.getByRole("form", { name: "Query browser" })).toHaveCount(0);
    });
  } finally {
    await api(page, "DELETE", `/api/v1/db/queries/${query}`);
    await api(page, "DELETE", `/api/v1/db/connections/${name}`);
  }
});

/**
 * DAT-015: the alarm journal moves onto a named connection from this page; the connection then lists the journal as
 * a user, and a journal pointed at a connection that does not exist shows the fault instead of failing silently.
 */
test("moves the alarm journal onto a named connection and shows a missing one as a fault", async ({ page }) => {
  await login(page);
  const name = stamp("e2ejournal");
  await api(page, "POST", "/api/v1/db/connections", {
    name,
    driver: "sqlite",
    url: `jdbc:sqlite:e2e/${name}.db`,
    description: "e2e journal",
  });
  try {
    await openSection(page, "Databases");
    const storage = page.getByRole("region", { name: "Alarm journal storage" });
    const status = page.getByTestId("journal-storage-status");
    await expect(status).toBeVisible();
    await storage.getByLabel("Journal connection").selectOption(name);
    await storage.getByRole("button", { name: "Save journal storage", exact: true }).click();
    await expect(status).toHaveText(`Connection ${name}: Valid`, { timeout: 15_000 });
    await expect(page.getByTestId(`db-used-by-${name}`)).toHaveText("Alarm journal");

    const missing = `${name}-gone`;
    await api(page, "PUT", "/api/v1/alarms/journal/settings", { connection: missing });
    await expect(status).toContainText(`Connection ${missing}: Faulted`, { timeout: 15_000 });
    await expect(status).toContainText(`database connection '${missing}' not found`);
    // The journal moved through the API, not this page, so the connection list is read again before checking it.
    await page.getByRole("button", { name: "Reload", exact: true }).click();
    await expect(page.getByTestId(`db-used-by-${name}`)).toHaveText("Nothing");
  } finally {
    await api(page, "PUT", "/api/v1/alarms/journal/settings", { connection: null });
    await api(page, "DELETE", `/api/v1/db/connections/${name}`);
  }
});
