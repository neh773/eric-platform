import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";

/**
 * M4.5: live alarm status table and power chart against a real gateway.
 *
 * The harness page (components/e2e/harness) proxies /api to the gateway that admin/e2e/start-gateway.mjs
 * starts. The password is the shared gateway test fixture (AuthSupport.TEST_PASSWORD), not a deployed secret.
 */
const PASSWORD = "Correct-horse-1";

async function signIn(request: APIRequestContext, username: string) {
  const res = await request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = (await res.json()) as { csrfToken?: string };
  if (!body.csrfToken) throw new Error("login did not return a CSRF token");
  return body.csrfToken;
}

function tagResource(name: string, path: string, value: number, alarm: boolean, version?: number) {
  return {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: version === undefined ? { name, module: "tags" } : { name, module: "tags", version },
    spec: {
      path,
      type: "memory",
      dataType: "Double",
      value,
      alarms: alarm ? [{ name: "High", mode: "AboveValue", priority: "High", setpointA: 100 }] : [],
    },
  };
}

async function putTag(page: Page, token: string, name: string, path: string, value: number, alarm: boolean) {
  const url = `/api/v1/config/tags/gateway/Tag/${name}`;
  const current = await page.request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : undefined;
  const res = await page.request.put(url, {
    data: tagResource(name, path, value, alarm, version),
    headers: { "X-CSRF-Token": token },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function withViewer(browser: Browser, adminToken: string, admin: Page, run: (page: Page) => Promise<void>) {
  const username = `view-${Date.now().toString(36)}`;
  const created = await admin.request.post("/api/v1/security/users", {
    data: { username, password: PASSWORD, roles: ["viewer"] },
    headers: { "X-CSRF-Token": adminToken },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await signIn(page.request, username);
    await run(page);
  } finally {
    await context.close();
  }
}

test("raises an alarm, shows it live, blocks a viewer ack, acks it, shelves and unshelves it", async ({ page, browser }) => {
  const stamp = Date.now().toString(36);
  const folder = `c6${stamp}`;
  const token = await signIn(page.request, "admin");
  await putTag(page, token, `c6-${stamp}-level`, `${folder}/level`, 150, true);
  const harness = `/?source=${encodeURIComponent(`*:/tag:${folder}/*`)}`;
  const display = `${folder}/level/High`;

  await withViewer(browser, token, page, async (viewer) => {
    await viewer.goto(harness);
    const row = viewer.getByRole("row").filter({ hasText: display });
    await expect(row).toHaveAttribute("data-state", "activeUnacked");
    await viewer.getByRole("checkbox", { name: `Select ${display}` }).check();
    await viewer.getByRole("button", { name: "Acknowledge" }).click();
    await expect(viewer.getByRole("alert")).toContainText("Acknowledge failed: 403");
    await expect(row).toHaveAttribute("data-state", "activeUnacked");
  });

  await page.goto(harness);
  const table = page.locator(".oip-alarm-table");
  const row = table.getByRole("row").filter({ hasText: display });
  await expect(row).toHaveAttribute("data-state", "activeUnacked");
  await expect(row).toContainText("Active and Unacknowledged");
  await expect(row).toContainText("High");

  await page.getByRole("checkbox", { name: `Select ${display}` }).check();
  await page.getByLabel("Acknowledgement notes").fill("checked level");
  await page.getByRole("button", { name: "Acknowledge" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Acknowledged 1 alarm." })).toBeVisible();
  await expect(row).toHaveAttribute("data-state", "activeAcked");
  await expect(row).toContainText("Active and Acknowledged");
  await expect(row).toContainText("admin");

  const alarms = (await (await page.request.get(`/api/v1/alarms?source=${encodeURIComponent(`*:/tag:${folder}/*`)}`)).json()) as {
    alarms: { ackNotes: string | null }[];
  };
  expect(alarms.alarms[0]?.ackNotes).toBe("checked level");

  await page.getByLabel("Shelve duration").selectOption({ label: "15 minutes" });
  await page.getByRole("checkbox", { name: `Select ${display}` }).check();
  await page.getByRole("button", { name: "Shelve" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Shelved 1 alarm for 15 minutes." })).toBeVisible();
  await expect(table.getByRole("row").filter({ hasText: display })).toHaveCount(0);
  await page.getByRole("tab", { name: "Shelved (1)" }).click();
  const shelved = table.getByRole("row").filter({ hasText: display });
  await expect(shelved).toHaveAttribute("data-state", "shelved");

  await page.getByRole("button", { name: `Unshelve ${display}` }).click();
  await expect(page.getByRole("status").filter({ hasText: `Unshelved ${display}.` })).toBeVisible();
  await page.getByRole("tab", { name: /Active alarms/ }).click();
  await expect(table.getByRole("row").filter({ hasText: display })).toHaveAttribute("data-state", "activeAcked");
});

test("power chart plots live tag values from the gateway and the time series chart follows its binding", async ({ page }) => {
  const stamp = Date.now().toString(36);
  const folder = `c6${stamp}`;
  const token = await signIn(page.request, "admin");
  const name = `c6-${stamp}-flow`;
  await putTag(page, token, name, `${folder}/flow`, 5, false);
  const pen = `[default]${folder}/flow`;

  await page.goto(`/?pens=${encodeURIComponent(pen)}&source=none`);
  const penRow = page.locator(`[data-pen="${pen}"]`);
  await expect(penRow.getByTestId("pen-value")).toHaveText("5.0");
  await putTag(page, token, name, `${folder}/flow`, 42.5, false);
  await expect(penRow.getByTestId("pen-value")).toHaveText("42.5");
  await expect(penRow).toContainText("5.0");
  await expect.poll(async () => Number(await penRow.getAttribute("data-samples"))).toBeGreaterThan(4);
  const line = page.locator(".oip-power-chart .oip-chart-line");
  await expect(line).toHaveAttribute("d", /^M.*L/);

  const series = page.locator(".oip-time-series");
  await expect.poll(async () => Number(await series.getAttribute("data-points"))).toBeGreaterThan(3);
  await expect(series.locator(".oip-chart-line")).toHaveAttribute("d", /^M.*L/);
});
