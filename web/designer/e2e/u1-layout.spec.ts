import { expect, test, type Page } from "@playwright/test";
import { canvasNode, openStudio, signIn, uniqueProject } from "./studio";

/**
 * U-1 layout fixes, checked in a real browser: a view wider than the canvas column scrolls inside it, a tab container
 * fills its frame on the canvas and at /hmi, long numbers stay inside their frames, running and stopped pumps are
 * drawn in their state colors, and Inspector checkboxes sit on their label's text line.
 */

const component = (id: string, type: string, frame: Record<string, number>, props: Record<string, unknown>) => ({
  id,
  kind: "component",
  name: id,
  type,
  frame,
  props,
});

const wideView = {
  params: {},
  width: 1800,
  height: 420,
  root: {
    id: "root",
    kind: "container",
    name: "Root",
    layout: "coordinate",
    props: { mode: "fixed" },
    children: [
      {
        id: "tabs",
        kind: "container",
        name: "Tabs",
        layout: "tab",
        frame: { x: 16, y: 16, width: 320, height: 160 },
        props: { tabs: [{ label: "One" }, { label: "Two" }] },
        children: [
          { id: "first", kind: "component", name: "first", type: "label", props: { text: "first panel" } },
          { id: "second", kind: "component", name: "second", type: "label", props: { text: "second panel" } },
        ],
      },
      component("big", "numeric", { x: 360, y: 16, width: 140, height: 48 }, { value: 123456789012.345, decimals: 3, prefix: "", suffix: " m3/h", announce: true }),
      component("gauge", "linear-gauge", { x: 520, y: 16, width: 160, height: 56 }, { label: "Flow", unit: "kg/h", value: 9876543210.5, min: 0, max: 100 }),
      component("running", "pump", { x: 360, y: 200, width: 90, height: 90 }, { label: "P-1", state: "running" }),
      component("stopped", "pump", { x: 470, y: 200, width: 90, height: 90 }, { label: "P-2", state: "stopped" }),
      component("far", "label", { x: 1600, y: 360, width: 180, height: 32 }, { text: "far edge" }),
      component("check", "checkbox", { x: 580, y: 200, width: 160, height: 32 }, { label: "Run permit", value: true }),
    ],
  },
};

/** A tab container in a column container with no row height, so the tab's own height is not definite. */
const columnView = {
  params: {},
  width: 600,
  height: 400,
  root: {
    id: "root",
    kind: "container",
    name: "Root",
    layout: "column",
    props: { breakpoints: [{ name: "sm", minWidth: 0 }] },
    children: [
      {
        id: "tabs",
        kind: "container",
        name: "Tabs",
        layout: "tab",
        columns: [{ name: "sm", span: 12, rowIndex: 0, colIndex: 0, order: 0 }],
        props: { tabs: [{ label: "One" }] },
        children: [
          {
            id: "pane",
            kind: "container",
            name: "Pane",
            layout: "flex",
            props: { direction: "column" },
            children: [
              component("a", "label", {}, { text: "line a" }),
              component("b", "label", {}, { text: "line b" }),
              component("c", "label", {}, { text: "line c" }),
            ],
          },
        ],
      },
    ],
  },
};

async function seed(page: Page): Promise<{ project: string; csrf: string }> {
  const project = uniqueProject("u1");
  const csrf = await signIn(page.request);
  const put = await page.request.put(`/api/v1/config/designer/project/${project}/View/wide`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "View", metadata: { name: "wide", module: "designer" }, spec: { path: "wide", title: "Wide", document: wideView } },
  });
  expect(put.ok(), await put.text()).toBeTruthy();
  const column = await page.request.put(`/api/v1/config/designer/project/${project}/View/column`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "View", metadata: { name: "column", module: "designer" }, spec: { path: "column", title: "Column", document: columnView } },
  });
  expect(column.ok(), await column.text()).toBeTruthy();
  return { project, csrf };
}

