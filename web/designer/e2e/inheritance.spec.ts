import { expect, test, type APIRequestContext } from "@playwright/test";
import { openStudio, signIn, uniqueProject } from "./studio";

/**
 * PRO-002 in the Designer: a child project shows its parent's views marked as inherited and read-only, and an
 * override saves the child's own copy; deleting that copy shows the parent's again. PRO-004: a disable made through
 * the gateway shows in the open Designer without a reload.
 */

async function createProject(request: APIRequestContext, csrf: string, body: Record<string, unknown>) {
  const res = await request.post("/api/v1/projects", { headers: { "X-CSRF-Token": csrf }, data: body });
  expect(res.status(), await res.text()).toBe(201);
}

async function putView(request: APIRequestContext, csrf: string, project: string, name: string, title: string, children: unknown[] = []) {
  const res = await request.put(`/api/v1/config/designer/project/${project}/View/${name}`, {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "View",
      metadata: { name, module: "designer", version: 0 },
      spec: {
        path: name,
        title,
        document: {
          params: {},
          width: 400,
          height: 200,
          root: { id: "root", kind: "container", name: "Root", layout: "flex", props: { direction: "column" }, children },
        },
      },
    },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("inherited views are marked, read-only until overridden, and the override is saved in the child", async ({ page }) => {
  const base = uniqueProject("base");
  const child = uniqueProject("child");
  const csrf = await signIn(page.request);
  await createProject(page.request, csrf, { name: base, inheritable: true, runnable: false });
  await createProject(page.request, csrf, { name: child, parent: base, title: "Child line" });
  await putView(page.request, csrf, base, "shared", "Shared", [
    { id: "from-base", kind: "component", name: "Note", type: "label", props: { text: "From the base project" } },
  ]);
  await putView(page.request, csrf, child, "local", "Local");

  await openStudio(page, child, csrf);
  const tree = page.getByRole("navigation", { name: "Project" });
  const shared = tree.locator('[data-view-path="shared"]');
  await expect(shared).toHaveAttribute("data-inherited-from", base);
  await expect(shared).toContainText(`(inherited from ${base})`);
  await expect(tree.locator('[data-view-path="local"]')).not.toHaveAttribute("data-inherited-from", /.+/);
  await expect(page.getByTestId("project-status")).toContainText(`inherits from ${base}`);

  // Read-only: adding a component to the inherited view changes nothing and leaves nothing to save.
  await shared.click();
  const banner = page.getByTestId("inherited-banner");
  await expect(banner).toContainText(`Inherited from project ${base}`);
  const components = page.locator(".oip-canvas-render [data-component]");
  await expect(components).toHaveCount(1);
  await page.getByRole("button", { name: "Add label" }).click();
  await expect(components).toHaveCount(1);

  // Override: the view becomes the child's own and is saved into the child project.
  await banner.getByRole("button", { name: "Override in this project" }).click();
  await expect(banner).toHaveCount(0);
  await expect(shared).not.toHaveAttribute("data-inherited-from", /.+/);
  await page.getByRole("button", { name: "Add label" }).click();
  await expect(components).toHaveCount(2);
  const status = page.getByRole("status").filter({ hasText: /unsaved|saved|Saving/ });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(status).toHaveText("All changes saved");
  const own = await page.request.get(`/api/v1/config/designer/project/${child}/View/shared`);
  expect(own.ok(), await own.text()).toBeTruthy();
  const parentCopy = await page.request.get(`/api/v1/config/designer/project/${base}/View/shared`);
  const parentRoot = ((await parentCopy.json()) as { spec: { document: { root: { children: unknown[] } } } }).spec.document.root;
  expect(parentRoot.children).toHaveLength(1);

  // Deleting the override shows the parent's view, marked inherited, after a reload.
  const removed = await page.request.delete(`/api/v1/config/designer/project/${child}/View/shared`, { headers: { "X-CSRF-Token": csrf } });
  expect(removed.status()).toBe(204);
  await page.reload();
  await expect(tree.locator('[data-view-path="shared"]')).toHaveAttribute("data-inherited-from", base);

  // An HMI session of the child runs the inherited view, resolved the same way.
  await page.goto(`/hmi/${child}/shared`);
  await expect(page.getByText("From the base project")).toBeVisible();
  await openStudio(page, child, csrf);

  // A disable made elsewhere shows in the open Designer.
  const disabled = await page.request.patch(`/api/v1/projects/${child}`, { headers: { "X-CSRF-Token": csrf }, data: { enabled: false } });
  expect(disabled.ok(), await disabled.text()).toBeTruthy();
  await expect(page.getByTestId("project-status")).toContainText("disabled", { timeout: 10_000 });
});
