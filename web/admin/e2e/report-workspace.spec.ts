import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/**
 * Report workspace (FEATURES REP-058, REP-028, REP-010, REP-013, REP-020, REP-034, REP-051) on the real gateway:
 * a report is created from a named query, given an expression parameter, a text block with a data key and a table,
 * Legal pages with a wider left margin, previewed through the gateway's report engine and PDF renderer, saved, and
 * scheduled. The named query reads a SQLite connection the test creates.
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

test("designs, previews, saves and schedules a report on the gateway", async ({ page }) => {
  await login(page);
  const connection = stamp("repdb");
  const query = `${connection}-q`;
  const report = stamp("repws");
  await api(page, "POST", "/api/v1/db/connections", { name: connection, driver: "sqlite", url: `jdbc:sqlite:e2e/${connection}.db`, description: "e2e" });
  await api(page, "POST", "/api/v1/db/queries", {
    name: query,
    connection,
    queryType: "query",
    sql: "SELECT :line AS line, :n AS count",
    parameters: [{ name: "line", dataType: "string" }, { name: "n", dataType: "integer" }],
  });

  try {
    await openSection(page, "Reports");
    const tab = (name: string) => page.getByRole("tab", { name, exact: true }).click();

    // A new report starts from a named query.
    await page.getByRole("button", { name: "New report", exact: true }).click();
    await page.getByLabel("New report name", { exact: true }).fill(report);
    await page.getByLabel("First data source", { exact: true }).selectOption(query);
    await page.getByRole("button", { name: "Create report", exact: true }).click();
    await expect(page.getByLabel("Report", { exact: true })).toHaveValue(report);

    // Data: a parameter with a default, one with an expression, and the query's bindings.
    await tab("Data");
    await page.getByRole("button", { name: "Add parameter", exact: true }).click();
    await page.getByLabel("Parameter 1 name", { exact: true }).fill("line");
    await page.getByLabel("Parameter 1 default", { exact: true }).fill("L1");
    await page.getByRole("button", { name: "Add parameter", exact: true }).click();
    await page.getByLabel("Parameter 2 name", { exact: true }).fill("day");
    await page.getByLabel("Parameter 2 type", { exact: true }).selectOption("timestamp");
    await page.getByLabel("Parameter 2 value from", { exact: true }).selectOption("expression");
    await page.getByLabel("Parameter 2 expression", { exact: true }).fill("startOfDay(now) - 1d");
    await page.getByLabel("Data source 1 line from", { exact: true }).selectOption("line");
    await page.getByLabel("Data source 1 n value", { exact: true }).fill("42");

    // Design: a text block with a data key picked from the list, and a table.
    await tab("Design");
    await page.getByRole("button", { name: "Add text block", exact: true }).click();
    await page.getByLabel("Text", { exact: true }).fill("Line ");
    await page.getByLabel("Data key", { exact: true }).selectOption("line");
    await page.getByRole("button", { name: "Insert data key", exact: true }).click();
    await expect(page.getByTestId("report-page")).toContainText("Line @line@");
    await page.getByRole("button", { name: "Add table", exact: true }).click();
    await expect(page.getByLabel("Data source", { exact: true }).first()).toHaveValue("rows");

    // Overview: Legal pages, a wider left margin.
    await tab("Overview");
    await page.getByLabel("Page size", { exact: true }).selectOption("Legal");
    await page.getByLabel("Margin left", { exact: true }).fill("60");

    // Preview: the draft runs on the gateway before anything is saved.
    await tab("Preview");
    await page.getByRole("button", { name: "Run preview", exact: true }).click();
    const preview = page.getByTestId("report-preview");
    await expect(preview).toContainText("Line L1");
    await expect(preview.getByRole("cell", { name: "42", exact: true })).toBeVisible();
    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Download PDF", exact: true }).click(),
    ]);
    const chunks: Buffer[] = [];
    for await (const chunk of await download.createReadStream()) chunks.push(chunk as Buffer);
    expect(Buffer.concat(chunks).subarray(0, 5).toString("latin1")).toBe("%PDF-");

    // Save, reload, and the design is back.
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByTestId("report-canvas").getByRole("status")).toHaveText("Saved");
    await page.reload();
    await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
    await openSection(page, "Reports");
    await page.getByLabel("Report", { exact: true }).selectOption(report);
    await expect(page.getByTestId("report-page")).toContainText("Line @line@");
    await tab("Overview");
    await expect(page.getByLabel("Page size", { exact: true })).toHaveValue("Legal");
    await expect(page.getByLabel("Margin left", { exact: true })).toHaveValue("60");

    // Schedule, then run the saved report now.
    await tab("Schedule");
    await page.getByLabel("Run on a schedule", { exact: true }).check();
    await page.getByLabel("Cron expression", { exact: true }).fill("0 6 * * *");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByTestId("report-canvas").getByRole("status")).toHaveText("Saved");
    await expect(page.getByText(/Next run /)).toBeVisible();
    await page.getByRole("button", { name: "Run now", exact: true }).click();
    await expect(page.getByText("Run finished.", { exact: true })).toBeVisible();
    await expect(page.getByRole("table", { name: "Latest runs" })).toContainText("manual");
  } finally {
    await api(page, "DELETE", `/api/v1/config/reporting/gateway/Report/${report}`).catch(() => undefined);
    await api(page, "DELETE", `/api/v1/db/queries/${query}`).catch(() => undefined);
    await api(page, "DELETE", `/api/v1/db/connections/${connection}`).catch(() => undefined);
  }
});
