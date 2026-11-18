import { expect, test } from "@playwright/test";
import { canvasNode, openStudio, selectOnCanvas, setTag, uniqueProject } from "./studio";

/**
 * D-3: bind a component property to an expression in the Inspector, add a transform, and see the rendered view change.
 */
test("binds a label to an expression with a format transform and the view shows the result", async ({ page }) => {
  await openStudio(page, uniqueProject("bindings"));
  // The Studio canvas is the live view (D-1).
  const title = canvasNode(page, "title");
  await expect(title).toContainText("North tank");

  await selectOnCanvas(page, "title");
  const inspector = page.getByTestId("inspector");
  await inspector.getByRole("button", { name: "Bind Text" }).click();
  const editor = inspector.getByRole("region", { name: "Binding for text" });
  await editor.getByRole("radio", { name: "Expression", exact: true }).click();

  const expression = editor.getByRole("textbox", { name: "Expression" });
  await expression.fill("{[default]plant/tank/level} * (");
  await expect(editor.getByRole("alert")).toContainText("unexpected token");
  await expression.fill("'Level ' + {[default]plant/tank/level} * 2");
  await expect(editor.getByRole("alert")).toHaveCount(0);
  await expect(editor.getByRole("status", { name: "Binding value" })).toHaveText('Value "Level 124.8"');
  await expect(title).toHaveText("Level 124.8");

  await setTag(page, "plant/tank/level", 10);
  await expect(title).toHaveText("Level 20");
  await expect(editor.getByRole("status", { name: "Binding value" })).toHaveText('Value "Level 20"');

  // A numeric result needs a format transform before the label shows it as text.
  await expression.fill("{[default]plant/tank/level} * 2");
  await editor.getByRole("button", { name: "+ Format" }).click();
  await editor.getByRole("textbox", { name: "Pattern" }).fill("0.00 m");
  await expect(editor.getByLabel("Step preview")).toHaveText('In 20 → Out "20.00 m"');
  await expect(title).toContainText("20.00 m");

  await editor.getByRole("button", { name: "+ Script" }).click();
  await editor.getByRole("textbox", { name: "Hook name" }).fill("shout");
  await expect(editor.getByText("Script runs on the gateway; not connected here.")).toBeVisible();
  await expect(title).toHaveAttribute("data-quality", "bad");
  // The design-mode canvas is inert (outside the accessibility tree), so read the quality flag by class.
  const flag = title.locator(".oip-quality-flag");
  await expect(flag).toHaveText("Script not connected");
  await expect(flag).toHaveAttribute("data-code", "bad_not_connected");
  await editor.getByRole("button", { name: "Remove Script transform 2" }).click();
  await expect(title).toContainText("20.00 m");

  await editor.getByRole("button", { name: "Close binding editor" }).click();
  await expect(inspector.getByRole("button", { name: "Text: Expression binding" })).toContainText('"20.00 m"');
});
