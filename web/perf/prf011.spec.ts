import { expect, test, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { PASSWORD, stamp } from "../admin/e2e/gateway";
import { BASELINE_P95_MS, latencyStats, prf011Config, regressionFails, worsePercent } from "./prf011";
import { startProtocolFarm, type ProtocolFarm } from "./protocolSession";

/**
 * PRF-011 (ADR 0010): a tag change reaches the rendered HMI value in under 250 ms at p95, with 50 sessions of 300
 * tags each, through the real gateway.
 *
 * The gateway is the one `admin/e2e/start-gateway.mjs` starts; it serves `/hmi` from the production designer bundle.
 * The test saves a project whose page shows 300 numeric displays, each with a direct tag binding to its own memory
 * tag. Every session signs in as its own operator user and holds its own `/api/v1/ws` session socket with 300
 * subscriptions. The first `rendered` sessions are real Chromium pages, each in its own browser context, at
 * `/hmi/<project>/`; every one of them is timed. The others are protocol sessions (protocolSession.ts): the same
 * socket, hello and subscriptions, reading every update frame, without a browser, in worker threads of their own. One 4 vCPU runner cannot also
 * paint 50 pages of 300 changing values in software (each page is a renderer with its own raster and compositor
 * work); in a plant each session is its own client machine, so the gateway carries 50 sessions and each screen
 * renders one. Protocol sessions report frame arrival latency separately, split at the gateway's `sent` time into
 * gateway queueing and client draining; both the rendered latency and the protocol arrival latency must hold
 * PRF-011's 250 ms p95 (review-4 F1).
 *
 * Tag changes come from the gateway side: `POST /api/v1/tags/write`, one batch every `batchMs`, so each tag changes
 * once per `tagPeriodMs`. Each written value is unique (the send time, in ms since the run's base time). The audit
 * profile is off while the farm runs and is put back after: with it on, every REST tag write first appends a chained,
 * fsynced audit record, which holds the write API under the target rate. That is the operator-write path, not the HMI
 * path; field values reach the tag engine from drivers without an audit record.
 *
 * Timing, in each page, on the host clock the gateway and the browsers share (checked below):
 * - change: the tag's own timestamp, set by the gateway when it applied the value, as the session socket delivers it.
 * - shown: `Date.now()` in a MutationObserver callback the moment React puts that value in the numeric display's
 *   `data-value` (the DOM the next frame paints).
 * PRF-011 latency is shown minus change. The time from the write request leaving the test to shown is reported too;
 * it adds the REST call.
 * Values written in the warm-up are left out.
 *
 * The binding behaviour timed here is the FEATURES PRF-011 row's documented source.
 */

const config = prf011Config(process.env);
const AUDIT_PROFILE = "/api/v1/config/org.oip.security/gateway/AuditProfile/internal";

interface Recorder {
  base: number;
  /** Gateway change time per `t<index>|<value>`, from the socket's update frames. */
  changed: Map<string, number>;
  /** Per value shown: node index, value, gateway change time (ms since base, or NaN), shown time, painted time. */
  index: number[];
  values: number[];
  change: number[];
  seen: number[];
  frames: number[];
}

declare global {
  interface Window {
    __prf011?: Recorder;
  }
}

async function signIn(request: APIRequestContext, username: string): Promise<string> {
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

/** Runs [task] over [items], [limit] at a time. */
async function pool<T>(items: T[], limit: number, task: (item: T) => Promise<void>) {
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) await task(items[next++]!);
  });
  await Promise.all(workers);
}

function tagPath(folder: string, index: number) {
  return `[default]${folder}/t${index}`;
}

