import { connect } from "node:net";
import { expect, test } from "@playwright/test";
import { createTag, DEVICE, ensureModbusDevice, login, openStudio, openTag, tagExists, tagRow } from "./ui";

/**
 * Online mode end to end on the Modbus device: a program output mapped to a device tag, forced from the watch table,
 * reaches the device's holding register and is held there every scan; Release gives it back to the program.
 * Everything is done through the admin page and the Logic workspace forms. The test reads the register straight from
 * the device (read only) to prove the value left the gateway.
 */
const CMD_TAG = "LiftStation/PlcPumpCmd";
const REGISTER = 420;

/** Reads one holding register (function 3) at a protocol address. */
function readRegister(address: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const socket = connect({ host: DEVICE.host, port: Number(DEVICE.port) });
    socket.setTimeout(3_000, () => { socket.destroy(); reject(new Error("Modbus read timed out")); });
    socket.on("error", reject);
    socket.on("connect", () => {
      const frame = Buffer.alloc(12);
      frame.writeUInt16BE(7, 0); frame.writeUInt16BE(0, 2); frame.writeUInt16BE(6, 4);
      frame.writeUInt8(1, 6); frame.writeUInt8(3, 7); frame.writeUInt16BE(address, 8); frame.writeUInt16BE(1, 10);
      socket.write(frame);
    });
    socket.on("data", (data) => {
      socket.end();
      if (data.length < 11 || data.readUInt8(7) !== 3) reject(new Error(`Modbus exception ${data.toString("hex")}`));
      else resolve(data.readUInt16BE(9));
    });
  });
}

/** The register the HR address names, whichever base the device uses. */
async function fieldValue(): Promise<number[]> {
  return [await readRegister(REGISTER - 1), await readRegister(REGISTER)];
}

test("PLC online mode: force an output to the Modbus device and release it", async ({ page }) => {
  test.setTimeout(300_000);
  await login(page);
  await ensureModbusDevice(page);
  if (!(await tagExists(page, CMD_TAG))) {
    await createTag(page, CMD_TAG, "opc", "Int32", async (form) => {
      await form.getByLabel("Device or connection").fill(DEVICE.name);
      await form.getByLabel("Item path").fill(`HRUS${REGISTER}`);
    });
  }

  await openStudio(page);
  await page.getByRole("button", { name: "Logic", exact: true }).click();
  await page.getByLabel("Program name").fill("force_check");
  await page.getByRole("button", { name: "New program" }).click();
  const editor = page.locator(".cm-content, textarea").first();
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("PROGRAM force_check\nVAR\n  cmd AT %QW0 : INT;\nEND_VAR\ncmd := 0;\nEND_PROGRAM\n");
  // The tag map belongs to the deployed program: clear rows left from another program, then map this one.
  await expect(page.getByRole("region", { name: "Tag mapping and runtime" })).toHaveAttribute("data-mapping-loaded", "yes");
  while (await page.getByRole("button", { name: "Remove mapping 0", exact: true }).count()) {
    await page.getByRole("button", { name: "Remove mapping 0", exact: true }).click();
  }
  await page.getByRole("button", { name: "Add mapping" }).click();
  await page.getByLabel("Variable 0", { exact: true }).fill("cmd");
  await page.getByLabel("Address 0", { exact: true }).fill("%QW0");
  await page.getByLabel("Tag 0", { exact: true }).fill(`[default]${CMD_TAG}`);
  await page.getByLabel("Type 0", { exact: true }).selectOption("INT");
  await page.getByLabel("Direction 0", { exact: true }).selectOption("out");
  await page.getByLabel("Safe 0", { exact: true }).fill("0");
  await page.getByRole("button", { name: "Compile and deploy" }).click();
  await expect(page.getByRole("status").filter({ hasText: /Deployed force_check/ })).toBeVisible({ timeout: 120_000 });
  await expect(page.locator("[aria-label='Logic status']")).toHaveText("running", { timeout: 30_000 });
  await expect.poll(fieldValue, { timeout: 20_000 }).toEqual([0, 0]);

  await page.getByRole("button", { name: "Go online" }).click();
  const table = page.getByRole("region", { name: "Watch table" });
  await table.getByLabel("Variable to watch").selectOption("cmd");
  await table.getByRole("button", { name: "Add to watch table" }).click();
  await table.getByLabel("New value for cmd").fill("1234");
  await table.getByRole("row", { name: /cmd/ }).getByRole("button", { name: "Force" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "1 value forced" })).toBeVisible({ timeout: 10_000 });
  await expect.poll(async () => (await fieldValue()).includes(1234), { timeout: 20_000 }).toBe(true);
  await page.waitForTimeout(2_000);
  expect((await fieldValue()).includes(1234), "held every scan against the program's 0").toBe(true);

  // The device tag on the admin page shows the forced value read back from the device.
  const studio = page.url();
  await page.goto("/admin/home");
  await openTag(page, CMD_TAG);
  await expect(async () => expect(await tagRow(page, "PlcPumpCmd").innerText()).toContain("1234")).toPass({ timeout: 30_000 });

  // Release: the program's 0 goes back to the device.
  await page.goto(studio);
  await page.getByRole("button", { name: "Logic", exact: true }).click();
  await page.getByRole("button", { name: "force_check", exact: true }).click();
  const banner = page.getByRole("alert").filter({ hasText: "1 value forced" });
  await expect(banner).toBeVisible({ timeout: 15_000 });
  await banner.getByRole("button", { name: "Release all" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "forced" })).toHaveCount(0, { timeout: 10_000 });
  await expect.poll(fieldValue, { timeout: 20_000 }).toEqual([0, 0]);
});
