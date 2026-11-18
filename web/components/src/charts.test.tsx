import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  appendWindowed,
  areaPath,
  interpolateAt,
  linePath,
  nearestIndex,
  niceExtent,
  niceTicks,
  parseTimestamp,
  pushBounded,
  rangeStats,
  seriesFromRows,
  timeTicks,
  valueExtent,
} from "./chartMath";
import { PowerChart, TimeSeriesChart, parsePens, parseTagRead } from "./charts";
import { GatewayClient, GatewayProvider } from "./gateway";
import { componentRegistry } from "./registry";

/**
 * Time series chart and power chart.
 */

const T0 = Date.parse("2026-10-27T10:00:00Z");

describe("chart math", () => {
  it("bounds buffers by count and by time window", () => {
    expect(pushBounded([1, 2, 3], 4, 3)).toEqual([2, 3, 4]);
    let samples = appendWindowed([], { t: T0, v: 1 }, 10_000, 100);
    samples = appendWindowed(samples, { t: T0 + 5_000, v: 2 }, 10_000, 100);
    samples = appendWindowed(samples, { t: T0 + 12_000, v: 3 }, 10_000, 100);
    expect(samples.map((sample) => sample.v)).toEqual([2, 3]);
    samples = appendWindowed(samples, { t: T0 + 12_000, v: 4 }, 10_000, 100);
    expect(samples.map((sample) => sample.v)).toEqual([2, 4]);
    expect(appendWindowed(samples, { t: T0 + 13_000, v: 5 }, 10_000, 2).map((sample) => sample.v)).toEqual([4, 5]);
  });

  it("parses rows into time-sorted series, with gaps for non-numeric values", () => {
    const series = seriesFromRows([
      { t: "2026-10-27T10:00:02Z", flow: 3, level: "bad" },
      { time: T0, flow: 1, level: 40 },
      { nope: 1 },
    ]);
    expect([...series.keys()]).toEqual(["flow", "level"]);
    expect(series.get("flow")).toEqual([{ t: T0, v: 1 }, { t: T0 + 2000, v: 3 }]);
    expect(series.get("level")).toEqual([{ t: T0, v: 40 }, { t: T0 + 2000, v: null }]);
    expect(parseTimestamp("nonsense")).toBeNull();
    expect(parseTimestamp(new Date(T0))).toBe(T0);
  });

  it("computes extents, nice ticks, and time ticks", () => {
    expect(valueExtent([[{ t: 1, v: 3 }, { t: 2, v: 9 }, { t: 3, v: null }]], 0, 10)).toEqual({ min: 3, max: 9 });
    expect(valueExtent([[{ t: 1, v: 5 }]], 0, 10)).toEqual({ min: 4, max: 6 });
    expect(valueExtent([], 0, 10)).toEqual({ min: 0, max: 1 });
    expect(niceTicks(0, 100, 5)).toEqual([0, 20, 40, 60, 80, 100]);
    expect(niceTicks(0.1, 0.5, 4)).toEqual([0.1, 0.2, 0.3, 0.4, 0.5]);
    expect(niceExtent({ min: 3, max: 97 })).toEqual({ min: 0, max: 100 });
    expect(timeTicks(T0, T0 + 60_000, 6)).toEqual([T0, T0 + 10_000, T0 + 20_000, T0 + 30_000, T0 + 40_000, T0 + 50_000, T0 + 60_000]);
  });

  it("draws lines and areas with gaps", () => {
    const samples = [
      { t: 0, v: 0 },
      { t: 5, v: 10 },
      { t: 6, v: null },
      { t: 10, v: 5 },
    ];
    expect(linePath(samples, 0, 10, { min: 0, max: 10 }, 100, 100)).toBe("M0.00,100.00L50.00,0.00M100.00,50.00");
    expect(areaPath(samples.slice(0, 2), 0, 10, { min: 0, max: 10 }, 100, 100)).toBe("M0.00,100L0.00,100.00L50.00,0.00L50.00,100Z");
  });

  it("finds the nearest sample, interpolates, and summarises a range", () => {
    const samples = [
      { t: 0, v: 0 },
      { t: 10, v: 10 },
      { t: 20, v: null },
      { t: 30, v: 4 },
    ];
    expect(nearestIndex(samples, 4)).toBe(0);
    expect(nearestIndex(samples, 6)).toBe(1);
    expect(nearestIndex([], 6)).toBe(-1);
    expect(interpolateAt(samples, 2.5)).toBe(2.5);
    expect(interpolateAt(samples, 15)).toBeNull();
    expect(interpolateAt(samples, 40)).toBeNull();
    expect(rangeStats(samples, 0, 30)).toEqual({ min: 0, max: 10, average: 14 / 3, last: 4, count: 3 });
    expect(rangeStats(samples, 25, 26).average).toBeNull();
  });
});

