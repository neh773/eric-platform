import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AlarmStatusTable } from "./AlarmStatusTable";
import { GatewayClient, GatewayProvider } from "./gateway";
import { componentRegistry, defaultsFromSchema } from "./registry";

/**
 * Live alarm status table against an in-memory stand-in for the gateway alarm REST API.
 */

interface FakeAlarm {
  eventId: string;
  source: string;
  displayPath: string;
  priority: string;
  priorityCode: number;
  active: boolean;
  acknowledged: boolean;
  ackNotes?: string;
}

function fakeGateway(initial: FakeAlarm[], opts: { role?: "operator" | "viewer" } = {}) {
  const alarms = initial.map((alarm) => ({ ...alarm }));
  const shelved = new Set<string>();
  const requests: { method: string; url: string; body?: unknown }[] = [];
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined;
    requests.push({ method, url, body });
    if (url === "/api/v1/auth/session") return Response.json({ principal: "u", csrfToken: "tok" });
    if (url.startsWith("/api/v1/alarms") && method === "GET") {
      return Response.json({
        alarms: alarms
          .filter((alarm) => !shelved.has(alarm.source))
          .map((alarm) => ({
            ...alarm,
            name: "High",
            state: `${alarm.active ? "Active" : "Cleared"} and ${alarm.acknowledged ? "Acknowledged" : "Unacknowledged"}`,
            stateCode: (alarm.active ? 2 : 0) + (alarm.acknowledged ? 1 : 0),
            activeTime: "2026-10-27T10:00:00Z",
            ackTime: alarm.acknowledged ? "2026-10-27T10:01:00Z" : null,
          })),
      });
    }
    if (opts.role === "viewer") {
      return Response.json({ type: "urn:oip:problem:forbidden", detail: "missing permission tags.write" }, { status: 403 });
    }
    if (url === "/api/v1/alarms/ack") {
      for (const id of body!.eventIds as string[]) {
        const alarm = alarms.find((candidate) => candidate.eventId === id)!;
        alarm.acknowledged = true;
        if (typeof body!.notes === "string") alarm.ackNotes = body!.notes;
      }
      return Response.json({ acknowledged: body!.eventIds });
    }
    if (url === "/api/v1/alarms/shelve") {
      for (const source of body!.sources as string[]) shelved.add(source);
      return Response.json({ shelved: body!.sources });
    }
    if (url === "/api/v1/alarms/unshelve") {
      for (const source of body!.sources as string[]) shelved.delete(source);
      return Response.json({ unshelved: body!.sources });
    }
    return Response.json({ detail: "not found" }, { status: 404 });
  });
  return { client: new GatewayClient({ fetch }), requests, alarms, fetch };
}

const fixtures: FakeAlarm[] = [
  { eventId: "e1", source: "prov:default:/tag:tank/level:/alm:High", displayPath: "Tank level", priority: "High", priorityCode: 3, active: true, acknowledged: false },
  { eventId: "e2", source: "prov:default:/tag:pump/trip:/alm:High", displayPath: "Pump trip", priority: "Critical", priorityCode: 4, active: true, acknowledged: false },
  { eventId: "e3", source: "prov:default:/tag:valve:/alm:High", displayPath: "Valve", priority: "Low", priorityCode: 1, active: false, acknowledged: true },
];

function mount(gateway: ReturnType<typeof fakeGateway>, props: Record<string, unknown> = {}) {
  const onChange = vi.fn();
  render(
    <GatewayProvider client={gateway.client}>
      <AlarmStatusTable nodeId="alarms" locale="en-US" onChange={onChange} props={{ refreshRate: 60_000, ...props }} />
    </GatewayProvider>,
  );
  return onChange;
}

function rowFor(name: string) {
  return screen.getByRole("checkbox", { name: `Select ${name}` }).closest("tr")!;
}

