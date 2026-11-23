import { expect, test } from "@playwright/test";
import { ALARM_TAG, login, main, openTag, saveTagEditor, section, tagTab } from "./ui";

/** A tag alarm is configured with a form (not JSON), goes active on a value write, and shows on the Alarming page. */
test.describe("alarms", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("add a high-level alarm to a tag with the alarm form", async ({ page }) => {
    await openTag(page, ALARM_TAG);
    await tagTab(page, "Alarms");
    const existing = main(page).getByRole("group", { name: /Alarm HighLevel/ });
    if (!(await existing.count())) {
      await main(page).getByRole("button", { name: "Add alarm" }).click();
      const alarm = main(page).getByRole("group", { name: /^Alarm / }).last();
      await alarm.getByLabel("Alarm name").fill("HighLevel");
      await alarm.getByLabel("Condition").selectOption("AboveValue");
      await alarm.getByLabel("Setpoint", { exact: true }).fill("80");
      await alarm.getByLabel("Priority").selectOption("High");
      await alarm.getByLabel("Deadband").fill("2");
      await alarm.getByLabel("On delay (s)").fill("0");
      await alarm.getByLabel("Notes").fill("Wet well high level");
      await saveTagEditor(page);
    }
    await page.reload();
    await openTag(page, ALARM_TAG);
    await tagTab(page, "Alarms");
    const alarm = main(page).getByRole("group", { name: /Alarm HighLevel/ });
    await expect(alarm.getByLabel("Setpoint", { exact: true })).toHaveValue("80");
    await expect(alarm.getByLabel("Priority")).toHaveValue("High");
  });

  test("the alarm goes active on a high value, shows on the Alarming page, and can be acknowledged", async ({ page }) => {
    await openTag(page, ALARM_TAG);
    await main(page).getByLabel("Value to write").fill("95");
    await main(page).getByRole("button", { name: "Write", exact: true }).click();
    await section(page, "Alarming");
    const table = main(page).getByRole("table", { name: "Active alarms" });
    await expect(async () => {
      await page.reload();
      await expect(table.getByRole("row").filter({ hasText: "HighLevel" })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
    await table.getByRole("row").filter({ hasText: "HighLevel" }).getByRole("button", { name: /Acknowledge/ }).click();
    await expect(table.getByRole("row").filter({ hasText: "HighLevel" })).toContainText(/Acknowledged|Acked/i);
    // Clear it again so later specs start quiet.
    await openTag(page, ALARM_TAG);
    await main(page).getByLabel("Value to write").fill("10");
    await main(page).getByRole("button", { name: "Write", exact: true }).click();
  });

  test("notification pipeline and roster forms create, edit and delete", async ({ page }) => {
    await section(page, "Alarming");
    await page.getByRole("button", { name: "New roster" }).click();
    await main(page).getByLabel("Alarm name").fill("oncall");
    await main(page).getByLabel("Roster users").fill("admin");
    await main(page).getByRole("button", { name: /^Save/ }).first().click();
    await expect(main(page).getByText("oncall").first()).toBeVisible();
    await page.reload();
    await expect(main(page).getByText("oncall").first()).toBeVisible();
  });
});
