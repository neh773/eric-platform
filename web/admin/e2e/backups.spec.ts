import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { login, openSection, withViewer } from "./gateway";
import { withRole } from "./status";

/**
 * GAT-014: list, create, download, and restore a gateway backup.
 * Restore asks for confirmation. The page repeats the secrets.key warning from docs/ops.md.
 * Review-1 F3: every backup is encrypted under a passphrase entered on the page. The download is the sealed
 * file (no zip header, no secrets.key entry name); a wrong passphrase is refused and changes nothing.
 * Create, download, and restore need backups.admin (administrator). An operator sees only the list.
 */
const RESOURCE = "/api/v1/config/org.oip.security/gateway/AuditProfile/internal";

async function setDescription(page: import("@playwright/test").Page, description: string) {
  await page.goto(`/admin/config?module=org.oip.security&scope=gateway&kind=AuditProfile&name=internal`);
  const box = page.getByRole("textbox", { name: "Description" });
  await expect(box).toBeVisible();
  await box.fill(description);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
}

async function descriptionOf(page: import("@playwright/test").Page): Promise<string> {
  const res = await page.request.get(RESOURCE);
  expect(res.ok()).toBeTruthy();
  return ((await res.json()) as { spec: { description: string } }).spec.description;
}

test("creates, downloads, and restores a backup after confirmation", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const marker = `Backup marker ${Date.now()}`;
  const changed = `After backup ${Date.now()}`;
  await login(page);
  await openSection(page, "Backups");
  await expect(page.getByTestId("backup-warning")).toContainText("backup contains secrets.key");

  await setDescription(page, marker);
  await openSection(page, "Backups");
  const passphrase = `e2e passphrase ${Date.now()}`;
  await expect(page.getByRole("button", { name: "Create backup" })).toBeDisabled();
  await page.getByLabel("Backup passphrase").fill("short");
  await page.getByLabel("Confirm passphrase").fill("short");
  await expect(page.getByRole("button", { name: "Create backup" })).toBeDisabled();
  await page.getByLabel("Backup passphrase").fill(passphrase);
  await page.getByLabel("Confirm passphrase").fill(passphrase);
  await page.getByRole("button", { name: "Create backup" }).click();
  const created = page.getByRole("status").filter({ hasText: /^Created / });
  await expect(created).toBeVisible();
  const name = ((await created.innerText()).match(/Created (.+)\./) ?? [])[1];
  if (!name) throw new Error("backup name was not shown");
  expect(name.endsWith(".oipbk")).toBeTruthy();
  await expect(page.getByRole("row", { name: new RegExp(name.replace(".", "\\.")) })).toContainText("Encrypted");
  await expect(page.getByLabel("Backup passphrase")).toHaveValue("");

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: `Download ${name}` }).click(),
  ]);
  const file = await download.path();
  if (!file) throw new Error("download was not saved");
  const sealed = readFileSync(file);
  expect(sealed.subarray(0, 7).toString("latin1")).toBe("OIPENC1");
  expect(sealed.includes(Buffer.from("PK\u0003\u0004", "latin1"))).toBeFalsy();
  expect(sealed.includes(Buffer.from("secrets.key", "latin1"))).toBeFalsy();

  await setDescription(page, changed);
  expect(await descriptionOf(page)).toBe(changed);

  await openSection(page, "Backups");
  await page.getByRole("button", { name: `Restore ${name}` }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(name);
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toHaveCount(0);
  expect(await descriptionOf(page)).toBe(changed);

  await page.getByRole("button", { name: `Restore ${name}` }).click();
  await expect(page.getByRole("button", { name: "Confirm restore" })).toBeDisabled();
  await page.getByLabel("Restore passphrase").fill("not the passphrase");
  await page.getByRole("button", { name: "Confirm restore" }).click();
  await expect(page.getByRole("alert")).toContainText("wrong passphrase");
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(await descriptionOf(page)).toBe(changed);

  await page.getByLabel("Restore passphrase").fill(passphrase);
  await page.getByRole("button", { name: "Confirm restore" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Restored ${name}` })).toBeVisible();
  expect(await descriptionOf(page)).toBe(marker);

  await withRole(browser, page, "operator", async (operator) => {
    await openSection(operator, "Backups");
    await expect(operator.getByRole("row", { name: new RegExp(name.replace(".", "\\.")) })).toBeVisible();
    await expect(operator.getByRole("link", { name: /Download / })).toHaveCount(0);
    await expect(operator.getByRole("button", { name: "Create backup" })).toHaveCount(0);
    await expect(operator.getByLabel("Backup passphrase")).toHaveCount(0);
    await expect(operator.getByRole("button", { name: /Restore / })).toHaveCount(0);
    const direct = await operator.request.get(`/api/v1/backups/${encodeURIComponent(name)}`);
    expect(direct.status()).toBe(403);
    expect(await direct.text()).toContain("backups.admin");
  });

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Backups");
    await expect(viewer.getByTestId("backup-warning")).toContainText("secrets.key");
    await expect(viewer.getByRole("alert")).toContainText("denied");
    await expect(viewer.getByRole("button", { name: "Create backup" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: /Restore / })).toHaveCount(0);
  });
});
