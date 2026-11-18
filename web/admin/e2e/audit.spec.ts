import { expect, test } from "@playwright/test";
import { login, openSection, withViewer } from "./gateway";

/**
 * SEC-001 / SEC-003: filter and page the audit log. A config edit shows up as an entry.
 * The edit itself uses the schema-driven config editor.
 */
const RESOURCE = "/api/v1/config/org.oip.security/gateway/AuditProfile/internal";

async function editAuditProfile(page: import("@playwright/test").Page, description: string) {
  const search = page.getByRole("searchbox", { name: "Search the gateway" });
  await search.fill("AuditProfile");
  await page.getByRole("option", { name: "AuditProfile internal", exact: true }).click();
  const descriptionBox = page.getByRole("textbox", { name: "Description" });
  await expect(descriptionBox).toBeVisible();
  await descriptionBox.fill(description);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
}

test("filters, pages, and shows a config edit in the audit log", async ({ page, browser }) => {
  test.setTimeout(60_000);
  await login(page);
  const marker = `Audit note ${Date.now()}`;
  await editAuditProfile(page, marker);
  await editAuditProfile(page, `${marker} again`);
  const saved = await page.request.get(RESOURCE);
  expect(saved.ok()).toBeTruthy();
  expect(((await saved.json()) as { spec: { description: string } }).spec.description).toBe(`${marker} again`);

  await openSection(page, "Audit");
  await page.getByLabel("Actor").fill("admin");
  await page.getByLabel("Action").fill("gateway config updated");
  await page.getByLabel("Target").fill("AuditProfile");
  await page.getByRole("button", { name: "Apply filters" }).click();
  const table = page.getByRole("table", { name: "Audit entries" });
  await expect(table).toContainText("gateway config updated");
  await expect(table).toContainText("AuditProfile");
  await expect(page.getByTestId("audit-total")).toContainText("of ");

  await page.getByLabel("From").fill("2099-01-01T00:00");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(page.getByTestId("audit-total")).toHaveText("No audit entries.");

  await page.getByLabel("From").fill("2000-01-01T00:00");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(table).toContainText("gateway config updated");

  await page.getByLabel("Page size").fill("1");
  await expect(page.getByTestId("audit-total")).toHaveText(/^Showing 1.1 of (\d+)$/);
  const total = Number(((await page.getByTestId("audit-total").innerText()).match(/of (\d+)/) ?? [])[1]);
  expect(total).toBeGreaterThan(1);
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByTestId("audit-total")).toHaveText(/^Showing 2.2 of \d+$/);
  await page.getByRole("button", { name: "Previous page" }).click();
  await expect(page.getByTestId("audit-total")).toHaveText(/^Showing 1.1 of \d+$/);

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Audit");
    await expect(viewer.getByRole("table", { name: "Audit entries" })).toBeVisible();
    await expect(viewer.getByRole("alert")).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: /Create|Delete|Install|Restore|Revoke/ })).toHaveCount(0);
  });
});