function farmView(folder: string, tags: number, columns: number) {
  const width = 88;
  const height = 24;
  const children = Array.from({ length: tags }, (_, index) => ({
    id: `t${index}`,
    kind: "component",
    name: `T${index}`,
    type: "numeric",
    frame: { x: (index % columns) * width, y: Math.floor(index / columns) * height, width, height },
    // Announce is off: a live region on every cell makes the browser spend the sample
    // telling assistive tech about 300 changes a second, which is not the timed path.
    props: { value: 0, decimals: 0, announce: false },
    bindings: { value: { kind: "tag", mode: "direct", path: tagPath(folder, index), transforms: [] } },
  }));
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "farm", module: "designer" },
    spec: {
      path: "farm",
      title: "Farm",
      document: {
        params: {},
        width: columns * width,
        height: Math.ceil(tags / columns) * height,
        root: { id: "farm-root", kind: "container", name: "Farm", layout: "coordinate", props: { mode: "fixed" }, children },
      },
    },
  };
}

/**
 * Installed before the bundle loads. Notes each update's gateway timestamp as the socket delivers it (its listener is
 * added in the constructor, so it runs before the runtime's), and records every new numeric `data-value` with the
 * time it appeared.
 */
function recorder(base: number) {
  const state: Recorder = { base, changed: new Map(), index: [], values: [], change: [], seen: [], frames: [] };
  window.__prf011 = state;
  const Native = window.WebSocket;
  class Recording extends Native {
    constructor(url: string | URL, protocols?: string | string[]) {
      super(url, protocols);
      this.addEventListener("message", (event: MessageEvent) => {
        if (typeof event.data !== "string" || !event.data.startsWith('{"type":"update"')) return;
        const frame = JSON.parse(event.data) as { updates: Array<{ path: string; value: unknown; timestamp: number }> };
        for (const update of frame.updates) {
          const slash = update.path.lastIndexOf("/");
          state.changed.set(`${update.path.slice(slash + 1)}|${String(update.value)}`, update.timestamp - state.base);
        }
      });
    }
  }
  window.WebSocket = Recording;
  let frameStart = -1;
  const observer = new MutationObserver((records) => {
    const now = Date.now() - state.base;
    const first = state.seen.length;
    for (const record of records) {
      const target = record.target as Element;
      if (record.attributeName !== "data-value" || target.getAttribute("data-testid") !== "numeric-value") continue;
      const raw = target.getAttribute("data-value");
      if (!raw) continue;
      const node = target.id.slice(0, target.id.length - "-value".length);
      const key = `${node}|${raw}`;
      const change = state.changed.get(key);
      state.changed.delete(key);
      state.index.push(Number(node.slice(1)));
      state.values.push(Number(raw));
      state.change.push(change ?? Number.NaN);
      state.seen.push(now);
    }
    // The first animation frame after the change: the frame that paints it.
    if (state.seen.length > first && frameStart < 0) {
      frameStart = first;
      requestAnimationFrame(() => {
        const painted = Date.now() - state.base;
        for (let index = frameStart; index < state.seen.length; index += 1) state.frames[index] = painted;
        frameStart = -1;
      });
    }
  });
  const start = () => observer.observe(document.documentElement, { subtree: true, attributes: true, attributeFilter: ["data-value"] });
  if (document.documentElement) start();
  else document.addEventListener("DOMContentLoaded", start);
}

