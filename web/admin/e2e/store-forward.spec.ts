import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";
import { withRole } from "./status";
import { pythonCommand } from "./python";

/**
 * P3-05 (HIS-025, HIS-026): the store-and-forward page shows a provider's pending and quarantined
 * counts and the alarm journal counters, export downloads the listed records, and a read-only
 * operator cannot retry or delete.
 */

async function put(request: APIRequestContext, token: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, {
    headers: { "X-CSRF-Token": token },
    data: { ...resource, metadata: { ...(resource.metadata as object), version } },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("shows provider counts, journal counters, export, and blocks a read-only user", async ({ page, browser }) => {
  test.setTimeout(90_000);
  await login(page);
  const name = stamp("sf");
  const token = await csrf(page);
  const tagPath = `${name}/flow`;
  await put(page.request, token, `/api/v1/config/tags/gateway/Tag/${name}-tag`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: `${name}-tag`, module: "tags" },
    spec: { path: tagPath, type: "memory", dataType: "Double", value: 10 },
  });
  // A provider whose database is missing keeps samples in the buffer, so pending is non-zero.
  await put(page.request, token, `/api/v1/config/historian/gateway/HistoryProvider/${name}`, {
    apiVersion: "oip/v1",
    kind: "HistoryProvider",
    metadata: { name, module: "historian" },
    spec: { type: "sql", connection: "missing-db", timescale: "off" },
  });
  await put(page.request, token, `/api/v1/config/historian/gateway/HistorySettings/${name}-hist`, {
    apiVersion: "oip/v1",
    kind: "HistorySettings",
    metadata: { name: `${name}-hist`, module: "historian" },
    spec: { tagPath, storageProvider: name, sampleMode: "onChange" },
  });
  const written = await page.request.post("/api/v1/tags/write", {
    headers: { "X-CSRF-Token": token },
    data: { writes: [{ path: `[default]${tagPath}`, value: 11 }] },
  });
  expect(written.ok(), await written.text()).toBeTruthy();
  // The read flushes the collector and attempts a forward, which leaves the samples pending.
  await page.request.get(`/api/v1/history/data?tagPath=${encodeURIComponent(tagPath)}&from=0&to=${Date.now() + 60_000}`);

  await expect
    .poll(async () => {
      const res = await page.request.get("/api/v1/diagnostics/history/providers");
      if (!res.ok()) return 0;
      const body = (await res.json()) as { providers: Array<{ name: string; pending: number }> };
      return body.providers.find((row) => row.name === name)?.pending ?? 0;
    }, { timeout: 15_000 })
    .toBeGreaterThan(0);

  await openSection(page, "Store and Forward");
  const row = page.getByTestId(`saf-${name}`);
  await expect
    .poll(async () => {
      const res = await page.request.get("/api/v1/diagnostics/history/providers");
      if (!res.ok()) return false;
      const body = (await res.json()) as { providers: Array<{ name: string; pending: number; quarantined: number }> };
      const provider = body.providers.find((item) => item.name === name);
      if (!provider || provider.pending < 1) return false;
      const pending = await row.getByTestId(`saf-pending-${name}`).innerText({ timeout: 1_000 }).catch(() => "");
      const quarantined = await row.getByTestId(`saf-quarantined-${name}`).innerText({ timeout: 1_000 }).catch(() => "");
      const buffer = await row.getByTestId(`saf-buffer-${name}`).innerText({ timeout: 1_000 }).catch(() => "");
      return pending === String(provider.pending) && quarantined === String(provider.quarantined) && buffer === `${provider.pending} records`;
    }, { timeout: 15_000 })
    .toBe(true);

  const journalRes = await page.request.get("/api/v1/diagnostics/alarms/journal");
  expect(journalRes.ok(), await journalRes.text()).toBeTruthy();
  const journal = (await journalRes.json()) as { queued: number; retrying: boolean; lostRows: number };
  await expect(page.getByTestId("journal-queued")).toHaveText(String(journal.queued));
  await expect(page.getByTestId("journal-retrying")).toHaveText(journal.retrying ? "yes" : "no");
  await expect(page.getByTestId("journal-lost")).toHaveText(String(journal.lostRows));

  const listedRes = await page.request.get("/api/v1/diagnostics/history/quarantine?limit=1000");
  expect(listedRes.ok(), await listedRes.text()).toBeTruthy();
  const listed = (await listedRes.json()) as { records: Array<{ id: number }> };
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: "Export JSON" }).click(),
  ]);
  const file = await download.path();
  if (!file) throw new Error("download was not saved");
  const exported = JSON.parse(readFileSync(file, "utf8")) as { records: Array<{ id: number }> };
  const listedIds = listed.records.map((item) => item.id).sort((a, b) => a - b);
  const exportedIds = exported.records.map((item) => item.id).sort((a, b) => a - b);
  expect(exportedIds).toEqual(listedIds);

  await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete" })).toBeVisible();

  await withRole(browser, page, "operator", async (viewer: Page) => {
    await openSection(viewer, "Store and Forward");
    await expect(viewer.getByTestId(`saf-pending-${name}`)).toBeVisible();
    await expect(viewer.getByTestId("journal-queued")).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Retry" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: "Delete" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: "Import" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: "Archive" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: "Load disk cache" })).toHaveCount(0);
    await expect(viewer.getByRole("link", { name: "Export JSON" })).toHaveCount(0);
    const token = await csrf(viewer);
    const headers = { "Content-Type": "application/json", "X-CSRF-Token": token };
    const retry = await viewer.request.post("/api/v1/diagnostics/history/quarantine/retry", {
      headers,
      data: { provider: name },
    });
    expect(retry.status()).toBe(403);
    expect(await retry.text()).toContain("gateway.write");
    const deleted = await viewer.request.post("/api/v1/diagnostics/history/quarantine/delete", {
      headers,
      data: { provider: name },
    });
    expect(deleted.status()).toBe(403);
    expect(await deleted.text()).toContain("gateway.write");
    const imported = await viewer.request.post("/api/v1/diagnostics/history/quarantine/import", {
      headers,
      data: { records: [] },
    });
    expect(imported.status()).toBe(403);
    expect(await imported.text()).toContain("gateway.write");
    const archived = await viewer.request.post(`/api/v1/diagnostics/history/cache/archive?provider=${name}`, { headers });
    expect(archived.status()).toBe(403);
    expect(await archived.text()).toContain("gateway.write");
    const loaded = await viewer.request.post("/api/v1/diagnostics/history/cache/load", {
      headers,
      data: { kind: "oip.store-forward.disk-cache", version: 1, records: [] },
    });
    expect(loaded.status()).toBe(403);
    expect(await loaded.text()).toContain("gateway.write");
  });
});

