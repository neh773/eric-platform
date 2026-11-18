import { expect, test } from "@playwright/test";
import { csrf, login, openSection, stamp, withViewer } from "./gateway";

/**
 * GAT-022: the Connections section lists device, database, service and OPC connections with live status.
 * Doc source: FEATURES GAT-022.
 */
test("the Connections list shows each kind with its status and links to the editor", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const database = stamp("db");
  const opc = stamp("opc");
  await login(page);
  const token = await csrf(page);
  const headers = { "X-CSRF-Token": token, "Content-Type": "application/json" };
  const db = await page.request.post("/api/v1/db/connections", {
    headers,
    data: { name: database, driver: "sqlite", url: "jdbc:sqlite::memory:", username: "", description: "e2e" },
  });
  expect(db.ok(), await db.text()).toBeTruthy();
  const created = await page.request.put(`/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/${opc}`, {
    headers,
    data: {
      apiVersion: "oip/v1",
      kind: "Connection",
      metadata: { name: opc, module: "org.oip.driver-opcua-client", version: 0 },
      spec: { name: opc, endpointUrl: "opc.tcp://127.0.0.1:1/none", securityPolicy: "NONE", securityMode: "NONE", trustAllServerCertificates: false },
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  try {
    await openSection(page, "Connections");
    await expect(page.getByTestId("connections-summary")).toContainText("connections:");
    await expect(page.getByTestId(`connection-database:sql:${database}`)).toHaveAttribute("data-health", "ok");
    await expect(page.getByTestId(`connection-opc:opcua-client:${opc}`)).toHaveAttribute("data-health", /pending|fault/);
    await expect(page.getByTestId("connection-service:opcua-server:OPC UA Server")).toBeVisible();

    await page.getByRole("button", { name: "Database", exact: true }).click();
    await expect(page.getByTestId(`connection-opc:opcua-client:${opc}`)).toHaveCount(0);
    await page.getByRole("link", { name: `Open ${database}`, exact: true }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Databases", exact: true })).toBeVisible();

    await withViewer(browser, page, async (viewer) => {
      expect((await viewer.request.get("/api/v1/connections")).status()).toBe(403);
    });
  } finally {
    await page.request.delete(`/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/${opc}`, { headers });
  }
});
