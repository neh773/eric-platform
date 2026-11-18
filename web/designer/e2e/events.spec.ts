import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { canvasNode, openPanel, openStudio, uniqueProject } from "./studio";

/**
 * D-5: event handlers authored in the Designer run in a session at /hmi/<project>/. Event scripts run on the gateway
 * as the signed-in user, get the DOM event in `event.payload`, and never run on the design canvas.
 */

async function memoryTag(request: APIRequestContext, csrf: string, path: string, dataType: string, value: unknown): Promise<void> {
  const name = path.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  const res = await request.put(`/api/v1/config/tags/gateway/Tag/${name}`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name, module: "tags", version: 0 }, spec: { path, type: "memory", dataType, value } },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function tagValue(request: APIRequestContext, csrf: string, path: string): Promise<unknown> {
  const res = await request.post("/api/v1/tags/read", { headers: { "X-CSRF-Token": csrf }, data: { paths: [path] } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { values: { value: unknown }[] }).values[0]?.value;
}

/** Adds a button to the open view, names its text, and opens its onClick actions in the Inspector. */
async function addButton(page: Page, text: string) {
  await openPanel(page, "Add");
  await page.getByRole("button", { name: "Add button" }).click();
  const inspector = page.getByTestId("inspector");
  await inspector.getByRole("textbox", { name: "Text", exact: true }).fill(text);
  await inspector.getByRole("button", { name: "onClick: no actions" }).click();
  return inspector.getByRole("region", { name: "Actions for onClick" });
}

async function addAction(actions: ReturnType<Page["getByRole"]>, type: string) {
  await actions.getByRole("combobox", { name: "New onClick action type" }).selectOption({ label: type });
  await actions.getByRole("button", { name: "+ Add action" }).click();
}

async function save(page: Page) {
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /unsaved|saved|Saving|conflict/i })).toHaveText("All changes saved");
}

test("an onClick script action added in the Designer runs in the session with the click's payload", async ({ page }) => {
  const project = uniqueProject("events");
  const csrf = await openStudio(page, project);
  const folder = uniqueProject("d5");
  const clicked = `[default]${folder}/clicked`;
  await memoryTag(page.request, csrf, `${folder}/clicked`, "String", "none");

  const actions = await addButton(page, "Record click");
  await addAction(actions, "Script");
  const action = actions.getByRole("listitem", { name: "onClick action 1: Script" });
  await action.getByRole("textbox", { name: "Hook name" }).fill("record-click");
  await action
    .getByRole("textbox", { name: "Script source" })
    .fill(`p = event.payload\nactions.writeTag("${clicked}", "%s %s %s" % (p.type, p.x > 0 and p.y > 0, p.clientX > p.x))`);

  // The design canvas runs no event script, saved or not.
  await save(page);
  const id = (await page.getByRole("application", { name: "View canvas" }).getAttribute("data-selected-node")) ?? "";
  const button = canvasNode(page, id);
  await expect(button).toContainText("Record click");
  const box = await button.boundingBox();
  if (!box) throw new Error("the new button is not on the canvas");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(500);
  expect(await tagValue(page.request, csrf, clicked)).toBe("none");

  // Saved with its source in the view, which is where the gateway reads it from.
  const stored = await page.request.get(`/api/v1/config/designer/project/${project}/View/overview`);
  expect(JSON.stringify(((await stored.json()) as { spec: unknown }).spec)).toContain('"hook":"record-click"');

  await page.goto(`/hmi/${project}/`);
  const run = page.getByRole("button", { name: "Record click" });
  await expect(run).toBeVisible();
  await run.click({ position: { x: 12, y: 8 } });
  await expect.poll(() => tagValue(page.request, csrf, clicked), { timeout: 10_000 }).toBe("click True True");
});

test("a project script hook from the Scripts panel and a navigation action run from one click", async ({ page }) => {
  const project = uniqueProject("hooks");
  const csrf = await openStudio(page, project);
  const folder = uniqueProject("d5h");
  const count = `[default]${folder}/count`;
  await memoryTag(page.request, csrf, `${folder}/count`, "Int32", 0);

  // The hook, typed with the editor's keys: Tab indents inside the text area.
  await openPanel(page, "Scripts");
  const panel = page.getByTestId("script-hooks-panel");
  await panel.getByRole("textbox", { name: "New hook name" }).fill("bump-count");
  await panel.getByRole("button", { name: "Add hook" }).click();
  const source = panel.getByRole("textbox", { name: "Hook source" });
  await source.click();
  await page.keyboard.type("def runAction(event, component):");
  await page.keyboard.press("Enter");
  await page.keyboard.press("Tab");
  await page.keyboard.type(`actions.writeTag("${count}", 7)`);
  await expect(source).toBeFocused();
  await expect(source).toHaveValue(`def runAction(event, component):\n    actions.writeTag("${count}", 7)`);
  // Esc then Tab leaves the editor.
  await page.keyboard.press("Escape");
  await page.keyboard.press("Tab");
  await expect(source).not.toBeFocused();

  const actions = await addButton(page, "Bump and go");
  await addAction(actions, "Script");
  await actions.getByRole("textbox", { name: "Hook name" }).fill("bump-count");
  await expect(actions.getByTestId("project-hook-note")).toHaveText('Runs the project script hook "bump-count".');
  await addAction(actions, "Navigation");
  await actions.getByRole("listitem", { name: "onClick action 2: Navigation" }).getByRole("textbox", { name: "Page URL" }).fill("/pumps");
  await save(page);

  const hooks = await page.request.get(`/api/v1/config/designer/project/${project}/ScriptHooks/script-hooks`);
  expect(hooks.ok(), await hooks.text()).toBeTruthy();
  expect(((await hooks.json()) as { spec: { hooks: Record<string, { source: string }> } }).spec.hooks["bump-count"]?.source).toContain("writeTag");

  await page.goto(`/hmi/${project}/`);
  await page.getByRole("button", { name: "Bump and go" }).click();
  await expect(page).toHaveURL(new RegExp(`/hmi/${project}/pumps$`));
  await expect.poll(() => tagValue(page.request, csrf, count), { timeout: 10_000 }).toBe(7);
});
