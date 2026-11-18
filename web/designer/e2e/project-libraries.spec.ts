import { expect, test, type Page } from "@playwright/test";
import { openStudio, signIn, uniqueProject } from "./studio";

/** PRO-001, DES-011: two projects with a script library module of the same name each run their own code. */

async function addLibrary(page: Page, project: string, source: string): Promise<string> {
  const csrf = await openStudio(page, project);
  await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: "Tools", exact: true }).click();
  await page.getByRole("tablist", { name: "Designer tools" }).getByRole("tab", { name: "Script libraries", exact: true }).click();
  const tool = page.getByTestId("script-libraries");
  await tool.getByRole("textbox", { name: "New library name", exact: true }).fill("calc");
  await tool.getByRole("button", { name: "Add library", exact: true }).click();
  const region = tool.getByRole("region", { name: "Library calc", exact: true });
  await region.getByRole("textbox", { name: "Library source", exact: true }).fill(source);
  await region.getByRole("button", { name: "Save library", exact: true }).click();
  await expect(tool.getByRole("status")).toHaveText("Saved calc");
  return csrf;
}

async function seedSession(page: Page, csrf: string, project: string, tagPath: string, tagName: string): Promise<void> {
  const tag = await page.request.put(`/api/v1/config/tags/gateway/Tag/${tagName}`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name: tagName, module: "tags", version: 0 }, spec: { path: tagPath, type: "memory", dataType: "String", value: "none" } },
  });
  expect(tag.ok(), await tag.text()).toBeTruthy();
  const source = `import calc\nactions.writeTag("[default]${tagPath}", calc.answer())`;
  const document = {
    params: {},
    width: 300,
    height: 100,
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        {
          id: "go",
          kind: "component",
          name: "go",
          type: "button",
          frame: { x: 8, y: 8, width: 200, height: 40 },
          props: { text: "Run calc" },
          events: [{ event: "onClick", actions: [{ kind: "script", hook: "run-calc", source }] }],
        },
      ],
    },
  };
  const view = await page.request.put(`/api/v1/config/designer/project/${project}/View/home`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "View", metadata: { name: "home", module: "designer" }, spec: { path: "home", title: "Home", document } },
  });
  expect(view.ok(), await view.text()).toBeTruthy();
  // The Studio's new project opens on its own first view; the session's root page shows this one.
  const pagesPath = `/api/v1/config/designer/project/${project}/PageConfig/page-config`;
  const existing = await page.request.get(pagesPath);
  const version = existing.ok() ? ((await existing.json()) as { metadata: { version: number } }).metadata.version : 0;
  const pages = await page.request.put(pagesPath, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "PageConfig", metadata: { name: "page-config", module: "designer", version }, spec: { pages: [{ url: "/", title: "Home", primaryView: "home" }] } },
  });
  expect(pages.ok(), await pages.text()).toBeTruthy();
}

async function tagValue(page: Page, csrf: string, tagPath: string): Promise<unknown> {
  const res = await page.request.post("/api/v1/tags/read", { headers: { "X-CSRF-Token": csrf }, data: { paths: [`[default]${tagPath}`] } });
  return ((await res.json()) as { values: { value: unknown }[] }).values[0]?.value;
}

test("each project's script library module runs its own code", async ({ page }) => {
  const a = uniqueProject("liba");
  const b = uniqueProject("libb");
  const folder = uniqueProject("pro001");
  const csrfA = await addLibrary(page, a, "def answer():\n    return 'from-a'\n");
  const csrfB = await addLibrary(page, b, "def answer():\n    return 'from-b'\n");
  const csrf = csrfB || csrfA || (await signIn(page.request));
  await seedSession(page, csrf, a, `${folder}/a`, `${folder}-a`);
  await seedSession(page, csrf, b, `${folder}/b`, `${folder}-b`);

  await page.goto(`/hmi/${a}/`);
  await page.getByRole("button", { name: "Run calc", exact: true }).click();
  await expect.poll(() => tagValue(page, csrf, `${folder}/a`), { timeout: 10_000 }).toBe("from-a");
  await page.goto(`/hmi/${b}/`);
  await page.getByRole("button", { name: "Run calc", exact: true }).click();
  await expect.poll(() => tagValue(page, csrf, `${folder}/b`), { timeout: 10_000 }).toBe("from-b");
});
