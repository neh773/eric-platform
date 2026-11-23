import { expect, test, type Locator, type Page } from "@playwright/test";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { REPO_ROOT, startFreshGateway } from "./freshGateway";

/**
 * 0.1.1: a FUNCTION_BLOCK with VAR_IN_OUT structure parameters, called twice for eight stations. In Control Canvas
 * Logic each station variable is bound to a gateway UDT instance from a list, the program is deployed to the
 * gateway's own scan engine, and the online values show the members. Needs the pinned compiler and eric-plc built.
 */
const port = Number(process.env.OIP_LOGIC_UDT_E2E_PORT ?? 8199);
const compiler = path.join(REPO_ROOT, "plc/eric-plc/.cache/rusty-1.0.8/plc");
const engine = path.join(REPO_ROOT, "plc/eric-plc/target/release/eric-plc");
const stations = ["CLS-1", "OS-1A", "OS-1B", "OS-1C", "CLS-2", "OS-2A", "OS-2B", "OS-2C"];
const variables = ["Cls1", "Os1A", "Os1B", "Os1C", "Cls2", "Os2A", "Os2B", "Os2C"];
const bools = ["CommOk", "PumpFault", "InhibitCmd", "OverrideActive", "AlmHH", "AlmCommLoss", "AlmPumpFault", "AlmCannotRelieve", "AlmCannotEvacuate", "AlmPumpIntoOverflow"];
const PASSWORD = "Logic-udt-Pass-9";

test("binds eight UDT instances to two function block calls and shows them online", async ({ browser }) => {
  test.skip(!existsSync(compiler) || !existsSync(engine), "run plc/eric-plc/scripts/fetch-rusty.sh and cargo build --release first");
  test.setTimeout(240_000);
  const fresh = await startFreshGateway(port, { ERIC_PLC_COMPILER: compiler, ERIC_PLC_ENGINE: engine });
  const context = await browser.newContext({ baseURL: fresh.base });
  try {
    const page = await context.newPage();
    await page.goto("/admin");
    const first = page.getByRole("form", { name: "Create the administrator" });
    await first.getByLabel("Administrator username").fill("admin");
    await first.getByLabel("New password").fill(PASSWORD);
    await first.getByLabel("Confirm password").fill(PASSWORD);
    await first.getByRole("button", { name: "Create administrator" }).click();
    await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();

    // Setup only (the UDT Definitions page has its own test): one station UDT and eight instances.
    const csrf = ((await (await page.request.get("/api/v1/auth/session")).json()) as { csrfToken: string }).csrfToken;
    const headers = { "Content-Type": "application/json", "X-CSRF-Token": csrf };
    const members = [{ name: "Level", type: "memory", dataType: "Double", value: 10 }, ...bools.map((name) => ({ name, type: "memory", dataType: "Boolean", value: name === "CommOk" }))];
    const udt = await page.request.post("/api/v1/config/tags/gateway/Udt", {
      headers, data: { apiVersion: "oip/v1", kind: "Udt", metadata: { name: "lift-station", module: "tags", version: 0 }, spec: { path: "Types/LiftStation", members } },
    });
    expect(udt.ok(), await udt.text()).toBeTruthy();
    for (const s of stations) {
      const res = await page.request.post("/api/v1/config/tags/gateway/Tag", {
        headers, data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name: `st-${s.toLowerCase()}`, module: "tags", version: 0 }, spec: { path: `MiamiDade/${s}`, type: "udt", typeId: "Types/LiftStation" } },
      });
      expect(res.ok(), await res.text()).toBeTruthy();
    }
    const project = await page.request.post("/api/v1/projects", { headers, data: { name: "lift-logic", title: "Lift logic" } });
    expect(project.ok(), await project.text()).toBeTruthy();

    // Control Canvas > Logic, all through the page.
    await page.goto("/studio?project=lift-logic");
    await page.getByRole("button", { name: "Logic", exact: true }).click();
    await page.getByRole("textbox", { name: "Program name" }).fill("CollectionInterlock");
    await page.getByRole("button", { name: "New program" }).click();
    const editor = page.getByRole("textbox", { name: "Structured Text" });
    await expect(editor).toContainText("PROGRAM CollectionInterlock");
    const source = readFileSync(path.join(REPO_ROOT, "gateway/server/src/test/resources/plc/FB_CentralInhibit.st"), "utf8");
    await replaceEditor(editor, source);
    await page.getByRole("button", { name: "Check program" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Check passed" })).toBeVisible({ timeout: 60_000 });

    const udts = page.getByRole("region", { name: "UDT instances" });
    for (let i = 0; i < variables.length; i++) {
      const group = udts.getByRole("group", { name: `UDT variable ${variables[i]}` });
      await group.getByLabel(`UDT instance for ${variables[i]}`).selectOption(`[default]MiamiDade/${stations[i]}`);
      await expect(group.getByRole("table", { name: `Members of ${variables[i]}` })).toBeVisible();
    }
    const cls1 = udts.getByRole("table", { name: "Members of Cls1" });
    await expect(cls1.getByLabel("Direction of Cls1.Level")).toHaveValue("auto");
    await expect(cls1.getByRole("row", { name: /Cls1\.Level/ })).toContainText("program reads it");
    await expect(cls1.getByRole("row", { name: /Cls1\.AlmHH/ })).toContainText("program writes it");

    await page.getByRole("button", { name: "Compile and deploy" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Deployed CollectionInterlock." })).toBeVisible({ timeout: 120_000 });
    await expect(page.locator("[aria-label='Logic status']")).toHaveText("running");

    // The field: central 1 at high-high, then OS-1A at its own high-high.
    await writeTag(page, headers, "[default]MiamiDade/CLS-1/Level", 96);
    const online = page.getByRole("list", { name: "Members of Os1A online" });
    await expect(online).toContainText("Os1A.InhibitCmd: true", { timeout: 20_000 });
    await writeTag(page, headers, "[default]MiamiDade/OS-1A/Level", 96);
    await expect(online).toContainText("Os1A.OverrideActive: true", { timeout: 20_000 });
    await expect(online).toContainText("Os1A.AlmPumpIntoOverflow: true");
    await expect(page.getByRole("list", { name: "Members of Cls1 online" })).toContainText("Cls1.Level: 96");
  } finally {
    await context.close();
    await fresh.stop();
  }
});

async function writeTag(page: Page, headers: Record<string, string>, tag: string, value: unknown) {
  const res = await page.request.post("/api/v1/tags/write", { headers, data: { writes: [{ path: tag, value }] } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

/** Writes through the editor document so Check saves the same text the page shows. */
async function replaceEditor(editor: Locator, text: string) {
  const host = editor.locator("xpath=ancestor::div[contains(@class,'oip-st-editor')]");
  await host.evaluate((node, value) => {
    const view = (node as HTMLElement & { oipEditor?: { dispatch: (change: unknown) => void; state: { doc: { length: number } } } }).oipEditor;
    if (!view) throw new Error("Structured Text editor is not ready");
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
  }, text);
  await expect(editor).toContainText("FUNCTION_BLOCK FB_CentralInhibit");
}
