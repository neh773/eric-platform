import { expect, test } from "@playwright/test";
import { sectionsByGroup } from "../src/sections";
import { login, openSection, stamp, withViewer } from "./gateway";

/**
 * R1-F13 against a running gateway.
 * GAT-034, GAT-036: the admin nav is grouped into Platform, Security, Services and Connections.
 * GAT-034: security zones and security settings pages.
 * GAT-035: gateway settings page.
 * Doc source: FEATURES GAT-034.
 * Doc source: FEATURES GAT-036.
 * Doc source: FEATURES GAT-035.
 */

// The expected nav comes from the same list the shell renders, so adding a section needs no edit here.
const GROUPS = sectionsByGroup().map(({ group, sections }) => ({ group: group.label, labels: sections.map((section) => section.label) }));

test("the nav is grouped and every grouped link opens its page", async ({ page }) => {
  test.setTimeout(60_000);
  await login(page);
  const nav = page.getByRole("navigation", { name: "Gateway sections" });
  await expect(nav.getByRole("group")).toHaveCount(GROUPS.length);
  for (const { group, labels } of GROUPS) {
    const box = nav.getByRole("group", { name: group, exact: true });
    await expect(box.getByRole("link")).toHaveText(labels);
    for (const label of labels) {
      await box.getByRole("link", { name: label, exact: true }).click();
      await expect(page.getByRole("heading", { level: 1, name: label, exact: true })).toBeVisible();
      await expect(page.getByTestId("section-placeholder")).toHaveCount(0);
      await expect(box.getByRole("link", { name: label, exact: true })).toHaveAttribute("aria-current", "page");
    }
  }
});

test("creates, edits and deletes a security zone; a viewer cannot read zones", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const name = stamp("zone");
  await login(page);
  await openSection(page, "Security Zones");
  await expect(page.getByTestId("default-zone")).toContainText("Default");

  const create = page.getByRole("form", { name: "Create security zone" });
  await create.getByLabel("Name", { exact: true }).fill(name);
  await create.getByRole("button", { name: "Add Ip Addresses" }).click();
  // TEST-NET address: matches no client of the shared test gateway.
  await create.getByLabel("Ip Addresses 1").fill("192.0.2.10");
  await create.getByRole("button", { name: "Create zone" }).click();

  const row = page.getByRole("row", { name: new RegExp(name) });
  await expect(row).toContainText("192.0.2.10");

  await page.getByRole("button", { name: `Edit ${name}` }).click();
  const edit = page.getByRole("region", { name: `Edit zone ${name}` });
  await edit.getByLabel("Priority").fill("7");
  await edit.getByRole("button", { name: "Save" }).click();
  await expect(edit.getByRole("status")).toHaveText("Saved.");
  await expect(row).toContainText("7");

  await page.reload();
  await expect(page.getByRole("row", { name: new RegExp(name) })).toContainText("7");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Security Zones");
    await expect(viewer.getByRole("alert")).toContainText("gateway.read denied");
    await expect(viewer.getByRole("form", { name: "Create security zone" })).toHaveCount(0);
    await expect(viewer.locator("body")).not.toContainText(name);
  });

  await page.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(page.getByRole("row", { name: new RegExp(name) })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("table", { name: "Security zones" })).not.toContainText(name);
});

test("edits the audit profile retention and shows gateway security and the password policy", async ({ page, browser }) => {
  test.setTimeout(60_000);
  await login(page);
  await openSection(page, "Security Settings");
  await expect(page.getByRole("region", { name: "Gateway security" }).getByTestId("schema-form")).toBeVisible();
  await expect(page.getByRole("region", { name: "Password policy" }).getByLabel("Min Length")).toBeVisible();
  await expect(page.locator("body")).not.toContainText("passwordHash");

  const audit = page.getByRole("region", { name: "Audit profile" });
  const retention = audit.getByLabel("Retention Days");
  const original = await retention.inputValue();
  await retention.fill("45");
  await audit.getByRole("button", { name: "Save" }).click();
  await expect(audit.getByRole("status")).toHaveText("Saved.");
  await page.reload();
  await expect(page.getByRole("region", { name: "Audit profile" }).getByLabel("Retention Days")).toHaveValue("45");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Security Settings");
    await expect(viewer.getByRole("alert").first()).toContainText("gateway.read denied");
    await expect(viewer.getByRole("button", { name: "Save" })).toHaveCount(0);
  });

  // Put the shared gateway back the way the other specs expect it.
  const again = page.getByRole("region", { name: "Audit profile" });
  await again.getByLabel("Retention Days").fill(original);
  await again.getByRole("button", { name: "Save" }).click();
  await expect(again.getByRole("status")).toHaveText("Saved.");
});

test("saves the system name and the default HMI project", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const systemName = stamp("Plant");
  await login(page);
  await openSection(page, "Gateway Settings");
  const form = page.getByRole("form", { name: "Gateway settings" });
  await expect(form.getByLabel("Serialization format")).toHaveValue("json");

  await form.getByLabel("System name").fill("bad|name");
  await expect(form.getByRole("alert")).toContainText("cannot contain '|'");
  await expect(form.getByRole("button", { name: "Save settings" })).toBeDisabled();

  await form.getByLabel("System name").fill(systemName);
  await form.getByLabel("Default HMI project").fill("line-1");
  await form.getByRole("button", { name: "Save settings" }).click();
  await expect(form.getByRole("status")).toHaveText("Saved.");
  await expect(page.getByTestId("effective-system-name")).toContainText(systemName);

  await page.reload();
  const reloaded = page.getByRole("form", { name: "Gateway settings" });
  await expect(reloaded.getByLabel("System name")).toHaveValue(systemName);
  await expect(reloaded.getByLabel("Default HMI project")).toHaveValue("line-1");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Gateway Settings");
    await expect(viewer.getByRole("alert")).toContainText("gateway.read denied");
    await expect(viewer.getByRole("button", { name: "Save settings" })).toHaveCount(0);
  });

  // Restore the shared gateway's defaults.
  await reloaded.getByLabel("System name").fill("");
  await reloaded.getByLabel("Default HMI project").fill("");
  await reloaded.getByRole("button", { name: "Save settings" }).click();
  await expect(reloaded.getByRole("status")).toHaveText("Saved.");
  await expect(page.getByTestId("effective-system-name")).not.toContainText(systemName);
});
