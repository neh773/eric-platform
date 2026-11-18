import { spawn, type ChildProcess } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, PASSWORD, stamp } from "./gateway";
import { pythonCommand } from "./python";

/**
 * C-16: the admin tag browser and editor (TAG-004, TAG-012, TAG-020, TAG-022), with per-tag permissions from C-1.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const simDir = path.resolve(here, "../../../test-infra/sim");

async function api(page: Page, method: "GET" | "POST" | "PUT" | "DELETE", url: string, data?: unknown) {
  const token = await csrf(page);
  return page.request.fetch(url, { method, data, headers: { "Content-Type": "application/json", "X-CSRF-Token": token } });
}

async function putTag(page: Page, name: string, spec: Record<string, unknown>) {
  const res = await api(page, "POST", "/api/v1/config/tags/gateway/Tag", {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name, module: "tags", version: 0 },
    spec,
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function readValue(page: Page, tagPath: string): Promise<{ value: unknown; quality: string }> {
  const res = await api(page, "POST", "/api/v1/tags/read", { paths: [tagPath] });
  expect(res.ok()).toBeTruthy();
  return ((await res.json()) as { values: { value: unknown; quality: string }[] }).values[0]!;
}

function row(page: Page, tagPath: string) {
  return page.locator(`tr[data-testid="tag-row"][data-path="${tagPath}"]`);
}

test("creates a memory tag, shows its live value, edits the deadband, handles a stale version, moves and deletes it", async ({ page }) => {
  const folder = `E2E_${stamp("tb").replace(/-/g, "_")}`;
  const tagPath = `[default]${folder}/Level`;
  await login(page);
  await openSection(page, "Tags");

  await page.getByRole("button", { name: "New tag" }).click();
  const form = page.getByTestId("create-tag");
  await form.getByLabel("Name").fill(`${folder}/Level`);
  await form.getByLabel("Tag type").selectOption("memory");
  await form.getByLabel("Data type", { exact: true }).selectOption("Double");
  await form.getByLabel("Initial value").fill("1");
  await form.getByRole("button", { name: "Create tag" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Created ${tagPath}.` })).toBeVisible();

  // Live value: a write elsewhere shows up in the tree without a reload.
  await expect(row(page, tagPath).getByTestId("tag-value")).toHaveText("1");
  const write = await api(page, "POST", "/api/v1/tags/write", { writes: [{ path: tagPath, value: 42 }] });
  expect(write.ok()).toBeTruthy();
  await expect(row(page, tagPath).getByTestId("tag-value")).toHaveText("42");
  await expect(row(page, tagPath).getByTestId("tag-quality")).toHaveText("Good");

  // Edit the deadband: persisted, and the resource version goes up by one.
  const editor = page.getByTestId("tag-editor");
  await expect(editor.getByTestId("tag-version")).toContainText(/version \d+/);
  const versionOf = async () => Number(((await editor.getByTestId("tag-version").textContent()) ?? "").match(/version (\d+)/)![1]);
  const created = await versionOf();
  await expect(editor.getByTestId("tag-live-value")).toHaveText("42");
  await editor.getByRole("tab", { name: "Numeric" }).click();
  await editor.getByLabel("Mode").selectOption("absolute");
  await editor.getByLabel("Value", { exact: true }).fill("0.5");
  await editor.getByRole("button", { name: "Save" }).click();
  await expect(editor.getByTestId("tag-version")).toContainText(`version ${created + 1}`);
  const resource = ((await editor.getByTestId("tag-version").textContent()) ?? "").match(/Resource (\S+)/)![1]!;
  const stored = await api(page, "GET", `/api/v1/config/tags/gateway/Tag/${resource}`);
  const doc = (await stored.json()) as { metadata: { version: number }; spec: Record<string, unknown> };
  expect(doc.metadata.version).toBe(created + 1);
  expect(doc.spec.deadband).toEqual({ mode: "absolute", value: 0.5 });

  // Someone else saves the next version; saving the stale form offers a reload.
  const other = await api(page, "PUT", `/api/v1/config/tags/gateway/Tag/${resource}`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: resource, module: "tags", version: created + 1 },
    spec: { ...doc.spec, documentation: "changed elsewhere" },
  });
  expect(other.ok(), await other.text()).toBeTruthy();
  await editor.getByRole("tab", { name: "Basic" }).click();
  await editor.getByLabel("Documentation").fill("my edit");
  await editor.getByRole("button", { name: "Save" }).click();
  const conflict = editor.getByTestId("version-conflict");
  await expect(conflict).toBeVisible();
  await conflict.getByRole("button", { name: "Reload" }).click();
  await expect(editor.getByTestId("tag-version")).toContainText(`version ${created + 2}`);
  await expect(editor.getByLabel("Documentation")).toHaveValue("changed elsewhere");

  // Move: the tag leaves the old path and keeps its value at the new one.
  const moved = `[default]${folder}/Moved/Level2`;
  await editor.getByLabel("New tag path").fill(`${folder}/Moved/Level2`);
  await editor.getByRole("button", { name: "Move" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Moved to ${moved}.` })).toBeVisible();
  await expect(row(page, moved)).toBeVisible();
  await expect(row(page, tagPath)).toHaveCount(0);
  await expect.poll(async () => (await readValue(page, moved)).value).toBe(42);

  // Delete.
  await editor.getByRole("button", { name: "Delete tag" }).click();
  await editor.getByRole("button", { name: "Confirm delete" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Deleted ${moved}.` })).toBeVisible();
  await expect(row(page, moved)).toHaveCount(0);
  expect((await api(page, "GET", `/api/v1/config/tags/gateway/Tag/${resource}`)).status()).toBe(404);
});

test.describe("from a simulator", () => {
  let sim: ChildProcess | null = null;
  let modbusPort = 0;

  test.beforeAll(async () => {
    sim = spawn(pythonCommand(), ["-m", "modbus.server", "--bind", "127.0.0.1", "--modbus-port", "0", "--http-port", "0"], {
      cwd: simDir,
      stdio: ["ignore", "pipe", "inherit"],
    });
    modbusPort = await new Promise<number>((resolve, reject) => {
      let out = "";
      const timer = setTimeout(() => reject(new Error(`simulator did not start: ${out}`)), 15_000);
      sim!.stdout!.on("data", (chunk: Buffer) => {
        out += chunk.toString();
        const match = out.match(/MODBUS_PORT=(\d+)/);
        if (match) {
          clearTimeout(timer);
          resolve(Number(match[1]));
        }
      });
      sim!.on("exit", (code) => reject(new Error(`simulator exited ${code}`)));
    });
  });

  let device = "";
  let folder = "";

  // Leave the gateway as it was: the tags made in the device folder, then the device.
  test.afterAll(async ({ browser }) => {
    try {
      if (!device) return;
      const context = await browser.newContext();
      const page = await context.newPage();
      try {
        await login(page);
        const listed = await api(page, "POST", "/api/v1/tags/browse", { path: `[default]${folder}`, limit: 1000 });
        const rows = listed.ok() ? ((await listed.json()) as { nodes: { resource?: string }[] }).nodes : [];
        for (const name of rows.map((r) => r.resource).filter((r): r is string => !!r)) {
          const gone = await api(page, "DELETE", `/api/v1/config/tags/gateway/Tag/${name}`);
          expect(gone.ok(), await gone.text()).toBeTruthy();
        }
        const removed = await api(page, "DELETE", `/api/v1/config/org.oip.driver-modbus/gateway/Device/${device}`);
        expect(removed.ok(), await removed.text()).toBeTruthy();
      } finally {
        await context.close();
      }
    } finally {
      sim?.kill("SIGTERM");
    }
  });

  test("creates tags from a Modbus device browse", async ({ page }) => {
    device = stamp("sim");
    folder = `Dev_${device.replace(/-/g, "_")}`;
    await login(page);
    const res = await api(page, "PUT", `/api/v1/config/org.oip.driver-modbus/gateway/Device/${device}`, {
      apiVersion: "oip/v1",
      kind: "Device",
      metadata: { name: device, module: "org.oip.driver-modbus", version: 0 },
      spec: { name: device, host: "127.0.0.1", port: modbusPort, mode: "TCP", unitId: 1, zeroBasedAddressing: false, defaultScanPeriodMs: 200 },
    });
    expect(res.ok(), await res.text()).toBeTruthy();
    await putTag(page, `${folder.toLowerCase().replace(/_/g, "-")}-anchor`, { path: `${folder}/Anchor`, type: "memory", dataType: "Int32", value: 0 });

    await openSection(page, "Tags");
    await row(page, `[default]${folder}`).getByRole("button", { name: folder, exact: true }).click();
    await page.getByRole("button", { name: "Create from device" }).click();
    const browser = page.getByTestId("device-browse");
    await browser.getByLabel("Device", { exact: true }).selectOption(device);
    await browser.getByRole("button", { name: "Holding registers ▸" }).click();
    const nodes = browser.getByRole("table", { name: "Device nodes" });
    await expect(nodes.getByRole("row", { name: /HR1\b/ }).first()).toContainText("Good", { timeout: 15_000 });
    await browser.getByLabel("Pick HR1", { exact: true }).check();
    await browser.getByLabel("Pick HR2", { exact: true }).check();
    await browser.getByLabel("Data type for new tags").selectOption("Int32");
    await browser.getByRole("button", { name: "Create 2 tags" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Created 2 tags from the device." })).toBeVisible();

    await expect(row(page, `[default]${folder}/HR1`)).toBeVisible();
    await expect.poll(async () => (await readValue(page, `[default]${folder}/HR1`)).quality, { timeout: 15_000 }).toBe("Good");
    await expect(row(page, `[default]${folder}/HR2`).getByTestId("tag-quality")).toHaveText("Good", { timeout: 15_000 });
  });
});

test("hides a protected value and refuses a write to a read-only tag for an operator", async ({ page, browser }) => {
  const folder = `Sec_${stamp("tb").replace(/-/g, "_")}`;
  const slug = folder.toLowerCase().replace(/_/g, "-");
  await login(page);
  await putTag(page, `${slug}-secret`, {
    path: `${folder}/Secret`,
    type: "memory",
    dataType: "Double",
    value: 3.25,
    readPermissions: { roles: ["administrator"], zones: [] },
  });
  await putTag(page, `${slug}-locked`, { path: `${folder}/Locked`, type: "memory", dataType: "Double", value: 5, readOnly: true });
  const username = stamp("op");
  const created = await api(page, "POST", "/api/v1/security/users", { username, password: PASSWORD, roles: ["operator"] });
  expect(created.ok(), await created.text()).toBeTruthy();

  // The administrator sees the protected value.
  await openSection(page, "Tags");
  await page.getByRole("button", { name: `Expand ${folder}` }).click();
  await expect(row(page, `[default]${folder}/Secret`).getByTestId("tag-value")).toHaveText("3.25");

  const context = await browser.newContext();
  const op = await context.newPage();
  try {
    await login(op, username, PASSWORD);
    await openSection(op, "Tags");
    await expect(op.getByTestId("tag-browser-readonly")).toContainText("gateway.write");
    await op.getByRole("button", { name: `Expand ${folder}` }).click();
    const secret = row(op, `[default]${folder}/Secret`);
    await expect(secret.getByTestId("tag-value")).toHaveText("Access denied");
    await expect(secret).not.toContainText("3.25");
    await secret.getByRole("button", { name: "Secret", exact: true }).click();
    // No editor for a tag the operator may not read: the row does not name its resource.
    const hidden = op.getByRole("region", { name: "Tag value" });
    await expect(hidden.getByTestId("tag-live-value")).toContainText("Access denied");
    await expect(op.getByTestId("tag-editor")).toHaveCount(0);

    await row(op, `[default]${folder}/Locked`).getByRole("button", { name: "Locked", exact: true }).click();
    const editor = op.getByTestId("tag-editor");
    await expect(editor.getByTestId("tag-editor-readonly")).toBeVisible();
    await expect(editor.getByRole("button", { name: "Save" })).toHaveCount(0);
    await expect(editor.getByTestId("tag-live-value")).toHaveText("5");
    await editor.getByLabel("Value to write").fill("9");
    await editor.getByRole("button", { name: "Write" }).click();
    await expect(editor.getByTestId("tag-write-result")).toContainText("Write refused: Bad_NotWritable");
    expect((await readValue(page, `[default]${folder}/Locked`)).value).toBe(5);
  } finally {
    await context.close();
  }
});
