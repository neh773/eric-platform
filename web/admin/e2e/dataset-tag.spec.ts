import { expect, test } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/**
 * Dataset memory tag in the admin tag editor (TAG-014). The data type is on the create form,
 * a JSON value shows in the live value, and a row the column type cannot hold is refused.
 */

test("creates a dataset tag and refuses a write that breaks a column type", async ({ page }) => {
  const folder = `Ds_${stamp("ds").replace(/-/g, "_")}`;
  const tagPath = `[default]${folder}/Table`;
  const initial = JSON.stringify({
    columns: [
      { name: "id", type: "Int32" },
      { name: "name", type: "String" },
    ],
    rows: [[7, "pump-17"]],
  });
  await login(page);
  await openSection(page, "Tags");

  await page.getByRole("button", { name: "New tag" }).click();
  const form = page.getByTestId("create-tag");
  await form.getByLabel("Name").fill(`${folder}/Table`);
  await form.getByLabel("Tag type").selectOption("memory");
  await form.getByLabel("Data type", { exact: true }).selectOption("Dataset");
  await form.getByLabel("Initial value").fill(initial);
  await form.getByRole("button", { name: "Create tag" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Created ${tagPath}.` })).toBeVisible();

  const editor = page.getByTestId("tag-editor");
  const resource = ((await editor.getByTestId("tag-version").textContent()) ?? "").match(/Resource (\S+)/)![1]!;
  try {
    await expect(editor.getByTestId("tag-live-value")).toContainText("pump-17");
    await expect(editor.getByTestId("tag-live-value")).toContainText("Int32");
    await expect(editor.getByTestId("tag-live-quality")).toHaveText("Good");

    await editor.getByLabel("Value to write").fill('{"columns":[{"name":"id","type":"Int32"}],"rows":[["nope"]]}');
    await editor.getByRole("button", { name: "Write" }).click();
    await expect(editor.getByTestId("tag-write-result")).toContainText("Write refused");
    await expect(editor.getByTestId("tag-live-value")).toContainText("pump-17");

    await editor.getByLabel("Value to write").fill(JSON.stringify({
      columns: [
        { name: "id", type: "Int32" },
        { name: "name", type: "String" },
      ],
      rows: [[8, "pump-18"]],
    }));
    await editor.getByRole("button", { name: "Write" }).click();
    await expect(editor.getByTestId("tag-write-result")).toHaveText("Written.");
    await expect(editor.getByTestId("tag-live-value")).toContainText("pump-18");
    await expect(editor.getByTestId("tag-live-value")).toContainText("String");
    await expect(editor.getByTestId("tag-live-quality")).toHaveText("Good");
  } finally {
    const token = await csrf(page);
    const stored = await page.request.get(`/api/v1/config/tags/gateway/Tag/${resource}`);
    if (stored.ok()) {
      const version = ((await stored.json()) as { metadata: { version: number } }).metadata.version;
      await page.request.delete(`/api/v1/config/tags/gateway/Tag/${resource}`, {
        headers: { "X-CSRF-Token": token, "If-Match": String(version) },
      });
    }
  }
});
