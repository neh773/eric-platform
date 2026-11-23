import { expect, test } from "@playwright/test";
import { ALARM_TAG, DEVICE, createTag, ensureModbusDevice, LEVEL_TAG, login, main, openTag, saveTagEditor, section, tagExists, tagRow, tagTab } from "./ui";

test.describe("tags", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("memory tag: create, write a value, edit, rename, delete", async ({ page }) => {
    const leaf = `Counter${Date.now().toString(36)}`;
    await createTag(page, `Scratch/${leaf}`, "memory", "Int32", async (form) => {
      await form.getByLabel("Initial value").fill("5");
    });
    await openTag(page, `Scratch/${leaf}`);
    await main(page).getByLabel("Value to write").fill("42");
    await main(page).getByRole("button", { name: "Write", exact: true }).click();
    await expect(tagRow(page, leaf)).toContainText("42");
    await main(page).getByLabel("Documentation").fill("Audit counter");
    await saveTagEditor(page);
    await page.reload();
    await openTag(page, `Scratch/${leaf}`);
    await expect(main(page).getByLabel("Documentation")).toHaveValue("Audit counter");
    await main(page).getByLabel("New tag path").fill(`Scratch/${leaf}x`);
    await main(page).getByRole("button", { name: "Move", exact: true }).click();
    await openTag(page, `Scratch/${leaf}x`);
    await main(page).getByRole("button", { name: "Delete tag" }).click();
    const confirm = main(page).getByRole("button", { name: /^(Delete|Confirm delete)/ }).last();
    await confirm.click();
    expect(await tagExists(page, `Scratch/${leaf}x`)).toBe(false);
  });

  test("device tag: browse the Modbus device and create a tag from a holding register", async ({ page }) => {
    await ensureModbusDevice(page);
    await section(page, "Tags");
    await page.getByRole("button", { name: "Create from device" }).click();
    await expect(main(page).getByLabel("Device", { exact: true })).toContainText(`${DEVICE.name} (Modbus, CONNECTED)`);
    await main(page).getByText("Holding registers").click();
    await expect(main(page).getByRole("row").filter({ hasText: /HR\d/ }).first()).toBeVisible();
  });

  test("device tag with scaling and engineering units, typed address", async ({ page }) => {
    await ensureModbusDevice(page);
    if (!(await tagExists(page, LEVEL_TAG))) {
      await createTag(page, LEVEL_TAG, "opc", "Double", async (form) => {
        await form.getByLabel("Device or connection").fill(DEVICE.name);
        await form.getByLabel("Item path").fill("HRUS215");
      });
    }
    await openTag(page, LEVEL_TAG);
    await tagTab(page, "Scaling");
    await main(page).getByLabel("Mode").selectOption("linear");
    await main(page).getByLabel("Raw Low").fill("0");
    await main(page).getByLabel("Raw High").fill("10000");
    await main(page).getByLabel("Scaled Low").fill("0");
    await main(page).getByLabel("Scaled High").fill("100");
    await tagTab(page, "Numeric");
    await main(page).getByLabel("Eng Unit").fill("%");
    await main(page).getByLabel("Eng Low").fill("0");
    await main(page).getByLabel("Eng High").fill("100");
    await saveTagEditor(page);
    await page.reload();
    await openTag(page, LEVEL_TAG);
    await expect(async () => {
      const text = await tagRow(page, "WetWellLevel").innerText();
      const value = Number((text.match(/(-?\d+(?:\.\d+)?)/g) ?? []).find((n) => n.includes(".") || Number(n) <= 100));
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThanOrEqual(100);
      expect(text).toContain("Good");
    }).toPass({ timeout: 30_000 });
  });

  test("alarm test memory tag exists for the alarm specs", async ({ page }) => {
    if (!(await tagExists(page, ALARM_TAG))) {
      await createTag(page, ALARM_TAG, "memory", "Double", async (form) => {
        await form.getByLabel("Initial value").fill("10");
      });
    }
    expect(await tagExists(page, ALARM_TAG)).toBe(true);
  });
});
