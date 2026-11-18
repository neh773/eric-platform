import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp, withViewer } from "./gateway";

/**
 * TAG-024: the UDT wizard previews N instances, refuses the whole batch when one row is bad,
 * then creates every instance in one request.
 */
async function createUdt(page: Page, name: string, path: string) {
  const token = await csrf(page);
  const res = await page.request.post("/api/v1/config/tags/gateway/Udt", {
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
    data: {
      apiVersion: "oip/v1",
      kind: "Udt",
      metadata: { name, module: "tags", version: 0 },
      spec: {
        path,
        parameters: [
          { name: "Gain", dataType: "Double", default: 2 },
          { name: "Device", dataType: "String" },
        ],
        members: [
          { name: "In", type: "memory", dataType: "Double", value: 5 },
          { name: "Out", type: "expression", dataType: "Double", expression: "{[.]In} * {Gain}" },
        ],
      },
    },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("previews, rejects a bad row, and creates UDT instances in one batch", async ({ page, browser }) => {
  const id = stamp("e2e");
  const typePath = `Types/Pump_${id.replace(/-/g, "_")}`;
  const folder = `Wizard_${id.replace(/-/g, "_")}`;
  await login(page);
  await createUdt(page, `pump-${id}`, typePath);

  await openSection(page, "Tags");
  await page.getByRole("navigation", { name: "Tags views" }).getByRole("link", { name: "UDT instances" }).click();
  const wizard = page.getByTestId("udt-wizard");
  await wizard.getByLabel("UDT definition").selectOption(typePath);
  await wizard.getByLabel("Folder").fill(folder);
  await wizard.getByLabel("Name pattern").fill("Pump_{n}");
  await wizard.getByLabel("Count").fill("3");
  await wizard.getByLabel("Device for Pump_01").fill("PLC1");
  await wizard.getByRole("button", { name: "Copy row 1 to all Device" }).click();
  await expect(wizard.getByLabel("Device for Pump_03")).toHaveValue("PLC1");
  await wizard.getByLabel("Gain for Pump_02").fill("not-a-number");

  await wizard.getByRole("button", { name: "Preview" }).click();
  const plan = page.getByTestId("udt-plan");
  await expect(plan).toContainText("Invalid: nothing will be created.");
  await expect(plan.getByRole("row", { name: /Pump_02/ })).toContainText("parameter 'Gain'");
  await expect(wizard.getByRole("button", { name: /^Create/ })).toBeDisabled();

  await wizard.getByLabel("Gain for Pump_02").fill("4");
  await wizard.getByRole("button", { name: "Preview" }).click();
  await expect(plan).toContainText("Will create 3, skip 0.");
  await wizard.getByRole("button", { name: "Create 3 instances" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Created 3 instances." })).toBeVisible();

  const token = await csrf(page);
  const read = await page.request.post("/api/v1/tags/read", {
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
    data: { paths: [`${folder}/Pump_01/Out`, `${folder}/Pump_02/Out`, `${folder}/Pump_03/Out`] },
  });
  expect(read.ok()).toBeTruthy();
  const values = ((await read.json()) as { values: { value: number }[] }).values.map((v) => v.value);
  expect(values).toEqual([10, 20, 10]);

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Tags");
    await viewer.getByRole("navigation", { name: "Tags views" }).getByRole("link", { name: "UDT instances" }).click();
    await expect(viewer.getByTestId("udt-wizard-readonly")).toContainText("tags.write");
    await expect(viewer.getByRole("button", { name: "Preview" })).toHaveCount(0);
  });
});
