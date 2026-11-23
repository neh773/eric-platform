import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/**
 * 0.1.1: an alarm is added to a tag, changed and removed in the tag editor's Alarms form (no JSON).
 * The API is only used to read back what the form saved.
 */
async function stored(page: Page, resource: string): Promise<Record<string, unknown>> {
  const token = await csrf(page);
  const res = await page.request.get(`/api/v1/config/tags/gateway/Tag/${resource}`, { headers: { "X-CSRF-Token": token } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { spec: Record<string, unknown> }).spec;
}

test("adds, edits and removes an alarm on a tag from the form", async ({ page }) => {
  const folder = `E2E_${stamp("alm").replace(/-/g, "_")}`;
  const tagPath = `[default]${folder}/WetWell`;
  await login(page);
  await openSection(page, "Tags");

  await page.getByRole("button", { name: "New tag" }).click();
  const create = page.getByTestId("create-tag");
  await create.getByLabel("Name").fill(`${folder}/WetWell`);
  await create.getByLabel("Tag type").selectOption("memory");
  await create.getByLabel("Data type", { exact: true }).selectOption("Double");
  await create.getByLabel("Initial value").fill("10");
  await create.getByRole("button", { name: "Create tag" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Created ${tagPath}.` })).toBeVisible();

  const editor = page.getByTestId("tag-editor");
  const resource = ((await editor.getByTestId("tag-version").textContent()) ?? "").match(/Resource (\S+)/)![1]!;
  await editor.getByRole("tab", { name: "Alarms", exact: true }).click();
  const alarms = editor.getByRole("group", { name: "Alarms on this tag" });
  await expect(alarms).toContainText("No alarms are configured on this tag.");
  await alarms.getByRole("button", { name: "Add alarm" }).click();
  let row = alarms.getByRole("group", { name: /^Alarm / });
  await row.getByLabel("Alarm name").fill("High wet well");
  await row.getByLabel("Condition").selectOption("AboveValue");
  await row.getByLabel("Priority").selectOption("High");
  await row.getByLabel("Setpoint").fill("85");
  await row.getByLabel("Deadband").fill("1");
  await editor.getByRole("button", { name: "Save", exact: true }).click();
  await expect(editor.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
  expect((await stored(page, resource)).alarms).toEqual([
    expect.objectContaining({ name: "High wet well", mode: "AboveValue", priority: "High", setpointA: 85, deadband: 1 }),
  ]);

  // Edit: a second alarm, and a new setpoint on the first.
  row = alarms.getByRole("group", { name: "Alarm High wet well" });
  await row.getByLabel("Setpoint").fill("88");
  await alarms.getByRole("button", { name: "Add alarm" }).click();
  const low = alarms.getByRole("group", { name: /^Alarm / }).nth(1);
  await low.getByLabel("Alarm name").fill("Low wet well");
  await low.getByLabel("Condition").selectOption("BelowValue");
  await low.getByLabel("Setpoint").fill("12");
  await editor.getByRole("button", { name: "Save", exact: true }).click();
  await expect(editor.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
  let spec = await stored(page, resource);
  expect(spec.alarms).toEqual([
    expect.objectContaining({ name: "High wet well", setpointA: 88 }),
    expect.objectContaining({ name: "Low wet well", mode: "BelowValue", setpointA: 12 }),
  ]);

  await expect(alarms.getByRole("group", { name: "Alarm Low wet well" })).toBeVisible();

  // Remove both.
  await alarms.getByRole("button", { name: "Remove alarm High wet well" }).click();
  await alarms.getByRole("button", { name: "Remove alarm Low wet well" }).click();
  await editor.getByRole("button", { name: "Save", exact: true }).click();
  await expect(editor.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
  spec = await stored(page, resource);
  expect(spec.alarms ?? []).toEqual([]);
});
