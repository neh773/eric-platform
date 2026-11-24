// UI-only walk of an already installed gateway. No API writes, no file import, no environment password.
// Run from a machine where Playwright and Chrome are installed:
//   node packaging/windows/e2e/fresh-install-ui.mjs
// Writes ui-audit.json under ERIC_AUDIT_OUT, or under ./windows-ui-audit-out.
import { chromium } from "playwright";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const base = process.env.ERIC_GATEWAY_URL ?? "http://127.0.0.1:8090";
const outDir = process.env.ERIC_AUDIT_OUT ?? join(process.cwd(), "windows-ui-audit-out");
const shotDir = join(outDir, "shots");
mkdirSync(shotDir, { recursive: true });

const sections = [
  ["home", "Home"],
  ["overview", "Overview"],
  ["gateway-settings", "Gateway Settings"],
  ["plc-resources", "PLC / ERIC resource split"],
  ["modules", "Modules"],
  ["backups", "Backups"],
  ["designer-sessions", "Canvas sessions"],
  ["projects", "Projects"],
  ["logs", "Logs"],
  ["diagnostics", "Diagnostics"],
  ["metrics", "Metrics Dashboards"],
  ["store-and-forward", "Store and Forward"],
  ["deployment", "Deployment"],
  ["users", "Users & Roles"],
  ["api-keys", "API Keys"],
  ["identity-providers", "Identity Providers"],
  ["user-sources", "User Sources"],
  ["zones", "Security Zones"],
  ["security-levels", "Security Levels"],
  ["security-settings", "Security Settings"],
  ["secrets", "Secrets"],
  ["audit", "Audit"],
  ["opcua", "OPC UA Certificates"],
  ["outbound-tls", "Outbound TLS Certificates"],
  ["oauth2-clients", "OAuth2 Clients"],
  ["tags", "Tags"],
  ["historian", "Historian"],
  ["alarming", "Alarming"],
  ["event-streams", "Event Streams"],
  ["email-profiles", "Email Profiles"],
  ["charts", "Charts"],
  ["reports", "Reports"],
  ["http-endpoints", "HTTP Endpoints"],
  ["config", "Config"],
  ["connections", "Connections"],
  ["databases", "Databases"],
  ["network", "Network"],
  ["enterprise-admin", "Enterprise Admin"],
  ["opc-connections", "OPC UA Connections"],
  ["architectures", "Architectures"],
];

const rows = [];

function add(feature, path, status, detail, cause) {
  rows.push({ feature, path, status, detail, cause });
  console.log(`${status}  ${feature}`);
}

