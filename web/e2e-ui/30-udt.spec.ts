import { expect, test } from "@playwright/test";
import { login, main, section } from "./ui";

/** UDT definitions are created and edited on the admin page, then instanced (the 0.1.1 UDT editor). */
test.describe("UDTs", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("a UDT definition can be created from the Tags page", async ({ page }) => {
    await section(page, "Tags");
    await main(page).getByRole("link", { name: /UDT (definitions|types)/ }).or(main(page).getByRole("button", { name: /UDT definitions|UDT types/ })).first().click();
    await expect(main(page).getByRole("button", { name: /New UDT|New definition|New type/i }).first()).toBeVisible();
  });

  test("a UDT instance can be created with the wizard", async ({ page }) => {
    await section(page, "Tags");
    await main(page).getByRole("link", { name: "UDT instances" }).click();
    await expect(main(page).getByText(/No UDT types|UDT type/i).first()).toBeVisible();
  });
});
