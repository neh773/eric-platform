import { expect, test } from "@playwright/test";
import { DEVICE, ensureModbusDevice, login, main, openTag, section, tagRow } from "./ui";

const TYPE = "LiftStationDuplex";

/** UDT definitions are created and edited on the admin page, then instanced with the wizard. */
test.describe("UDTs", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("create a UDT definition with a device parameter, a scaled Modbus member, an alarm and history", async ({ page }) => {
    await section(page, "UDT Definitions");
    const table = main(page).getByRole("table", { name: "UDT definitions" });
    if (await table.getByRole("row", { name: new RegExp(TYPE) }).count()) return;
    await page.getByRole("button", { name: "New UDT definition" }).click();
    const form = page.getByRole("form", { name: "UDT definition" });
    await form.getByLabel("Type path").fill(TYPE);
    await expect(form.getByLabel("Parameter 1 name")).toHaveValue("Device");
    await form.getByLabel("Parameter 1 default").fill(DEVICE.name);
    await form.getByRole("button", { name: "Add member" }).click();
    let member = form.getByRole("region", { name: /Member/ }).last();
    await member.getByLabel("Member name").fill("WetWellLevel");
    await member.getByLabel("Kind").selectOption("opc");
    await member.getByLabel("Data type").selectOption("Double");
    await member.getByLabel("Device", { exact: true }).selectOption("{Device}");
    await member.getByLabel("Address").fill("HRUS215");
    await member.getByLabel("Poll rate (ms)").fill("1000");
    await member.getByLabel("Scaling", { exact: true }).selectOption("linear");
    await member.getByLabel("Raw low").fill("0");
    await member.getByLabel("Raw high").fill("10000");
    await member.getByLabel("Scaled low").fill("0");
    await member.getByLabel("Scaled high").fill("100");
    await member.getByLabel("Engineering units", { exact: true }).fill("%");
    await member.getByRole("button", { name: "Add alarm" }).click();
    const alarm = member.getByRole("group", { name: /Alarm/ }).last();
    await alarm.getByLabel("Alarm name").fill("High wet well");
    await alarm.getByLabel("Condition").selectOption("AboveValue");
    await alarm.getByLabel("Priority").selectOption("High");
    await alarm.getByLabel("Setpoint", { exact: true }).fill("90");
    await member.getByLabel("Record history for this member").check();
    await member.getByLabel("History provider").selectOption("local");
    await member.getByLabel("Sample mode").selectOption("periodic");
    await member.getByLabel("Sample rate (ms)").fill("5000");
    await form.getByRole("button", { name: "Add member" }).click();
    member = form.getByRole("region", { name: /Member/ }).last();
    await member.getByLabel("Member name").fill("Pump1Running");
    await member.getByLabel("Kind").selectOption("opc");
    await member.getByLabel("Data type").selectOption("Boolean");
    await member.getByLabel("Device", { exact: true }).selectOption("{Device}");
    await member.getByLabel("Address").fill("HRUS401.1");
    await form.getByRole("button", { name: "Create definition" }).click();
    await expect(page.getByRole("status").filter({ hasText: `Created ${TYPE}` })).toBeVisible();
    await page.reload();
    await expect(main(page).getByRole("table", { name: "UDT definitions" }).getByRole("row", { name: new RegExp(TYPE) })).toBeVisible();
  });

  test("create an instance with the wizard; its members read live values from the device", async ({ page }) => {
    await ensureModbusDevice(page);
    await section(page, "Tags");
    await page.getByRole("navigation", { name: "Tags views" }).getByRole("link", { name: "UDT instances" }).click();
    const wizard = page.getByTestId("udt-wizard");
    await wizard.getByLabel("UDT definition").selectOption(TYPE);
    await wizard.getByLabel("Folder").fill("Stations");
    await wizard.getByLabel("Name pattern").fill("LS_{n}");
    await wizard.getByLabel("Count").fill("1");
    await wizard.getByLabel("Device for LS_01").fill(DEVICE.name);
    await wizard.getByRole("button", { name: "Preview" }).click();
    // The preview is shown below the wizard form.
    await expect(main(page).getByRole("status").filter({ hasText: /Will create 1, skip 0|Will create 0, skip 1/ })).toBeVisible();
    const create = wizard.getByRole("button", { name: /^Create 1 instance/ });
    if (await main(page).getByText("Will create 1, skip 0").count()) {
      await expect(create).toBeEnabled();
      await create.click();
      await expect(page.getByText(/Created 1 instance/).first()).toBeVisible();
    }
    await openTag(page, "Stations/LS_01/WetWellLevel");
    await expect(async () => {
      await page.getByRole("button", { name: "Refresh" }).click();
      expect(await tagRow(page, "WetWellLevel").innerText()).toContain("Good");
    }).toPass({ timeout: 45_000 });
  });

  test("edit the definition: change the alarm setpoint and it sticks", async ({ page }) => {
    await section(page, "UDT Definitions");
    await page.getByRole("button", { name: `Edit ${TYPE}`, exact: true }).click();
    const form = page.getByRole("form", { name: "UDT definition" });
    await form.getByRole("button", { name: "Edit member WetWellLevel" }).click();
    const member = form.getByRole("region", { name: "Member WetWellLevel" });
    await member.getByRole("group", { name: "Alarm High wet well" }).getByLabel("Setpoint", { exact: true }).fill("88");
    await form.getByRole("button", { name: "Save definition" }).click();
    await expect(page.getByRole("status").filter({ hasText: `Saved ${TYPE}` })).toBeVisible();
    await page.reload();
    await page.getByRole("button", { name: `Edit ${TYPE}`, exact: true }).click();
    await page.getByRole("form", { name: "UDT definition" }).getByRole("button", { name: "Edit member WetWellLevel" }).click();
    await expect(
      page.getByRole("region", { name: "Member WetWellLevel" }).getByRole("group", { name: "Alarm High wet well" }).getByLabel("Setpoint", { exact: true }),
    ).toHaveValue("88");
  });
});
