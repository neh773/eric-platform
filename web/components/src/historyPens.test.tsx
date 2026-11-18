import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TimeSeriesChart } from "./charts";
import { GatewayClient, GatewayProvider } from "./gateway";
import { HISTORY_ALL_RANGE_SECONDS, historyUrl, parseHistory } from "./historyPens";

/**
 * P4-13 (HMP-001): a time series chart pen that reads a tag's history from `/api/v1/history/data`, against a fake
 * history API.
 */

const NOW = Date.parse("2026-10-27T10:00:00Z");

/** A fake gateway history API: rows per tag path, answered for the asked range and limit like the gateway does. */
function fakeHistory(rows: Record<string, Array<{ t: number; v: unknown; quality?: string }>>, denied: string[] = []) {
  const calls: URL[] = [];
  const fetch = vi.fn(async (input: string) => {
    const url = new URL(input, "http://gateway");
    calls.push(url);
    if (url.pathname !== "/api/v1/history/data") return Response.json({ detail: "not found" }, { status: 404 });
    const path = url.searchParams.get("tagPath")!;
    const from = Number(url.searchParams.get("from"));
    const to = Number(url.searchParams.get("to"));
    const limit = Number(url.searchParams.get("limit"));
    const base = { tagPath: path, provider: "local", from: new Date(from).toISOString(), to: new Date(to).toISOString(), limit };
    if (denied.includes(path)) return Response.json({ ...base, quality: "Bad_AccessDenied", truncated: false, samples: [] });
    if (!(path in rows)) return Response.json({ type: "urn:oip:problem:unavailable", detail: "history provider unavailable" }, { status: 503 });
    const matched = rows[path]!.filter((row) => row.t >= from && row.t <= to);
    return Response.json({
      ...base,
      truncated: matched.length > limit,
      samples: matched.slice(0, limit).map((row) => ({ timestamp: new Date(row.t).toISOString(), value: row.v, quality: row.quality ?? "Good" })),
    });
  });
  return { fetch, calls, client: new GatewayClient({ fetch }) };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("history answer", () => {
  it("builds the query and reads rows as samples, with non-Good rows as gaps", () => {
    expect(historyUrl("[default]tank/level", 1000, 2000.5, 50)).toBe("/api/v1/history/data?tagPath=%5Bdefault%5Dtank%2Flevel&from=1000&to=2001&limit=50");
    const read = parseHistory({
      truncated: true,
      samples: [
        { timestamp: "2026-10-27T10:00:02Z", value: 3, quality: "Good" },
        { timestamp: "2026-10-27T10:00:01Z", value: 9, quality: "Bad_NotConnected" },
        { timestamp: "nonsense", value: 1 },
        { timestamp: "2026-10-27T10:00:00Z", value: 2.5, quality: "Good_Overridden" },
      ],
    });
    expect(read).toEqual({ samples: [{ t: NOW, v: 2.5 }, { t: NOW + 1000, v: null }, { t: NOW + 2000, v: 3 }], truncated: true, denied: false });
    expect(parseHistory({ quality: "Bad_AccessDenied", samples: [] }).denied).toBe(true);
    expect(parseHistory(null)).toEqual({ samples: [], truncated: false, denied: false });
  });
});

describe("TimeSeriesChart history pens", () => {
  it("plots each pen's stored history over the visible range, next to the live value", async () => {
    vi.useFakeTimers({ toFake: ["Date"], now: NOW });
    const api = fakeHistory({
      "[default]tank/level": [
        { t: NOW - 400_000, v: 1 }, // before the 300 s range
        { t: NOW - 200_000, v: 40 },
        { t: NOW - 100_000, v: 42 },
        { t: NOW - 10_000, v: 41 },
      ],
      "[default]tank/flow": [{ t: NOW - 50_000, v: 7 }],
    });
    render(
      <GatewayProvider client={api.client}>
        <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ title: "Tank", historyPens: "[default]tank/level, [default]tank/flow", rangeSeconds: 300, value: 43, valueTimestamp: NOW }} />
      </GatewayProvider>,
    );
    const root = document.querySelector(".oip-time-series")!;
    await waitFor(() => expect(root).toHaveAttribute("data-points", "5"));
    expect(root.querySelector("[data-series='level']")).toBeTruthy();
    expect(root.querySelector("[data-series='flow']")).toBeTruthy();
    expect(screen.getByRole("img", { name: "Tank: level 41.0, flow 7.0, Value 43.0" })).toBeInTheDocument();
    // One read per pen over [now - range, now].
    expect(api.calls).toHaveLength(2);
    for (const call of api.calls) {
      expect(Number(call.searchParams.get("from"))).toBe(NOW - 300_000);
      expect(Number(call.searchParams.get("to"))).toBe(NOW);
      expect(call.searchParams.get("limit")).toBe("10000");
    }
    // A pen toggles off from the legend like any series.
    fireEvent.click(screen.getByRole("checkbox", { name: "Show flow" }));
    expect(root.querySelector("[data-series='flow']")).toBeNull();
  });

  it("reads one day back for range 0, honours the row limit, and says when history was cut", async () => {
    vi.useFakeTimers({ toFake: ["Date"], now: NOW });
    const api = fakeHistory({ "[default]a": [1, 2, 3].map((n) => ({ t: NOW - n * 3_600_000, v: n })) });
    render(
      <GatewayProvider client={api.client}>
        <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ historyPens: "[default]a", rangeSeconds: 0, historyLimit: 2 }} />
      </GatewayProvider>,
    );
    await waitFor(() => expect(document.querySelector(".oip-time-series")).toHaveAttribute("data-points", "2"));
    expect(Number(api.calls[0]!.searchParams.get("from"))).toBe(NOW - HISTORY_ALL_RANGE_SECONDS * 1000);
    expect(screen.getByRole("alert")).toHaveTextContent("a: more history than 2 rows; the newest are not shown");
  });

  it("shows a refused or failed pen, and keeps the other pens", async () => {
    const api = fakeHistory({ "[default]ok": [{ t: Date.now() - 1000, v: 5 }] }, ["[default]secret"]);
    render(
      <GatewayProvider client={api.client}>
        <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ historyPens: "[default]ok, [default]secret, [default]gone" }} />
      </GatewayProvider>,
    );
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("secret: Access denied; gone: 503 history provider unavailable"));
    expect(document.querySelector(".oip-time-series")).toHaveAttribute("data-points", "1");
  });

  it("re-reads on the refresh interval, and not at all without one", async () => {
    vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"], now: NOW });
    const rows = [{ t: NOW - 1000, v: 1 }];
    const api = fakeHistory({ "[default]a": rows });
    const { unmount } = render(
      <GatewayProvider client={api.client}>
        <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ historyPens: "[default]a", historyRefreshSeconds: 10 }} />
      </GatewayProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(api.calls).toHaveLength(1);
    expect(document.querySelector(".oip-time-series")).toHaveAttribute("data-points", "1");
    rows.push({ t: NOW + 5000, v: 2 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(api.calls).toHaveLength(2);
    expect(document.querySelector(".oip-time-series")).toHaveAttribute("data-points", "2");
    unmount();

    api.calls.length = 0;
    render(
      <GatewayProvider client={api.client}>
        <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ historyPens: "[default]a" }} />
      </GatewayProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(api.calls).toHaveLength(1);
  });

  it("reads nothing without history pens", () => {
    const api = fakeHistory({});
    render(
      <GatewayProvider client={api.client}>
        <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ data: [{ t: NOW, a: 1 }] }} />
      </GatewayProvider>,
    );
    expect(api.fetch).not.toHaveBeenCalled();
  });
});
