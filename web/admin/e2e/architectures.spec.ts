import { expect, test } from "@playwright/test";
import { login, openSection, withViewer } from "./gateway";

/**
 * GAN-006 and RED-001 on the running admin gateway: a single independent gateway
 * matches the single-server row, and the redundancy table names the alarm journal.
 */
test("shows which reference architectures this gateway matches", async ({ page, browser }) => {
  test.setTimeout(60_000);
  await login(page);
  await openSection(page, "Architectures");
  await expect(page.getByTestId("architecture-single-server")).toContainText("Matches this gateway");
  await expect(page.getByTestId("architecture-enterprise")).toContainText("Does not match this gateway");
  await expect(page.getByTestId("architecture-cloud")).toContainText("Does not match this gateway");
  await expect(page.getByTestId("redundancy-subsystems")).toContainText("Alarm journal");
  await expect(page.getByTestId("redundancy-subsystems")).toContainText("active-node");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Architectures");
    await expect(viewer.getByTestId("architectures-no-read")).toBeVisible();
    await expect(viewer.getByTestId("architecture-single-server")).toHaveCount(0);
  });
});
