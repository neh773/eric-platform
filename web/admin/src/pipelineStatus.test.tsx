import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PipelineStatusPanel, PIPELINE_STATUS_PATH, parsePipelineStatus } from "./pipelineStatus";
import { fakeGateway, json } from "./statusTestKit";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("pipeline status panel", () => {
  it("lists the running pipelines and the events inside them", async () => {
    const { client } = fakeGateway((call) =>
      call.url === PIPELINE_STATUS_PATH
        ? json(200, {
            pipelines: [{ name: "page-ops", inFlight: 2 }],
            events: [
              { eventId: "e1", pipeline: "page-ops", block: "wait", alarm: "High", displayPath: "Tank/Level/High", state: "Active and Unacknowledged", waitingUntil: "2026-06-01T09:00:30Z" },
              { eventId: "e2", pipeline: "page-ops", block: "notify", alarm: "Low", displayPath: "", state: "Active and Unacknowledged", waitingUntil: null },
            ],
          })
        : undefined,
    );
    render(<PipelineStatusPanel client={client} />);
    expect(await screen.findByText("page-ops: 2 in flight")).toBeVisible();
    const table = screen.getByRole("table", { name: "Events in flight" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByText("Tank/Level/High")).toBeVisible();
    expect(within(rows[1]).getByText("wait")).toBeVisible();
    expect(within(rows[2]).getByText("Low")).toBeVisible();
    expect(within(rows[2]).getByText("Running")).toBeVisible();
  });

  it("says so when nothing is running and when the status cannot be read", async () => {
    const empty = fakeGateway((call) => (call.url === PIPELINE_STATUS_PATH ? json(200, { pipelines: [], events: [] }) : undefined));
    const first = render(<PipelineStatusPanel client={empty.client} />);
    expect(await screen.findByText("No pipelines are running.")).toBeVisible();
    first.unmount();
    const broken = fakeGateway(() => json(500, { title: "boom" }));
    render(<PipelineStatusPanel client={broken.client} />);
    expect(await screen.findByText("Pipeline status is unavailable.")).toBeVisible();
  });

  it("ignores malformed rows", () => {
    expect(parsePipelineStatus({ pipelines: [null, { name: "a", inFlight: 1 }], events: "x" })).toEqual({
      pipelines: [{ name: "a", inFlight: 1 }],
      events: [],
    });
  });
});
