import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * Phase 4 batch 2 against a real gateway: designer tools, an image in a session, a pie, and the live session list.
 */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata: { ...(resource.metadata as object), version } } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("designer tools, a session image, and the live session list", async ({ page }) => {
  const run = stamp("b2");
  const project = `${run}-plant`;
  const csrf = await signIn(page.request);
  await page.request.post("/api/v1/projects", { headers: { "X-CSRF-Token": csrf }, data: { name: project, title: "Plant" } });
  const db = `${run}-db`;
  await page.request.post("/api/v1/db/connections", {
    headers: { "X-CSRF-Token": csrf },
    data: { name: db, driver: "sqlite", url: `jdbc:sqlite:e2e/${db}.db`, password: "E2e-Db-Password-7" },
  });
  const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  const uploaded = await page.request.post(`/api/v1/projects/${project}/images`, {
    headers: { "X-CSRF-Token": csrf },
    data: { name: "mark.png", data: png },
  });
  expect(uploaded.ok(), await uploaded.text()).toBeTruthy();
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: {
        params: {},
        width: 640,
        height: 480,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "flex",
          props: { direction: "column" },
          children: [
            {
              id: "pie",
              kind: "component",
              name: "pie",
              type: "pie-chart",
              props: { title: "Load mix", slices: "Run:3, Idle:1" },
              flex: { grow: 0, shrink: 0, basis: "160px", display: true },
            },
            {
              id: "mark",
              kind: "component",
              name: "mark",
              type: "image",
              props: { source: `/api/v1/hmi/projects/${project}/images/mark.png`, alt: "Plant mark" },
              flex: { grow: 0, shrink: 0, basis: "40px", display: true },
            },
          ],
        },
      },
    },
  });

  await page.goto(`/studio?project=${project}`);
  await page.getByRole("tab", { name: "Console", exact: true }).click();
  await page.getByRole("textbox", { name: "Python" }).fill("print('hello-batch2')\n");
  await page.getByRole("button", { name: "Run", exact: true }).click();
  await expect(page.getByTestId("console-output")).toContainText("hello-batch2");
  await page.getByRole("tab", { name: "Tools" }).click();

  await page.getByRole("tab", { name: "Query browser" }).click();
  await page.getByLabel("Query connection").selectOption(db);
  await page.getByLabel("SQL statement").fill("SELECT 7 AS n");
  await page.getByRole("button", { name: "Run statement" }).click();
  await expect(page.getByLabel("Query browser result")).toContainText("7");

  await page.getByRole("tab", { name: "Guidance" }).click();
  await page.getByRole("button", { name: "Use Dark theme" }).click();
  await expect(page.getByRole("button", { name: "Dark", exact: true })).toHaveAttribute("aria-pressed", "true");

  await page.goto(`/hmi/${project}/`);
  await expect(page.getByRole("img", { name: "Plant mark" })).toBeVisible();
  await expect(page.getByRole("figure", { name: "Load mix" })).toBeVisible();
  await expect.poll(async () => {
    const res = await page.request.get("/api/v1/hmi/sessions");
    return (await res.text());
  }).toContain(project);

  await page.request.put("/api/v1/home/setup", { headers: { "X-CSRF-Token": csrf }, data: { complete: true } });
  await page.goto("/admin/home");
  await expect(page.getByRole("table", { name: "HMI sessions" })).toContainText(project);
});
