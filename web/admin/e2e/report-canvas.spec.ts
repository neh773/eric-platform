import { expect, test } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

test("the report canvas adds a text component and saves it", async ({ page }) => {
  await login(page);
  const name = stamp("rpt");
  const token = await csrf(page);
  const created = await page.request.put(`/api/v1/config/reporting/gateway/Report/${name}`, {
    headers: { "X-CSRF-Token": token },
    data: {
      apiVersion: "oip/v1",
      kind: "Report",
      metadata: { name, module: "reporting", version: 0 },
      spec: {
        title: "Daily",
        dataSources: [{ name: "rows", type: "namedQuery", query: "daily" }],
      },
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  await openSection(page, "Reports");
  await page.getByLabel("Report").selectOption(name);
  await page.getByRole("button", { name: "Add text" }).click();
  await page.getByLabel("Text").fill("Line @line@");
  await expect(page.getByTestId("report-page")).toContainText("Line @line@");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByTestId("report-canvas").getByRole("status")).toHaveText("Saved");

  await page.reload();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
  await openSection(page, "Reports");
  await page.getByLabel("Report").selectOption(name);
  await expect(page.getByTestId("report-page")).toContainText("Line @line@");
});
