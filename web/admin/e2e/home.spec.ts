import { expect, test } from "@playwright/test";
import { csrf, login, stamp } from "./gateway";
import { withRole } from "./status";

/**
 * GAT-023: the gateway's root opens the home page. While setup is not finished it shows the first-run steps; once an
 * administrator finishes them it shows launch links for the designer and the HMI and the launchable projects, and
 * those links open the designer and an HMI session. A read-only role gets no designer link and no setup steps.
 */

test("first-run steps, then launch links that open the designer and the HMI", async ({ page }) => {
  await login(page);
  const token = await csrf(page);
  // Back to the first-run state, whatever earlier specs did.
  const reset = await page.request.put("/api/v1/home/setup", { data: { complete: false }, headers: { "X-CSRF-Token": token } });
  expect(reset.ok(), await reset.text()).toBeTruthy();
  const project = stamp("home");
  const hidden = stamp("home-off");
  for (const body of [{ name: project, title: "Home floor" }, { name: hidden, enabled: false }]) {
    const res = await page.request.post("/api/v1/projects", { data: body, headers: { "X-CSRF-Token": token } });
    expect(res.status(), await res.text()).toBe(201);
  }

  await page.goto("/");
  await expect(page).toHaveURL(/\/admin\/home$/);
  const steps = page.getByTestId("first-run");
  await expect(steps).toBeVisible();
  await expect(steps.locator('[data-step="project"]')).toHaveAttribute("data-done", "yes");
  // Launch links show during setup too (0.1.1), so a new user can open the designer before finishing.
  await expect(page.getByTestId("launch")).toBeVisible();

  await page.getByRole("button", { name: "Finish setup" }).click();
  const launch = page.getByTestId("launch");
  await expect(launch).toBeVisible();
  await expect(steps).toHaveCount(0);
  await expect(launch.getByRole("link", { name: "Control Canvas", exact: true })).toHaveAttribute("href", "/studio/");
  await expect(launch.getByRole("link", { name: "HMI sessions", exact: true })).toHaveAttribute("href", "/hmi/");
  await expect(launch.getByRole("link", { name: `Open ${hidden} session` })).toHaveCount(0);

  // The project's links open its HMI session and the designer on it.
  await launch.getByRole("link", { name: `Open ${project} session` }).click();
  await expect(page).toHaveURL(new RegExp(`/hmi/${project}/`));
  // The session opened the project (signed in, launchable); it has no views yet, so it says so.
  await expect(page.locator('[data-hmi-state="missing"]')).toBeVisible();
  await page.goto("/admin/home");
  await page.getByTestId("launch").getByRole("link", { name: `Edit ${project} in Control Canvas` }).click();
  await expect(page).toHaveURL(new RegExp(`/studio\\?project=${project}`));
  await expect(page.getByText("Control Canvas").first()).toBeVisible();
});

test("a read-only role sees projects and the HMI link, but no designer link or setup", async ({ page, browser }) => {
  await login(page);
  const token = await csrf(page);
  const res = await page.request.put("/api/v1/home/setup", { data: { complete: false }, headers: { "X-CSRF-Token": token } });
  expect(res.ok()).toBeTruthy();
  await withRole(browser, page, "operator", async (reader) => {
    await reader.goto("/admin/home");
    const launch = reader.getByTestId("launch");
    await expect(launch).toBeVisible();
    await expect(reader.getByTestId("first-run")).toHaveCount(0);
    await expect(launch.getByRole("link", { name: "HMI sessions", exact: true })).toBeVisible();
    await expect(launch.getByRole("link", { name: "Control Canvas", exact: true })).toHaveCount(0);
    await expect(launch.getByRole("link", { name: /in Control Canvas/ })).toHaveCount(0);
  });
  await page.request.put("/api/v1/home/setup", { data: { complete: true }, headers: { "X-CSRF-Token": token } });
});
