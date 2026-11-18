import { expect, test } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";

/** U-1: the audit log uses the page's width without pushing it past the window, and form checkboxes sit on their
 * label's text line. */

test("the audit table fills the page and keeps long values inside their cells", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await login(page);
  // A tag with a long name (names stop at 63 characters) gives the log a long target.
  const name = stamp("u1-" + "long-tag-name-".repeat(3));
  const token = await csrf(page);
  const put = await page.request.put(`/api/v1/config/tags/gateway/Tag/${name}`, {
    headers: { "X-CSRF-Token": token },
    data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name, module: "tags" }, spec: { path: `u1/${name}`, type: "memory", dataType: "Double", value: 1 } },
  });
  expect(put.ok(), await put.text()).toBeTruthy();
  await openSection(page, "Audit");
  const table = page.getByRole("table", { name: "Audit entries" });
  await expect(table).toContainText(name);
  const layout = await table.evaluate((element) => {
    const main = element.closest("main")!;
    const style = getComputedStyle(main);
    const content = main.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    const cells = Array.from(element.querySelectorAll<HTMLTableCellElement>("tbody td"));
    return {
      pageFits: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
      tableWidth: element.getBoundingClientRect().width,
      content,
      untitled: cells.filter((cell) => cell.textContent && cell.title !== cell.textContent).length,
      spilled: cells.filter((cell) => cell.classList.contains("cell-wrap") && cell.scrollWidth > cell.clientWidth + 1).length,
    };
  });
  expect(layout.pageFits).toBe(true);
  expect(layout.tableWidth).toBeGreaterThanOrEqual(layout.content - 1);
  expect(layout.untitled).toBe(0);
  expect(layout.spilled).toBe(0);
});

test("checkboxes sit beside their label text, not above it", async ({ page }) => {
  await login(page);
  await openSection(page, "Network");
  const check = page.getByRole("checkbox", { name: "Serve HTTPS" });
  await expect(check).toBeVisible();
  const offset = await check.evaluate((input) => {
    const label = input.closest("label")!;
    const text = Array.from(label.childNodes).find((node) => node !== input && (node.textContent ?? "").trim())!;
    const range = document.createRange();
    range.selectNodeContents(text);
    const a = input.getBoundingClientRect();
    const b = range.getBoundingClientRect();
    return { dy: Math.abs(a.top + a.height / 2 - (b.top + b.height / 2)), beside: b.left >= a.right };
  });
  expect(offset.dy).toBeLessThanOrEqual(1.5);
  expect(offset.beside).toBe(true);
});
