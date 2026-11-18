import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * Event streams are created, changed, tried, and removed from the Event Streams page.
 * Doc source: FEATURES MES-003, MES-006, MES-008.
 */

test("creates, edits, tries, and deletes an event stream", async ({ page }) => {
  const name = stamp("stream");
  await login(page);
  await openSection(page, "Event Streams");
  const main = page.getByRole("main");
  await main.getByRole("button", { name: "New event stream" }).click();
  await main.getByLabel("Stream name").fill(name);
  await main.getByLabel("Source").selectOption("listener");
  await main.getByLabel("Handler message").fill("heard {event.data}");
  await main.getByRole("button", { name: "Save stream" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  await expect(main.getByRole("button", { name: "Send test event" })).toBeVisible();
  await main.getByRole("textbox", { name: "Test event" }).fill('{"n":1}');
  await main.getByRole("button", { name: "Send test event" }).click();
  await expect(main.getByRole("status")).toContainText(`Sent a test event to ${name}.`);
  await expect(main.getByTestId("events-received")).toHaveText("1");

  await main.getByLabel("Handler message").fill("again {event.data}");
  await main.getByRole("button", { name: "Save stream" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  await main.getByRole("button", { name: "Edit as JSON" }).click();
  await expect(main.getByLabel("Stream JSON")).toHaveValue(/again \{event\.data\}/);
  await main.getByRole("button", { name: "Edit as form" }).click();

  await main.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
  await expect(main.getByRole("cell", { name, exact: true })).toHaveCount(0);
});
