import { expect, test } from "@playwright/test";
import { csrf, login, openSection } from "./gateway";
import { expectNoSecrets, withRole } from "./status";

/**
 * GAT-016/GAT-017: the Overview page shows health, version, profile, uptime, CPU, heap, disk,
 * clock drift, and a module count, with banners when a figure passes its cutoff.
 */
test("overview shows gateway health and live figures", async ({ page }) => {
  await login(page);
  const health = (await (await page.request.get("/api/v1/health")).json()) as {
    version: string;
    profile: string;
    modules: number;
    startedAt: string;
  };
  await openSection(page, "Overview");
  await expect(page.getByTestId("section-placeholder")).toHaveCount(0);
  await expect(page.getByTestId("overview-health")).toHaveText("UP");
  await expect(page.getByTestId("overview-version")).toHaveText(health.version);
  await expect(page.getByTestId("overview-profile")).toHaveText(health.profile);
  await expect(page.getByTestId("overview-modules")).toHaveText(String(health.modules));
  await expect(page.getByTestId("overview-uptime")).toContainText(`started ${new Date(health.startedAt).toISOString()}`);
  // No in-app restart in this beta: a gateway started from the Linux archive would not come back.
  await expect(page.getByRole("button", { name: /restart/i })).toHaveCount(0);
  await expect(page.getByTestId("overview-restart-help")).toContainText("To restart, stop ERIC and start it again");
  await expect(page.getByTestId("overview-cpu")).toHaveText(/%|n\/a/);
  await expect(page.getByTestId("overview-heap")).toContainText(" of ");
  await expect(page.getByTestId("overview-disk")).toContainText(" of ");
  await expect(page.getByTestId("overview-drift")).toContainText("ms late");
  await expect(page.getByTestId("overview-module-states")).toContainText("modules running");
  // A quiet test gateway is inside every cutoff, or names the figure that is not.
  const banners = page.getByTestId("overview-banners");
  await expect(banners.locator("p").first()).toBeVisible();
  await expectNoSecrets(page);
});

test("a viewer sees public health only and the API refuses live figures", async ({ page, browser }) => {
  await login(page);
  await withRole(browser, page, "viewer", async (viewer) => {
    await openSection(viewer, "Overview");
    await expect(viewer.getByTestId("overview-health")).toHaveText("UP");
    // /api/v1/health is public, so the viewer still sees the uptime.
    await expect(viewer.getByTestId("overview-uptime")).toContainText("started");
    await expect(viewer.getByTestId("overview-no-read")).toBeVisible();
    await expect(viewer.getByTestId("overview-performance")).toHaveCount(0);
    expect((await viewer.request.get("/api/v1/diagnostics/performance")).status()).toBe(403);
    await expectNoSecrets(viewer);
  });
});

/**
 * Review-1 F11 (GAT-033): the Overview page reads and switches the active deployment mode.
 */
test("overview switches the active deployment mode", async ({ page, browser }) => {
  await login(page);
  const token = await csrf(page);
  const reset = () => page.request.put("/api/v1/deployment-mode", { data: { mode: null }, headers: { "X-CSRF-Token": token } });
  expect((await reset()).status()).toBe(200);
  try {
    await openSection(page, "Overview");
    await expect(page.getByTestId("overview-deployment-mode")).toHaveText("None");
    await page.getByLabel("Deployment mode").fill("staging");
    await page.getByRole("button", { name: "Switch mode" }).click();
    await expect(page.getByTestId("overview-deployment-mode")).toHaveText("staging");
    const after = (await (await page.request.get("/api/v1/deployment-mode")).json()) as { mode: string | null };
    expect(after.mode).toBe("staging");

    await page.getByLabel("Deployment mode").fill("Not Valid");
    await page.getByRole("button", { name: "Switch mode" }).click();
    await expect(page.getByTestId("overview-deployment-mode-error")).toBeVisible();
    await expect(page.getByTestId("overview-deployment-mode")).toHaveText("staging");

    await page.getByRole("button", { name: "Clear mode" }).click();
    await expect(page.getByTestId("overview-deployment-mode")).toHaveText("None");

    await withRole(browser, page, "operator", async (operator) => {
      await openSection(operator, "Overview");
      await expect(operator.getByTestId("overview-deployment-mode")).toHaveText("None");
      await expect(operator.getByRole("button", { name: "Switch mode" })).toHaveCount(0);
    });
  } finally {
    await reset();
  }
});
