import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * P4-11 (HMP-117) on the real gateway: a component event sends a session message that a message handler (a gateway
 * script) receives with its payload, and a logout action returns the session to sign-in.
 */

async function signIn(request: APIRequestContext, username = "admin"): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata: { ...(resource.metadata as object), version: 0 } } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function tagValue(request: APIRequestContext, csrf: string, path: string): Promise<unknown> {
  const res = await request.post("/api/v1/tags/read", { headers: { "X-CSRF-Token": csrf }, data: { paths: [path] } });
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = (await res.json()) as { results?: Array<{ value?: unknown }>; values?: Array<{ value?: unknown }> };
  return (body.results ?? body.values ?? [])[0]?.value;
}

function button(id: string, text: string, actions: unknown[]) {
  return { id, kind: "component", name: id, type: "button", props: { text, enabled: true }, events: [{ event: "onClick", actions }] };
}

test("a message action reaches its handler on the gateway, and the logout action returns the session to sign-in", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("act");
  const project = `${run}-plant`;
  const heard = `[default]${run}/heard`;
  const csrf = await signIn(page.request);
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${run}-heard`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: `${run}-heard`, module: "tags" },
    spec: { path: `${run}/heard`, type: "memory", dataType: "String", value: "nothing" },
  });
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: {
        params: {},
        width: 400,
        height: 200,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "flex",
          props: { direction: "column", alignItems: "flex-start" },
          children: [
            button("announce", "Announce", [{ kind: "message", messageType: "shift.change", scope: "page", payload: { crew: "B" } }]),
            {
              id: "listener",
              kind: "component",
              name: "Listener",
              type: "label",
              props: { text: "Listening" },
              messageHandlers: [
                {
                  messageType: "shift.change",
                  scopes: ["page"],
                  script: { kind: "script", hook: "listener.shift", source: `actions.writeTag("${heard}", "%s crew %s" % (event.name, event.payload.crew))` },
                },
              ],
            },
            button("leave", "Sign out", [{ kind: "logout" }]),
          ],
        },
      },
    },
  });
  const operator = `${run}-op`;
  const created = await page.request.post("/api/v1/security/users", {
    data: { username: operator, password: PASSWORD, roles: ["operator"] },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  const context = await browser.newContext({ baseURL });
  try {
    await signIn(context.request, operator);
    const session = await context.newPage();
    await session.goto(`/hmi/${project}/`);
    await expect(session.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");

    await session.getByRole("button", { name: "Announce" }).click();
    await expect.poll(() => tagValue(page.request, csrf, heard), { timeout: 10_000 }).toBe("shift.change crew B");

    await session.getByRole("button", { name: "Sign out" }).click();
    await expect(session.getByRole("form", { name: "Sign in to the gateway" })).toBeVisible();
    await expect(session.getByRole("button", { name: "Announce" })).toHaveCount(0);
    // The login is over on the gateway, not only on screen.
    expect((await context.request.get("/api/v1/auth/session")).status()).toBe(401);

    // Signing in again opens the project.
    await session.getByLabel("Username").fill(operator);
    await session.getByLabel("Password").fill(PASSWORD);
    await session.getByRole("button", { name: "Sign in" }).click();
    await expect(session.getByRole("button", { name: "Announce" })).toBeVisible();
  } finally {
    await context.close();
  }
});
