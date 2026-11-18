import { expect, test } from "@playwright/test";
import { waitForTestHook } from "./studio";

/** SPEC §7: a tag change is rendered in under 250 ms (p95 on this mock path). */
test("a mock tag change renders in under 250 ms", async ({ page }) => {
  await page.goto("/latency");
  const value = page.getByTestId("numeric-value");
  await expect(value).toHaveText("0.0");
  await waitForTestHook(page);
  const samples = await page.evaluate(async () => {
    const node = document.querySelector("[data-testid='numeric-value']");
    if (!node) throw new Error("numeric missing");
    const samples: number[] = [];
    for (let index = 0; index < 20; index += 1) {
      const next = (20 + index) / 10;
      const target = next.toFixed(1);
      const start = performance.now();
      window.__oipTest!.setTag("sim/level", next);
      const elapsed = await new Promise<number>((resolve, reject) => {
        const timeout = window.setTimeout(() => reject(new Error(`timed out waiting for ${target}`)), 2000);
        const check = () => {
          if (node.getAttribute("data-value") === String(next) && node.textContent === target) {
            window.clearTimeout(timeout);
            resolve(performance.now() - start);
            return;
          }
          window.requestAnimationFrame(check);
        };
        window.requestAnimationFrame(check);
      });
      samples.push(elapsed);
    }
    return samples;
  });
  const sorted = [...samples].sort((a, b) => a - b);
  const p95 = sorted[Math.ceil(sorted.length * 0.95) - 1] ?? sorted[sorted.length - 1]!;
  console.log(`latency samples ms: ${sorted.map((sample) => sample.toFixed(1)).join(", ")}`);
  console.log(`latency p95 ms: ${p95.toFixed(1)}`);
  expect(p95).toBeLessThan(250);
});
