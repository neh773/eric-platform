import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MetricsPage, nextTileId, parseTiles } from "./metricsPage";
import { adminSession, fakeGateway, json, readerSession, sectionById } from "./statusTestKit";

afterEach(() => vi.unstubAllGlobals());

const metrics = {
  intervalMs: 5000,
  times: [1_000, 6_000, 11_000],
  metrics: [
    { id: "cpu.system", label: "System CPU", unit: "%", max: 100, value: 42.5, values: [10, 20, 42.5] },
    { id: "heap.used", label: "Heap used", unit: "MiB", max: 1024, value: 300, values: [100, 200, 300] },
    { id: "history.queueDepth", label: "History queue depth", unit: "records", max: null, value: 0, values: [0, 0, 0] },
  ],
};

function doc(version: number, tiles: unknown[]) {
  return {
    apiVersion: "oip/v1",
    kind: "MetricsDashboard",
    metadata: { name: "plant", module: "org.oip.gateway", version },
    spec: { title: "Plant", tiles },
  };
}

function gateway(initial: unknown[] = [{ id: "cpu", type: "gauge", metric: "cpu.system", title: "CPU" }, { id: "heap", type: "series", metric: "heap.used", title: "" }, { id: "gone", type: "gauge", metric: "plugin.x", title: "Plugin" }]) {
  let stored = doc(1, initial);
  const kit = fakeGateway(({ url, method, body }) => {
    if (url === "/api/v1/diagnostics/metrics") return json(200, metrics);
    const base = "/api/v1/config/org.oip.gateway/gateway/MetricsDashboard";
    if (url === base && method === "GET") return json(200, { items: [stored] });
    if (url === `${base}/plant` && method === "PUT") {
      const sent = JSON.parse(String(body)) as { spec: Record<string, unknown> };
      stored = { ...stored, metadata: { ...stored.metadata, version: stored.metadata.version + 1 }, spec: sent.spec as never };
      return json(200, stored);
    }
    return undefined;
  });
  return kit;
}

describe("metrics dashboards page", () => {
  it("draws a gauge and a time series from the gateway's metrics and says when a metric is missing", async () => {
    const kit = gateway();
    render(<MetricsPage section={sectionById("metrics")} client={kit.client} session={adminSession} />);
    const gauge = await screen.findByTestId("tile-cpu");
    expect(gauge.querySelector('[role="meter"]')).toHaveAttribute("aria-valuenow", "42.5");
    expect(gauge).toHaveTextContent("CPU");
    const series = screen.getByTestId("tile-heap");
    expect(series.querySelector(".oip-time-series")).not.toBeNull();
    expect(series).toHaveTextContent("Heap used");
    expect(screen.getByTestId("tile-gone")).toHaveTextContent("does not publish the metric plugin.x");
  });

  it("adds a tile, saves the dashboard as a config resource, and removes a tile", async () => {
    const kit = gateway([]);
    render(<MetricsPage section={sectionById("metrics")} client={kit.client} session={adminSession} />);
    await screen.findByText("This dashboard has no tiles. Add a gauge or a time series above.");
    await screen.findByRole("option", { name: "History queue depth (records)" });
    await kit.signIn();
    fireEvent.change(screen.getByLabelText("Metric"), { target: { value: "history.queueDepth" } });
    fireEvent.change(screen.getByLabelText("Tile title"), { target: { value: "Queue" } });
    fireEvent.click(screen.getByRole("button", { name: "Add tile" }));
    expect(await screen.findByTestId("tile-tile-1")).toHaveTextContent("Queue");
    fireEvent.change(screen.getByLabelText("Metric"), { target: { value: "cpu.system" } });
    fireEvent.change(screen.getByLabelText("Tile"), { target: { value: "series" } });
    fireEvent.click(screen.getByRole("button", { name: "Add tile" }));
    expect(await screen.findByTestId("tile-tile-2")).toHaveAttribute("data-type", "series");
    fireEvent.click(screen.getByRole("button", { name: "Remove Queue" }));
    expect(screen.queryByTestId("tile-tile-1")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Save dashboard" }));
    await waitFor(() => expect(screen.getByRole("status", { name: "" })).toHaveTextContent("Dashboard saved."));
    const put = kit.calls.find((c) => c.method === "PUT")!;
    expect(put.headers.get("If-Match")).toBe("1");
    const sent = JSON.parse(String(put.body)) as { spec: { tiles: { id: string; type: string; metric: string }[] } };
    expect(sent.spec.tiles).toEqual([{ id: "tile-2", type: "series", metric: "cpu.system", title: "" }]);
  });

  it("is read-only without gateway.write", async () => {
    const kit = gateway();
    render(<MetricsPage section={sectionById("metrics")} client={kit.client} session={readerSession} />);
    await screen.findByTestId("tile-cpu");
    expect(screen.queryByRole("button", { name: "Save dashboard" })).toBeNull();
    expect(screen.queryByRole("form", { name: "Add a tile" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove CPU" })).toBeNull();
  });

  it("parses stored tiles defensively and numbers new ones past the used ids", () => {
    expect(parseTiles({ tiles: [{ id: "a", type: "gauge", metric: "m", max: 50 }, { id: "b", type: "pie", metric: "m" }, 7, { id: "c" }] })).toEqual([
      { id: "a", type: "gauge", metric: "m", title: "", max: 50 },
    ]);
    expect(nextTileId([{ id: "tile-2", type: "gauge", metric: "m", title: "" }])).toBe("tile-3");
  });
});
