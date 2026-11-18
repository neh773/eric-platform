import { expect, test, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { csrf, login, openSection, withViewer } from "./gateway";
import { pythonCommand } from "./python";

/**
 * GAT-001: install, upgrade, and remove a module package from the admin UI.
 */
const MODULE = "org.oip.hello-world";
const zipPath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../modules/hello-world/build/module-package/hello-world.oipmodule.zip",
);

function packageWithVersion(version: string): string {
  const dir = mkdtempSync(path.join(tmpdir(), "oip-mod-"));
  const out = path.join(dir, "upgrade.zip");
  const script = `
import sys, zipfile, re
src, dest, version = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dest, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.filename.replace("\\\\", "/").rsplit("/", 1)[-1] == "module.yaml":
            text = re.sub(r"(?m)^version:.*$", "version: " + version, data.decode("utf-8"))
            data = text.encode("utf-8")
        zout.writestr(info.filename, data)
`;
  execFileSync(pythonCommand(), ["-c", script, zipPath, out, version], { stdio: "inherit" });
  return out;
}

async function removeHello(page: Page) {
  const token = await csrf(page);
  const res = await page.request.delete(`/api/v1/modules/${MODULE}`, {
    headers: { "X-CSRF-Token": token },
  });
  expect([204, 404]).toContain(res.status());
}

test("installs, upgrades, and uninstalls a module, and shows a failed install", async ({ page, browser }) => {
  test.setTimeout(60_000);
  await login(page);
  await removeHello(page);
  await openSection(page, "Modules");
  await expect(page.getByRole("row", { name: /Modbus Driver/ })).toContainText("0.1.0");
  await expect(page.getByRole("row", { name: /Hello World/ })).toHaveCount(0);

  const bad = path.join(tmpdir(), `not-a-module-${Date.now()}.zip`);
  writeFileSync(bad, "this is not a module package");
  await page.getByLabel("Module package").setInputFiles(bad);
  await page.getByRole("button", { name: "Install", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByRole("alert")).not.toHaveText("");
  await expect(page.getByRole("row", { name: /Hello World/ })).toHaveCount(0);

  await page.getByLabel("Module package").setInputFiles(zipPath);
  await page.getByRole("button", { name: "Install", exact: true }).click();
  const installed = page.getByRole("row", { name: /Hello World/ });
  await expect(installed).toContainText("0.1.0");
  await expect(installed).toContainText("RUNNING");
  await expect(page.getByRole("alert")).toHaveCount(0);

  const upgrade = packageWithVersion("0.2.0");
  try {
    await page.getByLabel("Upgrade package for Hello World").setInputFiles(upgrade);
    await page.getByRole("button", { name: "Upgrade Hello World" }).click();
    await expect(installed).toContainText("0.2.0");
    await expect(installed).toContainText("RUNNING");
  } finally {
    rmSync(path.dirname(upgrade), { recursive: true, force: true });
  }

  await page.getByRole("button", { name: "Uninstall Hello World" }).click();
  await expect(page.getByRole("row", { name: /Hello World/ })).toHaveCount(0);

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Modules");
    await expect(viewer.getByRole("alert")).toContainText("denied");
    await expect(viewer.getByRole("button", { name: "Install", exact: true })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: /Uninstall/ })).toHaveCount(0);
  });
});

/** Review-1 F5: a removed built-in driver is listed as available and can be installed again on purpose. */
test("a removed built-in driver can be installed again from the Modules page", async ({ page }) => {
  const MQTT = "org.oip.driver-mqtt";
  await login(page);
  try {
    await openSection(page, "Modules");
    const installed = page.getByRole("table", { name: "Installed modules" });
    const available = page.getByRole("table", { name: "Available built-in modules" });
    await expect(installed.getByRole("row", { name: /MQTT Driver/ })).toContainText("RUNNING");
    await page.getByRole("button", { name: "Uninstall MQTT Driver" }).click();
    await expect(installed.getByRole("row", { name: /MQTT Driver/ })).toHaveCount(0);
    await expect(available.getByRole("row", { name: /MQTT Driver/ })).toContainText(MQTT);

    await page.getByRole("button", { name: "Install MQTT Driver" }).click();
    await expect(installed.getByRole("row", { name: /MQTT Driver/ })).toContainText("RUNNING");
    await expect(available.getByRole("row", { name: /MQTT Driver/ })).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
  } finally {
    // Leave the shared gateway with the driver installed for later specs.
    const token = await csrf(page);
    const res = await page.request.post(`/api/v1/modules/${MQTT}/install`, { headers: { "X-CSRF-Token": token } });
    expect([201, 409]).toContain(res.status());
  }
});
