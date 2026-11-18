import { expect, test } from "@playwright/test";
import { canvasNode, openPanel, openStudio, uniqueProject } from "./studio";

/**
 * P4-07 (HMP-093): a component's style is edited in the Studio's style editor (a CSS subset), saved with the view,
 * and applied in a `/hmi` session. CSS text outside the subset is refused and changes nothing.
 */
test("a style edited in the Studio is saved with the view and shows in a session", async ({ page }) => {
  const project = uniqueProject("style");
  await openStudio(page, project);
  await openPanel(page, "Add");
  await page.getByRole("button", { name: "Add label", exact: true }).click();
  const inspector = page.getByTestId("inspector");
  await inspector.getByRole("textbox", { name: "Text", exact: true }).fill("Styled tank");
  const id = (await page.getByRole("application", { name: "View canvas" }).getAttribute("data-selected-node")) ?? "";
  const node = canvasNode(page, id);
  await expect(node).toContainText("Styled tank");

  const editor = inspector.getByRole("group", { name: "Style" });
  const css = editor.getByRole("textbox", { name: "Style CSS" });

  // Outside the subset: refused, nothing changes.
  await css.fill("color: red; position: fixed; background: url(https://example.test/x.png)");
  await editor.getByRole("button", { name: "Apply CSS" }).click();
  await expect(editor.getByRole("alert")).toContainText("position is not a supported style property");
  await expect(editor.getByRole("alert")).toContainText("is not allowed");
  await expect(node).not.toHaveCSS("color", "rgb(255, 0, 0)");

  await css.fill("color: rgb(192, 57, 43); border-style: solid; border-width: 3px; border-color: #0000ff; border-radius: 6px; font-weight: 700");
  await editor.getByRole("button", { name: "Apply CSS" }).click();
  await expect(editor.getByRole("alert")).toHaveCount(0);
  // The field editor too: add a property and set it.
  await editor.getByRole("combobox", { name: "Style property to add" }).selectOption("textAlign");
  await editor.getByRole("button", { name: "Add" }).click();
  await editor.getByRole("combobox", { name: "Text align" }).selectOption("center");
  await expect(node).toHaveCSS("color", "rgb(192, 57, 43)");
  await expect(node).toHaveCSS("border-top-left-radius", "6px");
  await expect(node).toHaveCSS("text-align", "center");

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /unsaved|saved|Saving|conflict/i })).toHaveText("All changes saved");
  const stored = (await (await page.request.get(`/api/v1/config/designer/project/${project}/View/overview`)).json()) as {
    spec: { document: { root: { children: Array<{ id: string; style?: unknown }> } } };
  };
  expect(stored.spec.document.root.children.find((child) => child.id === id)?.style).toEqual({
    color: "rgb(192, 57, 43)",
    fontWeight: 700,
    textAlign: "center",
    borderWidth: 3,
    borderStyle: "solid",
    borderColor: "#0000ff",
    borderRadius: 6,
  });

  await page.goto(`/hmi/${project}/`);
  const shown = page.locator(".oip-node[data-component='label']").filter({ hasText: "Styled tank" });
  await expect(shown).toBeVisible();
  await expect(shown).toHaveCSS("color", "rgb(192, 57, 43)");
  await expect(shown).toHaveCSS("border-top-style", "solid");
  await expect(shown).toHaveCSS("border-top-width", "3px");
  await expect(shown).toHaveCSS("border-top-color", "rgb(0, 0, 255)");
  await expect(shown).toHaveCSS("border-top-left-radius", "6px");
  await expect(shown).toHaveCSS("font-weight", "700");
  await expect(shown).toHaveCSS("text-align", "center");
});
