import { expect, test, type Locator, type Page } from "@playwright/test";
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import net from "node:net";
import path from "node:path";
import { REPO_ROOT, startFreshGateway, type FreshGateway } from "./freshGateway";
import { pythonCommand } from "./python";

/**
 * 0.1.1 online mode on the gateway's own scan engine, all through Control Canvas Logic: the tag map's data types,
 * mapped outputs writing their tags, live values beside each variable in the code, right-click Force / Write once /
 * Release, the forced banner with Release all, the watch table, forces in the audit log, forces released on stop,
 * a force on an output reaching a Modbus field register (the test Modbus server), a viewer and an operator
 * refused, and a running program that comes back after a gateway restart without its forces.
 */
const port = Number(process.env.OIP_LOGIC_ONLINE_E2E_PORT ?? 8198);
const compiler = path.join(REPO_ROOT, "plc/eric-plc/.cache/rusty-1.0.8/plc");
const engine = path.join(REPO_ROOT, "plc/eric-plc/target/release/eric-plc");
const env = { ERIC_PLC_COMPILER: compiler, ERIC_PLC_ENGINE: engine };
const PASSWORD = "Logic-online-Pass-9";
const SOURCE = `PROGRAM OnlineCheck
VAR
    Run AT %IX0.0 : BOOL;
    Level AT %ID1 : REAL;
    Motor AT %QX0.0 : BOOL;
    Speed AT %QD1 : REAL;
    FieldCmd AT %QW8 : INT;
END_VAR
    Motor := Run AND Level > 50.0;
    Speed := Level * 2.0;
    IF Motor THEN
        FieldCmd := 1;
    ELSE
        FieldCmd := 0;
    END_IF;
END_PROGRAM
`;

