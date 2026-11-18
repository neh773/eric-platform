import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * A saved view's query binding runs the named query on the gateway and shows the value in the session.
 */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const metadata = { ...(resource.metadata as Record<string, unknown>), version };
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("a session shows a named-query binding from the gateway", async ({ page }) => {
  const project = stamp("qry");
  const connection = stamp("qdb");
  const query = `${connection}-answer`;
  const csrf = await signIn(page.request);
  const created = await page.request.post("/api/v1/db/connections", {
    headers: { "X-CSRF-Token": csrf },
    data: { name: connection, driver: "sqlite", url: `jdbc:sqlite:e2e/${connection}.db`, password: "E2e-Db-Password-7" },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  const saved = await page.request.post("/api/v1/db/queries", {
    headers: { "X-CSRF-Token": csrf },
    data: { name: query, connection, queryType: "scalar", sql: "SELECT 42" },
  });
  expect(saved.ok(), await saved.text()).toBeTruthy();

  const config = `/api/v1/config/designer/project/${project}`;
  await put(page.request, csrf, `${config}/View/overview`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "overview", module: "designer" },
    spec: {
      path: "overview",
      title: "Query",
      document: {
        params: {},
        width: 320,
        height: 120,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction: "column" },
          children: [
            {
              id: "answer",
              kind: "component",
              name: "Answer",
              type: "label",
              props: { text: "waiting" },
              bindings: { text: { kind: "query", path: query, returnFormat: "scalar" } },
            },
          ],
        },
      },
    },
  });
  await put(page.request, csrf, `${config}/PageConfig/page-config`, {
    apiVersion: "oip/v1",
    kind: "PageConfig",
    metadata: { name: "page-config", module: "designer" },
    spec: { pages: [{ url: "/", title: "Query", primaryView: "overview" }] },
  });

  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  await expect(page.locator("[data-node-id='answer'] .oip-label")).toHaveText("42");
});
