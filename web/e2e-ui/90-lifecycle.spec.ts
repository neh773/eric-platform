import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
// @ts-expect-error plain ESM harness
import { backToPrevious, freshInstall, readState, reinstallKeepingData, restart, start, stop } from "./harness.mjs";
import {
  ALARM_TAG,
  BACKUP_PASSPHRASE,
  createTag,
  DEVICE,
  ensureModbusDevice,
  expectConnected,
  LEVEL_TAG,
  login,
  main,
  openTag,
  PROJECT,
  section,
  tagRow,
  tagTab,
  VIEW,
} from "./ui";

/**
 * Everything configured by the earlier specs (device, tags, scaling, alarm, history, project screen, user) must still be
 * there and still working after each lifecycle event. The checks only read the screen.
 */
async function verifyEverything(page: Page, label: string) {
  await test.step(`${label}: sign in`, () => login(page));
  await test.step(`${label}: Modbus device reconnects`, () => expectConnected(page));
  await test.step(`${label}: device tag value flows with scaling`, async () => {
    await openTag(page, LEVEL_TAG);
    await expect(async () => {
      await page.getByRole("button", { name: "Refresh" }).click();
      const text = await tagRow(page, "WetWellLevel").innerText();
      expect(text).toContain("Good");
    }).toPass({ timeout: 45_000 });
    await tagTab(page, "Scaling");
    await expect(main(page).getByLabel("Raw High")).toHaveValue("10000");
  });
  await test.step(`${label}: tag alarm configuration`, async () => {
    await openTag(page, ALARM_TAG);
    await tagTab(page, "Alarms");
    await expect(main(page).getByRole("group", { name: /Alarm HighLevel/ }).getByLabel("Setpoint")).toHaveValue("80");
  });
  await test.step(`${label}: history setting`, async () => {
    await section(page, "Historian");
    await expect(main(page).getByRole("row").filter({ hasText: "wetwell-level" }).first()).toBeVisible();
  });
  await test.step(`${label}: published screen shows live data`, async () => {
    await page.goto(`/hmi/${PROJECT}/${VIEW}`);
    await expect(page.getByText("Wet well", { exact: true })).toBeVisible();
    await expect(page.getByText(/^\d+(\.\d+)?$/).first()).toBeVisible();
  });
  await test.step(`${label}: operator account still signs in`, async () => {
    const ctx = await page.context().browser()!.newContext();
    const op = await ctx.newPage();
    await login(op, "op1", "Operator-pass-1");
    await ctx.close();
  });
}

test.describe.serial("lifecycle", () => {
  test("baseline: everything configured is working", async ({ page }) => {
    await verifyEverything(page, "baseline");
  });

  test("graceful restart (service stop/start)", async ({ page }) => {
    await restart();
    await verifyEverything(page, "graceful restart");
  });

  test("hard kill (power-loss style) then start", async ({ page }) => {
    await restart({ hard: true });
    await verifyEverything(page, "hard kill");
  });

  test("host reboot equivalent: stop, wait, new process on the same data directory", async ({ page }) => {
    await stop();
    await page.waitForTimeout(3_000);
    await start(readState().dataDir);
    await verifyEverything(page, "reboot");
  });

  test("uninstall and reinstall keeping data", async ({ page }) => {
    await reinstallKeepingData();
    await verifyEverything(page, "reinstall");
  });

  test("backup on the admin page, restore it onto a fresh install from the file", async ({ page }) => {
    await login(page);
    await section(page, "Backups");
    await main(page).getByLabel("Backup passphrase").fill(BACKUP_PASSPHRASE);
    await main(page).getByLabel("Confirm passphrase").fill(BACKUP_PASSPHRASE);
    await main(page).getByRole("button", { name: "Create backup" }).click();
    const created = main(page).getByText(/^Created .*\.oipbk\.$/);
    await expect(created).toBeVisible();
    const name = (await created.innerText()).replace(/^Created /, "").replace(/\.$/, "");
    const download = page.waitForEvent("download");
    await main(page).getByRole("button", { name: `Download ${name}` }).or(main(page).getByRole("link", { name: `Download ${name}` })).click();
    const file = await (await download).path();
    expect(readFileSync(file!).length).toBeGreaterThan(1000);

    await freshInstall();
    await login(page);
    await section(page, "Backups");
    await main(page).getByLabel("Backup file").setInputFiles(file!);
    await main(page).getByLabel("Passphrase for the backup file").fill(BACKUP_PASSPHRASE);
    await main(page).getByRole("button", { name: "Restore from file" }).click();
    const confirm = main(page).getByRole("button", { name: /^Restore( now)?$/ });
    if (await confirm.count()) await confirm.click();
    await expect(main(page).getByText(/Restored/i).first()).toBeVisible({ timeout: 60_000 });
    await verifyEverything(page, "restore onto fresh install");
    // Restart after restore: still all there.
    await restart();
    await verifyEverything(page, "restart after restore");
  });

  test("upgrade: data directory configured on 0.1.0-beta opens on this build", async ({ page }) => {
    const old = process.env.ERIC_UI_UPGRADE_FROM;
    test.skip(!old, "set ERIC_UI_UPGRADE_FROM to a 0.1.0-beta launcher");
    await freshInstall(old);
    await login(page);
    await ensureModbusDevice(page);
    await createTag(page, "Upgrade/Level", "opc", "Double", async (form) => {
      await form.getByLabel("Device or connection").fill(DEVICE.name);
      await form.getByLabel("Item path").fill("HRUS215");
    });
    const dataDir = readState().dataDir;
    await stop();
    await start(dataDir);
    await login(page);
    await expectConnected(page);
    await openTag(page, "Upgrade/Level");
    await expect(async () => {
      await page.getByRole("button", { name: "Refresh" }).click();
      expect(await tagRow(page, "Level").innerText()).toContain("Good");
    }).toPass({ timeout: 45_000 });
    await restart();
    await login(page);
    await expectConnected(page);
    await backToPrevious();
  });
});