test("online mode: types, outputs, live values, force and release, restart", async ({ browser }) => {
  test.skip(!existsSync(compiler) || !existsSync(engine), "run plc/eric-plc/scripts/fetch-rusty.sh and cargo build --release first");
  test.setTimeout(300_000);
  const sim = await startModbusServer();
  let fresh: FreshGateway | null = await startFreshGateway(port, env);
  const dataDir = fresh.dataDir;
  let context = await browser.newContext({ baseURL: fresh.base });
  try {
    let page = await context.newPage();
    await page.goto("/admin");
    const first = page.getByRole("form", { name: "Create the administrator" });
    await first.getByLabel("Administrator username").fill("admin");
    await first.getByLabel("New password").fill(PASSWORD);
    await first.getByLabel("Confirm password").fill(PASSWORD);
    await first.getByRole("button", { name: "Create administrator" }).click();
    await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();

    // Setup only: four memory tags and a project (the tag and project forms have their own tests).
    let headers = await csrfHeaders(page);
    for (const [name, dataType, value] of [["Run", "Boolean", false], ["Level", "Double", 10], ["Motor", "Boolean", false], ["Speed", "Double", 0]] as const) {
      const res = await page.request.post("/api/v1/config/tags/gateway/Tag", {
        headers, data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name: `plant-${name.toLowerCase()}`, module: "tags", version: 0 }, spec: { path: `Plant/${name}`, type: "memory", dataType, value } },
      });
      expect(res.ok(), await res.text()).toBeTruthy();
    }
    expect((await page.request.post("/api/v1/projects", { headers, data: { name: "online", title: "Online" } })).ok()).toBeTruthy();
    // The field device (the device form has its own test) and a tag on its holding register HR6 (protocol address 5).
    const device = await page.request.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/field", {
      headers, data: { apiVersion: "oip/v1", kind: "Device", metadata: { name: "field", module: "org.oip.driver-modbus", version: 0 },
        spec: { name: "field", host: "127.0.0.1", port: sim.port, mode: "TCP", unitId: 1, zeroBasedAddressing: false, defaultScanPeriodMs: 200 } },
    });
    expect(device.ok(), await device.text()).toBeTruthy();
    const field = await page.request.post("/api/v1/config/tags/gateway/Tag", {
      headers, data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name: "plant-fieldcmd", module: "tags", version: 0 },
        spec: { path: "Plant/FieldCmd", type: "opc", dataType: "Int32", opcServer: "field", opcItemPath: "HR6" } },
    });
    expect(field.ok(), await field.text()).toBeTruthy();
    for (const [username, role] of [["viewer1", "viewer"], ["operator1", "operator"]]) {
      const made = await page.request.post("/api/v1/security/users", { headers, data: { username, password: PASSWORD, roles: [role] } });
      expect(made.ok(), await made.text()).toBeTruthy();
    }

    await page.goto("/studio?project=online");
    await page.getByRole("button", { name: "Logic", exact: true }).click();
    await page.getByRole("textbox", { name: "Program name" }).fill("OnlineCheck");
    await page.getByRole("button", { name: "New program" }).click();
    const editor = page.getByRole("textbox", { name: "Structured Text" });
    await expect(editor).toContainText("PROGRAM OnlineCheck");
    await replaceEditor(editor, SOURCE);

    // Tag map rows through the form. The type follows the address size and can be changed.
    const rows: Array<[string, string, string, "in" | "out", string]> = [
      ["Run", "%IX0.0", "[default]Plant/Run", "in", "false"],
      ["Level", "%ID1", "[default]Plant/Level", "in", "0"],
      ["Motor", "%QX0.0", "[default]Plant/Motor", "out", "false"],
      ["Speed", "%QD1", "[default]Plant/Speed", "out", "0"],
      ["FieldCmd", "%QW8", "[default]Plant/FieldCmd", "out", "0"],
    ];
    for (let i = 0; i < rows.length; i++) {
      const [name, address, tag, direction, safe] = rows[i]!;
      await page.getByRole("button", { name: "Add mapping" }).click();
      await page.getByLabel(`Variable ${i}`, { exact: true }).fill(name);
      await page.getByLabel(`Address ${i}`, { exact: true }).fill(address);
      await page.getByLabel(`Tag ${i}`, { exact: true }).fill(tag);
      await page.getByLabel(`Direction ${i}`, { exact: true }).selectOption(direction);
      await page.getByLabel(`Safe ${i}`, { exact: true }).fill(safe);
    }
    await expect(page.getByLabel("Type 1", { exact: true })).toHaveValue("REAL");
    await expect(page.getByLabel("Type 2", { exact: true })).toHaveValue("BOOL");

    await page.getByRole("button", { name: "Compile and deploy" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Deployed OnlineCheck." })).toBeVisible({ timeout: 120_000 });
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("running");
    await expect(page.getByText(/exec [1-9]\d* ns/)).toBeVisible({ timeout: 15_000 });

    // A REAL input is read as a REAL and a mapped output writes its tag.
    await writeTag(page, headers, "[default]Plant/Level", 60.5);
    await writeTag(page, headers, "[default]Plant/Run", true);
    await expect.poll(() => readTag(page, headers, "[default]Plant/Speed"), { timeout: 20_000 }).toBe(121);
    await expect.poll(() => readTag(page, headers, "[default]Plant/Motor"), { timeout: 20_000 }).toBe(true);

    // Online: values beside every use of a variable, BOOL as TRUE/FALSE.
    await page.getByRole("button", { name: "Go online" }).click();
    const host = page.locator(".oip-st-editor");
    await expect(host.locator("[data-online='Motor'].oip-st-online-true").first()).toHaveText("TRUE", { timeout: 10_000 });
    await expect(host.locator("[data-online='Level']").first()).toHaveText("60.50");
    expect(await host.locator("[data-online='Level']").count()).toBeGreaterThanOrEqual(3);

    // Right-click Run > Force FALSE: Motor drops, the banner counts it, Run shows an F.
    await host.locator("[data-online='Run']").last().click({ button: "right" });
    const menu = page.getByRole("menu", { name: "Online actions for Run" });
    await menu.getByRole("menuitem", { name: "Force FALSE" }).click();
    const banner = page.getByRole("alert").filter({ hasText: "1 value forced" });
    await expect(banner).toBeVisible({ timeout: 10_000 });
    await expect(host.locator("[data-online='Run'] .oip-st-forced").first()).toHaveText("F");
    await expect.poll(() => readTag(page, headers, "[default]Plant/Motor"), { timeout: 20_000 }).toBe(false);

    // Force a value on Level from the menu: two forced.
    await host.locator("[data-online='Level']").last().click({ button: "right" });
    const levelMenu = page.getByRole("menu", { name: "Online actions for Level" });
    await levelMenu.getByLabel("Value for Level").fill("20");
    await levelMenu.getByRole("menuitem", { name: "Force value" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "2 values forced" })).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => readTag(page, headers, "[default]Plant/Speed"), { timeout: 20_000 }).toBe(40);

    // Release all, and the forces are in the audit log.
    await page.getByRole("alert").getByRole("button", { name: "Release all" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "forced" })).toHaveCount(0, { timeout: 10_000 });
    await expect.poll(() => readTag(page, headers, "[default]Plant/Motor"), { timeout: 20_000 }).toBe(true);
    const audit = await (await page.request.get("/api/v1/audit?limit=200")).text();
    expect(audit).toContain("plc.force");
    expect(audit).toContain("plc.force.release");

    // Watch table: pin Run, write it once (no force).
    const table = page.getByRole("region", { name: "Watch table" });
    await table.getByLabel("Variable to watch").selectOption("Run");
    await table.getByRole("button", { name: "Add to watch table" }).click();
    await table.getByLabel("New value for Run").fill("false");
    await table.getByRole("button", { name: "Write once" }).click();
    await expect(table.getByLabel("Value of Run")).toHaveText("false", { timeout: 10_000 });
    await expect.poll(() => readTag(page, headers, "[default]Plant/Run"), { timeout: 10_000 }).toBe(false);
    await expect(page.getByRole("alert").filter({ hasText: "forced" })).toHaveCount(0);

    // Motor is off, so the program writes 0 to the field register. A force holds 1 there; release returns it.
    await expect.poll(() => sim.read(5), { timeout: 20_000 }).toBe(0);
    await table.getByLabel("Variable to watch").selectOption("FieldCmd");
    await table.getByRole("button", { name: "Add to watch table" }).click();
    await table.getByLabel("New value for FieldCmd").fill("1");
    await table.getByRole("row", { name: /FieldCmd/ }).getByRole("button", { name: "Force" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "1 value forced" })).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => sim.read(5), { timeout: 20_000 }).toBe(1);
    await page.waitForTimeout(1_000);
    expect(await sim.read(5), "held every scan against the program's 0").toBe(1);
    await table.getByRole("row", { name: /FieldCmd/ }).getByRole("button", { name: "Release" }).click();
    await expect.poll(() => sim.read(5), { timeout: 20_000 }).toBe(0);

    // Roles: a viewer and an operator cannot force or write.
    for (const username of ["viewer1", "operator1"]) {
      const other = await browser.newContext({ baseURL: fresh.base });
      const login = await other.request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
      expect(login.ok(), await login.text()).toBeTruthy();
      const token = ((await login.json()) as { csrfToken: string }).csrfToken;
      const refused = await other.request.post("/api/v1/logic/forces", { headers: { "Content-Type": "application/json", "X-CSRF-Token": token }, data: { name: "Run", value: true } });
      expect(refused.status(), `${username} force`).toBe(403);
      const write = await other.request.post("/api/v1/logic/write", { headers: { "Content-Type": "application/json", "X-CSRF-Token": token }, data: { name: "Run", value: true } });
      expect(write.status(), `${username} write`).toBe(403);
      await other.close();
    }

    // A force is released on Stop.
    await host.locator("[data-online='Run']").last().click({ button: "right" });
    await page.getByRole("menu", { name: "Online actions for Run" }).getByRole("menuitem", { name: "Force TRUE" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "1 value forced" })).toBeVisible({ timeout: 10_000 });
    await page.getByRole("button", { name: "Stop", exact: true }).click();
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("stopped", { timeout: 10_000 });
    await page.getByRole("button", { name: "Start", exact: true }).click();
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("running", { timeout: 10_000 });
    await expect(page.getByRole("alert").filter({ hasText: "forced" })).toHaveCount(0, { timeout: 10_000 });

    // Force again, then restart the gateway: the program comes back running without the force.
    await host.locator("[data-online='Run']").last().click({ button: "right" });
    await page.getByRole("menu", { name: "Online actions for Run" }).getByRole("menuitem", { name: "Force TRUE" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "1 value forced" })).toBeVisible({ timeout: 10_000 });
    await context.close();
    await fresh.stop(true);
    fresh = await startFreshGateway(port, env, dataDir);
    context = await browser.newContext({ baseURL: fresh.base });
    page = await context.newPage();
    await page.goto("/admin");
    await page.getByLabel("Username").fill("admin");
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: /sign in/i }).click();
    await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
    headers = await csrfHeaders(page);
    await page.goto("/studio?project=online");
    await page.getByRole("button", { name: "Logic", exact: true }).click();
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("running", { timeout: 30_000 });
    await page.getByRole("button", { name: "Go online" }).click();
    await expect(page.locator(".oip-st-editor [data-online='Run']").first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("alert").filter({ hasText: "forced" })).toHaveCount(0);
    await writeTag(page, headers, "[default]Plant/Level", 30);
    await expect.poll(() => readTag(page, headers, "[default]Plant/Speed"), { timeout: 20_000 }).toBe(60);
    await page.getByRole("button", { name: "Stop", exact: true }).click();
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("stopped", { timeout: 10_000 });
    await page.getByRole("button", { name: "Start", exact: true }).click();
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("running", { timeout: 10_000 });
  } finally {
    await context.close();
    await fresh?.stop();
    sim.stop();
  }
});

