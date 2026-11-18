import { expect, test } from "@playwright/test";
import { PASSWORD } from "./gateway";

/**
 * Chart design and monitor views, and a scripted web endpoint, on a real gateway.
 */

test("a chart monitor highlights the active step and a python page answers", async ({ page }) => {
  const login = await page.request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(login.ok(), await login.text()).toBeTruthy();
  const csrf = ((await login.json()) as { csrfToken: string }).csrfToken;
  const chart = `wait${Date.now().toString(36)}`;
  const pageName = `page${Date.now().toString(36)}`;
  const saved = await page.request.post("/api/v1/config/sfc/gateway/Chart", {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "Chart",
      metadata: { name: chart, module: "sfc" },
      spec: {
        executionMode: "callable",
        variables: [{ name: "ready", initial: false }],
        elements: [
          { id: "begin", type: "begin" },
          { id: "hold", type: "action", timerPeriodMillis: 60000 },
          { id: "go", type: "transition", expression: "{ready}" },
          { id: "end", type: "end" },
        ],
        links: [
          { from: "begin", to: "hold" },
          { from: "hold", to: "go" },
          { from: "go", to: "end" },
        ],
      },
    },
  });
  expect(saved.ok(), await saved.text()).toBeTruthy();
  const source = "def doGet(request):\n    return 'page-' + request['path']\n";
  const pageResource = await page.request.post("/api/v1/config/webdev/gateway/WebResource", {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "WebResource",
      metadata: { name: pageName, module: "webdev" },
      spec: { path: pageName, type: "python", source },
    },
  });
  expect(pageResource.ok(), await pageResource.text()).toBeTruthy();

  await page.goto("/admin/charts");
  await expect(page.getByText("Use a chart when the work is a sequence of steps and transitions.")).toBeVisible();
  await expect(page.getByRole("table", { name: "Charts" })).toContainText(chart);
  await page.getByRole("button", { name: `Start ${chart}` }).click();
  await page.getByRole("tab", { name: "Monitor" }).click();
  await expect(page.locator("[data-active='true']", { hasText: "hold" })).toBeVisible();

  const body = await page.request.get(`/system/webdev/gateway/${pageName}`);
  expect(body.status()).toBe(200);
  expect(await body.text()).toBe(`page-${pageName}`);
});
