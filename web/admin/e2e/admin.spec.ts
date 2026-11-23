import { expect, test } from "@playwright/test";

/**
 * GAT-016: sign in and walk every admin section.
 *
 * The password matches the gateway test fixture. It is not a deployed secret.
 */
const PASSWORD = "Correct-horse-1";

const SECTIONS = [
  "Overview",
  "Config",
  "Tags",
  "Modules",
  "Users & Roles",
  "API Keys",
  "Audit",
  "Backups",
  "Projects",
  "Logs",
  "Canvas sessions",
  "Diagnostics",
  "Store and Forward",
  "Network",
  "Architectures",
  "OPC UA Certificates",
  "Connections",
  "Databases",
  "OPC UA Connections",
  "Gateway Settings",
  "Security Zones",
  "Security Settings",
  "Historian",
  "Alarming",
  "Event Streams",
  "Email Profiles",
  "Secrets",
  "HTTP Endpoints",
  "Deployment",
];

test("logs in, visits every section, searches, and logs out", async ({ page }) => {
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/admin\/login$/);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill("not-the-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert")).toContainText("invalid credentials");
  await expect(page).toHaveURL(/\/admin\/login$/);

  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
  await expect(page.getByTestId("status-banners")).toContainText("UP");
  // 0.1.1: a browser on the gateway machine gets no plain-HTTP warning on a new install.
  await expect(page.getByTestId("status-banners")).not.toContainText("plain HTTP");

  const nav = page.getByRole("navigation", { name: "Gateway sections" });
  for (const name of SECTIONS) {
    await nav.getByRole("link", { name, exact: true }).click();
    await expect(page.getByRole("heading", { level: 1, name, exact: true })).toBeVisible();
    const pages = new Set([
      "Overview", "Config", "Tags", "Modules", "Users & Roles", "API Keys", "Audit", "Backups", "Network", "Architectures",
      "OPC UA Certificates", "Projects", "Logs", "Canvas sessions", "Diagnostics", "Store and Forward", "Connections", "Databases",
      "OPC UA Connections",
      "Gateway Settings", "Security Zones", "Security Settings", "Historian", "Alarming", "Event Streams", "Email Profiles", "Secrets", "HTTP Endpoints", "Deployment",
    ]);
    if (pages.has(name)) {
      await expect(page.getByTestId("section-placeholder")).toHaveCount(0);
    } else {
      await expect(page.getByTestId("section-placeholder")).toBeVisible();
    }
    if (name === "Config") {
      await expect(page.getByTestId("config-resources")).toBeVisible();
    }
    if (name === "Network") {
      await expect(page.getByTestId("https-status")).toContainText("off");
      await expect(page.locator("body")).not.toContainText("PRIVATE KEY");
    }
  }

  const search = page.getByRole("searchbox", { name: "Search the gateway" });
  await search.fill("Audit");
  await page.getByRole("option", { name: "Audit", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Audit" })).toBeVisible();

  await search.fill("admin");
  const results = page.getByTestId("search-results");
  await expect(results.getByRole("option", { name: "User admin", exact: true })).toBeVisible();
  await expect(results).not.toContainText("passwordHash");
  await results.getByRole("option", { name: "User admin", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/config\?/);
  await expect(page).toHaveURL(/kind=User/);
  await expect(page).toHaveURL(/name=admin/);
  await expect(page.getByRole("heading", { level: 1, name: "Config" })).toBeVisible();
  await expect(page.locator("body")).not.toContainText("passwordHash");

  const stored = await page.evaluate(() => localStorage.length + sessionStorage.length);
  expect(stored).toBe(0);

  await page.getByRole("button", { name: "Log out" }).click();
  await expect(page).toHaveURL(/\/admin\/login$/);
  await page.goto("/admin/modules");
  await expect(page).toHaveURL(/\/admin\/login$/);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
});
