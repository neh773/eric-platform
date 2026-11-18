import { expect, test, type Page } from "@playwright/test";
import { mockJournalResponse, mockJournalRows } from "../src/alarmJournalMock";

/**
 * C-14: alarm journal table against a mocked GET /api/v1/alarms/journal (no gateway needed).
 *
 * The harness page renders only the journal table with `?journal=1`. Every /api request is answered by
 * the route below, so the spec does not depend on the e2e gateway's journal data.
 */

async function mockJournal(page: Page): Promise<URL[]> {
  const rows = mockJournalRows();
  const seen: URL[] = [];
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname !== "/api/v1/alarms/journal") {
      await route.fulfill({ status: 404, json: { detail: "not mocked" } });
      return;
    }
    seen.push(url);
    const { status, body } = mockJournalResponse(rows, url.pathname + url.search);
    await route.fulfill({ status, json: body });
  });
  return seen;
}

test("pages, filters, sorts and expands journal rows from the REST API", async ({ page }) => {
  const seen = await mockJournal(page);
  await page.goto("/?journal=1");
  const table = page.locator(".oip-alarm-journal");
  const rows = table.locator("tbody tr[data-journal-id]");
  const pager = page.getByRole("navigation", { name: "Journal pages" });

  await expect(pager.getByRole("status")).toHaveText("Rows 1–25 of 71 (page 1 of 3)");
  await expect(rows).toHaveCount(25);
  await expect(rows.first()).toHaveAttribute("data-event-type", "unshelve");
  await expect(rows.first()).toContainText("Unshelved");
  await expect(table).not.toContainText("Room/Temperature");
  const first = seen[seen.length - 1]!.searchParams;
  expect(first.get("start")).toBe("2026-10-28T00:00:00.000Z");
  expect(first.get("minPriority")).toBe("Low");

  await pager.getByRole("button", { name: "Next" }).click();
  await expect(pager.getByRole("status")).toHaveText("Rows 26–50 of 71 (page 2 of 3)");
  expect(seen[seen.length - 1]!.searchParams.get("offset")).toBe("25");

  // Event type filter goes to the gateway and returns to page 1.
  for (const name of ["Active", "Cleared", "Acknowledged"]) await page.getByRole("checkbox", { name }).uncheck();
  await expect(pager.getByRole("status")).toHaveText("Rows 1–2 of 2 (page 1 of 1)");
  await expect(rows).toHaveCount(2);
  expect(seen[seen.length - 1]!.searchParams.get("eventType")).toBe("shelve,unshelve");
  await page.getByRole("checkbox", { name: "Active" }).check();
  await page.getByRole("checkbox", { name: "Acknowledged" }).check();
  await page.getByRole("checkbox", { name: "Cleared" }).check();
  await expect(pager.getByRole("status")).toHaveText("Rows 1–25 of 71 (page 1 of 3)");

  // Priority range: only Critical (the pump).
  await page.getByLabel("Lowest priority").selectOption("Critical");
  await expect(pager.getByRole("status")).toHaveText("Rows 1–25 of 26 (page 1 of 2)");
  await expect(table.locator('tbody tr[data-journal-id]:not([data-priority="critical"])')).toHaveCount(0);

  // Oldest first from the header.
  await table.getByRole("button", { name: "Event time" }).click();
  await expect(table.getByRole("columnheader", { name: "Event time" })).toHaveAttribute("aria-sort", "ascending");
  await expect(rows.first()).toHaveAttribute("data-event-id", "ev-1");
  await expect(rows.first()).toHaveAttribute("data-event-type", "active");

  // Details for the first acknowledgement.
  const ack = table.locator('tbody tr[data-event-type="ack"]').first();
  await ack.click();
  await expect(ack).toHaveAttribute("aria-expanded", "true");
  const details = table.locator(".oip-alarm-journal-details dl");
  await expect(details).toContainText("ackNotes");
  await expect(details).toContainText("note 1");
  await expect(details).toContainText("operator1");

  // Search is local to the loaded page.
  const before = seen.length;
  await page.getByLabel("Search", { exact: true }).fill("note 5");
  await expect(rows).toHaveCount(1);
  expect(seen.length).toBe(before);
});

test("shows a gateway error and polls the recent range", async ({ page }) => {
  let fail = true;
  const rows = mockJournalRows();
  const seen: URL[] = [];
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    seen.push(url);
    if (fail) {
      await route.fulfill({ status: 403, json: { type: "urn:oip:problem:forbidden", detail: "missing permission tags.read" } });
      return;
    }
    const { status, body } = mockJournalResponse(rows, url.pathname + url.search);
    await route.fulfill({ status, json: body });
  });
  await page.goto("/?journal=1&mode=realtime");
  await expect(page.getByRole("alert")).toHaveText("Could not load the alarm journal: 403 missing permission tags.read");
  const start = Date.parse(seen[0]!.searchParams.get("start")!);
  expect(Math.abs(Date.now() - 60 * 60_000 - start)).toBeLessThan(30_000);
  expect(seen[0]!.searchParams.has("end")).toBe(false);

  fail = false;
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect.poll(() => seen.length).toBeGreaterThan(2);
  // The fixture rows are from a fixed morning, so a recent window is empty unless the clock is that morning.
  await expect(page.getByRole("navigation", { name: "Journal pages" }).getByRole("status")).toHaveText(/^Rows \d+–\d+ of \d+/);
});