/** The test Modbus server, and a holding-register read straight from it (function 03), not through the gateway. */
async function startModbusServer(): Promise<{ port: number; read: (register: number) => Promise<number>; stop: () => void }> {
  const child: ChildProcess = spawn(pythonCommand(), ["-m", "modbus.server", "--bind", "127.0.0.1", "--modbus-port", "0", "--http-port", "0"], {
    cwd: path.join(REPO_ROOT, "test-infra/sim"),
    stdio: ["ignore", "pipe", "inherit"],
  });
  const port = await new Promise<number>((resolve, reject) => {
    let out = "";
    const timer = setTimeout(() => reject(new Error(`Modbus server did not start: ${out}`)), 15_000);
    child.stdout!.on("data", (chunk: Buffer) => {
      out += chunk.toString();
      const match = out.match(/MODBUS_PORT=(\d+)/);
      if (match) {
        clearTimeout(timer);
        resolve(Number(match[1]));
      }
    });
  });
  const read = (register: number) => new Promise<number>((resolve, reject) => {
    const socket = net.connect(port, "127.0.0.1");
    socket.setTimeout(3_000, () => { socket.destroy(); reject(new Error("Modbus read timed out")); });
    socket.on("error", reject);
    socket.on("connect", () => socket.write(Buffer.from([0, 1, 0, 0, 0, 6, 1, 3, register >> 8, register & 0xff, 0, 1])));
    socket.on("data", (data: Buffer) => {
      socket.end();
      if (data.length < 11 || data[7] !== 3) reject(new Error(`Modbus exception ${data.toString("hex")}`));
      else resolve(data.readUInt16BE(9));
    });
  });
  return { port, read, stop: () => child.kill("SIGTERM") };
}

