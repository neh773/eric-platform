import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { csrf, login, openSection, stamp } from "./gateway";
import { expectNoSecrets, withRole } from "./status";

/**
 * PRO-003: list projects, export one, and import it back with each conflict policy.
 *
 * The project holds one AuditProfile resource. Any registered kind can live in a project scope.
 */
const MODULE = "org.oip.security";
const KIND = "AuditProfile";

function resourcePath(project: string): string {
  return `/api/v1/config/${MODULE}/project/${project}/${KIND}/main`;
}

async function current(page: Page, project: string): Promise<{ version: number; description: string } | null> {
  const res = await page.request.get(resourcePath(project));
  if (res.status() === 404) return null;
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = (await res.json()) as { metadata: { version: number }; spec: { description: string } };
  return { version: body.metadata.version, description: body.spec.description };
}

async function putDescription(page: Page, project: string, description: string) {
  const existing = await current(page, project);
  const version = existing?.version ?? 0;
  const token = await csrf(page);
  const res = await page.request.put(resourcePath(project), {
    data: {
      apiVersion: "oip/v1",
      kind: KIND,
      metadata: { name: "main", module: MODULE, version },
      spec: { description, retentionDays: 30, enabled: true },
    },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token, ...(existing ? { "If-Match": String(version) } : {}) },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function importArchive(page: Page, archive: string, target: string, policy: "skip" | "overwrite") {
  await page.getByLabel("Project archive").setInputFiles(archive);
  await page.getByLabel("Import into project").fill(target);
  await page.getByRole("radio", { name: new RegExp(`\\(${policy}\\)`) }).check();
  await page.getByRole("button", { name: "Import", exact: true }).click();
}

test("exports a project and round-trips it with skip and overwrite", async ({ page }) => {
  const project = stamp("plant");
  const copy = `${project}-copy`;
  await login(page);
  await putDescription(page, project, "exported copy");

  await openSection(page, "Projects");
  await expect(page.getByRole("row", { name: new RegExp(project) }).first()).toBeVisible();
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: `Export ${project}`, exact: true }).click(),
  ]);
  expect(download.suggestedFilename()).toBe(`project-${project}.zip`);
  const archive = await download.path();
  if (!archive) throw new Error("download was not saved");
  expect(readFileSync(archive).subarray(0, 2).toString()).toBe("PK");

  await putDescription(page, project, "edited after export");

  // Skip keeps the gateway's copy.
  await importArchive(page, archive, project, "skip");
  await expect(page.getByTestId("import-result")).toHaveText(`Imported into ${project} (skip): 0 created, 0 overwritten, 1 skipped.`);
  expect((await current(page, project))?.description).toBe("edited after export");

  // Overwrite puts the archive copy back.
  await importArchive(page, archive, project, "overwrite");
  await expect(page.getByTestId("import-result")).toHaveText(`Imported into ${project} (overwrite): 0 created, 1 overwritten, 0 skipped.`);
  expect((await current(page, project))?.description).toBe("exported copy");

  // Either policy creates what is missing; here into a new project name.
  await importArchive(page, archive, copy, "skip");
  await expect(page.getByTestId("import-result")).toHaveText(`Imported into ${copy} (skip): 1 created, 0 overwritten, 0 skipped.`);
  expect((await current(page, copy))?.description).toBe("exported copy");
  await expect(page.getByRole("link", { name: `Export ${copy}`, exact: true })).toBeVisible();
  await expectNoSecrets(page);
});

test("read-only roles get no import control and the API refuses their import", async ({ page, browser }) => {
  const project = stamp("ro");
  await login(page);
  await putDescription(page, project, "read only");
  const archive = await (await page.request.get(`/api/v1/projects/${project}/export`)).body();

  await withRole(browser, page, "operator", async (reader) => {
    await openSection(reader, "Projects");
    await expect(reader.getByRole("link", { name: `Export ${project}`, exact: true })).toBeVisible();
    await expect(reader.getByRole("button", { name: "Import", exact: true })).toHaveCount(0);
    await expect(reader.getByLabel("Project archive")).toHaveCount(0);
    const token = await csrf(reader);
    const res = await reader.request.post(`/api/v1/projects/${project}/import?policy=overwrite`, {
      data: archive,
      headers: { "Content-Type": "application/zip", "X-CSRF-Token": token },
    });
    expect(res.status()).toBe(403);
  });
  await withRole(browser, page, "viewer", async (viewer) => {
    await openSection(viewer, "Projects");
    await expect(viewer.getByRole("alert").first()).toContainText("denied");
    await expect(viewer.getByRole("button", { name: "Import", exact: true })).toHaveCount(0);
    const token = await csrf(viewer);
    const res = await viewer.request.post(`/api/v1/projects/${project}/import?policy=skip`, {
      data: archive,
      headers: { "Content-Type": "application/zip", "X-CSRF-Token": token },
    });
    expect(res.status()).toBe(403);
  });
});
