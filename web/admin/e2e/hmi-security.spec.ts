import { expect, test, type APIRequestContext, type Browser } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * P4-04 (HMP-092) on the real gateway:
 * - a view whose security names the operator role: a viewer opening its page sees a refusal and nothing of the view
 *   (the gateway sends only its path); an operator sees the view;
 * - a project whose session roles name the operator role: a viewer is refused the whole project, an operator runs it.
 */

async function signIn(request: APIRequestContext, username = "admin"): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
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

function view(path: string, text: string, security?: { roles: string[] }) {
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: path, module: "designer" },
    spec: {
      path,
      title: path,
      document: {
        params: {},
        width: 360,
        height: 200,
        ...(security ? { security } : {}),
        root: {
          id: `${path}-root`,
          kind: "container",
          name: path,
          layout: "flex",
          props: { direction: "column", alignItems: "flex-start" },
          children: [{ id: `${path}-text`, kind: "component", name: "Text", type: "label", props: { text, align: "start" } }],
        },
      },
    },
  };
}

async function user(request: APIRequestContext, csrf: string, username: string, roles: string[]) {
  const res = await request.post("/api/v1/security/users", {
    data: { username, password: PASSWORD, roles },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function as<T>(browser: Browser, username: string, run: (page: import("@playwright/test").Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  try {
    await signIn(context.request, username);
    return await run(await context.newPage());
  } finally {
    await context.close();
  }
}

test("a viewer without the role sees a refusal for a secured view and project, an operator sees them", async ({ page, browser }) => {
  const run = stamp("sec");
  const project = `${run}-plant`;
  const guarded = `${run}-ops`;
  const csrf = await signIn(page.request);
  const config = `/api/v1/config/designer/project/${project}`;
  await put(page.request, csrf, `${config}/View/home`, view("home", "Plant overview"));
  await put(page.request, csrf, `${config}/View/reactor`, view("reactor", "Reactor bypass controls", { roles: ["operator"] }));
  await put(page.request, csrf, `/api/v1/config/designer/project/${guarded}/View/home`, view("home", "Operations only"));
  const patched = await page.request.patch(`/api/v1/projects/${guarded}`, { headers: { "X-CSRF-Token": csrf }, data: { sessionRoles: ["operator"] } });
  expect(patched.ok(), await patched.text()).toBeTruthy();
  const viewer = `${run}-viewer`;
  const operator = `${run}-op`;
  await user(page.request, csrf, viewer, ["viewer"]);
  await user(page.request, csrf, operator, ["operator"]);

  await as(browser, viewer, async (session) => {
    // The open view runs; the secured view's page shows the refusal and loads nothing of the view.
    const answer = session.waitForResponse((res) => res.url().includes(`/api/v1/hmi/projects/${project}`) && res.status() === 200);
    await session.goto(`/hmi/${project}/`);
    const body = await (await answer).text();
    expect(body).not.toContain("Reactor bypass controls");
    expect(body).toContain('"deniedViews":["reactor"]');
    await expect(session.getByText("Plant overview")).toBeVisible();
    await session.goto(`/hmi/${project}/reactor`);
    await expect(session.locator("[data-view-refused='reactor']")).toHaveText('You do not have access to view "reactor".');
    await expect(session.getByText("Reactor bypass controls")).toHaveCount(0);

    // A project whose sessions need the operator role is refused as a whole.
    await session.goto(`/hmi/${guarded}/`);
    await expect(session.locator("[data-hmi-state='refused']")).toContainText(`You do not have access to project "${guarded}"`);
    await expect(session.locator("[data-hmi-state='refused']")).toContainText("operator");
    await expect(session.getByText("Operations only")).toHaveCount(0);
  });

  await as(browser, operator, async (session) => {
    await session.goto(`/hmi/${project}/reactor`);
    await expect(session.getByText("Reactor bypass controls")).toBeVisible();
    await expect(session.locator("[data-view-refused]")).toHaveCount(0);
    await session.goto(`/hmi/${guarded}/`);
    await expect(session.getByText("Operations only")).toBeVisible();
  });
});
