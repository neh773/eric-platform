import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/**
 * Tag diagnostics opened from the tag browser (TAG-003). The value on the page follows a write.
 */

async function api(page: Page, method: "GET" | "POST" | "DELETE", url: string, data?: unknown) {
  const token = await csrf(page);
  return page.request.fetch(url, {
    method,
    data,
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
}

test("opens tag diagnostics from the tag browser and shows a live value change", async ({ page }) => {
  const folder = `Diag_${stamp("td").replace(/-/g, "_")}`;
  const name = `${folder.toLowerCase().replace(/_/g, "-")}-level`;
  const tagPath = `[default]${folder}/Level`;
  await login(page);
  const created = await api(page, "POST", "/api/v1/config/tags/gateway/Tag", {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name, module: "tags", version: 0 },
    spec: { path: `${folder}/Level`, type: "memory", dataType: "Int32", value: 1 },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  try {
    await openSection(page, "Tags");
    const folderRow = page.locator(`tr[data-testid="tag-row"][data-path="[default]${folder}"]`);
    await folderRow.getByRole("button", { name: `Expand ${folder}` }).click();
    const row = page.locator(`tr[data-testid="tag-row"][data-path="${tagPath}"]`);
    await row.getByRole("button", { name: `Diagnostics for ${tagPath}` }).click();

    const panel = page.getByTestId("tag-diagnostics");
    await expect(panel.getByTestId("diag-value")).toHaveText("1");
    await expect(panel.getByTestId("diag-quality")).toHaveText("Good");
    await expect(panel.getByTestId("diag-subscribers")).toBeVisible();
    await expect(panel.getByTestId("diag-evaluated")).toBeVisible();
    await expect(panel.getByTestId("diag-published")).toBeVisible();
    await expect(panel.getByTestId("diag-overruns")).toBeVisible();

    const write = await api(page, "POST", "/api/v1/tags/write", { writes: [{ path: tagPath, value: 42 }] });
    expect(write.ok(), await write.text()).toBeTruthy();
    await expect(panel.getByTestId("diag-value")).toHaveText("42");
    await expect(panel.getByTestId("diag-quality")).toHaveText("Good");
  } finally {
    const token = await csrf(page);
    const stored = await page.request.get(`/api/v1/config/tags/gateway/Tag/${name}`);
    if (stored.ok()) {
      const version = ((await stored.json()) as { metadata: { version: number } }).metadata.version;
      await page.request.delete(`/api/v1/config/tags/gateway/Tag/${name}`, { headers: { "X-CSRF-Token": token, "If-Match": String(version) } });
    }
  }
});
