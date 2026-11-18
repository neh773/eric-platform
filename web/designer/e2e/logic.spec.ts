import { expect, test, type Locator } from "@playwright/test";
import { signIn, uniqueProject } from "./studio";

test("creates a program, shows a compile error, deploys, and forces a value", async ({ page }) => {
  const project = uniqueProject("logic");
  const csrf = await signIn(page.request);
  const created = await page.request.post("/api/v1/projects", {
    headers: { "X-CSRF-Token": csrf },
    data: { name: project, title: "Logic line" },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  await page.goto(`/?project=${project}`);
  await page.getByRole("button", { name: "Logic", exact: true }).click();
  await page.getByRole("textbox", { name: "Program name" }).fill("Pump");
  await page.getByRole("button", { name: "New program" }).click();
  const editor = page.getByRole("textbox", { name: "Structured Text" });
  await expect(editor).toContainText("PROGRAM Pump");
  await replaceEditor(editor, "PROGRAM Pump\nVAR\n  not_a_keyword : BOOL;\nEND_VAR\nEND_PROGRAM\n");
  await page.getByRole("button", { name: "Check program" }).click();
  await expect(page.getByRole("list", { name: "Problems" })).toContainText("Line 3, column 5");

  await replaceEditor(editor, "PROGRAM Pump\nVAR\n  Motor : BOOL;\nEND_VAR\nEND_PROGRAM\n");
  await page.getByRole("button", { name: "Add mapping" }).click();
  await page.getByRole("textbox", { name: "Variable 0" }).fill("Motor");
  await page.getByRole("textbox", { name: "Tag 0" }).fill("pid/motor");
  await page.getByRole("button", { name: "Compile and deploy" }).click();
  await expect(page.locator("[aria-label='Logic status']")).toHaveText("running");
  await expect(page.getByRole("list", { name: "Watched variables" })).toContainText("Motor");

  await page.getByRole("button", { name: "Force", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Forces are active" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Watched variables" })).toContainText("forced");

  await page.getByRole("button", { name: "Unforce" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Forces are active" })).toHaveCount(0);
});

/** Writes through the editor document so Check saves the same text the page shows. */
async function replaceEditor(editor: Locator, text: string) {
  const host = editor.locator("xpath=ancestor::div[contains(@class,'oip-st-editor')]");
  await host.evaluate((node, value) => {
    const view = (node as HTMLElement & { oipEditor?: { dispatch: (change: unknown) => void; state: { doc: { length: number } } } }).oipEditor;
    if (!view) throw new Error("Structured Text editor is not ready");
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
  }, text);
  await expect(editor).toContainText(text.split("\n")[2]!.trim());
}
