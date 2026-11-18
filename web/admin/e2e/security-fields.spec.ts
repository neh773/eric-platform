import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * User grants and remote service policies are edited from the screen, with the JSON view kept for levels.
 * Doc source: FEATURES SEC-019, SEC-030.
 */

test("grants one user a security level and removes the grant", async ({ page }) => {
  const user = stamp("user");
  await login(page);
  await openSection(page, "Security Levels");
  const main = page.getByRole("main");
  await main.getByLabel("New level path").fill("Plant/Night");
  await main.getByRole("button", { name: "Add level" }).click();
  const grants = main.getByRole("region", { name: "User grants" });
  await grants.getByLabel("Grant username").fill(user);
  await grants.getByLabel("Plant/Night").check();
  await grants.getByRole("button", { name: "Add user grant" }).click();
  await main.getByRole("button", { name: "Save levels and rules" }).click();
  await expect(main.getByRole("status")).toContainText("Security levels saved");
  await main.getByRole("button", { name: "Edit as JSON" }).click();
  await expect(main.getByLabel("Levels JSON")).toHaveValue(new RegExp(user));
  await main.getByRole("button", { name: "Edit as form" }).click();
  await main.getByRole("button", { name: `Remove grant for ${user}` }).click();
  await main.getByRole("button", { name: "Save levels and rules" }).click();
  await expect(main.getByRole("status")).toContainText("Security levels saved");
  await expect(main.getByText(user)).toHaveCount(0);
});

test("saves and deletes a remote service policy", async ({ page }) => {
  const peer = stamp("peer");
  await login(page);
  await openSection(page, "Network");
  const policies = page.getByRole("region", { name: "Service policies" });
  await policies.getByLabel("Policy peer").fill(peer);
  await policies.getByLabel("Policy service").selectOption("history");
  await policies.getByLabel("Policy access").selectOption("read");
  await policies.getByRole("button", { name: "Save policy" }).click();
  await expect(policies.getByRole("cell", { name: peer, exact: true })).toBeVisible();
  await policies.getByRole("button", { name: `Delete history for ${peer}` }).click();
  await expect(policies.getByRole("cell", { name: peer, exact: true })).toHaveCount(0);
});
