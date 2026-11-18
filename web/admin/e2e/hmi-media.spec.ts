import { expect, test, type APIRequestContext, type Page, type Route } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * HMP-031, HMP-033, HMP-035, HMP-042 on the real gateway: a session page with a drawing, a PDF viewer, an audio
 * player and a video player, served under the gateway's Content-Security-Policy. A tag animates one shape of the
 * drawing, a tag starts and stops the audio and the operator stopping it writes the tag back, the PDF frames from an
 * http(s) URL and the media load from one, and a `javascript:` source is never loaded.
 */

const MEDIA = "https://media.test";

async function signIn(request: APIRequestContext, username = "admin"): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const metadata = { ...(resource.metadata as Record<string, unknown>), version };
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function tagValue(request: APIRequestContext, csrf: string, path: string): Promise<unknown> {
  const res = await request.post("/api/v1/tags/read", { headers: { "X-CSRF-Token": csrf }, data: { paths: [path] } });
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = (await res.json()) as { results?: Array<{ value?: unknown }>; values?: Array<{ value?: unknown }> };
  return (body.results ?? body.values ?? [])[0]?.value;
}

/** A mono 8-bit PCM WAV of [seconds] of a quiet tone, small enough to fulfil a route. */
function wav(seconds: number): Buffer {
  const rate = 8000;
  const samples = Math.round(rate * seconds);
  const buffer = Buffer.alloc(44 + samples);
  buffer.write("RIFF", 0, "ascii");
  buffer.writeUInt32LE(36 + samples, 4);
  buffer.write("WAVEfmt ", 8, "ascii");
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(rate, 24);
  buffer.writeUInt32LE(rate, 28);
  buffer.writeUInt16LE(1, 32);
  buffer.writeUInt16LE(8, 34);
  buffer.write("data", 36, "ascii");
  buffer.writeUInt32LE(samples, 40);
  for (let index = 0; index < samples; index += 1) buffer[44 + index] = 128 + Math.round(4 * Math.sin((index / rate) * 2 * Math.PI * 440));
  return buffer;
}

const PDF = Buffer.from(
  "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2>>endobj\n" +
    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n4 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n" +
    "trailer<</Root 1 0 R>>\n%%EOF\n",
  "latin1",
);

/** Serves the media host from the test, and records what the page asked it for. */
async function serveMedia(page: Page): Promise<string[]> {
  const requested: string[] = [];
  await page.context().route(`${MEDIA}/**`, async (route: Route) => {
    const url = new URL(route.request().url());
    requested.push(url.pathname);
    if (url.pathname.endsWith(".pdf")) return route.fulfill({ status: 200, contentType: "application/pdf", body: PDF });
    if (url.pathname.endsWith(".wav")) return route.fulfill({ status: 200, contentType: "audio/wav", body: wav(3) });
    return route.fulfill({ status: 200, contentType: "audio/wav", body: wav(3) });
  });
  return requested;
}

const DRAWING = '<svg viewBox="0 0 100 100"><rect id="shell" x="20" y="0" width="60" height="100" fill="#d9dee3"/><rect id="level" x="22" y="0" width="56" height="10" fill="#3d7ab8"/></svg>';

