import { expect, test } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/**
 * 0.1.1: a database connection is added, tested, edited and deleted from the Databases page form, with no API call
 * from the test. The password field is masked, the password is never shown again, and an empty field on edit keeps
 * the stored one.
 */
const DB_PASSWORD = "E2e-Form-Password-3";

test("adds, tests, edits and deletes a database connection from the form", async ({ page }) => {
  await login(page);
  const name = stamp("e2eform");
  page.on("dialog", (dialog) => void dialog.accept());
  await openSection(page, "Databases");

  await page.getByRole("button", { name: "New connection" }).click();
  const form = page.getByRole("form", { name: "Database connection" });
  await form.getByLabel("Connection name").fill(name);
  await form.getByLabel("Database type").selectOption("postgres");
  await expect(form.getByLabel("Port")).toHaveValue("5432");
  await form.getByLabel("Host").fill("db.plant.local");
  await form.getByLabel("Database", { exact: true }).fill("historian");
  await expect(form.getByTestId("db-url-preview")).toContainText("jdbc:postgresql://db.plant.local:5432/historian");
  // The test gateway has no PostgreSQL; a SQLite file is the database that answers here.
  await form.getByLabel("Database type").selectOption("sqlite");
  await form.getByLabel("Database file").fill(`e2e/${name}.db`);
  await form.getByLabel("Username").fill("eric");
  const password = form.getByLabel("Password", { exact: true });
  await expect(password).toHaveAttribute("type", "password");
  await password.fill(DB_PASSWORD);
  await form.getByLabel("Description").fill("Form-made connection");
  await form.getByRole("button", { name: "Create and test" }).click();

  await expect(page.getByRole("status").filter({ hasText: `Connection ${name} was saved and is valid.` })).toBeVisible();
  const row = page.getByRole("table", { name: "Database connections" }).getByRole("row", { name: new RegExp(name) });
  await expect(row).toContainText("sqlite");
  await expect(row).toContainText("Stored");
  await expect(page.getByTestId(`db-status-${name}`)).toHaveText("Valid");
  await expect(page.locator("body")).not.toContainText(DB_PASSWORD);

  await page.getByRole("button", { name: `Test ${name}`, exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: `Connection ${name} is valid.` })).toBeVisible();

  await page.getByRole("button", { name: `Edit ${name}`, exact: true }).click();
  const edit = page.getByRole("form", { name: "Database connection" });
  await expect(edit.getByLabel("Connection name")).toBeDisabled();
  await expect(edit.getByLabel("Password", { exact: true })).toHaveValue("");
  await expect(edit.getByLabel("Password", { exact: true })).toHaveAttribute("placeholder", /Stored/);
  await edit.getByLabel("Description").fill("Edited from the form");
  await edit.getByRole("button", { name: "Save and test" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Connection ${name} was saved` })).toBeVisible();
  await expect(row).toContainText("Stored");

  const stored = await page.request.get(`/api/v1/db/connections/${name}`);
  const body = (await stored.json()) as Record<string, unknown>;
  expect(body.description).toBe("Edited from the form");
  expect(body.passwordSet).toBe(true);
  expect(JSON.stringify(body)).not.toContain(DB_PASSWORD);

  await page.getByRole("button", { name: `Delete ${name}`, exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: `Connection ${name} was deleted.` })).toBeVisible();
  await expect(page.getByRole("table", { name: "Database connections" })).not.toContainText(name);
  const gone = await page.request.get(`/api/v1/db/connections/${name}`, { headers: { "X-CSRF-Token": await csrf(page) } });
  expect(gone.status()).toBe(404);
});
