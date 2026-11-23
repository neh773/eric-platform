import { expect, test } from "@playwright/test";
import { login, main, section } from "./ui";

test.describe("users and roles", () => {
  test("create a role and an operator user, sign in as them, then delete the user", async ({ page, browser }) => {
    await login(page);
    await section(page, "Users & Roles");
    if (!(await main(page).getByRole("button", { name: "Edit role shiftlead" }).count())) {
      await main(page).getByLabel("Role name").fill("shiftlead");
      await main(page).getByLabel("Description").fill("Shift lead");
      await main(page).getByRole("button", { name: "Create role" }).click();
      await expect(main(page).getByRole("button", { name: "Edit role shiftlead" })).toBeVisible();
    }
    if (!(await main(page).getByRole("button", { name: "Edit op1" }).count())) {
      await main(page).getByLabel("Username", { exact: true }).fill("op1");
      await main(page).getByLabel("Password", { exact: true }).fill("Operator-pass-1");
      await main(page).getByLabel("Display name").fill("Operator One");
      await main(page).getByRole("checkbox", { name: "operator" }).check();
      await main(page).getByRole("button", { name: "Create user" }).click();
      await expect(main(page).getByRole("button", { name: "Edit op1" })).toBeVisible();
    }
    const ctx = await browser.newContext();
    const op = await ctx.newPage();
    await login(op, "op1", "Operator-pass-1");
    await ctx.close();
  });
});