describe("TimeSeriesChart", () => {
  it("is registered for the designer", () => {
    expect(componentRegistry().get("time-series-chart")?.title).toBe("Time series chart");
    expect(componentRegistry().get("power-chart")?.title).toBe("Power chart");
  });

  it("plots rows as series, toggles a series from the legend, and shows an x-trace", () => {
    const data = [
      { t: T0, flow: 1, level: 40 },
      { t: T0 + 30_000, flow: 3, level: 42 },
      { t: T0 + 60_000, flow: 2, level: 41 },
    ];
    render(<TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ title: "Tank", data, rangeSeconds: 0, renderer: "area" }} />);
    const root = document.querySelector(".oip-time-series")!;
    expect(root).toHaveAttribute("data-points", "6");
    expect(root.querySelectorAll("[data-series]")).toHaveLength(2);
    expect(root.querySelector("[data-series='flow'] .oip-chart-fill")).toBeTruthy();
    expect(screen.getByRole("img", { name: "Tank: flow 2.0, level 41.0" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Show level" }));
    expect(root.querySelectorAll("[data-series]")).toHaveLength(1);
    const plot = screen.getByTestId("chart-plot");
    plot.getBoundingClientRect = () => ({ left: 0, width: 100, top: 0, height: 50, right: 100, bottom: 50, x: 0, y: 0, toJSON: () => ({}) });
    fireEvent.pointerMove(plot, { clientX: 50 });
    expect(screen.getByTestId("x-trace")).toHaveTextContent("flow: 3.0");
    fireEvent.pointerLeave(plot);
    expect(screen.queryByTestId("x-trace")).toBeNull();
  });

  it("appends a live value binding into a rolling buffer", () => {
    const { rerender } = render(
      <TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ value: 5, valueTimestamp: T0, valueName: "Level" }} />,
    );
    for (let index = 1; index <= 3; index += 1) {
      rerender(<TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ value: 5 + index, valueTimestamp: T0 + index * 1000, valueName: "Level" }} />);
    }
    expect(document.querySelector(".oip-time-series")).toHaveAttribute("data-points", "4");
    expect(screen.getByRole("img", { name: "Time series: Level 8.0" })).toBeInTheDocument();
    rerender(<TimeSeriesChart nodeId="c" locale="en-US" onChange={() => {}} props={{ value: 50, valueTimestamp: T0 + 400_000, valueName: "Level", rangeSeconds: 300 }} />);
    expect(document.querySelector(".oip-time-series")).toHaveAttribute("data-points", "1");
  });
});

describe("PowerChart", () => {
  it("parses pens and tag read rows", () => {
    expect(parsePens(" [default]tank/level, [default]tank/flow ,[default]tank/level")).toEqual([
      { path: "[default]tank/level", name: "level" },
      { path: "[default]tank/flow", name: "flow" },
    ]);
    expect(parseTagRead({ values: [{ path: "a", value: 3, quality: "Good" }, { path: "b", value: 4, quality: "Bad_NotFound" }] })).toEqual([
      { path: "a", value: 3 },
      { path: "b", value: null },
    ]);
  });

  it("polls tag reads in realtime, fills the pen table, and pauses", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let level = 10;
      const reads: unknown[] = [];
      const fetch = vi.fn(async (url: string, init?: RequestInit) => {
        if (url === "/api/v1/auth/session") return Response.json({ csrfToken: "tok" });
        reads.push(JSON.parse(String(init!.body)));
        level += 1;
        return Response.json({ values: [{ path: "[default]tank/level", value: level, quality: "Good", timestamp: new Date().toISOString() }] });
      });
      render(
        <GatewayProvider client={new GatewayClient({ fetch })}>
          <PowerChart nodeId="p" locale="en-US" onChange={() => {}} props={{ pens: "[default]tank/level", pollRate: 250 }} />
        </GatewayProvider>,
      );
      await waitFor(() => expect(screen.getByTestId("pen-value")).toHaveTextContent("11.0"));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(800);
      });
      expect(reads[0]).toEqual({ paths: ["[default]tank/level"] });
      const row = document.querySelector("[data-pen='[default]tank/level']")!;
      expect(Number(row.getAttribute("data-samples"))).toBeGreaterThanOrEqual(3);
      expect(row).toHaveTextContent("11.0");
      fireEvent.click(screen.getByRole("button", { name: "Pause" }));
      const count = reads.length;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000);
      });
      expect(reads.length).toBe(count);
      expect(screen.getByRole("button", { name: "Resume" })).toHaveAttribute("aria-pressed", "true");
    } finally {
      vi.useRealTimers();
    }
  });

  it("shows read errors and plots history rows in historical mode without polling", async () => {
    const fetch = vi.fn(async () => Response.json({ type: "urn:oip:problem:forbidden", detail: "nope" }, { status: 403 }));
    const { unmount } = render(
      <GatewayProvider client={new GatewayClient({ fetch })}>
        <PowerChart nodeId="p" locale="en-US" onChange={() => {}} props={{ pens: "[default]a" }} />
      </GatewayProvider>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not read pens: 403 nope");
    unmount();
    fetch.mockClear();
    render(
      <GatewayProvider client={new GatewayClient({ fetch })}>
        <PowerChart
          nodeId="p"
          locale="en-US"
          onChange={() => {}}
          props={{
            pens: "[default]a",
            mode: "historical",
            history: [
              { t: T0, "[default]a": 2 },
              { t: T0 + 1000, "[default]a": 6 },
            ],
          }}
        />
      </GatewayProvider>,
    );
    const row = document.querySelector("[data-pen='[default]a']")!;
    expect(row).toHaveTextContent("6.0");
    expect(row).toHaveTextContent("4.0");
    expect(fetch).not.toHaveBeenCalled();
  });
});