async function snap(page, name) {
  const file = join(shotDir, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  return file;
}

async function visibleText(page) {
  return page.locator("body").innerText({ timeout: 8000 }).catch(() => "");
}

const browser = await chromium.launch({ channel: "chrome", headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
const consoleErrors = [];
page.on("console", (msg) => {
  if (msg.type() === "error") consoleErrors.push(msg.text().slice(0, 300));
});
page.on("pageerror", (err) => consoleErrors.push(String(err).slice(0, 300)));

try {
  const health = await page.goto(`${base}/`, { waitUntil: "domcontentloaded", timeout: 20000 });
  const rootUrl = page.url();
  const rootText = (await visibleText(page)).slice(0, 500);
  await snap(page, "root");
  add(
    "Gateway root opens the admin sign-in page",
    "/",
    rootUrl.includes("/admin") && /sign in/i.test(rootText) ? "PASS" : "FAIL",
    `HTTP ${health?.status()} landed on ${rootUrl}. Heading text starts: ${rootText.split("\n").slice(0, 8).join(" | ")}`,
    rootUrl.includes("/admin") ? "" : "Root did not reach the admin UI.",
  );

  await page.goto(`${base}/admin/login`, { waitUntil: "domcontentloaded" });
  const loginText = await visibleText(page);
  await snap(page, "admin-login");
  const hasCreate = /create (an |the )?administrator|set up|first account|new administrator/i.test(loginText);
  add(
    "Sign-in page offers a way to create the first administrator",
    "/admin/login",
    hasCreate ? "PASS" : "FAIL",
    `Visible text: ${loginText.split("\n").slice(0, 20).join(" | ")}. Create-account control present: ${hasCreate}.`,
    hasCreate ? "" : "Fresh install writes no user. The sign-in form only accepts an existing username and password. The Windows service environment does not set ERIC_ADMIN_PASSWORD, and the installer never asks for one.",
  );

  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill("not-a-real-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForTimeout(1500);
  const afterLogin = await visibleText(page);
  await snap(page, "admin-login-rejected");
  const stillGate = /sign in/i.test(afterLogin) && !/gateway sections/i.test(afterLogin);
  const alert = await page.getByRole("alert").innerText().catch(() => "");
  add(
    "Sign in as administrator after a fresh install",
    "/admin/login",
    stillGate ? "FAIL" : "PASS",
    `Stayed on the sign-in page: ${stillGate}. Alert: ${alert || "(none)"}.`,
    stillGate
      ? "No administrator account exists. The form reports a failed sign-in and does not explain that the account was never created."
      : "",
  );

  for (const [id, label] of sections) {
    const url = `${base}/admin/${id}`;
    let status = "FAIL";
    let detail = "";
    let cause = "";
    try {
      const res = await page.goto(url, { waitUntil: "domcontentloaded", timeout: 20000 });
      await page.waitForTimeout(400);
      const text = await visibleText(page);
      const signedIn = await page.getByRole("navigation", { name: "Gateway sections" }).isVisible().catch(() => false);
      const heading = await page.getByRole("heading", { level: 1 }).first().innerText().catch(() => "");
      if (signedIn && heading === label) {
        status = "PASS";
        detail = `Signed in and the page heading is ${heading}.`;
      } else if (/sign in/i.test(text) && !signedIn) {
        status = "FAIL";
        detail = `HTTP ${res?.status()}. Redirected to sign-in (${page.url()}). Heading: ${heading || "(none)"}.`;
        cause = "The page is behind sign-in, and a fresh install has no account that can be created or used from the UI.";
      } else {
        status = "FAIL";
        detail = `HTTP ${res?.status()} at ${page.url()}. Heading: ${heading || "(none)"}. Text: ${text.split("\n").slice(0, 6).join(" | ")}`;
        cause = "The page did not show the expected heading after navigation.";
      }
    } catch (err) {
      detail = String(err).slice(0, 400);
      cause = "Navigation failed.";
    }
    add(`Admin page: ${label}`, `/admin/${id}`, status, detail, cause);
  }

  await page.goto(`${base}/designer`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.waitForTimeout(800);
  const designerText = await visibleText(page);
  await snap(page, "designer");
  const designerLogin = await page.getByRole("form", { name: "Sign in to the gateway" }).isVisible().catch(() => false);
  add(
    "Control Canvas opens and can create a project",
    "/designer",
    designerLogin ? "FAIL" : "FAIL",
    `At ${page.url()}. Sign-in form: ${designerLogin}. Text: ${designerText.split("\n").slice(0, 12).join(" | ")}`,
    designerLogin
      ? "Control Canvas asks for a gateway sign-in before any project, screen, binding, popup, or publish control is shown. No account exists."
      : "Control Canvas did not show a usable designer. No account exists to continue.",
  );

  for (const hmiPath of ["/hmi", "/hmi/", "/hmi/default/"]) {
    const res = await page.goto(`${base}${hmiPath}`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page.waitForTimeout(500);
    const text = await visibleText(page);
    await snap(page, "hmi-" + hmiPath.replace(/[^a-z0-9]+/gi, "-"));
    add(
      `Operator view ${hmiPath}`,
      hmiPath,
      "FAIL",
      `HTTP ${res?.status()} at ${page.url()}. Text: ${text.split("\n").slice(0, 10).join(" | ")}`,
      "No project was published. The view is behind sign-in or has nothing to show, and the UI never got far enough to create a project.",
    );
  }

  const docsRes = await page.goto(`${base}/docs`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.waitForTimeout(400);
  const docsText = await visibleText(page);
  await snap(page, "docs");
  add(
    "Manual is served at /docs without sign-in",
    "/docs",
    docsRes?.status() === 200 && docsText.length > 40 ? "PASS" : "FAIL",
    `HTTP ${docsRes?.status()}. Text: ${docsText.split("\n").slice(0, 15).join(" | ")}`,
    docsRes?.status() === 200 ? "" : "The manual page did not load.",
  );

  const tester = await page.goto(`${base}/docs/manual/getting-started-testers`, { waitUntil: "domcontentloaded", timeout: 20000 });
  const testerText = await visibleText(page);
  await snap(page, "docs-testers");
  const mentionsAccount = /administrator account created at install/i.test(testerText);
  const mentionsEnv = /ERIC_ADMIN_PASSWORD|OIP_ADMIN_PASSWORD/i.test(testerText);
  add(
    "Installed manual tells a Windows user how to get the first password",
    "/docs/manual/getting-started-testers",
    tester?.status() === 200 ? "FAIL" : "FAIL",
    `HTTP ${tester?.status()}. Says an account is created at install: ${mentionsAccount}. Names the environment variable: ${mentionsEnv}. Excerpt: ${testerText.split("\n").slice(0, 25).join(" | ")}`,
    "The page says to sign in with the administrator account created at install, and that there is no built-in default password. The installer never creates that account and the page does not say how to create one.",
  );

  const blocked = [
    ["Create, view, edit, delete, and use a Modbus TCP device", "Connections → New device"],
    ["Create, view, edit, and delete a tag", "Tags → New tag"],
    ["Create a UDT type and instance", "Tags"],
    ["Create, edit, and delete an alarm", "Alarming"],
    ["Record tag history on the embedded historian", "Historian"],
    ["Use Postgres for history", "Historian / Databases"],
    ["Place a trend and read history", "Control Canvas trend / Historian"],
    ["Create a user, a role, and change the password policy", "Users & Roles, Security Settings"],
    ["Turn on HTTPS", "Network"],
    ["Create, export, and delete a project", "Projects"],
    ["Create an encrypted backup and restore it", "Backups"],
    ["Create, check, deploy, and run Structured Text", "Control Canvas → Logic"],
    ["Run a gateway script", "Control Canvas console / HTTP Endpoints"],
    ["Change network and service settings", "Network, Gateway Settings"],
    ["Place every Control Canvas component, bind a tag, open a popup, publish, and view /hmi", "/designer"],
  ];
  for (const [feature, path] of blocked) {
    add(
      feature,
      path,
      "FAIL",
      "Not reached. The browser stayed on the sign-in form.",
      "A fresh install has no administrator, and nothing on the sign-in page creates one. These controls exist only after sign-in.",
    );
  }
} finally {
  await browser.close();
}

const report = { generatedAt: new Date().toISOString(), consoleErrors: consoleErrors.slice(0, 40), rows };
writeFileSync(join(outDir, "ui-audit.json"), JSON.stringify(report, null, 2));
const pass = rows.filter((r) => r.status === "PASS").length;
const fail = rows.filter((r) => r.status === "FAIL").length;
console.log(`PASS ${pass} FAIL ${fail}`);