test("drawing, PDF, audio and video run in a session under the gateway's security policy", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("media");
  const project = `${run}-plant`;
  const folder = run;
  const levelPath = `[default]${folder}/level`;
  const hornPath = `[default]${folder}/horn`;
  const csrf = await signIn(page.request);
  for (const [name, dataType, value] of [
    ["level", "Double", 25],
    ["horn", "Boolean", false],
  ] as const) {
    await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${run}-${name}`, {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name: `${run}-${name}`, module: "tags" },
      spec: { path: `${folder}/${name}`, type: "memory", dataType, value },
    });
  }
  const component = (id: string, type: string, frame: Record<string, number>, props: Record<string, unknown>, bindings?: Record<string, unknown>) => ({
    id,
    kind: "component",
    name: id,
    type,
    frame,
    props,
    ...(bindings ? { bindings } : {}),
  });
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: {
        params: {},
        width: 1100,
        height: 700,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "coordinate",
          props: {},
          children: [
            component("tank", "drawing", { x: 0, y: 0, width: 160, height: 160 }, { svg: DRAWING, alt: "Tank drawing", elements: {} }, {
              "elements.level.height": { kind: "tag", mode: "direct", path: levelPath },
            }),
            component("manual", "pdf-viewer", { x: 180, y: 0, width: 420, height: 540 }, { source: `${MEDIA}/docs/manual.pdf`, page: 2, title: "Pump manual" }),
            component("horn", "audio", { x: 0, y: 180, width: 160, height: 56 }, { source: `${MEDIA}/sounds/horn.wav`, label: "Horn", loop: true, muted: true }, {
              playing: { kind: "tag", mode: "direct", path: hornPath, bidirectional: true },
            }),
            component("camera", "video-player", { x: 620, y: 0, width: 400, height: 225 }, { source: `${MEDIA}/clips/line.webm`, label: "Line camera" }),
            component("bad-pdf", "pdf-viewer", { x: 0, y: 260, width: 160, height: 100 }, { source: "javascript:alert(document.domain)", title: "Refused PDF" }),
            component("bad-audio", "audio", { x: 0, y: 380, width: 160, height: 56 }, { source: "data:audio/wav;base64,UklGRg==", label: "Refused audio" }),
          ],
        },
      },
    },
  });
  const operator = `${run}-op`;
  const created = await page.request.post("/api/v1/security/users", {
    data: { username: operator, password: PASSWORD, roles: ["operator"] },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  const context = await browser.newContext({ baseURL });
  try {
    await signIn(context.request, operator);
    const session = await context.newPage();
    const requested = await serveMedia(session);
    const violations: string[] = [];
    await session.exposeFunction("__oipCspViolation", (text: string) => violations.push(text));
    await session.addInitScript(() => {
      document.addEventListener("securitypolicyviolation", (event) => {
        (window as unknown as { __oipCspViolation: (text: string) => void }).__oipCspViolation(`${event.violatedDirective} ${event.blockedURI}`);
      });
    });
    const response = await session.goto(`/hmi/${project}/`);
    expect(response?.headers()["content-security-policy"]).toContain("frame-src 'self' http: https:");
    await expect(session.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");

    // Drawing: the tag sets the height of one shape; the shell keeps its own.
    const drawing = session.getByRole("img", { name: "Tank drawing" });
    const level = drawing.locator("rect[id$='-level']");
    await expect(level).toHaveAttribute("height", "25");
    await expect(drawing.locator("rect[id$='-shell']")).toHaveAttribute("height", "100");
    await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${run}-level`, {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name: `${run}-level`, module: "tags" },
      spec: { path: `${folder}/level`, type: "memory", dataType: "Double", value: 70 },
    });
    await expect(level).toHaveAttribute("height", "70");

    // PDF: framed from the http(s) URL at page 2, not blocked by the policy.
    const frame = session.locator("iframe[title='Pump manual']");
    await expect(frame).toHaveAttribute("src", `${MEDIA}/docs/manual.pdf#page=2`);
    await expect.poll(() => requested).toContain("/docs/manual.pdf");

    // Video: loaded from the http(s) URL.
    const video = session.locator("video[aria-label='Line camera']");
    await expect(video).toHaveAttribute("src", `${MEDIA}/clips/line.webm`);
    await expect.poll(() => requested).toContain("/clips/line.webm");

    // Audio: the tag starts it; the operator pausing it writes the tag back.
    const audio = session.locator("audio[aria-label='Horn']");
    await expect(audio).toHaveJSProperty("paused", true);
    await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${run}-horn`, {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name: `${run}-horn`, module: "tags" },
      spec: { path: `${folder}/horn`, type: "memory", dataType: "Boolean", value: true },
    });
    await expect(audio).toHaveJSProperty("paused", false);
    expect(requested).toContain("/sounds/horn.wav");
    await audio.evaluate((element: HTMLAudioElement) => element.pause());
    await expect.poll(() => tagValue(page.request, csrf, hornPath)).toBe(false);
    await expect(audio).toHaveJSProperty("paused", true);

    // Refused sources: nothing is framed or loaded.
    await expect(session.getByRole("document", { name: "Refused PDF" })).toHaveAttribute("data-empty", "true");
    await expect(session.locator("iframe[title='Refused PDF']")).toHaveCount(0);
    await expect(session.getByRole("group", { name: "Refused audio" })).toHaveAttribute("data-empty", "true");
    await expect(session.locator("audio[aria-label='Refused audio']")).toHaveCount(0);
    expect(violations).toEqual([]);
  } finally {
    await context.close();
  }
});
