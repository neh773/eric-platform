import { expect, test } from "@playwright/test";
import { signIn, uniqueProject } from "./studio";

/** HMP-084: a session's user, device info and time zone are readable from bindings and session scripts; the time zone is writable. */

const label = (id: string, y: number, path: string) => ({
  id,
  kind: "component",
  name: id,
  type: "label",
  frame: { x: 16, y, width: 520, height: 28 },
  props: { text: "" },
  bindings: { text: { kind: "property", source: { scope: "session", path } } },
});

test("bindings show the user, device and time zone, and a script reads them and sets the time zone", async ({ page }) => {
  const project = uniqueProject("sessprops");
  const folder = uniqueProject("hmp084");
  const csrf = await signIn(page.request);
  const tagPath = `${folder}/seen`;
  const tagName = tagPath.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  const tag = await page.request.put(`/api/v1/config/tags/gateway/Tag/${tagName}`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "Tag", metadata: { name: tagName, module: "tags", version: 0 }, spec: { path: tagPath, type: "memory", dataType: "String", value: "none" } },
  });
  expect(tag.ok(), await tag.text()).toBeTruthy();

  const source = [
    "p = session.props",
    `actions.writeTag("[default]${tagPath}", "%s|%s|%s" % (p.auth.userName, p.device.id, p.device.screenWidth > 0))`,
    "session.props.timeZoneId = 'Asia/Tokyo'",
  ].join("\n");
  const document = {
    params: {},
    width: 600,
    height: 300,
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        label("user", 16, "auth.userName"),
        label("device", 52, "device.id"),
        label("agent", 88, "device.userAgent"),
        label("zone", 124, "timeZoneId"),
        {
          id: "go",
          kind: "component",
          name: "go",
          type: "button",
          frame: { x: 16, y: 170, width: 200, height: 40 },
          props: { text: "Read session" },
          events: [{ event: "onClick", actions: [{ kind: "script", hook: "read-session", source }] }],
        },
      ],
    },
  };
  const view = await page.request.put(`/api/v1/config/designer/project/${project}/View/home`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "View", metadata: { name: "home", module: "designer" }, spec: { path: "home", title: "Home", document } },
  });
  expect(view.ok(), await view.text()).toBeTruthy();
  const pages = await page.request.put(`/api/v1/config/designer/project/${project}/PageConfig/page-config`, {
    headers: { "X-CSRF-Token": csrf },
    data: { apiVersion: "oip/v1", kind: "PageConfig", metadata: { name: "page-config", module: "designer" }, spec: { pages: [{ url: "/", title: "Home", primaryView: "home" }] } },
  });
  expect(pages.ok(), await pages.text()).toBeTruthy();

  await page.goto(`/hmi/${project}/`);
  await expect(page.getByText("admin", { exact: true })).toBeVisible();
  // A session with no zone of its own starts in the gateway's default zone (HMP-101).
  const bundle = await page.request.get(`/api/v1/hmi/projects/${project}`);
  expect(bundle.ok(), await bundle.text()).toBeTruthy();
  const gatewayTimeZone = ((await bundle.json()) as { gatewayTimeZone: string }).gatewayTimeZone;
  expect(gatewayTimeZone).toBeTruthy();
  await expect(page.getByText(gatewayTimeZone, { exact: true })).toBeVisible();
  const agent = await page.evaluate(() => navigator.userAgent);
  await expect(page.getByText(agent, { exact: true })).toBeVisible();
  const deviceId = await page.evaluate(() => window.localStorage.getItem("oip.session.device-id"));
  expect(deviceId).toBeTruthy();
  await expect(page.getByText(deviceId ?? "", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Read session", exact: true }).click();
  await expect(page.getByText("Asia/Tokyo", { exact: true })).toBeVisible();
  await expect
    .poll(async () => {
      const res = await page.request.post("/api/v1/tags/read", { headers: { "X-CSRF-Token": csrf }, data: { paths: [`[default]${tagPath}`] } });
      return ((await res.json()) as { values: { value: unknown }[] }).values[0]?.value;
    }, { timeout: 10_000 })
    .toBe(`admin|${deviceId}|True`);
});
