import { expect, test } from "@playwright/test";
import { csrf, login, openSection, PASSWORD, stamp } from "./gateway";
import { startFreshGateway } from "./freshGateway";

/**
 * 0.1.1: a backup made on one gateway is restored on a brand-new gateway from the Backups page
 * ("Restore from a file"), with no file copied into the data folder by hand.
 */
const port = Number(process.env.OIP_BACKUP_UPLOAD_E2E_PORT ?? 8198);

test("a fresh gateway restores a backup file made on another gateway", async ({ page, browser }) => {
  test.setTimeout(180_000);
  // 1. On the shared gateway: a marker tag, then a backup downloaded through the page.
  const folder = `Upload_${stamp("bk").replace(/-/g, "_")}`;
  await login(page);
  const token = await csrf(page);
  const created = await page.request.post("/api/v1/config/tags/gateway/Tag", {
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
    data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name: folder.toLowerCase().replace(/_/g, "-"), module: "tags", version: 0 }, spec: { path: `${folder}/Marker`, type: "memory", dataType: "Int32", value: 7 } },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  await openSection(page, "Backups");
  const passphrase = `upload passphrase ${Date.now()}`;
  await page.getByLabel("Backup passphrase").fill(passphrase);
  await page.getByLabel("Confirm passphrase").fill(passphrase);
  await page.getByRole("button", { name: "Create backup" }).click();
  const status = page.getByRole("status").filter({ hasText: /^Created / });
  await expect(status).toBeVisible();
  const name = ((await status.innerText()).match(/Created (.+)\./) ?? [])[1]!;
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: `Download ${name}` }).click()]);
  const file = await download.path();
  if (!file) throw new Error("download was not saved");

  // 2. A brand-new gateway: create its administrator, then restore the file from the page.
  const fresh = await startFreshGateway(port);
  const context = await browser.newContext({ baseURL: fresh.base });
  try {
    const other = await context.newPage();
    await other.goto("/admin");
    const first = other.getByRole("form", { name: "Create the administrator" });
    await first.getByLabel("Administrator username").fill("newadmin");
    await first.getByLabel("New password").fill("Fresh-gateway-Pass-9");
    await first.getByLabel("Confirm password").fill("Fresh-gateway-Pass-9");
    await first.getByRole("button", { name: "Create administrator" }).click();
    await expect(other.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
    await openSection(other, "Backups");
    const upload = other.getByRole("form", { name: "Restore from a backup file" });
    await expect(upload.getByRole("button", { name: "Restore from file" })).toBeDisabled();
    await upload.getByLabel("Backup file").setInputFiles(file);

    // A wrong passphrase is refused and changes nothing.
    await upload.getByLabel("Passphrase for the backup file").fill("not the passphrase");
    await upload.getByRole("button", { name: "Restore from file" }).click();
    await other.getByRole("dialog", { name: "Confirm restore from file" }).getByRole("button", { name: "Restore now" }).click();
    await expect(other.getByRole("alert")).toBeVisible();

    await upload.getByLabel("Passphrase for the backup file").fill(passphrase);
    await upload.getByRole("button", { name: "Restore from file" }).click();
    await other.getByRole("dialog", { name: "Confirm restore from file" }).getByRole("button", { name: "Restore now" }).click();
    await expect(other.getByRole("status").filter({ hasText: /^Restored / })).toBeVisible({ timeout: 60_000 });

    // 3. The restored gateway has the other gateway's users and tags.
    const signin = await context.newPage();
    await signin.context().clearCookies();
    await login(signin, "admin", PASSWORD);
    const readToken = await csrf(signin);
    const read = await signin.request.post("/api/v1/tags/read", {
      headers: { "Content-Type": "application/json", "X-CSRF-Token": readToken },
      data: { paths: [`[default]${folder}/Marker`] },
    });
    expect(read.ok()).toBeTruthy();
    expect(((await read.json()) as { values: { value: unknown }[] }).values[0]!.value).toBe(7);
  } finally {
    await context.close();
    fresh.stop();
  }
});
