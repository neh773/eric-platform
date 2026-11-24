import { expect, test, type Page } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * 0.1.1: a UDT definition is created, edited and deleted from the UDT Definitions form: a parameter, a member bound to
 * a device address through {Device}, scaling, engineering units and range, an alarm, history settings, and a
 * nested UDT member. The test checks the saved config resource and that an instance made on the Tags page expands.
 */
async function udt(page: Page, name: string): Promise<Record<string, unknown>> {
  const res = await page.request.get(`/api/v1/config/tags/gateway/Udt/${name}`);
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { spec: Record<string, unknown> }).spec;
}

test("creates, edits and deletes a UDT definition from the form", async ({ page }) => {
  await login(page);
  const id = stamp("e2e").replace(/-/g, "_");
  const typePath = `Types/Lift_${id}`;
  const innerPath = `Types/Pump_${id}`;
  page.on("dialog", (dialog) => void dialog.accept());
  await openSection(page, "UDT Definitions");

  // A small type to nest.
  await page.getByRole("button", { name: "New UDT definition" }).click();
  let form = page.getByRole("form", { name: "UDT definition" });
  await form.getByLabel("Type path").fill(innerPath);
  await form.getByRole("button", { name: "Add member" }).click();
  let member = form.getByRole("region", { name: /Member/ });
  await member.getByLabel("Member name").fill("Running");
  await member.getByLabel("Kind").selectOption("memory");
  await member.getByLabel("Data type").selectOption("Boolean");
  await form.getByRole("button", { name: "Create definition" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Created ${innerPath}` })).toBeVisible();

  await page.getByRole("button", { name: "New UDT definition" }).click();
  form = page.getByRole("form", { name: "UDT definition" });
  await form.getByLabel("Type path").fill(typePath);
  const resource = await form.getByLabel("Resource name").inputValue();
  expect(resource).toMatch(/^types-lift-/);
  await expect(form.getByLabel("Parameter 1 name")).toHaveValue("Device");
  await form.getByLabel("Parameter 1 default").fill("ls014");

  await form.getByRole("button", { name: "Add member" }).click();
  member = form.getByRole("region", { name: /Member/ });
  await member.getByLabel("Member name").fill("WetWellLevel");
  await expect(member.getByLabel("Kind")).toHaveValue("opc");
  await member.getByLabel("Data type").selectOption("Double");
  await member.getByLabel("Device", { exact: true }).selectOption("{Device}");
  await member.getByLabel("Address").fill("HR215");
  await member.getByLabel("Poll rate (ms)").fill("500");
  await member.getByLabel("Scaling", { exact: true }).selectOption("linear");
  await member.getByLabel("Raw low").fill("0");
  await member.getByLabel("Raw high").fill("10000");
  await member.getByLabel("Scaled low").fill("0");
  await member.getByLabel("Scaled high").fill("100");
  await member.getByLabel("Engineering units", { exact: true }).fill("%");
  await member.getByLabel("Engineering low").fill("0");
  await member.getByLabel("Engineering high").fill("100");
  await member.getByRole("button", { name: "Add alarm" }).click();
  const alarm = member.getByRole("group", { name: /Alarm/ });
  await alarm.getByLabel("Alarm name").fill("High wet well");
  await alarm.getByLabel("Condition").selectOption("AboveValue");
  await alarm.getByLabel("Priority").selectOption("High");
  await alarm.getByLabel("Setpoint").fill("85");
  await alarm.getByLabel("Deadband").fill("1");
  await member.getByLabel("Record history for this member").check();
  await member.getByLabel("History provider").selectOption("local");
  await member.getByLabel("Sample mode").selectOption("periodic");
  await member.getByLabel("Sample rate (ms)").fill("5000");
  await member.getByLabel("History deadband", { exact: true }).selectOption("absolute");
  await member.getByLabel("History deadband amount").fill("0.1");

  await form.getByRole("button", { name: "Add member" }).click();
  member = form.getByRole("region", { name: /Member/ });
  await member.getByLabel("Member name").fill("P1");
  await member.getByLabel("Kind").selectOption("udt");
  await member.getByLabel("Nested UDT type").selectOption(innerPath);

  await form.getByRole("button", { name: "Create definition" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Created ${typePath}` })).toBeVisible();
  const table = page.getByRole("table", { name: "UDT definitions" });
  await expect(table.getByRole("row", { name: new RegExp(typePath) })).toContainText("Device");

  const saved = await udt(page, resource);
  expect(saved.path).toBe(typePath);
  const members = saved.members as Record<string, unknown>[];
  expect(members.map((m) => m.name)).toEqual(["WetWellLevel", "P1"]);
  const level = members[0]!;
  expect(level).toMatchObject({ type: "opc", dataType: "Double", opcServer: "{Device}", opcItemPath: "HR215", scanPeriodMillis: 500, engUnit: "%", engLow: 0, engHigh: 100 });
  expect(level.scaling).toMatchObject({ mode: "linear", rawHigh: 10000, scaledHigh: 100 });
  expect(level.alarms).toEqual([expect.objectContaining({ name: "High wet well", mode: "AboveValue", priority: "High", setpointA: 85, deadband: 1 })]);
  expect(level.history).toMatchObject({ enabled: true, storageProvider: "local", sampleMode: "periodic", sampleRateMillis: 5000, deadband: { mode: "absolute", value: 0.1 } });
  expect(members[1]).toMatchObject({ type: "udt", typeId: innerPath });

  // Edit: change the alarm setpoint and turn history off.
  await page.getByRole("button", { name: `Edit ${typePath}`, exact: true }).click();
  form = page.getByRole("form", { name: "UDT definition" });
  await form.getByRole("button", { name: "Edit member WetWellLevel" }).click();
  member = form.getByRole("region", { name: "Member WetWellLevel" });
  await member.getByRole("group", { name: "Alarm High wet well" }).getByLabel("Setpoint").fill("88");
  await member.getByLabel("Record history for this member").uncheck();
  await form.getByRole("button", { name: "Save definition" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Saved ${typePath}` })).toBeVisible();
  const edited = await udt(page, resource);
  const editedLevel = (edited.members as Record<string, unknown>[])[0]!;
  expect((editedLevel.alarms as Record<string, unknown>[])[0]!.setpointA).toBe(88);
  expect((editedLevel.history as Record<string, unknown>).enabled).toBe(false);

  // The Tags page links to the definitions, and ?type= opens one in the editor.
  await openSection(page, "Tags");
  await page.getByRole("navigation", { name: "Tags views" }).getByRole("link", { name: "UDT definitions" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "UDT Definitions" })).toBeVisible();
  await page.goto(`/admin/udt-types?type=${encodeURIComponent(typePath)}`);
  await expect(page.getByRole("form", { name: "UDT definition" }).getByRole("heading", { name: `Edit ${typePath}` })).toBeVisible();
  await page.getByRole("button", { name: "Cancel" }).click();

  await page.getByRole("button", { name: `Delete ${typePath}`, exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: `Deleted ${typePath}` })).toBeVisible();
  await page.getByRole("button", { name: `Delete ${innerPath}`, exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: `Deleted ${innerPath}` })).toBeVisible();
  await expect(table).not.toContainText(typePath);
});
