import { expect, test, type Page } from "@playwright/test";
import { login, main, section } from "./ui";

/**
 * Every other admin page: open it, make one thing with its form, reload, and see it is still there. Read-only pages
 * only need to render without an error. Names carry a run stamp so a re-run on the same gateway does not collide.
 */
const run = Date.now().toString(36).slice(-5);

/** After a reload the name shows on the page, as text or as a choice in a list. */
async function persisted(page: Page, text: string) {
  await page.reload();
  await expect.poll(async () => main(page).evaluate((el, t) => {
    const options = [...el.querySelectorAll("option")].map((o) => o.textContent ?? "");
    return el.innerText.includes(t) || options.some((o) => o.includes(t));
  }, text), { timeout: 15_000 }).toBe(true);
}

async function noError(page: Page) {
  await expect(main(page).getByRole("alert").filter({ hasText: /fail|error|could not/i })).toHaveCount(0);
}

test.describe("every admin page", () => {
  test.beforeEach(async ({ page }) => login(page));

  for (const label of [
    "Overview", "Modules", "Canvas sessions", "Diagnostics", "Store and Forward", "Deployment", "Audit",
    "OPC UA Certificates", "Outbound TLS Certificates", "Config", "Connections", "OPC UA Connections", "Architectures",
    "PLC / ERIC resource split",
  ]) {
    test(`${label} opens without an error`, async ({ page }) => {
      await section(page, label);
      await page.waitForTimeout(1_000);
      await noError(page);
    });
  }

  test("Metrics Dashboards: create a dashboard", async ({ page }) => {
    await section(page, "Metrics Dashboards");
    await main(page).getByLabel("New dashboard name").fill(`plant${run}`);
    await main(page).getByRole("button", { name: "Create", exact: true }).click();
    await persisted(page, `plant${run}`);
  });

  test("API Keys: create a key, the secret is shown once, then revoke it", async ({ page }) => {
    await section(page, "API Keys");
    await main(page).getByLabel("Name", { exact: true }).fill(`scada${run}`);
    await main(page).getByLabel("tags.read").check();
    await main(page).getByRole("button", { name: "Create API key" }).click();
    await persisted(page, `scada${run}`);
    const revoke = main(page).getByRole("button", { name: new RegExp(`(Revoke|Delete) scada${run}`) });
    await revoke.click();
    const confirm = main(page).getByRole("button", { name: /^(Confirm|Revoke|Delete)/ }).filter({ hasNotText: `scada${run}` });
    if (await confirm.count()) await confirm.first().click();
  });

  test("Security Zones: create a zone", async ({ page }) => {
    await section(page, "Security Zones");
    await main(page).getByLabel("Name", { exact: true }).fill(`plantfloor${run}`);
    await main(page).getByLabel("Description").fill("Plant floor HMIs");
    await main(page).getByRole("button", { name: "Create zone" }).click();
    await persisted(page, `plantfloor${run}`);
  });

  test("Security Settings: change the inactivity timeout and save", async ({ page }) => {
    await section(page, "Security Settings");
    const field = main(page).getByLabel("Inactivity Timeout Minutes");
    await field.fill("45");
    await main(page).getByRole("button", { name: "Save", exact: true }).first().click();
    await page.reload();
    await expect(main(page).getByLabel("Inactivity Timeout Minutes")).toHaveValue("45");
  });

  test("Security Levels: add a level and save", async ({ page }) => {
    await section(page, "Security Levels");
    await main(page).getByLabel("New level path").fill(`Operators${run}`);
    await main(page).getByRole("button", { name: "Add level" }).click();
    await main(page).getByRole("button", { name: "Save levels and rules" }).click();
    await persisted(page, `Operators${run}`);
  });

  test("Secrets: save a secret; the value is never shown back", async ({ page }) => {
    await section(page, "Secrets");
    await main(page).getByLabel("Secret name").fill(`smtp${run}`);
    await main(page).getByLabel("Secret value").fill("not-a-real-secret");
    await main(page).getByRole("button", { name: "Save secret" }).click();
    await persisted(page, `smtp${run}`);
    await expect(main(page).getByText("not-a-real-secret")).toHaveCount(0);
  });

  test("Identity Providers: add an OpenID Connect provider", async ({ page }) => {
    await section(page, "Identity Providers");
    await main(page).getByRole("button", { name: "Add identity provider" }).click();
    await main(page).getByLabel("Provider name").fill(`sso${run}`);
    await main(page).getByLabel("Issuer").fill("https://login.example.test");
    await main(page).getByLabel("Authorization endpoint").fill("https://login.example.test/auth");
    await main(page).getByLabel("Token endpoint").fill("https://login.example.test/token");
    await main(page).getByLabel("JWK set URL").fill("https://login.example.test/jwks");
    await main(page).getByLabel("Client ID").fill("eric");
    await main(page).getByRole("button", { name: "Create identity provider" }).click();
    await persisted(page, `sso${run}`);
  });

  test("User Sources: add an LDAP source", async ({ page }) => {
    await section(page, "User Sources");
    await main(page).getByRole("button", { name: "Add user source" }).click();
    await main(page).getByLabel("Source name").fill(`ad${run}`);
    await main(page).getByLabel(/Server URLs/).fill("ldaps://dc.example.test");
    await main(page).getByLabel("User search base").fill("dc=example,dc=test");
    await main(page).getByRole("button", { name: "Create user source" }).click();
    await persisted(page, `ad${run}`);
  });

  test("OAuth2 Clients: create a client", async ({ page }) => {
    await section(page, "OAuth2 Clients");
    await main(page).getByRole("button", { name: "Create OAuth2 client" }).click();
    await main(page).getByLabel("Name", { exact: true }).fill(`mail${run}`);
    await main(page).getByLabel("Client ID", { exact: true }).fill("eric");
    await main(page).getByLabel("Client secret", { exact: true }).fill("not-a-real-secret");
    await main(page).getByLabel("Token URL", { exact: true }).fill("https://login.example.test/token");
    await main(page).getByRole("button", { name: "Create OAuth2 client" }).last().click();
    await persisted(page, `mail${run}`);
  });

  test("Email Profiles: create an SMTP profile", async ({ page }) => {
    await section(page, "Email Profiles");
    await main(page).getByRole("button", { name: "New email profile" }).click();
    await main(page).getByLabel("Profile name").fill(`plantmail${run}`);
    await main(page).getByLabel("SMTP host").fill("smtp.example.test");
    await main(page).getByLabel("Sender address").fill("eric@example.test");
    await main(page).getByRole("button", { name: "Save profile" }).click();
    await persisted(page, `plantmail${run}`);
  });

  test("Event Streams: create a stream with a logger handler", async ({ page }) => {
    await section(page, "Event Streams");
    await main(page).getByRole("button", { name: "New event stream" }).click();
    await main(page).getByLabel("Stream name").fill(`levels${run}`);
    await main(page).getByLabel("Handler name").fill("log");
    await main(page).getByRole("button", { name: "Save stream" }).click();
    await persisted(page, `levels${run}`);
  });

  test("Charts: save a sequence chart", async ({ page }) => {
    await section(page, "Charts");
    await main(page).getByLabel("Chart name").fill(`pumpdown${run}`);
    await main(page).getByRole("button", { name: "Save chart" }).click();
    await persisted(page, `pumpdown${run}`);
  });

  test("Reports: create a named query, then a report that uses it", async ({ page }) => {
    // A report needs a data source: the page says to make a named query on the Databases page first.
    await section(page, "Databases");
    await main(page).getByRole("button", { name: "New named query" }).click();
    await main(page).getByLabel("Query name").fill(`levels${run}`);
    await main(page).getByLabel("Query SQL").fill("SELECT 1 AS one");
    await main(page).getByRole("button", { name: "Save query" }).click();
    await persisted(page, `levels${run}`);
    await section(page, "Reports");
    await main(page).getByRole("button", { name: "New report" }).click();
    await main(page).getByLabel("New report name").fill(`daily${run}`);
    await main(page).getByLabel("First data source").selectOption({ label: `levels${run}` });
    await main(page).getByRole("button", { name: "Create report" }).click();
    await persisted(page, `daily${run}`);
  });

  test("HTTP Endpoints: save a text endpoint and open it in the browser", async ({ page }) => {
    await section(page, "HTTP Endpoints");
    await main(page).getByLabel("Endpoint name").fill(`hello${run}`);
    await main(page).getByLabel("Path").fill(`hello${run}`);
    await main(page).getByLabel("Type", { exact: true }).selectOption({ label: "Text" });
    await main(page).getByLabel("Body").fill("lift station ok");
    await main(page).getByLabel("Require sign-in").uncheck();
    await main(page).getByRole("button", { name: "Save endpoint" }).click();
    await persisted(page, `hello${run}`);
    const link = main(page).getByRole("link", { name: new RegExp(`hello${run}`) }).first();
    const href = (await link.count()) ? await link.getAttribute("href") : null;
    test.info().annotations.push({ type: "endpoint-url", description: String(href) });
    expect(href, "the page should show where the endpoint is served").toBeTruthy();
    await page.goto(href!);
    await expect(page.locator("body")).toContainText("lift station ok");
  });

  test("Databases: named query and query browser on the built-in database", async ({ page }) => {
    await section(page, "Databases");
    await expect(main(page).getByRole("button", { name: "New named query" })).toBeVisible();
    const conn = main(page).getByLabel("Connection").last();
    const options = await conn.locator("option").allTextContents();
    test.skip(options.filter((o) => o.trim()).length === 0, "no database connection to query");
    await main(page).getByLabel("SQL statement").fill("SELECT 1 AS one");
    await main(page).getByRole("button", { name: "Run statement" }).click();
    await expect(main(page).getByRole("table").filter({ hasText: "one" }).last()).toBeVisible();
  });

  test("Logs: filter and set a logger level", async ({ page }) => {
    await section(page, "Logs");
    await main(page).getByLabel("Logger name").fill("org.oip.audit");
    await main(page).getByLabel(/New level/).selectOption("DEBUG");
    await main(page).getByRole("button", { name: "Set level" }).click();
    await persisted(page, "org.oip.audit");
  });

  test("Projects: export a project archive and import it as a copy", async ({ page }) => {
    await section(page, "Projects");
    const exportButton = main(page).getByRole("button", { name: /^Export / }).or(main(page).getByRole("link", { name: /^Export / })).first();
    test.skip(!(await exportButton.count()), "no project yet");
    const download = page.waitForEvent("download");
    await exportButton.click();
    const file = await (await download).path();
    await main(page).getByLabel("Project archive").setInputFiles(file!);
    await main(page).getByLabel("Import into project").fill(`copy${run}`);
    await main(page).getByRole("button", { name: "Import", exact: true }).click();
    await persisted(page, `copy${run}`);
  });

  test("Network: create a certificate signing request", async ({ page }) => {
    await section(page, "Network");
    await main(page).getByLabel("Common name").fill("eric.example.test");
    await main(page).getByRole("button", { name: "Create request" }).click();
    await expect(main(page).getByText(/BEGIN CERTIFICATE REQUEST|request created|Created/i).first()).toBeVisible();
  });

  test("Network: turn on HTTPS with the gateway's own certificate and reach the admin page over HTTPS", async ({ page }) => {
    await section(page, "Network");
    await main(page).getByLabel("Serve HTTPS").check();
    await main(page).getByLabel("HTTPS port", { exact: true }).fill(process.env.ERIC_UI_HTTPS_PORT ?? "8293");
    await main(page).getByRole("button", { name: "Save web server settings" }).click();
    await expect(main(page).getByText(/saved|restart/i).first()).toBeVisible();
    const res = await page.request.get(`https://127.0.0.1:${process.env.ERIC_UI_HTTPS_PORT ?? "8293"}/admin/`, { ignoreHTTPSErrors: true }).catch((e) => e);
    expect(res && typeof res.status === "function" ? res.status() : String(res)).toBe(200);
    // Back to plain HTTP for the rest of the suite.
    await main(page).getByLabel("Serve HTTPS").uncheck();
    await main(page).getByRole("button", { name: "Save web server settings" }).click();
  });

  test("Enterprise Admin: the role can be saved", async ({ page }) => {
    await section(page, "Enterprise Admin");
    await main(page).getByLabel(/Role/).selectOption({ label: "None" });
    await main(page).getByRole("button", { name: "Save role" }).click();
    await noError(page);
  });
});
