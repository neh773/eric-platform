import { expect, test } from "@playwright/test";
import { canvas, openStudio, setTag, uniqueProject } from "./studio";

/**
 * M4.5: the alarm status table, time series chart, and power chart come from the component registry,
 * so the Designer palette can place them. A tag binding drives the time series chart live.
 */
test("places the live alarm table and charts from the palette and binds a chart to a tag", async ({ page }) => {
  await openStudio(page, uniqueProject("charts"));
  const preview = canvas(page);
  const components = preview.locator(".oip-canvas-render [data-component]");
  const before = await components.count();

  await page.getByRole("button", { name: "Add alarm status table" }).click();
  await page.getByRole("button", { name: "Add power chart" }).click();
  await page.getByRole("button", { name: "Add time series chart" }).click();
  await expect(components).toHaveCount(before + 3);
  await expect(preview.locator(".oip-alarm-table")).toBeVisible();
  await expect(preview.locator(".oip-power-chart")).toBeVisible();
  const chart = preview.locator(".oip-time-series");
  await expect(chart).toBeVisible();

  const inspector = page.getByTestId("inspector");
  await expect(inspector.getByRole("textbox", { name: "Title" })).toHaveValue("Time series");
  await inspector.locator("[data-prop='value']").getByRole("button", { name: /^Bind / }).click();
  const binding = inspector.getByRole("region", { name: "Binding for value" });
  await binding.getByRole("radio", { name: "Tag", exact: true }).click();
  await binding.getByRole("textbox", { name: "Tag path" }).fill("sim/level");
  await expect(binding.getByRole("checkbox", { name: "Enabled" })).toBeChecked();
  for (const value of [10, 40, 25, 60]) {
    await setTag(page, "sim/level", value);
    // The design-mode canvas is inert (outside the accessibility tree), so read the chart's label directly.
    await expect(chart.locator("[role='img']")).toHaveAttribute("aria-label", `Time series: Value ${value.toFixed(1)}`);
  }
  await expect.poll(async () => Number(await chart.getAttribute("data-points"))).toBeGreaterThanOrEqual(4);
  await expect(chart.locator(".oip-chart-line")).toHaveAttribute("d", /^M.*L/);
});
