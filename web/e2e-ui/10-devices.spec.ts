import { expect, test } from "@playwright/test";
// @ts-expect-error plain ESM helper shared with global setup
import { ADMIN_PASSWORD } from "./harness.mjs";
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
    await page.getByLabel("Device name", { exact: true }).fill("Bad Name!");
    await page.getByLabel("Host", { exact: true }).fill("10.0.0.1");
    await page.getByRole("button", { name: "Save device" }).click();
    await expect(main(page).getByText(/lowercase/i).first()).toBeVisible();
  });

  for (const kind of [
    { type: "modbus", name: "rtu01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host", { exact: true }).fill(DEVICE.host); await p.getByLabel("Mode").selectOption("RTU_OVER_TCP"); } },
    { type: "simulator", name: "sim01", fill: async () => {} },
    { type: "opcua", name: "opc01", fill: async (p: import("@playwright/test").Page) => { const url = p.getByLabel(/Endpoint/i).first(); if (await url.count()) await url.fill("opc.tcp://127.0.0.1:4840/eric"); } },
    { type: "s7", name: "s701", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host", { exact: true }).fill("127.0.0.2"); } },
    { type: "logix", name: "logix01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host", { exact: true }).fill("127.0.0.2"); } },
    { type: "dnp3", name: "dnp01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host", { exact: true }).fill("127.0.0.2"); } },
    { type: "bacnet-local", name: "bacl01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("BACnet device name").fill("Audit gateway"); await p.getByLabel("Port").fill("47911"); } },
    { type: "mqtt", name: "mqtt01", fill: async (p: import("@playwright/test").Page) => { await p.getByLabel("Host", { exact: true }).fill("127.0.0.1"); await p.getByLabel("Port").fill("18830"); await p.getByLabel("Username").fill("broker-user"); await p.getByLabel("Password").fill("broker-pass-1"); } },
    { type: "udp", name: "udp01", fill: async () => {} },
  ]) {
    test(`${kind.type}: create, list, delete through the form`, async ({ page }) => {
      await section(page, "Connections");
      await page.getByRole("button", { name: "New device" }).click();
      await page.getByLabel("Device type").selectOption(kind.type);
      await page.getByLabel("Device name", { exact: true }).fill(kind.name);
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

  test("bacnet-remote: needs a local device; create both, list, delete through the form", async ({ page }) => {
    await section(page, "Connections");
    await page.getByRole("button", { name: "New device" }).click();
    await page.getByLabel("Device type").selectOption("bacnet-remote");
    await page.getByLabel("Device name", { exact: true }).fill("bac01");
    await page.getByLabel("Host", { exact: true }).fill("127.0.0.2");
    await page.getByRole("button", { name: "Save device" }).click();
    await expect(main(page).getByText("Name the local BACnet device this remote device uses.")).toBeVisible();
    for (const [type, name] of [["bacnet-local", "bacl02"], ["bacnet-remote", "bac01"]]) {
      await section(page, "Connections");
      await page.getByRole("button", { name: "New device" }).click();
      await page.getByLabel("Device type").selectOption(type);
      await page.getByLabel("Device name", { exact: true }).fill(name);
      if (type === "bacnet-local") {
        await page.getByLabel("BACnet device name").fill("Audit gateway 2");
        await page.getByLabel("Port").fill("47912");
      } else {
        await page.getByLabel("Local device").fill("bacl02");
        await page.getByLabel("Host", { exact: true }).fill("127.0.0.2");
      }
      await page.getByRole("button", { name: "Save device" }).click();
      await expect(main(page).getByText(`Saved ${name}.`)).toBeVisible();
    }
    for (const name of ["bac01", "bacl02"]) {
      await page.reload();
      await expect(connectionRow(page, name)).toBeVisible();
      await main(page).getByRole("button", { name: `Edit ${name}` }).click();
      page.once("dialog", (d) => d.accept());
      await main(page).getByRole("button", { name: `Delete ${name}` }).click();
      const confirm = main(page).getByRole("button", { name: /^(Delete|Confirm)/ }).filter({ hasNotText: name });
      if (await confirm.count()) await confirm.first().click().catch(() => {});
      await page.reload();
      await expect(connectionRow(page, name)).toHaveCount(0);
    }
  });

  test("OPC UA client: connect to an OPC UA server (this gateway's own) with a username and it shows Connected", async ({ page }) => {
    await section(page, "Connections");
    if (!(await connectionRow(page, "selfua").count())) {
      await page.getByRole("button", { name: "New device" }).click();
      await page.getByLabel("Device type").selectOption("opcua");
      await page.getByLabel("Device name", { exact: true }).fill("selfua");
      await page.getByLabel("Endpoint URL").fill(`opc.tcp://127.0.0.1:${process.env.ERIC_UI_OPCUA_PORT ?? "48411"}/oip`);
      // This gateway's server turns anonymous sessions away, so sign in as a gateway user.
      await page.getByLabel("Username").fill("admin");
      await page.getByLabel("Password").fill(ADMIN_PASSWORD);
      await page.getByRole("button", { name: "Save device" }).click();
      await expect(main(page).getByText("Saved selfua.")).toBeVisible();
    }
    await expectConnected(page, "selfua");
    // The password went to the secret store; the device keeps only a reference.
    await main(page).getByRole("button", { name: "Edit selfua" }).click();
    await expect(page.getByLabel("Password")).toHaveValue("");
    await expect(page.getByLabel("Password")).toHaveAttribute("placeholder", /Saved/);
  });

  test("MQTT broker: the device form offers MQTT with sign-in fields", async ({ page }) => {
    await section(page, "Connections");
    await page.getByRole("button", { name: "New device" }).click();
    const options = await page.getByLabel("Device type").locator("option").allTextContents();
    expect(options.join(" ")).toMatch(/MQTT/i);
    await page.getByLabel("Device type").selectOption("mqtt");
    for (const label of ["Host", "Port", "MQTT version", "Client ID", "Use TLS", "Sparkplug host ID", "Username", "Password"]) {
      await expect(page.getByLabel(label, { exact: true })).toBeVisible();
    }
  });
});
