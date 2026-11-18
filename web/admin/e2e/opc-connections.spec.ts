import { expect, test } from "@playwright/test";
import { csrf, login, openSection, stamp, withViewer } from "./gateway";

/**
 * DEV-016: the OPC UA connection status page shows each connection's counts.
 * A connection with no session reports zero monitored items.
 * Doc source: FEATURES DEV-016.
 */
test("the status page and the connections route show per-connection counts", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const name = stamp("opc");
  await login(page);
  await openSection(page, "OPC UA Connections");

  const token = await csrf(page);
  const created = await page.request.put(`/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/${name}`, {
    headers: { "X-CSRF-Token": token, "Content-Type": "application/json" },
    data: {
      apiVersion: "oip/v1",
      kind: "Connection",
      metadata: { name, module: "org.oip.driver-opcua-client", version: 0 },
      spec: {
        name,
        endpointUrl: "opc.tcp://127.0.0.1:1/none",
        securityPolicy: "NONE",
        securityMode: "NONE",
        trustAllServerCertificates: false,
      },
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  try {
    await page.reload();
    await expect(page.getByRole("heading", { level: 1, name: "OPC UA Connections" })).toBeVisible();
    const row = page.getByTestId(`opc-connection-${name}`);
    await expect(row).toBeVisible();
    await expect(row).toHaveAttribute("data-connected", "false");
    await expect(page.getByTestId(`opc-monitored-items-${name}`)).toHaveText("0");
    await expect(page.getByTestId(`opc-subscriptions-${name}`)).toHaveText("0");

    const body = await page.request.get("/api/v1/diagnostics/opcua/connections");
    expect(body.ok()).toBeTruthy();
    const json = (await body.json()) as { connections: Array<Record<string, unknown>> };
    const found = json.connections.find((item) => item.name === name);
    expect(found).toBeTruthy();
    expect(found?.connected).toBe(false);
    expect(found?.monitoredItemCount).toBe(0);
    expect(found?.subscriptionCount).toBe(0);

    await withViewer(browser, page, async (viewer) => {
      await openSection(viewer, "OPC UA Connections");
      await expect(viewer.getByTestId("opc-connections-no-read")).toBeVisible();
      await expect(viewer.getByTestId("opc-connections")).toHaveCount(0);
      expect((await viewer.request.get("/api/v1/diagnostics/opcua/connections")).status()).toBe(403);
    });
  } finally {
    const again = await csrf(page);
    await page.request.delete(`/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/${name}`, {
      headers: { "X-CSRF-Token": again },
    });
  }
});
