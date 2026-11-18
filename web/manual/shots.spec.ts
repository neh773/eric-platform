import { expect, test, type Page } from "@playwright/test";
import { csrf, login } from "../admin/e2e/gateway";
import { openPanel, selectOnCanvas } from "../designer/e2e/studio";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const out = process.env.OIP_MANUAL_IMG ?? path.resolve(here, "../../docs/manual/img");
const PROJECT = "demo-plant";

async function shot(page: Page, name: string) {
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${out}/${name}.png` });
}

async function put(page: Page, token: string, url: string, resource: Record<string, unknown>) {
  const current = await page.request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const metadata = { ...(resource.metadata as object), version };
  const res = await page.request.put(url, { headers: { "X-CSRF-Token": token }, data: { ...resource, metadata } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

const tags: Array<[string, string, string | number | boolean]> = [
  ["tank-level", "Double", 62.4],
  ["flow", "Double", 118.2],
  ["pump-running", "Boolean", true],
  ["setpoint", "Double", 70],
  ["power-kw", "Double", 41.5],
];

const lbl = (id: string, text: string, extra: Record<string, unknown> = {}) => ({ id, kind: "component", name: id, type: "label", props: { text, align: "start" }, ...extra });
const bindText = (name: string, mode = "number") => ({ text: { kind: "tag", mode: "direct", path: `[default]demo/${name}`, transforms: [{ kind: "format", type: "numeric", mode }] } });
const box = (id: string, type: string, props: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({ id, kind: "component", name: id, type, props, ...extra });

async function buildDemo(page: Page, token: string) {
  for (const [name, dataType, value] of tags) {
    await put(page, token, `/api/v1/config/tags/gateway/Tag/demo-${name}`, {
      apiVersion: "oip/v1", kind: "Tag", metadata: { name: `demo-${name}`, module: "tags" },
      spec: { path: `demo/${name}`, type: "memory", dataType, value },
    });
  }
  await put(page, token, "/api/v1/config/historian/gateway/HistorySettings/demo-tank-level", {
    apiVersion: "oip/v1", kind: "HistorySettings", metadata: { name: "demo-tank-level", module: "historian" },
    spec: { tagPath: "[default]demo/tank-level", sampleMode: "periodic", sampleRateMillis: 5000, deadband: { mode: "absolute", value: 0.5 }, maxTimeBetweenSamplesMillis: 60000 },
  });
  await put(page, token, "/api/v1/config/org.oip.driver-modbus/gateway/Device/boiler-plc", {
    apiVersion: "oip/v1", kind: "Device", metadata: { name: "boiler-plc", module: "org.oip.driver-modbus" },
    spec: { name: "boiler-plc", enabled: false, host: "192.168.10.20", port: 502, mode: "TCP", unitId: 1 },
  });
  const db = await page.request.post("/api/v1/db/connections", { headers: { "X-CSRF-Token": token }, data: { name: "plant-db", driver: "sqlite", url: "jdbc:sqlite:plant-demo.db", description: "Demo database" } });
  expect(db.ok() || db.status() === 409, await db.text()).toBeTruthy();
  const nq = await page.request.post("/api/v1/db/queries", { headers: { "X-CSRF-Token": token }, data: { name: "tank-by-id", connection: "plant-db", queryType: "query", sql: "SELECT :id AS tank, 62.4 AS level", parameters: [{ name: "id", dataType: "integer" }] } });
  expect(nq.ok() || nq.status() === 409, await nq.text()).toBeTruthy();
  const base = `/api/v1/config/designer/project/${PROJECT}`;
  const view = (name: string, title: string, children: unknown[], width = 900, height = 560) => ({
    apiVersion: "oip/v1", kind: "View", metadata: { name, module: "designer" },
    spec: { path: name, title, document: { params: {}, width, height,
      root: { id: `${name}-root`, kind: "container", name: title, layout: "flex", props: { direction: "column", alignItems: "flex-start", gap: 12 }, children } } },
  });
  await put(page, token, `${base}/View/overview`, view("overview", "Overview", [
    lbl("heading", "Tank farm overview"),
    lbl("level-t", "Tank level (%)"), lbl("level", "", { bindings: bindText("tank-level") }),
    lbl("flow-t", "Flow (m3/h)"), lbl("flow", "", { bindings: bindText("flow") }),
    box("gauge", "radial-gauge", { label: "Tank level", min: 0, max: 100, value: 62 }, { bindings: { value: { kind: "tag", mode: "direct", path: "[default]demo/tank-level" } } }),
    box("start", "button", { text: "Start pump", enabled: true }, { events: [{ event: "onClick", actions: [{ kind: "tag-write", path: "[default]demo/pump-running", value: true }] }] }),
    box("power", "power-chart", { title: "Power (kW)", pens: "[default]demo/power-kw,[default]demo/flow", mode: "realtime" }),
  ]));
  await put(page, token, `${base}/View/pumps`, view("pumps", "Pumps", [
    lbl("pumps-title", "Pumps"),
    lbl("sp-t", "Setpoint"), lbl("sp", "", { bindings: bindText("setpoint") }),
    box("alarms", "alarm-status-table", { live: true }),
    box("sp-entry", "numeric-entry", { label: "Setpoint", value: 70, minimum: 0, maximum: 100, limitMin: true, limitMax: true, suffix: " %" }, { bindings: { value: { kind: "tag", mode: "direct", path: "[default]demo/setpoint", bidirectional: true } } }),
  ]));
  await put(page, token, `${base}/View/menu`, view("menu", "Menu", [
    box("go-overview", "button", { text: "Overview", enabled: true }, { events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/" }] }] }),
    box("go-pumps", "button", { text: "Pumps", enabled: true }, { events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }] }),
  ], 160, 560));
  await put(page, token, `${base}/PageConfig/page-config`, {
    apiVersion: "oip/v1", kind: "PageConfig", metadata: { name: "page-config", module: "designer" },
    spec: { pages: [{ url: "/", title: "Overview", primaryView: "overview" }, { url: "/pumps", title: "Pumps", primaryView: "pumps" }],
      sharedDocks: [{ id: "menu", edge: "left", view: "menu", size: 160 }] },
  });
}

test("manual screenshots", async ({ page, browser }) => {
  test.setTimeout(540_000);
  page.setDefaultTimeout(10_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  const skipped: string[] = [];
  const step = async (name: string, run: () => Promise<void>) => {
    try { await run(); } catch (e) { skipped.push(name); console.log("SKIPPED", name, String(e).slice(0, 200)); await page.screenshot({ path: `/tmp/dbg-${name.replace(/ /g, "_")}.png` }); }
  };
  // The Studio remembers a customised dock layout; restore the default so the project tree is showing.
  const resetLayout = async () => {
    await page.getByRole("button", { name: "File", exact: true }).waitFor({ timeout: 20_000 });
    const reset = page.getByRole("button", { name: "Reset layout" });
    if (await reset.isVisible()) await reset.click();
    await expect(page.getByRole("tree").first()).toBeVisible({ timeout: 20_000 });
  };
  const visit = async (url: string, name: string) => {
    await page.goto(url);
    await page.waitForLoadState("networkidle", { timeout: 5000 }).catch(() => {});
    await shot(page, name);
  };

  await page.goto("/admin/login");
  await shot(page, "admin-login");
  await login(page);
  const token = await csrf(page);
  await buildDemo(page, token);

  // Gateway admin.
  await visit("/admin/home", "admin-home");
  await visit("/admin/overview", "admin-overview");
  await visit("/admin/connections", "admin-connections");
  await step("tags", async () => {
    await visit("/admin/tags", "admin-tags");
    await page.getByRole("button", { name: "Expand demo" }).click().catch(async () => {
      await page.getByRole("button", { name: "[default]", exact: true }).click();
      await page.getByRole("button", { name: "Expand demo" }).click();
    });
    await page.getByRole("button", { name: "tank-level", exact: true }).click();
    await shot(page, "admin-tag-editor");
    await page.getByRole("tab", { name: "Alarms", exact: true }).click();
    await page.getByLabel("alarms (JSON)").fill(JSON.stringify([{ name: "High level", mode: "AboveValue", priority: "High", setpointA: 50 }], null, 2));
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await page.getByRole("table", { name: "Alarms" }).waitFor();
    await shot(page, "admin-tag-alarms");
    await page.getByRole("button", { name: "demo", exact: true }).click();
    await page.getByRole("button", { name: "New tag" }).click();
    await page.getByLabel("Name", { exact: true }).fill("pump-speed");
    await shot(page, "admin-tag-new");
  });
  await visit("/admin/historian", "admin-historian");
  await visit("/admin/alarming", "admin-alarming");
  await visit("/admin/databases", "admin-databases");
  await visit("/admin/users", "admin-users");
  await visit("/admin/projects", "admin-projects");
  await visit("/admin/backups", "admin-backups");
  await visit("/admin/diagnostics", "admin-diagnostics");
  await step("diagnostics scripts", async () => {
    await page.getByRole("tab", { name: "Scripts", exact: true }).click().catch(() => page.getByRole("button", { name: "Scripts", exact: true }).click());
    await page.getByRole("heading", { name: "Scripts", exact: true }).waitFor();
    await shot(page, "admin-diagnostics-scripts");
  });
  await step("metrics", async () => {
    await page.goto("/admin/metrics");
    await page.getByLabel("New dashboard name").fill("gateway-health");
    await page.getByRole("button", { name: "Create", exact: true }).click();
    const tileForm = page.getByRole("form", { name: "Add a tile" });
    await tileForm.waitFor();
    await tileForm.locator("select").nth(0).selectOption({ index: 0 });
    await tileForm.locator("select").nth(1).selectOption("gauge");
    await tileForm.getByRole("button", { name: "Add tile" }).click();
    await tileForm.locator("select").nth(0).selectOption({ index: 1 });
    await tileForm.locator("select").nth(1).selectOption("series");
    await tileForm.getByRole("button", { name: "Add tile" }).click();
    await page.getByRole("button", { name: "Save dashboard" }).click();
    await page.waitForTimeout(3500);
    await shot(page, "admin-metrics");
  });
  await visit("/admin/logs", "admin-logs");

  // Studio.
  await step("studio", async () => {
    await page.goto(`/studio/?project=${PROJECT}`);
    await expect(page.getByRole("tree").first()).toBeVisible({ timeout: 20_000 });
    await shot(page, "studio-open");
    await page.getByRole("treeitem", { name: /overview/i }).first().dblclick();
    await page.waitForTimeout(800);
    await shot(page, "studio-workspace");
    await selectOnCanvas(page, "gauge");
    await shot(page, "studio-selected");
    // The right column is shared by three panels; collapse the other two so the tools panel has room.
    await page.getByRole("button", { name: "Collapse Project browser" }).click();
    await page.getByRole("button", { name: "Collapse Outline" }).click();
    await openPanel(page, "Add");
    await shot(page, "studio-palette");
    await openPanel(page, "Tags");
    await shot(page, "studio-tags");
    await page.getByRole("button", { name: "Expand Project browser" }).click();
    await page.getByRole("button", { name: "Expand Outline" }).click();
    await selectOnCanvas(page, "level");
    await step("binding", async () => {
      await page.getByTestId("inspector").locator("[data-prop='text']").getByRole("button", { name: /^Bind / }).click();
      await shot(page, "studio-binding");
    });
    await selectOnCanvas(page, "start");
    await step("events", async () => {
      await page.getByTestId("inspector").getByRole("button", { name: /onClick/ }).click();
      await shot(page, "studio-events");
    });
    await page.getByRole("button", { name: "Collapse Project browser" }).click();
    await page.getByRole("button", { name: "Collapse Outline" }).click();
    await openPanel(page, "Styles");
    await shot(page, "studio-styles");
    await openPanel(page, "Scripts");
    await shot(page, "studio-scripts");
    for (const tab of ["Pages", "Find", "Diagnostics"]) {
      await step(`panel ${tab}`, async () => {
        await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: tab, exact: true }).click();
        if (tab === "Find") await page.getByRole("textbox", { name: "Find", exact: true }).fill("tank-level");
        await shot(page, `studio-${tab.toLowerCase()}`);
      });
    }
    await step("file menu", async () => {
      await page.getByRole("button", { name: "File", exact: true }).click();
      await shot(page, "studio-file-menu");
      await page.getByRole("menuitem", { name: "New project…" }).click();
      const dialog = page.getByRole("dialog", { name: "New project" });
      await dialog.getByLabel("Project name").fill("menu-demo");
      await dialog.getByLabel("Title").fill("Menu demo");
      await dialog.getByLabel("Template").selectOption({ label: "Menu nav" });
      await shot(page, "studio-new-project");
      await dialog.getByRole("button", { name: "Create project" }).click();
      await page.getByRole("alertdialog", { name: "Unsaved changes" }).getByRole("button", { name: "Open anyway" }).click({ timeout: 3000 }).catch(() => {});
      // The File menu opens a project at "/?project=...", which on a gateway is not the Studio path, so go there directly.
      await page.waitForTimeout(1500);
      await page.goto("/studio/?project=menu-demo");
      await resetLayout();
      await shot(page, "studio-menu-nav-project");
    });
    await step("script builder", async () => {
      await page.goto(`/studio/?project=${PROJECT}`);
      await resetLayout();
      await page.getByRole("treeitem", { name: /overview/i }).first().dblclick();
      await selectOnCanvas(page, "start");
      const inspector = page.getByTestId("inspector");
      await inspector.getByRole("button", { name: /onClick/ }).click();
      const actions = inspector.getByRole("region", { name: "Actions for onClick" });
      await actions.getByRole("combobox", { name: "New onClick action type" }).selectOption({ label: "Script" });
      await actions.getByRole("button", { name: "+ Add action" }).click();
      await actions.getByLabel("Builder handler").selectOption({ label: "Write a value to a tag" });
      await actions.getByLabel(/^Builder (tag|target)/i).fill("[default]demo/pump-running");
      await actions.getByLabel("Builder value").fill("true");
      await actions.getByLabel("Builder handler").scrollIntoViewIfNeeded();
      await shot(page, "studio-script-builder");
    });
  });

  // Runtime sessions.
  await step("runtime", async () => {
    await page.goto(`/hmi/${PROJECT}/`);
    await expect(page.getByText("Tank farm overview")).toBeVisible();
    await page.waitForTimeout(2500);
    await shot(page, "runtime-overview");
    await page.getByRole("button", { name: "Pumps" }).click();
    await expect(page.getByText("Setpoint").first()).toBeVisible();
    const entry = page.locator("[data-node-id='sp-entry'] input").first();
    await entry.click();
    await entry.press("Control+A");
    await entry.pressSequentially("75");
    await entry.press("Enter");
    await page.waitForTimeout(1500);
    await shot(page, "runtime-pumps");
  });
  await step("touchscreen", async () => {
    await page.goto(`/hmi/${PROJECT}/pumps?touchscreen=on`);
    const entry = page.locator("[data-node-id='sp-entry'] input").first();
    await entry.click();
    await expect(page.getByRole("group", { name: "On-screen keyboard" })).toBeVisible();
    await shot(page, "runtime-keyboard");
  });
  await step("runtime sign-in", async () => {
    const guest = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const gp = await guest.newPage();
    await gp.goto(`/hmi/${PROJECT}/`);
    await gp.waitForTimeout(1500);
    await gp.screenshot({ path: `${out}/runtime-signin.png` });
    await guest.close();
  });
  console.log("SKIPPED STEPS:", skipped.join(", ") || "none");
});
