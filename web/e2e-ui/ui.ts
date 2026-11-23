import { expect, type Locator, type Page } from "@playwright/test";
// @ts-expect-error plain ESM helper shared with global setup
import { ADMIN_PASSWORD } from "./harness.mjs";

/**
 * UI-only helpers for the new-user audit. Everything here clicks and types like a person; nothing calls the REST API
 * to create or change configuration. Reads through the page (text on screen) only.
 */

export const DEVICE = { name: "ls01", host: process.env.ERIC_UI_MODBUS_HOST ?? "10.20.40.15", port: process.env.ERIC_UI_MODBUS_PORT ?? "502" };
export const LEVEL_TAG = "LiftStation/WetWellLevel";
export const ALARM_TAG = "LiftStation/HighLevelTest";
export const PROJECT = "liftstation";
export const VIEW = "station";
export const BACKUP_PASSPHRASE = "audit-backup-phrase-1";

export async function login(page: Page, username = "admin", password: string = ADMIN_PASSWORD) {
  await page.goto("/admin/login");
  // Fresh install: the first visit asks for the administrator account instead of a sign-in.
  const firstRun = page.getByRole("form", { name: "Create the administrator" });
  await expect(firstRun.or(page.getByRole("button", { name: "Sign in", exact: true }))).toBeVisible();
  if (await firstRun.count()) {
    await firstRun.getByLabel("Administrator username").fill(username);
    await firstRun.getByLabel("New password").fill(password);
    await firstRun.getByLabel("Confirm password").fill(password);
    await firstRun.getByRole("button", { name: "Create administrator" }).click();
    await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
    return;
  }
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
}

export async function section(page: Page, label: string) {
  await page.getByRole("navigation", { name: "Gateway sections" }).getByRole("link", { name: label, exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: label, exact: true })).toBeVisible();
}

export function main(page: Page): Locator {
  return page.locator("main");
}

/** The Connections page row for [name], whatever the kind. */
export function connectionRow(page: Page, name: string): Locator {
  return main(page).getByRole("row").filter({ has: page.getByRole("cell", { name, exact: true }) }).first();
}

/** Adds the Modbus TCP field device through the New device form unless it is already listed. */
export async function ensureModbusDevice(page: Page) {
  await section(page, "Connections");
  // The device list loads after the page; count rows only once it (or its empty state) is on screen.
  await expect(main(page).getByRole("status").filter({ hasText: "Loading devices." })).toHaveCount(0);
  if (await connectionRow(page, DEVICE.name).count()) return;
  await page.getByRole("button", { name: "New device" }).click();
  await page.getByLabel("Device name", { exact: true }).fill(DEVICE.name);
  await page.getByLabel("Device type").selectOption("modbus");
  await page.getByLabel("Host", { exact: true }).fill(DEVICE.host);
  await page.getByLabel("Port").fill(DEVICE.port);
  await page.getByLabel("Unit ID").fill("1");
  await page.getByRole("button", { name: "Save device" }).click();
  await expect(main(page).getByText(`Saved ${DEVICE.name}.`)).toBeVisible();
}

export async function expectConnected(page: Page, name = DEVICE.name) {
  await section(page, "Connections");
  await expect(async () => {
    await page.reload();
    await expect(connectionRow(page, name)).toContainText("Connected", { timeout: 2_000 });
  }).toPass({ timeout: 45_000 });
}

/** The tag browser row button for a tag leaf (expands folders on the way). */
export async function openTag(page: Page, fullPath: string) {
  await section(page, "Tags");
  await expect(main(page).getByRole("button", { name: "[default]", exact: true })).toBeVisible();
  const parts = fullPath.split("/");
  for (const folder of parts.slice(0, -1)) {
    const expand = main(page).getByRole("button", { name: `Expand ${folder}`, exact: true });
    await expect(expand.or(main(page).getByRole("button", { name: `Collapse ${folder}`, exact: true }))).toBeVisible();
    if (await expand.count()) await expand.click();
  }
  await main(page).getByRole("button", { name: parts[parts.length - 1], exact: true }).first().click();
  await expect(main(page).getByRole("heading", { name: `[default]${fullPath}` })).toBeVisible();
}

export function tagRow(page: Page, leaf: string): Locator {
  return main(page).getByRole("row").filter({ has: page.getByRole("button", { name: leaf, exact: true }) }).first();
}

export async function tagExists(page: Page, fullPath: string): Promise<boolean> {
  await section(page, "Tags");
  await expect(main(page).getByRole("button", { name: "[default]", exact: true })).toBeVisible();
  const parts = fullPath.split("/");
  for (const folder of parts.slice(0, -1)) {
    const expand = main(page).getByRole("button", { name: `Expand ${folder}`, exact: true });
    const collapse = main(page).getByRole("button", { name: `Collapse ${folder}`, exact: true });
    // The tree fills in after the provider row shows; give it a moment before deciding the folder is missing.
    await expand.or(collapse).first().waitFor({ timeout: 5_000 }).catch(() => {});
    if (await expand.count()) await expand.click();
    else if (!(await collapse.count())) return false;
  }
  const leaf = main(page).getByRole("button", { name: parts[parts.length - 1], exact: true });
  await leaf.first().waitFor({ timeout: 5_000 }).catch(() => {});
  return (await leaf.count()) > 0;
}

/** New tag form. [extra] fills type-specific fields after the type is chosen. */
export async function createTag(
  page: Page,
  fullPath: string,
  type: string,
  dataType: string,
  extra?: (form: Locator) => Promise<void>,
) {
  await section(page, "Tags");
  await page.getByRole("button", { name: "New tag" }).click();
  const form = main(page).locator("form").filter({ has: page.getByRole("button", { name: "Create tag" }) });
  await form.getByLabel("Name", { exact: true }).fill(fullPath);
  await form.getByLabel("Tag type").selectOption(type);
  await form.getByLabel("Data type").selectOption(dataType);
  if (extra) await extra(form);
  await form.getByRole("button", { name: "Create tag" }).click();
  await expect(main(page).getByText(`Created [default]${fullPath}.`)).toBeVisible();
}

/** Opens an editor category tab in the tag editor. */
export async function tagTab(page: Page, label: string) {
  await main(page).getByRole("tablist", { name: "Property categories" }).getByRole("tab", { name: label, exact: true }).click();
}

export async function saveTagEditor(page: Page) {
  const save = main(page).getByRole("button", { name: "Save", exact: true });
  // Nothing changed (a re-run on the same gateway): Save and Discard are both off.
  if ((await save.isDisabled()) && (await main(page).getByRole("button", { name: "Discard changes" }).isDisabled())) return;
  await save.click();
  await expect(main(page).getByText(/Saved|version \d+/).first()).toBeVisible();
}

/** Studio for a project, signed in through the admin login cookie already on the context. */
export async function openStudio(page: Page, project = PROJECT) {
  await page.goto(`/studio/?project=${project}`);
  // A project with views shows the project tree; a new, empty one shows only the New view form.
  await expect(page.getByRole("tree").first().or(page.getByRole("form", { name: "New view" })).first()).toBeVisible();
}

export async function studioSave(page: Page) {
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /unsaved|saved|Saving|conflict/i }).first()).toHaveText("All changes saved");
}
