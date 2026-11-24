/**
 * A brand-new install, used the way a new person would: open /admin, create the administrator account in the
 * first-run form, sign out, sign in again, then load the admin pages, Control Canvas and the manual.
 * Then it deploys a small Structured Text program with the bundled compiler and scan engine and reads its output.
 * The gateway under test must have an empty data directory and no ERIC_ADMIN_PASSWORD.
 */
import { expect, test } from "@playwright/test";
import { randomBytes } from "node:crypto";

const username = "firstadmin";
const password = `Fresh-${randomBytes(9).toString("base64url")}`;

test("a fresh install can create its administrator and serve every web app", async ({ page }) => {
  const setup = await (await page.request.get("/api/v1/setup")).json();
  expect(setup).toEqual({ needsAdmin: true, fromThisMachine: true });

  await page.goto("/admin");
  const form = page.getByRole("form", { name: "Create the administrator" });
  await expect(form.getByRole("heading", { name: "Create administrator account" })).toBeVisible();
  await form.getByLabel("Administrator username").fill(username);
  await form.getByLabel("New password").fill(password);
  await form.getByLabel("Confirm password").fill(password);
  await form.getByRole("button", { name: "Create administrator" }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();

  await page.getByRole("button", { name: "Log out" }).click();
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("form", { name: "Create the administrator" })).toHaveCount(0);
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();

  const again = await page.request.post("/api/v1/setup/admin", { headers: { "Content-Type": "application/json" }, data: { username: "second", password } });
  expect(again.status()).toBe(409);

  await page.goto("/designer");
  await expect(page).toHaveTitle(/Control Canvas/);
  await expect(page.locator("#root")).not.toBeEmpty();

  await page.goto("/docs/");
  await expect(page).toHaveTitle(/ERIC manual/);
  await expect(page.getByRole("heading").first()).toBeVisible();
});

test("a fresh install compiles and runs a PLC program with its own compiler and scan engine", async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto("/admin");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
  const { csrfToken } = (await (await page.request.get("/api/v1/auth/session")).json()) as { csrfToken: string };
  const headers = { "Content-Type": "application/json", "X-CSRF-Token": csrfToken };
  const call = async (method: "post" | "put", url: string, data: unknown) => {
    const res = await page.request[method](url, { headers, data });
    expect(res.ok(), `${url}: ${await res.text()}`).toBeTruthy();
    const text = await res.text();
    return text ? JSON.parse(text) : {};
  };
  for (const [name, value] of [["In", false], ["Out", false]] as const) {
    await call("post", "/api/v1/config/tags/gateway/Tag", {
      apiVersion: "oip/v1", kind: "Tag", metadata: { name: `fresh-${name.toLowerCase()}`, module: "tags", version: 0 },
      spec: { path: `Fresh/${name}`, type: "memory", dataType: "Boolean", value },
    });
  }
  await call("post", "/api/v1/logic/programs", { name: "FreshCheck" });
  await call("put", "/api/v1/logic/programs/FreshCheck", {
    source: "PROGRAM FreshCheck\nVAR\n    In AT %IX0.0 : BOOL;\n    Out AT %QX0.0 : BOOL;\nEND_VAR\n    Out := NOT In;\nEND_PROGRAM\n",
  });
  await call("put", "/api/v1/logic/mapping", {
    inputs: [{ address: "%IX0.0", tag: "[default]Fresh/In", type: "BOOL" }],
    outputs: [{ address: "%QX0.0", tag: "[default]Fresh/Out", type: "BOOL" }],
  });
  const deployed = (await call("post", "/api/v1/logic/deploy", { program: "FreshCheck" })) as { ok?: boolean; message?: string };
  expect(deployed.ok, deployed.message).toBe(true);
  await expect.poll(async () => {
    const rows = (await call("post", "/api/v1/tags/read", { paths: ["[default]Fresh/Out"] })) as { values?: Array<{ value?: unknown }> };
    return rows.values?.[0]?.value;
  }, { timeout: 20_000 }).toBe(true);
});
