import { expect, test } from "@playwright/test";
import { openStudio, uniqueProject } from "./studio";

test("the designer console runs a script on the gateway", async ({ page }) => {
  await openStudio(page, uniqueProject("console"));
  await page.getByRole("tab", { name: "Console" }).click();
  await page.getByRole("textbox", { name: "Python" }).fill("print('console-ok')\n_result = 6 + 1\n");
  await page.getByRole("button", { name: "Run" }).click();
  await expect(page.getByTestId("console-output")).toContainText("console-ok");
  await expect(page.getByTestId("console-output")).toContainText("result: 7");
  await expect(page.getByTestId("scope-hints")).toContainText("system.tag.readBlocking");
  await expect(page.getByTestId("scope-hints")).toContainText("system.opcua.listConnections");
});

/**
 * SCR-042: a project library module saved on the gateway is importable from the designer console, and a save of
 * the module is what the next console run sees.
 */
test("the designer console calls a project library module and sees its next save", async ({ page }) => {
  const csrf = await openStudio(page, uniqueProject("console-lib"));
  const name = `e2e-lib-${Date.now().toString(36)}`;
  const module = name.replace(/-/g, "_");
  const path = `/api/v1/config/scripting/gateway/ScriptModule/${name}`;
  const save = async (word: string, version: number) => {
    const res = await page.request.put(path, {
      data: {
        apiVersion: "oip/v1",
        kind: "ScriptModule",
        metadata: { name, module: "scripting", version },
        spec: { source: `def greet(who):\n    return '${word} ' + who\n` },
      },
      headers: { "X-CSRF-Token": csrf },
    });
    expect(res.ok(), await res.text()).toBeTruthy();
    return ((await res.json()) as { metadata: { version: number } }).metadata.version;
  };
  const first = await save("hello", 0);
  try {
    await page.getByRole("tab", { name: "Console" }).click();
    await page.getByRole("textbox", { name: "Python" }).fill(`import ${module}\n_result = ${module}.greet('designer')\n`);
    await page.getByRole("button", { name: "Run", exact: true }).click();
    await expect(page.getByTestId("console-output")).toContainText("result: hello designer");

    await save("welcome", first);
    await page.getByRole("button", { name: "Run", exact: true }).click();
    await expect(page.getByTestId("console-output")).toContainText("result: welcome designer");
  } finally {
    await page.request.delete(path, { headers: { "X-CSRF-Token": csrf } });
  }
});