test("PRF-011: tag change to rendered HMI value under 250 ms p95 through the gateway session farm", async ({ browser, page }) => {
  const { sessions, rendered, tags, batchMs, tagPeriodMs, warmupMs, measureMs } = config;
  test.setTimeout(config.timeoutMs);
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("prf011");
  const folder = run;
  const project = run;

  const adminCsrf = await signIn(page.request, "admin");

  // Tags, one memory Double per bound component.
  await pool(
    Array.from({ length: tags }, (_, index) => index),
    8,
    async (index) => {
      const name = `${run}-t${index}`;
      await put(page.request, adminCsrf, `/api/v1/config/tags/gateway/Tag/${name}`, {
        apiVersion: "oip/v1",
        kind: "Tag",
        metadata: { name, module: "tags" },
        spec: { path: `${folder}/t${index}`, type: "memory", dataType: "Double", value: 0 },
      });
    },
  );

  // The project: one page, one view of [tags] tag-bound numeric displays.
  const projectConfig = `/api/v1/config/designer/project/${project}`;
  await put(page.request, adminCsrf, `${projectConfig}/View/farm`, farmView(folder, tags, 15));
  await put(page.request, adminCsrf, `${projectConfig}/PageConfig/page-config`, {
    apiVersion: "oip/v1",
    kind: "PageConfig",
    metadata: { name: "page-config", module: "designer" },
    spec: { pages: [{ url: "/", title: "Farm", primaryView: "farm" }], sharedDocks: [] },
  });

  // One operator user per session (the gateway caps one principal at 8 session sockets).
  const users = Array.from({ length: sessions }, (_, index) => `${run}-op${index}`);
  await pool(users, 4, async (username) => {
    const res = await page.request.post("/api/v1/security/users", {
      data: { username, password: PASSWORD, roles: ["operator"] },
      headers: { "Content-Type": "application/json", "X-CSRF-Token": adminCsrf },
    });
    expect(res.ok(), await res.text()).toBeTruthy();
  });

  const base = Date.now();
  const contexts: BrowserContext[] = [];
  const pages: Page[] = [];
  let protocol: ProtocolFarm | null = null;
  const auditSpec = ((await (await page.request.get(AUDIT_PROFILE)).json()) as { spec: Record<string, unknown> }).spec;
  const setAudit = async (spec: Record<string, unknown>) =>
    put(page.request, adminCsrf, AUDIT_PROFILE, { apiVersion: "oip/v1", kind: "AuditProfile", metadata: { name: "internal", module: "org.oip.security" }, spec });
  await setAudit({ ...auditSpec, enabled: false });
  try {
    const paths = Array.from({ length: tags }, (_, index) => tagPath(folder, index));
    const protocolUsers = users.slice(rendered);
    if (protocolUsers.length > 0) {
      protocol = startProtocolFarm({ baseURL, password: PASSWORD, users: protocolUsers, paths, base, workers: 2 });
      await protocol.ready;
    }
    await pool(users.slice(0, rendered), 5, async (username) => {
      const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } });
      contexts.push(context);
      await signIn(context.request, username);
      const session = await context.newPage();
      pages.push(session);
      await session.addInitScript(recorder, base);
      await session.goto(`/hmi/${project}/`);
      await expect(session.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway", { timeout: 30_000 });
      // Every binding shows its first value from the gateway.
      await expect(session.locator("[data-testid='numeric-value'][data-value='0']")).toHaveCount(tags, { timeout: 30_000 });
    });
    expect(pages).toHaveLength(rendered);


    // The browsers and the test process read the same host clock: each page's Date.now() falls between two reads in
    // the test process (5 ms of slack). clockSkewMs is the largest distance outside that bracket.
    let clockSkewMs = 0;
    for (const session of pages) {
      let best = Number.POSITIVE_INFINITY;
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const before = Date.now();
        const inPage = await session.evaluate(() => Date.now());
        const after = Date.now();
        best = Math.min(best, Math.max(0, before - inPage, inPage - after));
      }
      clockSkewMs = Math.max(clockSkewMs, best);
    }
    expect(clockSkewMs).toBeLessThanOrEqual(5);

    // Drive: every batchMs one write request for the next slice of tags, so each tag changes once per tagPeriodMs.
    // Requests are not held back by a slow one; at most 4 are in flight.
    const cookie = (await page.context().cookies(baseURL)).map((entry) => `${entry.name}=${entry.value}`).join("; ");
    const writeHeaders = { "Content-Type": "application/json", "X-CSRF-Token": adminCsrf, Cookie: cookie };
    const batches = Math.max(1, Math.round(tagPeriodMs / batchMs));
    const perBatch = Math.ceil(tags / batches);
    const written: number[] = [];
    const writeMs: number[] = [];
    let writeErrors = 0;
    let skippedBatches = 0;
    const inFlight = new Set<Promise<void>>();
    const send = (slice: number) => {
      const value = Date.now() - base;
      const writes = [];
      for (let index = slice * perBatch; index < Math.min(tags, (slice + 1) * perBatch); index += 1) {
        writes.push({ path: tagPath(folder, index), value });
      }
      const request = (async () => {
        try {
          const res = await fetch(`${baseURL}/api/v1/tags/write`, { method: "POST", headers: writeHeaders, body: JSON.stringify({ writes }) });
          const body = res.ok ? ((await res.json()) as { results: Array<{ ok: boolean }> }) : null;
          writeErrors += body ? body.results.filter((row) => !row.ok).length : writes.length;
          if (body) for (let index = 0; index < writes.length; index += 1) written.push(value);
        } catch {
          writeErrors += writes.length;
        }
        writeMs.push(Date.now() - base - value);
      })();
      inFlight.add(request);
      void request.finally(() => inFlight.delete(request));
    };
    const total = warmupMs + measureMs;
    const startedAt = Date.now();
    for (let batch = 0; startedAt + batch * batchMs < startedAt + total; batch += 1) {
      const wait = startedAt + batch * batchMs - Date.now();
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      if (inFlight.size >= 4) {
        skippedBatches += 1;
        continue;
      }
      send(batch % batches);
    }
    await Promise.all(inFlight);
    // Let the last changes land.
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    expect(writeErrors).toBe(0);

    const windowFrom = startedAt - base + warmupMs;
    const windowTo = startedAt - base + total;
    const expectedPerPage = written.filter((value) => value >= windowFrom && value < windowTo).length;
    const collected = await Promise.all(
      pages.map((session) =>
        session.evaluate(() => {
          const state = window.__prf011!;
          return { index: state.index, values: state.values, change: state.change, seen: state.seen, frames: state.frames };
        }),
      ),
    );

    const latency: number[] = [];
    // p95 per 5 s of write time across the window: a backlog shows as a rising line.
    const buckets = new Map<number, number[]>();
    const painted: number[] = [];
    const fromRequest: number[] = [];
    const perPage: Array<{ samples: number; p95: number }> = [];
    let missed = 0;
    let unmatched = 0;
    for (const samples of collected) {
      const own: number[] = [];
      for (let index = 0; index < samples.values.length; index += 1) {
        const value = samples.values[index]!;
        if (value < windowFrom || value >= windowTo) continue;
        const change = samples.change[index]!;
        fromRequest.push(samples.seen[index]! - value);
        if (Number.isNaN(change)) {
          unmatched += 1;
          continue;
        }
        own.push(samples.seen[index]! - change);
        const bucket = Math.floor((value - windowFrom) / 5_000);
        let list = buckets.get(bucket);
        if (!list) buckets.set(bucket, (list = []));
        list.push(samples.seen[index]! - change);
        const frame = samples.frames[index];
        if (frame !== undefined && frame !== null) painted.push(frame - change);
      }
      latency.push(...own);
      missed += Math.max(0, expectedPerPage - own.length);
      perPage.push({ samples: own.length, p95: latencyStats(own).p95 });
    }
    const arrival: number[] = [];
    const queue: number[] = [];
    const drain: number[] = [];
    let protocolMissed = 0;
    const protocolResults = protocol ? await protocol.collect(windowFrom, windowTo) : [];
    expect(protocolResults).toHaveLength(sessions - rendered);
    for (const session of protocolResults) {
      arrival.push(...session.arrival);
      queue.push(...session.queue);
      drain.push(...session.drain);
      protocolMissed += Math.max(0, expectedPerPage - session.samples);
    }
    const protocolStats = latencyStats(arrival);

    const stats = latencyStats(latency);
    const measuredSeconds = measureMs / 1000;
    const result = {
      scenario: "PRF-011",
      size: config.size,
      sessions,
      renderedSessions: pages.length,
      timedSessions: pages.length,
      headlessProtocolClients: protocolResults.length,
      tagsPerSession: tags,
      batchMs,
      tagPeriodMs,
      targetChangesPerSecond: Math.round((tags * 1000) / tagPeriodMs),
      writtenChangesPerSecond: Number((expectedPerPage / measuredSeconds).toFixed(1)),
      sessionUpdatesPerSecond: Number(((expectedPerPage * sessions) / measuredSeconds).toFixed(1)),
      renderedUpdatesPerSecond: Number(((expectedPerPage * pages.length) / measuredSeconds).toFixed(1)),
      skippedBatches,
      warmupMs,
      measureMs,
      expectedSamples: expectedPerPage * pages.length,
      missedSamples: missed,
      unmatchedSamples: unmatched,
      clockSkewMs: Number(clockSkewMs.toFixed(1)),
      latencyMs: stats,
      protocolArrivalMs: protocolStats,
      protocolGatewayQueueMs: latencyStats(queue),
      protocolClientDrainMs: latencyStats(drain),
      protocolMissedSamples: protocolMissed,
      paintFrameMs: latencyStats(painted),
      writeRequestToShownMs: latencyStats(fromRequest),
      writeRequestMs: latencyStats(writeMs),
      p95Every5s: [...buckets.entries()].sort((x, y) => x[0] - y[0]).map(([, list]) => latencyStats(list).p95),
      worstSessionP95: Math.max(...perPage.map((entry) => entry.p95)),
      minSessionSamples: Math.min(...perPage.map((entry) => entry.samples)),
      thresholdMsP95: 250,
      baselineP95Ms: BASELINE_P95_MS,
      worseThanBaselinePercent: Number(worsePercent(BASELINE_P95_MS, stats.p95).toFixed(1)),
    };
    console.log(`PRF-011 ${JSON.stringify(result)}`);
    const out = process.env.OIP_PRF011_OUT ?? path.join(test.info().outputDir, "prf-011.json");
    mkdirSync(path.dirname(out), { recursive: true });
    writeFileSync(out, `${JSON.stringify(result, null, 2)}\n`);
    await test.info().attach("prf-011.json", { path: out, contentType: "application/json" });

    // The load is the one asked for: at least 95% of the target change rate was written.
    expect(result.writtenChangesPerSecond).toBeGreaterThanOrEqual(result.targetChangesPerSecond * 0.95);
    // Every session saw changes, and at most 1% of the written changes were not timed: coalesced away by a newer
    // value (latest value wins on a busy socket) or shown without a matching update frame.
    expect(result.minSessionSamples).toBeGreaterThan(0);
    expect(missed).toBeLessThanOrEqual(Math.ceil(result.expectedSamples * 0.01));
    expect(protocolMissed).toBeLessThanOrEqual(Math.ceil(expectedPerPage * protocolResults.length * 0.01));
    expect(stats.count).toBeGreaterThan(0);
    expect(stats.p95).toBeLessThan(250);
    // Review-4 F1: the protocol sessions are part of the 50; their change-to-arrival latency holds the same bound.
    if (protocolResults.length > 0) {
      expect(protocolStats.count).toBeGreaterThan(0);
      expect(protocolStats.p95, `protocol arrival p95 ${protocolStats.p95} ms`).toBeLessThan(250);
    }
    // PRF-013 on the baseline's own hardware (a 4 vCPU / 16 GB runner): more than 15% worse fails. Shared CI runners
    // vary more than that between runs, so the every-PR run reports it and a same-hardware run sets the flag.
    if (process.env.OIP_PRF011_ENFORCE_BASELINE === "1") {
      expect(regressionFails(BASELINE_P95_MS, stats.p95), `PRF-013 p95 ${stats.p95} ms vs baseline ${BASELINE_P95_MS} ms`).toBe(false);
    }
  } finally {
    await protocol?.close();
    await Promise.all(contexts.map((context) => context.close().catch(() => undefined)));
    await setAudit(auditSpec);
  }
});
