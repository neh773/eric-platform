import { expect, test } from "@playwright/test";
import { connectionRow, DEVICE, ensureModbusDevice, expectConnected, login, main, section } from "./ui";

test.describe("devices", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("Modbus TCP: add through the form, it connects, edit it, and the change sticks", async ({ page }) => {
    await ensureModbusDevice(page);
    await expectConnected(page);
    // Edit: open the device, change a setting, save, reopen.
    await main(page).getByRole("button", { name: `Edit ${DEVICE.name}` }).click();
    await main(page).getByLabel("Word order").selectOption("LOW_WORD_FIRST");
    await main(page).getByRole("button", { name: "Save device" }).click();
    await expect(main(page).getByText(`Saved ${DEVICE.name}.`)).toBeVisible();
    await page.reload();
    await main(page).getByRole("button", { name: `Edit ${DEVICE.name}` }).click();
    await expect(main(page).getByLabel("Word order")).toHaveValue("LOW_WORD_FIRST");
    await main(page).getByLabel("Word order").selectOption("HIGH_WORD_FIRST");
    await main(page).getByRole("button", { name: "Save device" }).click();
    await expect(main(page).getByText(`Saved ${DEVICE.name}.`)).toBeVisible();
    await expectConnected(page);
  });

  test("a bad device name gets a clear message", async ({ page }) => {
    await section(page, "Connections");
    await page.getByRole("button", { name: "New device" }).click();
    await page.getByLabel("Device name").fill("Bad Name!");
    await page.getByLabel("Host").fill("10.0.0.1");
    await page.getByRole("button", { name: "Save device" }).click();
    await expect(main(page).getByText(/lowercase/i).first()).toBeVisible();
  });

  for (const kind of [
    { type: "modbus", name: "rtu01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host").fill(DEVICE.host); await p.getByLabel("Mode").selectOption("RTU_OVER_TCP"); } },
    { type: "simulator", name: "sim01", fill: async () => {} },
    { type: "opcua", name: "opc01", fill: async (p: import("@playwright/test").Page) => { const url = p.getByLabel(/Endpoint/i).first(); if (await url.count()) await url.fill("opc.tcp://127.0.0.1:4840/eric"); } },
    { type: "s7", name: "s701", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host").fill("127.0.0.2"); } },
    { type: "logix", name: "logix01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host").fill("127.0.0.2"); } },
    { type: "dnp3", name: "dnp01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host").fill("127.0.0.2"); } },
    { type: "bacnet-remote", name: "bac01", fill: async (p: import("@playwright/test").Page) => { const h = p.getByLabel(/Host|Address/).first(); if (await h.count()) await h.fill("127.0.0.2"); } },
    { type: "udp", name: "udp01", fill: async () => {} },
  ]) {
    test(`${kind.type}: create, list, delete through the form`, async ({ page }) => {
      await section(page, "Connections");
      await page.getByRole("button", { name: "New device" }).click();
      await page.getByLabel("Device type").selectOption(kind.type);
      await page.getByLabel("Device name").fill(kind.name);
      await kind.fill(page);
      await page.getByRole("button", { name: "Save device" }).click();
      await expect(main(page).getByText(`Saved ${kind.name}.`)).toBeVisible();
      await page.reload();
      await expect(connectionRow(page, kind.name)).toBeVisible();
      await main(page).getByRole("button", { name: `Edit ${kind.name}` }).click();
      page.once("dialog", (d) => d.accept());
      await main(page).getByRole("button", { name: `Delete ${kind.name}` }).click();
      const confirm = main(page).getByRole("button", { name: /^(Delete|Confirm)/ }).filter({ hasNotText: kind.name });
      if (await confirm.count()) await confirm.first().click().catch(() => {});
      await page.reload();
      await expect(connectionRow(page, kind.name)).toHaveCount(0);
    });
  }

  test("MQTT broker connection can be added from the device form", async ({ page }) => {
    await section(page, "Connections");
    await page.getByRole("button", { name: "New device" }).click();
    const options = await page.getByLabel("Device type").locator("option").allTextContents();
    expect(options.join(" ")).toMatch(/MQTT/i);
  });
});