describe("AlarmStatusTable", () => {
  it("is registered for the designer with inspector defaults", () => {
    const module = componentRegistry().get("alarm-status-table");
    expect(module?.title).toBe("Alarm status table");
    expect(defaultsFromSchema(module!.schema)).toMatchObject({ refreshRate: 2000, showClearedAcked: false, showDiagnostic: false });
  });

  it("loads live alarms, hides cleared-acknowledged by default, and renders states", async () => {
    const gateway = fakeGateway(fixtures);
    mount(gateway);
    await screen.findByText("Tank level");
    expect(screen.getByText("Pump trip")).toBeInTheDocument();
    expect(screen.queryByText("Valve")).toBeNull();
    expect(rowFor("Tank level")).toHaveAttribute("data-state", "activeUnacked");
    expect(within(rowFor("Tank level")).getByText("Active and Unacknowledged")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Cleared, acknowledged" }));
    expect(rowFor("Valve")).toHaveAttribute("data-state", "clearedAcked");
    expect(screen.getByRole("tab", { name: "Active alarms (3)" })).toBeInTheDocument();
  });

  it("sorts by priority from the header and filters by search and priority", async () => {
    const gateway = fakeGateway(fixtures);
    const onChange = mount(gateway);
    await screen.findByText("Tank level");
    fireEvent.click(screen.getByRole("button", { name: "Priority" }));
    expect(screen.getByRole("columnheader", { name: "Priority" })).toHaveAttribute("aria-sort", "descending");
    let rows = screen.getAllByRole("row").slice(1);
    expect(rows[0]).toHaveTextContent("Pump trip");
    fireEvent.click(screen.getByRole("button", { name: "Priority" }));
    rows = screen.getAllByRole("row").slice(1);
    expect(rows[0]).toHaveTextContent("Tank level");
    fireEvent.change(screen.getByLabelText("Search alarms"), { target: { value: "pump" } });
    expect(screen.queryByText("Tank level")).toBeNull();
    expect(onChange).toHaveBeenCalledWith("filter", "pump");
    fireEvent.change(screen.getByLabelText("Search alarms"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Critical" }));
    expect(screen.queryByText("Pump trip")).toBeNull();
    expect(screen.getByText("Tank level")).toBeInTheDocument();
  });

  it("acknowledges the selected alarm with notes and shows the new state after refresh", async () => {
    const gateway = fakeGateway(fixtures);
    mount(gateway);
    await screen.findByText("Tank level");
    const ack = screen.getByRole("button", { name: "Acknowledge" });
    expect(ack).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Select Tank level" }));
    fireEvent.change(screen.getByLabelText("Acknowledgement notes"), { target: { value: "walked down" } });
    fireEvent.click(ack);
    await screen.findByText("Acknowledged 1 alarm.");
    await waitFor(() => expect(rowFor("Tank level")).toHaveAttribute("data-state", "activeAcked"));
    expect(gateway.requests.find((request) => request.url === "/api/v1/alarms/ack")?.body).toEqual({ eventIds: ["e1"], notes: "walked down" });
    expect(rowFor("Pump trip")).toHaveAttribute("data-state", "activeUnacked");
  });

  it("shelves for the chosen duration, hides the alarm, and unshelves it from the shelved view", async () => {
    const gateway = fakeGateway(fixtures);
    mount(gateway, { shelvingTimes: "10, 120" });
    await screen.findByText("Pump trip");
    fireEvent.change(screen.getByLabelText("Shelve duration"), { target: { value: "120" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Select Pump trip" }));
    fireEvent.click(screen.getByRole("button", { name: "Shelve" }));
    await screen.findByText("Shelved 1 alarm for 2 hours.");
    await waitFor(() => expect(screen.queryByText("Pump trip")).toBeNull());
    expect(gateway.requests.find((request) => request.url === "/api/v1/alarms/shelve")?.body).toEqual({
      sources: ["prov:default:/tag:pump/trip:/alm:High"],
      durationSeconds: 7200,
    });
    fireEvent.click(screen.getByRole("tab", { name: "Shelved (1)" }));
    fireEvent.click(screen.getByRole("button", { name: "Unshelve Pump trip" }));
    await screen.findByText("Unshelved Pump trip.");
    fireEvent.click(screen.getByRole("tab", { name: /Active alarms/ }));
    await screen.findByText("Pump trip");
  });

  it("shows a 403 when a viewer tries to acknowledge, and keeps the row", async () => {
    const gateway = fakeGateway(fixtures, { role: "viewer" });
    mount(gateway);
    await screen.findByText("Tank level");
    fireEvent.click(screen.getByRole("checkbox", { name: "Select Tank level" }));
    fireEvent.click(screen.getByRole("button", { name: "Acknowledge" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Acknowledge failed: 403 missing permission tags.write");
    expect(rowFor("Tank level")).toHaveAttribute("data-state", "activeUnacked");
  });

  it("polls at the refresh rate and reports a failed poll", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const gateway = fakeGateway(fixtures);
      mount(gateway, { refreshRate: 500 });
      await screen.findByText("Tank level");
      const polls = () => gateway.requests.filter((request) => request.method === "GET" && request.url.startsWith("/api/v1/alarms")).length;
      const before = polls();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1600);
      });
      expect(polls()).toBeGreaterThanOrEqual(before + 3);
      gateway.alarms.push({ eventId: "e9", source: "s9", displayPath: "New alarm", priority: "Medium", priorityCode: 2, active: true, acknowledged: false });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(600);
      });
      expect(screen.getByText("New alarm")).toBeInTheDocument();
      gateway.fetch.mockImplementation(async () => Response.json({ detail: "gateway down" }, { status: 503 }));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(600);
      });
      expect(screen.getByRole("alert")).toHaveTextContent("Could not load alarms: 503 gateway down");
      // Last good rows stay on screen.
      expect(screen.getByText("New alarm")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not poll when live is off", async () => {
    const gateway = fakeGateway(fixtures);
    mount(gateway, { live: false });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(gateway.fetch).not.toHaveBeenCalled();
  });
});
