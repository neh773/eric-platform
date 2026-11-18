import { expect, test } from "@playwright/test";
import { login, openSection, withViewer } from "./gateway";

/**
 * GAT-031 / GAT-032: the Network page's web-server fields, TLS choice, and certificate signing request.
 */
test("edits web server settings, TLS policy, and a CSR from the Network page", async ({ page, browser }) => {
  test.setTimeout(60_000);
  await login(page);
  await openSection(page, "Network");

  const settings = page.getByTestId("web-server-settings");
  await expect(settings.getByLabel("HTTP port", { exact: true })).toBeVisible();
  await expect(settings.getByLabel("HTTPS port", { exact: true })).toBeVisible();
  await expect(settings.getByLabel("Public address", { exact: true })).toBeVisible();
  await expect(settings.getByLabel("Send plain HTTP requests to HTTPS", { exact: true })).toBeVisible();
  await settings.getByLabel("Public address", { exact: true }).fill("gateway.e2e.test");
  await settings.getByRole("button", { name: "Save web server settings" }).click();
  await expect(settings.getByRole("status")).toContainText("Restart the gateway");
  await expect(page.getByTestId("settings-restart")).toBeVisible();

  const tls = page.getByTestId("tls-policy");
  await tls.getByLabel("Only the suites checked below", { exact: true }).check();
  await tls.getByLabel("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", { exact: true }).check();
  await tls.getByLabel("TLS 1.3", { exact: true }).uncheck();
  await tls.getByRole("button", { name: "Apply TLS settings" }).click();
  await expect(tls.getByRole("status")).toContainText("TLS settings applied");
  // Read back through the page's own session; an API request context may not carry a Secure cookie over http.
  const status = await page.evaluate(async () => {
    const res = await fetch("/api/v1/https", { credentials: "same-origin" });
    return (await res.json()) as { configuredProtocols: string[]; configuredCipherSuites: string[] | null };
  });
  expect(status.configuredProtocols).toEqual(["TLSv1.2"]);
  expect(status.configuredCipherSuites).toEqual(["TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"]);
  await tls.getByLabel("TLS 1.3", { exact: true }).check();
  await tls.getByLabel("Use the default list", { exact: true }).check();
  await tls.getByRole("button", { name: "Apply TLS settings" }).click();
  await expect(tls.getByRole("status")).toContainText("TLS settings applied");

  const csr = page.getByTestId("https-csr");
  await csr.getByLabel("Common name", { exact: true }).fill("gateway.e2e.test");
  await csr.getByLabel(/Other names/).fill("gateway.e2e.test\n127.0.0.1");
  await csr.getByRole("combobox", { name: /^Key/ }).selectOption("ec256");
  await csr.getByRole("button", { name: "Create request" }).click();
  const pending = page.getByTestId("csr-pending");
  await expect(pending).toContainText("CN=gateway.e2e.test");
  await expect(pending).toContainText("IP:127.0.0.1");
  const download = await page.evaluate(async () => {
    const res = await fetch("/api/v1/https/csr?download=true", { credentials: "same-origin" });
    return { ok: res.ok, type: res.headers.get("Content-Type") ?? "", text: await res.text() };
  });
  expect(download.ok).toBeTruthy();
  expect(download.type).toContain("pkcs10");
  const pem = download.text;
  expect(pem).toContain("BEGIN CERTIFICATE REQUEST");
  expect(pem).not.toContain("PRIVATE KEY");
  expect(await page.locator("body").innerText()).not.toContain("PRIVATE KEY");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Network");
    const refused = await viewer.evaluate(async () => {
      const res = await fetch("/api/v1/https/csr", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ commonName: "viewer.e2e.test" }),
      });
      return res.status;
    });
    expect(refused).toBeGreaterThanOrEqual(400);
    await expect(viewer.getByRole("button", { name: "Save web server settings" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: "Discard request" })).toHaveCount(0);
    await expect(viewer.getByTestId("csr-form")).toHaveCount(0);
  });

  await pending.getByRole("button", { name: "Discard request" }).click();
  await expect(page.getByTestId("csr-none")).toBeVisible();
});

test("shows the gateway diagram, diagnostics, and a new certificate", async ({ page }) => {
  test.setTimeout(60_000);
  await login(page);
  await openSection(page, "Network");
  const live = await page.evaluate(async () => {
    const res = await fetch("/api/v1/gateway-network", { credentials: "same-origin" });
    return (await res.json()) as { gatewayName?: string; certificate?: { sha256Fingerprint?: string } };
  });
  const diagram = page.getByTestId("gateway-network-diagram");
  await expect(diagram).toBeVisible();
  await expect(diagram).toContainText(live.gatewayName || "gateway");
  await expect(page.getByTestId("gateway-network-diagnostics")).toBeVisible();
  const before = live.certificate?.sha256Fingerprint ?? "";
  await page.getByRole("button", { name: "Regenerate certificate" }).click();
  await expect(page.getByTestId("gateway-network-fingerprint")).not.toHaveText(before);
  await expect(page.locator("body")).not.toContainText("PRIVATE KEY");
});