async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const csrf = ((await (await page.request.get("/api/v1/auth/session")).json()) as { csrfToken: string }).csrfToken;
  return { "Content-Type": "application/json", "X-CSRF-Token": csrf };
}

async function writeTag(page: Page, headers: Record<string, string>, tag: string, value: unknown) {
  const res = await page.request.post("/api/v1/tags/write", { headers, data: { writes: [{ path: tag, value }] } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function readTag(page: Page, headers: Record<string, string>, tag: string): Promise<unknown> {
  const res = await page.request.post("/api/v1/tags/read", { headers, data: { paths: [tag] } });
  const text = await res.text();
  const body = (text ? JSON.parse(text) : {}) as { values?: Array<{ value?: unknown }> };
  const value = body.values?.[0]?.value;
  return typeof value === "number" ? Math.round(value * 100) / 100 : value;
}

async function replaceEditor(editor: Locator, text: string) {
  const host = editor.locator("xpath=ancestor::div[contains(@class,'oip-st-editor')]");
  await host.evaluate((node, value) => {
    const view = (node as HTMLElement & { oipEditor?: { dispatch: (change: unknown) => void; state: { doc: { length: number } } } }).oipEditor;
    if (!view) throw new Error("Structured Text editor is not ready");
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
  }, text);
  await expect(editor).toContainText("Speed := Level * 2.0;");
}
