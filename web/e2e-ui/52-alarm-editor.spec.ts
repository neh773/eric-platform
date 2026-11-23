import { expect, test } from "@playwright/test";
import { createTag, login, main, openTag, section, tagExists, tagTab } from "./ui";

const TAG = "AlarmLevel";

/**
 * The clean-machine path: a memory tag at 10, then an alarm added in the tag editor with a hyphenated name, Enabled,
 * Above setpoint from a fixed value of 5. Save must turn on, and the alarm must show as active.
 */
test("an alarm added in the tag editor saves and goes active", async ({ page }) => {
  await login(page);
  if (!(await tagExists(page, TAG))) {
    await createTag(page, TAG, "memory", "Double", async (form) => {
      await form.getByLabel("Initial value").fill("10");
    });
  }
  await openTag(page, TAG);
  await tagTab(page, "Alarms");
  await main(page).getByRole("button", { name: "Add alarm" }).click();
  const alarm = main(page).getByRole("group", { name: /^Alarm / }).last();
  await alarm.getByLabel("Alarm name").fill("high-level");
  await expect(alarm.getByLabel("Enabled")).toBeChecked();
  await alarm.getByLabel("Condition").selectOption("AboveValue");
  await alarm.getByLabel("Setpoint source").selectOption("value");
  await alarm.getByLabel("Setpoint", { exact: true }).fill("5");
  const save = main(page).getByRole("button", { name: "Save", exact: true });
  await expect(save).toBeEnabled();
  await save.click();
  await expect(main(page).getByText(/Saved|version \d+/).first()).toBeVisible();
  await section(page, "Alarming");
  const table = main(page).getByRole("table", { name: "Active alarms" });
  await expect(async () => {
    await page.reload();
    await expect(table.getByRole("row").filter({ hasText: "high-level" })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});