/**
 * P3-07 (HIS-025): import retries a quarantine export, archive and load round-trip the disk cache,
 * and the store rate moves when samples enter the cache.
 * (doc source: FEATURES HIS-025)
 */
test("imports a quarantine export, archives and loads the disk cache, and the store rate moves", async ({ page }) => {
  test.setTimeout(120_000);
  await login(page);
  const name = stamp("sfc");
  const token = await csrf(page);
  const tagPath = `${name}/flow`;
  const qualified = `[default]${tagPath}`;
  await put(page.request, token, `/api/v1/config/tags/gateway/Tag/${name}-tag`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: `${name}-tag`, module: "tags" },
    spec: { path: tagPath, type: "memory", dataType: "Double", value: 10 },
  });
  await put(page.request, token, `/api/v1/config/historian/gateway/HistoryProvider/${name}`, {
    apiVersion: "oip/v1",
    kind: "HistoryProvider",
    metadata: { name, module: "historian" },
    spec: { type: "sql", connection: "missing-db", timescale: "off" },
  });
  await put(page.request, token, `/api/v1/config/historian/gateway/HistorySettings/${name}-hist`, {
    apiVersion: "oip/v1",
    kind: "HistorySettings",
    metadata: { name: `${name}-hist`, module: "historian" },
    spec: { tagPath, storageProvider: name, sampleMode: "onChange" },
  });

  await openSection(page, "Store and Forward");
  const rate = page.getByTestId(`saf-store-rate-${name}`);
  await expect(rate).toBeVisible();
  const before = rateValue(await rate.innerText());

  for (let value = 11; value <= 25; value += 1) {
    const written = await page.request.post("/api/v1/tags/write", {
      headers: { "X-CSRF-Token": token },
      data: { writes: [{ path: qualified, value }] },
    });
    expect(written.ok(), await written.text()).toBeTruthy();
  }
  const flushed = await page.request.get(`/api/v1/history/data?tagPath=${encodeURIComponent(tagPath)}&from=0&to=${Date.now() + 60_000}`);
  expect([200, 503]).toContain(flushed.status());
  await expect.poll(async () => rateValue(await rate.innerText()), { timeout: 15_000 }).toBeGreaterThan(before);

  const pendingOf = async () => {
    const res = await page.request.get("/api/v1/diagnostics/history/providers");
    expect(res.ok(), await res.text()).toBeTruthy();
    const body = (await res.json()) as { providers: Array<{ name: string; pending: number }> };
    return body.providers.find((row) => row.name === name)?.pending ?? 0;
  };
  await expect.poll(pendingOf, { timeout: 15_000 }).toBeGreaterThan(0);
  const pendingBeforeArchive = await pendingOf();

  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("locks the cache");
    await dialog.accept();
  });
  await page.getByTestId(`saf-archive-${name}`).click();
  const row = page.locator(`[data-testid="saf-archive-row"][data-provider="${name}"]`);
  await expect(row).toBeVisible();
  await expect(row).toContainText(String(pendingBeforeArchive));
  await expect.poll(pendingOf, { timeout: 15_000 }).toBe(0);
  await expect(page.getByTestId(`saf-pending-${name}`)).toHaveText("0");

  await row.getByRole("button", { name: "Load", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: `Loaded ${pendingBeforeArchive}.` })).toBeVisible();
  await expect.poll(pendingOf, { timeout: 15_000 }).toBe(pendingBeforeArchive);

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    row.getByRole("link", { name: "Download" }).click(),
  ]);
  const archivePath = await download.path();
  if (!archivePath) throw new Error("disk-cache download was not saved");
  const archivedText = readFileSync(archivePath, "utf8");
  expect(archivedText).toContain(qualified);
  expect(archivedText.split("\n").filter((line) => line.length > 0)).toHaveLength(pendingBeforeArchive + 1);

  await row.getByRole("button", { name: "Delete archive" }).click();
  await expect(row).toHaveCount(0);

  const perf = await page.request.get("/api/v1/diagnostics/performance");
  expect(perf.ok(), await perf.text()).toBeTruthy();
  const dataDir = ((await perf.json()) as { disk: { path: string } }).disk.path;
  const held = `${name}/held`;
  execFileSync(
    pythonCommand(),
    [
      "-c",
      [
        "import sqlite3, sys",
        "db, provider, tag = sys.argv[1:]",
        "c = sqlite3.connect(db, timeout=10)",
        "c.execute('PRAGMA busy_timeout=5000')",
        "c.execute(",
        "  'INSERT INTO forward_quarantine(provider, seq, tag_path, ts_millis, value_kind, value_text, quality, error, quarantined_millis) VALUES (?,?,?,?,?,?,?,?,?)',",
        "  (provider, 1, tag, 1781000000000, 'double', '42.5', 'Good', '23514: check constraint', 1781000000000),",
        ")",
        "c.commit()",
      ].join("\n"),
      `${dataDir}/db/history-forward.sqlite`,
      name,
      `[default]${held}`,
    ],
    { encoding: "utf8" },
  );
  await expect
    .poll(async () => {
      const res = await page.request.get(`/api/v1/diagnostics/history/quarantine?provider=${name}&limit=1000`);
      if (!res.ok()) return "";
      const body = (await res.json()) as { records: Array<{ id: number; tagPath: string }> };
      return body.records.find((row) => row.tagPath === `[default]${held}`)?.id ?? "";
    }, { timeout: 15_000 })
    .not.toBe("");
  const listed = await page.request.get(`/api/v1/diagnostics/history/quarantine?provider=${name}&limit=1000`);
  const records = ((await listed.json()) as { records: Array<{ id: number; tagPath: string }> }).records;
  const heldId = records.find((row) => row.tagPath === `[default]${held}`)?.id;
  if (heldId == null) throw new Error("quarantine row was not listed");

  await page.getByLabel("Quarantine provider").selectOption(name);
  await expect(page.getByRole("cell", { name: `[default]${held}` })).toBeVisible();
  const [exported] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: "Export JSON" }).click(),
  ]);
  const exportPath = await exported.path();
  if (!exportPath) throw new Error("quarantine export was not saved");
  const exportFile = JSON.parse(readFileSync(exportPath, "utf8")) as { records: Array<{ tagPath: string; value: number }> };
  expect(exportFile.records.map((row) => row.tagPath)).toContain(`[default]${held}`);

  await page.getByRole("checkbox", { name: `Select ${heldId}` }).check();
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Deleted" })).toBeVisible();
  await expect.poll(async () => {
    const res = await page.request.get(`/api/v1/diagnostics/history/quarantine?provider=${name}&limit=1000`);
    const body = (await res.json()) as { records: Array<{ id: number }> };
    return body.records.some((row) => row.id === heldId);
  }).toBe(false);

  const pendingBeforeImport = await pendingOf();
  await page.getByTestId("quarantine-import-file").setInputFiles(exportPath);
  await page.getByRole("button", { name: "Import", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Retried" })).toBeVisible();
  await expect.poll(pendingOf, { timeout: 15_000 }).toBeGreaterThan(pendingBeforeImport);
});

function rateValue(text: string): number {
  const match = /^(-?\d+(?:\.\d+)?) \/s$/.exec(text.trim());
  if (!match) throw new Error(`store rate was '${text}'`);
  return Number(match[1]);
}
