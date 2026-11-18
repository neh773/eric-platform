import { expect, test } from "@playwright/test";
import { waitForTestHook } from "./studio";

test("500 bound components render a tag batch", async ({ page }) => {
  await page.goto("/bench");
  await page.waitForFunction(() => {
    const nodes = document.querySelectorAll("[data-testid='numeric-value']");
    return nodes.length === 500 && [...nodes].every((node) => node.getAttribute("data-value") === "0");
  });
  await waitForTestHook(page);
  const elapsed = await page.evaluate(async () => {
    const start = performance.now();
    for (let index = 0; index < 500; index += 1) window.__oipTest!.setTag(`bench/${index}`, 1000 + index);
    const ready = await new Promise<number>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error("500 components did not update")), 2000);
      const check = () => {
        const nodes = document.querySelectorAll("[data-testid='numeric-value']");
        const done = nodes.length === 500 && [...nodes].every((node, index) => node.getAttribute("data-value") === String(1000 + index) && node.textContent === String(1000 + index));
        if (done) {
          window.clearTimeout(timeout);
          resolve(performance.now() - start);
          return;
        }
        window.requestAnimationFrame(check);
      };
      window.requestAnimationFrame(check);
    });
    return ready;
  });
  console.log(`render 500 bound components ms: ${elapsed.toFixed(1)}`);
  expect(elapsed).toBeLessThan(1000);
});

test("50 embedded views render a tag batch", async ({ page }) => {
  await page.goto("/bench-embed");
  await page.waitForFunction(() => {
    const nodes = document.querySelectorAll("[data-testid='numeric-value']");
    return nodes.length === 50 && [...nodes].every((node) => node.getAttribute("data-value") === "0");
  });
  await waitForTestHook(page);
  const elapsed = await page.evaluate(async () => {
    const start = performance.now();
    for (let index = 0; index < 50; index += 1) window.__oipTest!.setTag(`embed/${index}`, 1000 + index);
    const ready = await new Promise<number>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error("50 embedded views did not update")), 2000);
      const check = () => {
        const nodes = document.querySelectorAll("[data-testid='numeric-value']");
        const done = nodes.length === 50 && [...nodes].every((node, index) => node.getAttribute("data-value") === String(1000 + index) && node.textContent === String(1000 + index));
        if (done) {
          window.clearTimeout(timeout);
          resolve(performance.now() - start);
          return;
        }
        window.requestAnimationFrame(check);
      };
      window.requestAnimationFrame(check);
    });
    return ready;
  });
  console.log(`render 50 embedded views ms: ${elapsed.toFixed(1)}`);
  expect(elapsed).toBeLessThan(1000);
});
