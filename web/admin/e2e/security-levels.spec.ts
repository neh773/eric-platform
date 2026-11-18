import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { csrf, login, openSection, PASSWORD, stamp, withViewer } from "./gateway";

/**
 * SEC-016 and SEC-017 on the real gateway: an administrator adds a security level and a rule that grants it at login
 * from the Security Levels page, tries the rule, and the level then gates an HMI component: the user the rule matches
 * sees it, another user's session never receives it.
 * SEC-022: the Outbound TLS Certificates page imports, lists and removes a trusted server certificate.
 * Doc source: FEATURES SEC-016.
 * Doc source: FEATURES SEC-017.
 * Doc source: FEATURES SEC-022.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const PEM = path.join(here, "fixtures", "opcua-client.pem");
/** The SHA-256 thumbprint of the fixture certificate when it is trusted, or "" (public part only; no key is stored). */
async function sha256(page: Page): Promise<string> {
  const listed = await page.request.get("/api/v1/tls/outbound");
  const body = (await listed.json()) as { trusted: { thumbprint: string; commonName: string | null }[] };
  return body.trusted.find((c) => c.commonName === "E2E Test Client")?.thumbprint ?? "";
}

async function user(request: APIRequestContext, token: string, username: string, roles: string[]) {
  const res = await request.post("/api/v1/security/users", {
    data: { username, password: PASSWORD, roles },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function as<T>(browser: Browser, username: string, run: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  try {
    const res = await context.request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
    expect(res.ok(), await res.text()).toBeTruthy();
    return await run(await context.newPage());
  } finally {
    await context.close();
  }
}

function view(level: string) {
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer", version: 0 },
    spec: {
      path: "home",
      title: "home",
      document: {
        params: {},
        width: 360,
        height: 200,
        root: {
          id: "home-root",
          kind: "container",
          name: "home",
          layout: "flex",
          props: { direction: "column", alignItems: "flex-start" },
          children: [
            { id: "open", kind: "component", name: "Open", type: "label", props: { text: "Shift handover", align: "start" } },
            {
              id: "night",
              kind: "component",
              name: "Night",
              type: "label",
              security: { levels: [level] },
              props: { text: "Night shift controls", align: "start" },
            },
          ],
        },
      },
    },
  };
}

test("an administrator adds a level and a rule, tries it, and the level gates an HMI component", async ({ page, browser }) => {
  const run = stamp("lvl");
  const level = `Plant/${run}`;
  const night = `${run}-night`;
  const day = `${run}-day`;
  await login(page);
  const token = await csrf(page);
  await user(page.request, token, night, ["viewer"]);
  await user(page.request, token, day, ["viewer"]);

  await openSection(page, "Security Levels");
  const tree = page.getByRole("list", { name: "Security level tree" });
  await expect(tree).toContainText("Public");
  await page.getByLabel("New level path").fill(level);
  await page.getByRole("button", { name: "Add level", exact: true }).click();
  await expect(tree.getByText(level, { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Add rule", exact: true }).click();
  const added = page.getByRole("group", { name: /^Rule rule-\d+$/ }).last();
  await added.getByLabel("Rule name").fill(run);
  const rule = page.getByRole("group", { name: `Rule ${run}`, exact: true });
  await rule.getByLabel("Sources").fill("internal");
  await rule.getByRole("checkbox", { name: level, exact: true }).check();
  await rule.getByRole("button", { name: `Add condition to ${run}`, exact: true }).click();
  const condition = page.getByRole("group", { name: `Condition 1 of ${run}`, exact: true });
  await condition.getByLabel("Type").selectOption("username");
  await condition.getByLabel("Value").fill(night);
  await page.getByRole("button", { name: "Save levels and rules", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Security levels saved." })).toBeVisible();

  const trial = page.getByRole("form", { name: "Try the rules" });
  await trial.getByLabel("User name").fill(night);
  await trial.getByRole("button", { name: "Try rules", exact: true }).click();
  await expect(page.getByTestId("trial-levels")).toContainText(level);
  await expect(page.getByTestId("trial-rules")).toContainText(run);

  const project = `${run}-plant`;
  const saved = await page.request.post(`/api/v1/config/designer/project/${project}/View`, { headers: { "X-CSRF-Token": token }, data: view(level) });
  expect(saved.ok(), await saved.text()).toBeTruthy();

  await as(browser, night, async (session) => {
    const levels = (await (await session.request.get("/api/v1/auth/session")).json()) as { securityLevels: string[] };
    expect(levels.securityLevels).toEqual(expect.arrayContaining(["Public", "Authenticated", "Plant", level]));
    await session.goto(`/hmi/${project}/`);
    await expect(session.getByText("Night shift controls")).toBeVisible();
    await expect(session.getByText("Shift handover")).toBeVisible();
  });

  await as(browser, day, async (session) => {
    const answer = session.waitForResponse((res) => res.url().includes(`/api/v1/hmi/projects/${project}`) && res.status() === 200);
    await session.goto(`/hmi/${project}/`);
    // The gateway removed the component before sending the project: nothing of it reaches this browser.
    expect(await (await answer).text()).not.toContain("Night shift controls");
    await expect(session.getByText("Shift handover")).toBeVisible();
    await expect(session.getByText("Night shift controls")).toHaveCount(0);
  });
});

test("the outbound TLS page imports, lists and removes a trusted server certificate", async ({ page, browser }) => {
  await login(page);
  const token = await csrf(page);
  const left = await sha256(page);
  if (left) await page.request.delete(`/api/v1/tls/outbound/trusted/${left}`, { headers: { "X-CSRF-Token": token } });

  await openSection(page, "Outbound TLS Certificates");
  await expect(page.getByRole("table", { name: "Rejected server certificates" })).toBeVisible();
  const trusted = page.getByRole("table", { name: "Trusted server certificates" });
  await expect(trusted).not.toContainText("E2E Test Client");

  await page.getByLabel("Certificate file (PEM or DER)").setInputFiles(PEM);
  await page.getByRole("button", { name: "Import and trust", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Certificate imported and trusted." })).toBeVisible();
  const thumbprint = await sha256(page);
  expect(thumbprint).toMatch(/^[0-9a-f]{64}$/);
  await expect(trusted.getByRole("row", { name: /E2E Test Client/ })).toContainText(thumbprint);
  await expect(page.locator("body")).not.toContainText("PRIVATE KEY");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Outbound TLS Certificates");
    await expect(viewer.getByRole("alert")).toBeVisible();
    await expect(viewer.getByRole("button", { name: /Remove|Trust|Import/ })).toHaveCount(0);
  });

  await page.getByRole("button", { name: "Remove E2E Test Client", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("will be refused");
  await page.getByRole("button", { name: "Confirm removal", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Removed trust for E2E Test Client." })).toBeVisible();
  await expect(trusted).not.toContainText("E2E Test Client");

  const audit = await page.request.get("/api/v1/audit?action=outbound%20tls%20certificate%20removed");
  expect(audit.ok()).toBeTruthy();
  expect(((await audit.json()) as { total: number }).total).toBeGreaterThanOrEqual(1);
});
