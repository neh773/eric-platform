import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GatewayClient, GatewayProvider } from "./gateway";
import { RadialGauge, TagBrowse, XyChart, parseGaugeRanges, parseXySeries } from "./batchCharts";
import { PowerChart } from "./charts";
import { componentRegistry } from "./registry";

describe("xy chart, radial gauge and tag browse", () => {
  it("parses series and ranges and draws them", () => {
    expect(parseXySeries("A:0,1;1,2 | B@y2:bad;0,0")).toEqual([
      { name: "A", axis: "y", points: [{ x: 0, y: 1 }, { x: 1, y: 2 }] },
      { name: "B", axis: "y2", points: [{ x: 0, y: 0 }] },
    ]);
    expect(parseGaugeRanges("0:50:ok,nope,50:100:alarm", 0, 100)).toEqual([
      { from: 0, to: 50, token: "ok" },
      { from: 50, to: 100, token: "alarm" },
    ]);
    const xy = render(<XyChart nodeId="x" locale="en-US" onChange={() => {}} props={{ title: "Plot", series: "A:0,1;1,2" }} />);
    expect(xy.container.querySelector("[data-series='A']")).not.toBeNull();
    const gauge = render(<RadialGauge nodeId="g" locale="en-US" onChange={() => {}} props={{ value: 40, value2: 80, showSecond: true, ranges: "0:100:accent" }} />);
    expect(gauge.container.querySelector("[data-needle='secondary']")).not.toBeNull();
    expect(componentRegistry().get("tag-browse")?.category).toBe("display");
    expect(componentRegistry().get("xy-chart")?.category).toBe("chart");
    expect(componentRegistry().get("radial-gauge")?.category).toBe("chart");
  });

  it("browses tags and selects one", async () => {
    const calls: Array<{ path: string; body: unknown }> = [];
    const fetchImpl: typeof fetch = async (input, init) => {
      const url = String(input);
      calls.push({ path: url, body: init?.body ? JSON.parse(String(init.body)) : null });
      if (url.endsWith("/api/v1/auth/session")) return json({ csrfToken: "t" });
      return json({
        nodes: [
          { name: "Level", path: "[default]Tank/Level", kind: "tag", hasChildren: false },
          { name: "More", path: "[default]Tank/More", kind: "folder", hasChildren: true },
        ],
      });
    };
    const client = new GatewayClient({ fetch: fetchImpl });
    const chosen: unknown[] = [];
    render(
      <GatewayProvider client={client}>
        <TagBrowse nodeId="b" locale="en-US" onChange={(_prop, value) => chosen.push(value)} props={{ root: "[default]Tank", label: "Tags" }} />
      </GatewayProvider>,
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "Level" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Level" }));
    expect(chosen).toEqual(["[default]Tank/Level"]);
    fireEvent.click(screen.getByRole("button", { name: "More" }));
    await waitFor(() => expect(calls.some((call) => (call.body as { path?: string })?.path === "[default]Tank/More")).toBe(true));
  });

  it("adds a browsed tag as a power chart pen", async () => {
    const fetchImpl: typeof fetch = async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/auth/session")) return json({ csrfToken: "t" });
      if (url.endsWith("/api/v1/tags/read")) return json({ values: [] });
      return json({ nodes: [{ name: "Flow", path: "[default]Tank/Flow", kind: "tag", hasChildren: false }] });
    };
    const client = new GatewayClient({ fetch: fetchImpl });
    const changes: unknown[] = [];
    render(
      <GatewayProvider client={client}>
        <PowerChart nodeId="p" locale="en-US" onChange={(_prop, value) => changes.push(value)} props={{ pens: "[default]Tank/Level", mode: "realtime" }} />
      </GatewayProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Browse tags" }));
    const add = await screen.findByRole("button", { name: "Flow" });
    fireEvent.click(add);
    expect(changes).toContain("[default]Tank/Level, [default]Tank/Flow");
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
}