/** Height of the active panel's child next to the tab container's own height and its strip's. */
async function tabSizes(page: Page, scope: string) {
  return page.evaluate((root) => {
    const tab = document.querySelector<HTMLElement>(`${root} [data-node-id='tabs']`)!;
    const strip = tab.querySelector<HTMLElement>("[role='tablist']")!;
    const child = tab.querySelector<HTMLElement>("[role='tabpanel']:not([hidden]) > [data-node-id]")!;
    return { tab: tab.getBoundingClientRect().height, strip: strip.getBoundingClientRect().height, child: child.getBoundingClientRect().height };
  }, scope);
}

test.describe("U-1 layout", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("a wide view scrolls inside the canvas column without covering the side columns", async ({ page }) => {
    const { project, csrf } = await seed(page);
    await openStudio(page, project, csrf);
    await page.getByRole("treeitem", { name: "Wide", exact: true }).click();
    await expect(canvasNode(page, "far")).toHaveCount(1);
    const layout = await page.evaluate(() => {
      const box = (selector: string) => document.querySelector(selector)!.getBoundingClientRect();
      const scroll = document.querySelector<HTMLElement>(".oip-canvas-scroll")!;
      const left = box(".oip-dock-left");
      const side = box(".oip-side");
      const inspector = box(".oip-inspector");
      // Which column is on top at a point (a column may carry more classes, such as its dock).
      const hit = (x: number, y: number) => {
        const column = document.elementFromPoint(x, y)?.closest(".oip-dock-left, .oip-inspector, .oip-stage, .oip-side");
        return ["oip-dock-left", "oip-inspector", "oip-stage", "oip-side"].find((name) => column?.classList.contains(name));
      };
      return {
        page: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
        scrolls: scroll.scrollWidth > scroll.clientWidth,
        stage: box(".oip-stage"),
        leftRight: left.right,
        sideLeft: side.left,
        inspectorInRight: inspector.left >= side.left - 1 && inspector.right <= side.right + 1,
        onLeft: hit(left.right - 4, left.top + 200),
        onInspector: hit(inspector.right - 4, inspector.top + 200),
      };
    });
    expect(layout.page).toBe(true);
    expect(layout.scrolls).toBe(true);
    expect(layout.stage.left).toBeGreaterThanOrEqual(layout.leftRight - 1);
    expect(layout.stage.right).toBeLessThanOrEqual(layout.sideLeft + 1);
    expect(layout.inspectorInRight).toBe(true);
    expect(layout.onLeft).toBe("oip-dock-left");
    expect(layout.onInspector).toBe("oip-inspector");
    // Scrolling the canvas brings the far edge into view, still inside the canvas column.
    await page.locator(".oip-canvas-scroll").evaluate((element) => element.scrollTo({ left: element.scrollWidth }));
    const far = await canvasNode(page, "far").boundingBox();
    expect(far).not.toBeNull();
    expect(far!.x + far!.width).toBeLessThanOrEqual(layout.sideLeft);
  });

  test("a tab container fills its frame on the canvas and at /hmi", async ({ page }) => {
    const { project, csrf } = await seed(page);
    await openStudio(page, project, csrf);
    await page.getByRole("treeitem", { name: "Wide", exact: true }).click();
    await expect(canvasNode(page, "first")).toBeVisible();
    const canvas = await tabSizes(page, ".oip-canvas-render");
    expect(canvas.tab).toBeCloseTo(160, 0);
    expect(canvas.child).toBeCloseTo(canvas.tab - canvas.strip, 0);

    await page.goto(`/hmi/${project}/wide`);
    await expect(page.locator("[data-node-id='first']")).toBeVisible();
    const runtime = await tabSizes(page, "body");
    expect(runtime.tab).toBeCloseTo(160, 0);
    expect(runtime.child).toBeCloseTo(runtime.tab - runtime.strip, 0);
    await page.getByRole("tab", { name: "Two" }).click();
    const second = await page.locator("[data-node-id='second']").boundingBox();
    expect(second!.height).toBeCloseTo(runtime.tab - runtime.strip, 0);
  });

  test("a tab container with no height of its own grows to its panel's content", async ({ page }) => {
    const { project } = await seed(page);
    await page.goto(`/hmi/${project}/column`);
    await expect(page.locator("[data-node-id='c']")).toBeVisible();
    const sizes = await page.evaluate(() => {
      const tab = document.querySelector<HTMLElement>("[data-node-id='tabs']")!;
      const strip = tab.querySelector<HTMLElement>("[role='tablist']")!.getBoundingClientRect();
      const panel = tab.querySelector<HTMLElement>("[role='tabpanel']")!.getBoundingClientRect();
      const last = document.querySelector<HTMLElement>("[data-node-id='c']")!.getBoundingClientRect();
      return { strip: strip.height, panel: panel.height, panelBottom: panel.bottom, lastBottom: last.bottom };
    });
    // Before the fix the panel was 0px tall and the tab container drew only its strip.
    expect(sizes.panel).toBeGreaterThan(sizes.strip);
    expect(sizes.lastBottom).toBeLessThanOrEqual(sizes.panelBottom + 0.5);
  });

  test("long numbers stay inside their frames, and pumps show their state colors", async ({ page }) => {
    const { project } = await seed(page);
    await page.goto(`/hmi/${project}/wide`);
    const value = page.locator("[data-node-id='big'] [data-testid='numeric-value']");
    await expect(value).toHaveAttribute("title", "123456789012.345 m3/h");
    const fit = await value.evaluate((element) => {
      const frame = element.closest<HTMLElement>("[data-node-id]")!.getBoundingClientRect();
      const box = element.getBoundingClientRect();
      return { clipped: element.scrollWidth > element.clientWidth, inside: box.right <= frame.right + 0.5 && box.bottom <= frame.bottom + 0.5 };
    });
    expect(fit).toEqual({ clipped: true, inside: true });
    const gauge = await page.locator("[data-node-id='gauge'] .oip-gauge-head").evaluate((head) => {
      const [label, number] = Array.from(head.children).map((child) => child.getBoundingClientRect());
      return { overlap: label!.right > number!.left + 0.5, inside: number!.right <= head.getBoundingClientRect().right + 0.5 };
    });
    expect(gauge).toEqual({ overlap: false, inside: true });

    const fill = (id: string) => page.locator(`[data-node-id='${id}'] .oip-symbol-body`).first().evaluate((shape) => getComputedStyle(shape).fill);
    expect(await fill("running")).toBe("rgb(242, 244, 247)");
    expect(await fill("stopped")).toBe("rgb(91, 99, 112)");
  });

  test("Inspector checkboxes sit on their label's text line", async ({ page }) => {
    const { project, csrf } = await seed(page);
    await openStudio(page, project, csrf);
    await page.getByRole("treeitem", { name: "Wide", exact: true }).click();
    const box = await canvasNode(page, "big").boundingBox();
    await page.mouse.click(box!.x + 4, box!.y + 4);
    const check = page.getByRole("checkbox", { name: "Announce changes" });
    await expect(check).toBeVisible();
    const offset = await check.evaluate((input) => {
      const label = input.closest("label")!;
      const text = Array.from(label.childNodes).find((node) => node !== input && (node.textContent ?? "").trim())!;
      const range = document.createRange();
      range.selectNodeContents(text);
      const a = input.getBoundingClientRect();
      const b = range.getBoundingClientRect();
      return { dy: Math.abs(a.top + a.height / 2 - (b.top + b.height / 2)), left: a.left - label.getBoundingClientRect().left };
    });
    expect(offset.dy).toBeLessThanOrEqual(1);
    expect(offset.left).toBeLessThanOrEqual(0.5);
  });

  test("the studio's checkbox styling does not reach HMI components on the canvas", async ({ page }) => {
    const { project, csrf } = await seed(page);
    const look = (input: HTMLInputElement) => {
      const style = getComputedStyle(input);
      return { margin: style.margin, width: style.width, accent: style.accentColor };
    };
    await page.goto(`/hmi/${project}/wide`);
    const runtime = await page.locator("[data-node-id='check'] input[type='checkbox']").evaluate(look);
    await openStudio(page, project, csrf);
    await page.getByRole("treeitem", { name: "Wide", exact: true }).click();
    const onCanvas = await canvasNode(page, "check").locator("input[type='checkbox']").evaluate(look);
    expect(onCanvas).toEqual(runtime);
  });
});
