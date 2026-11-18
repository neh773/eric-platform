import { expect, test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { csrf, login, openSection, withViewer } from "./gateway";

/**
 * OPC UA client-certificate trust (UAS-002): import a certificate, see it trusted with its subject,
 * thumbprint, and expiry, then remove trust after confirmation. A viewer cannot open the list.
 * The e2e gateway runs with the OPC UA server off; the trust list is still managed.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const PEM = path.join(here, "fixtures", "opcua-client.pem");
/** SHA-1 of the fixture certificate (public part only; no key is stored). */
const THUMBPRINT = "ec9eade3a3a2f76272213413480a4f2520c64d6b";

test("imports, lists, and removes a trusted OPC UA client certificate", async ({ page, browser }) => {
  await login(page);
  // Start from a clean list in case an earlier run left the fixture trusted.
  const token = await csrf(page);
  await page.request.delete(`/api/v1/opcua/certificates/trusted/${THUMBPRINT}`, { headers: { "X-CSRF-Token": token } });

  await openSection(page, "OPC UA Certificates");
  await expect(page.getByTestId("opcua-server-status")).toContainText("off");
  const trusted = page.getByRole("table", { name: "Trusted certificates" });
  await expect(trusted).toContainText("No trusted client certificates.");
  await expect(page.getByRole("table", { name: "Quarantined certificates" })).toContainText("No quarantined certificates.");

  await page.getByLabel("Certificate file (PEM or DER)").setInputFiles(PEM);
  await page.getByRole("button", { name: "Import and trust" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Certificate imported and trusted." })).toBeVisible();
  const row = trusted.getByRole("row", { name: /E2E Test Client/ });
  await expect(row).toContainText(THUMBPRINT);
  await expect(row).toContainText("urn:oip:e2e:client");
  await expect(page.locator("body")).not.toContainText("PRIVATE KEY");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "OPC UA Certificates");
    await expect(viewer.getByRole("alert")).toBeVisible();
    await expect(viewer.getByRole("button", { name: /Remove|Trust|Import/ })).toHaveCount(0);
  });

  await page.getByRole("button", { name: "Remove E2E Test Client" }).click();
  await expect(page.getByRole("dialog")).toContainText("quarantine again");
  await page.getByRole("button", { name: "Confirm removal" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Removed trust for E2E Test Client." })).toBeVisible();
  await expect(trusted).toContainText("No trusted client certificates.");

  const audit = await page.request.get("/api/v1/audit?action=opcua%20certificate%20removed");
  expect(audit.ok()).toBeTruthy();
  expect(((await audit.json()) as { total: number }).total).toBeGreaterThanOrEqual(1);
});
