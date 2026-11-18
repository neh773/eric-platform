import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AlarmJournalTable, DATE_DEBOUNCE_MS } from "./alarmJournal";
import { mockJournalResponse, mockJournalRows } from "./alarmJournalMock";
import { GatewayClient, GatewayProvider } from "./gateway";
import { componentRegistry, defaultsFromSchema } from "./registry";

/**
 * Alarm journal table against an in-memory stand-in for GET /api/v1/alarms/journal.
 */

const MORNING = { rangeMode: "historical", startTime: "2026-10-28T00:00:00Z", endTime: "2026-10-29T00:00:00Z" };

function fakeGateway(opts: { fail?: number } = {}) {
  const rows = mockJournalRows();
  const requests: string[] = [];
  const fetch = vi.fn(async (url: string) => {
    requests.push(url);
    if (opts.fail) return Response.json({ detail: "journal unavailable" }, { status: opts.fail });
    if (url.startsWith("/api/v1/alarms/journal")) {
      const { status, body } = mockJournalResponse(rows, url);
      return Response.json(body, { status });
    }
    return Response.json({ detail: "not found" }, { status: 404 });
  });
  return { client: new GatewayClient({ fetch }), requests, fetch, rows };
}

/** A gateway whose journal responses stay pending until the test resolves them, in any order. */
function deferredGateway() {
  const rows = mockJournalRows();
  const pending: { url: string; resolve: () => void }[] = [];
  const fetch = vi.fn(
    (url: string) =>
      new Promise<Response>((resolve) => {
        pending.push({
          url,
          resolve: () => {
            const { status, body } = mockJournalResponse(rows, url);
            resolve(Response.json(body, { status }));
          },
        });
      }),
  );
  return { client: new GatewayClient({ fetch }), pending, fetch };
}

function mount(gateway: { client: GatewayClient }, props: Record<string, unknown> = {}) {
  const onChange = vi.fn();
  const view = (next: Record<string, unknown>) => (
    <GatewayProvider client={gateway.client}>
      <AlarmJournalTable nodeId="journal" locale="en-US" onChange={onChange} props={{ ...MORNING, ...next }} />
    </GatewayProvider>
  );
  const result = render(view(props));
  return Object.assign(onChange, {
    rerender: (next: Record<string, unknown>) => result.rerender(view(next)),
    unmount: result.unmount,
  });
}

function lastQuery(gateway: ReturnType<typeof fakeGateway>): URLSearchParams {
  const url = gateway.requests[gateway.requests.length - 1]!;
  return new URLSearchParams(url.split("?")[1]);
}

function bodyRows() {
  return screen.getAllByRole("row").slice(1).filter((row) => row.hasAttribute("data-journal-id"));
}

describe("AlarmJournalTable", () => {
  it("is registered for the designer with inspector defaults", () => {
    const module = componentRegistry().get("alarm-journal-table");
    expect(module?.title).toBe("Alarm journal table");
    expect(defaultsFromSchema(module!.schema)).toMatchObject({
      rangeMode: "realtime",
      realtimeMinutes: 480,
      minPriority: "Low",
      maxPriority: "Critical",
      pageSize: 100,
      newestFirst: true,
    });
  });

  it("loads the newest page first, leaves out Diagnostic by default, and pages through the gateway", async () => {
    const gateway = fakeGateway();
    mount(gateway, { pageSize: 25 });
    await screen.findByText("Rows 1–25 of 71 (page 1 of 3)");
    const query = lastQuery(gateway);
    expect(query.get("start")).toBe("2026-10-28T00:00:00.000Z");
    expect(query.get("end")).toBe("2026-10-29T00:00:00.000Z");
    expect(query.get("minPriority")).toBe("Low");
    expect(query.get("limit")).toBe("25");
    expect(bodyRows()).toHaveLength(25);
    const first = bodyRows()[0]!;
    expect(first).toHaveAttribute("data-event-type", "unshelve");
    expect(within(first).getByText("Unshelved")).toBeInTheDocument();
    expect(within(first).getByText("admin")).toBeInTheDocument();
    expect(screen.queryByText("Room/Temperature")).toBeNull();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Rows 26–50 of 71 (page 2 of 3)");
    expect(lastQuery(gateway).get("offset")).toBe("25");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Rows 51–71 of 71 (page 3 of 3)");
    expect(bodyRows()).toHaveLength(21);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Previous" }));
    await screen.findByText("Rows 26–50 of 71 (page 2 of 3)");
  });

  it("filters by event type on the gateway and starts again from the first page", async () => {
    const gateway = fakeGateway();
    const onChange = mount(gateway, { pageSize: 25 });
    await screen.findByText("Rows 1–25 of 71 (page 1 of 3)");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText(/^Rows 26–50/);
    for (const name of ["Active", "Cleared", "Acknowledged"]) fireEvent.click(screen.getByRole("checkbox", { name }));
    await screen.findByText("Rows 1–2 of 2 (page 1 of 1)");
    const query = lastQuery(gateway);
    expect(query.get("eventType")).toBe("shelve,unshelve");
    expect(query.has("offset")).toBe(false);
    expect(bodyRows().map((row) => row.getAttribute("data-event-type"))).toEqual(["unshelve", "shelve"]);
    expect(onChange).toHaveBeenCalledWith("showActive", false);

    for (const name of ["Shelved", "Unshelved"]) fireEvent.click(screen.getByRole("checkbox", { name }));
    await screen.findByText("No journal events match the filters.");
    expect(screen.getByText("Rows 0–0 of 0 (page 1 of 1)")).toBeInTheDocument();
  });

  it("narrows by priority range and source pattern", async () => {
    const gateway = fakeGateway();
    mount(gateway, { sourceFilter: "*:/tag:pump/*,*:/tag:tank/*", pageSize: 100 });
    await screen.findByText(/^Rows 1–/);
    expect(lastQuery(gateway).get("source")).toBe("*:/tag:pump/*,*:/tag:tank/*");
    expect(new Set(bodyRows().map((row) => row.getAttribute("data-priority")))).toEqual(new Set(["high", "critical"]));

    fireEvent.change(screen.getByLabelText("Lowest priority"), { target: { value: "Critical" } });
    await waitFor(() => expect(lastQuery(gateway).get("minPriority")).toBe("Critical"));
    await waitFor(() => expect(new Set(bodyRows().map((row) => row.getAttribute("data-priority")))).toEqual(new Set(["critical"])));

    fireEvent.change(screen.getByLabelText("Lowest priority"), { target: { value: "Diagnostic" } });
    fireEvent.change(screen.getByLabelText("Highest priority"), { target: { value: "Low" } });
    await waitFor(() => expect(lastQuery(gateway).get("maxPriority")).toBe("Low"));
    expect(lastQuery(gateway).has("minPriority")).toBe(false);
    await screen.findByText("No journal events match the filters.");
  });

  it("reverses the order from the event time header", async () => {
    const gateway = fakeGateway();
    const onChange = mount(gateway, { pageSize: 10 });
    await screen.findByText(/^Rows 1–10/);
    expect(screen.getByRole("columnheader", { name: "Event time" })).toHaveAttribute("aria-sort", "descending");
    fireEvent.click(screen.getByRole("button", { name: "Event time" }));
    await waitFor(() => expect(lastQuery(gateway).get("order")).toBe("asc"));
    await waitFor(() => expect(bodyRows()[0]).toHaveAttribute("data-event-id", "ev-0"));
    expect(bodyRows()[0]).toHaveAttribute("data-event-type", "active");
    expect(screen.getByRole("columnheader", { name: "Event time" })).toHaveAttribute("aria-sort", "ascending");
    expect(onChange).toHaveBeenCalledWith("newestFirst", false);
  });

  it("shows row details with the event data and reports the selected event", async () => {
    const gateway = fakeGateway();
    const onChange = mount(gateway, { pageSize: 100, columns: "eventTime, displayPath, eventType, actor, shelvedUntil" });
    await screen.findByText(/^Rows 1–/);
    const shelve = bodyRows().find((row) => row.getAttribute("data-event-type") === "shelve")!;
    expect(within(shelve).getByText("operator1")).toBeInTheDocument();
    expect(shelve.querySelector('[data-column="shelvedUntil"]')!.textContent).not.toBe("");
    const ack = bodyRows().find((row) => row.getAttribute("data-event-type") === "ack")!;
    fireEvent.click(ack);
    expect(ack).toHaveAttribute("aria-expanded", "true");
    const details = document.querySelector<HTMLElement>(`[data-details-for="${ack.getAttribute("data-journal-id")}"] dl`)!;
    expect(within(details).getByText("ackNotes")).toBeInTheDocument();
    expect(within(details).getByText("note 29")).toBeInTheDocument();
    expect(onChange).toHaveBeenCalledWith("selectedEventId", ack.getAttribute("data-event-id"));
    fireEvent.click(ack);
    expect(ack).toHaveAttribute("aria-expanded", "false");
    expect(onChange).toHaveBeenLastCalledWith("selectedEventId", null);
  });

  it("searches the loaded page in the browser without a new query", async () => {
    const gateway = fakeGateway();
    mount(gateway, { pageSize: 100, columns: "eventTime, displayPath, eventType, ackNotes" });
    await screen.findByText(/^Rows 1–/);
    const before = gateway.requests.length;
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "note 28" } });
    expect(bodyRows()).toHaveLength(1);
    expect(bodyRows()[0]).toHaveTextContent("Tank/Level high");
    expect(gateway.requests.length).toBe(before);
  });

  it("reports a bad historical date without querying, and gateway errors as alerts", async () => {
    const gateway = fakeGateway();
    mount(gateway, { startTime: "not a date" });
    expect(await screen.findByRole("alert")).toHaveTextContent('Start time "not a date" is not a valid date.');
    expect(gateway.requests).toHaveLength(0);

    const failing = fakeGateway({ fail: 403 });
    render(
      <GatewayProvider client={failing.client}>
        <AlarmJournalTable nodeId="j2" locale="en-US" onChange={vi.fn()} props={MORNING} />
      </GatewayProvider>,
    );
    await waitFor(() =>
      expect(screen.getAllByRole("alert").map((alert) => alert.textContent)).toContain("Could not load the alarm journal: 403 journal unavailable"),
    );
  });

  it("looks back the recent range from now and polls", async () => {
    const gateway = fakeGateway();
    mount(gateway, { rangeMode: "realtime", realtimeMinutes: 60, refreshRate: 1000 });
    await waitFor(() => expect(gateway.requests.length).toBeGreaterThan(0));
    const start = Date.parse(lastQuery(gateway).get("start")!);
    expect(Math.abs(Date.now() - 60 * 60_000 - start)).toBeLessThan(10_000);
    expect(lastQuery(gateway).has("end")).toBe(false);
    await waitFor(() => expect(gateway.requests.length).toBeGreaterThan(1), { timeout: 3000 });
  });

  it("queries once in the date range mode", async () => {
    const gateway = fakeGateway();
    mount(gateway, { refreshRate: 1000 });
    await screen.findByText(/^Rows 1–/);
    await new Promise((resolve) => setTimeout(resolve, 1500));
    expect(gateway.requests).toHaveLength(1);
  });

  it("clamps the page to the last one that still has rows when the journal shrinks", async () => {
    const gateway = fakeGateway();
    // A look-back wide enough to include the fixture morning, polled every second.
    mount(gateway, { rangeMode: "realtime", realtimeMinutes: 20_000_000, refreshRate: 1000, pageSize: 25 });
    await screen.findByText("Rows 1–25 of 71 (page 1 of 3)");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText(/^Rows 26–50/);
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Rows 51–71 of 71 (page 3 of 3)");

    const kept = gateway.rows.filter((row) => row.priority !== "Diagnostic");
    gateway.rows.splice(0, gateway.rows.length, ...kept.slice(0, 30));
    await screen.findByText("Rows 26–30 of 30 (page 2 of 2)", undefined, { timeout: 3000 });
    expect(bodyRows()).toHaveLength(5);
    expect(lastQuery(gateway).get("offset")).toBe("25");

    gateway.rows.splice(24);
    await screen.findByText("Rows 1–24 of 24 (page 1 of 1)", undefined, { timeout: 3000 });
    expect(bodyRows()).toHaveLength(24);
    expect(lastQuery(gateway).has("offset")).toBe(false);
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();

    gateway.rows.splice(0);
    await screen.findByText("Rows 0–0 of 0 (page 1 of 1)", undefined, { timeout: 3000 });
  });

  it("drops a response that arrives after a newer one", async () => {
    const gateway = deferredGateway();
    mount(gateway, { pageSize: 25 });
    await waitFor(() => expect(gateway.pending).toHaveLength(1));
    await act(async () => gateway.pending[0]!.resolve());
    await screen.findByText("Rows 1–25 of 71 (page 1 of 3)");

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(gateway.pending).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(gateway.pending).toHaveLength(3));
    expect(new URL(gateway.pending[1]!.url, "http://x").searchParams.get("offset")).toBe("25");
    expect(new URL(gateway.pending[2]!.url, "http://x").searchParams.get("offset")).toBe("50");

    await act(async () => gateway.pending[2]!.resolve());
    await screen.findByText("Rows 51–71 of 71 (page 3 of 3)");
    await act(async () => {
      gateway.pending[1]!.resolve();
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(screen.getByText("Rows 51–71 of 71 (page 3 of 3)")).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(21);
  });

  it("stops polling and ignores an in-flight response after unmount", async () => {
    const settled = fakeGateway();
    const first = mount(settled, { rangeMode: "realtime", realtimeMinutes: 60, refreshRate: 1000 });
    await waitFor(() => expect(settled.requests).toHaveLength(1));
    await new Promise((resolve) => setTimeout(resolve, 50));
    first.unmount();
    await new Promise((resolve) => setTimeout(resolve, 1500));
    expect(settled.requests).toHaveLength(1);

    const inFlight = deferredGateway();
    const errors = vi.spyOn(console, "error");
    const second = mount(inFlight, { rangeMode: "realtime", realtimeMinutes: 60, refreshRate: 1000 });
    await waitFor(() => expect(inFlight.pending).toHaveLength(1));
    second.unmount();
    await act(async () => inFlight.pending[0]!.resolve());
    await new Promise((resolve) => setTimeout(resolve, 1500));
    expect(inFlight.fetch).toHaveBeenCalledTimes(1);
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it("follows every prop after mount", async () => {
    const gateway = fakeGateway();
    let props: Record<string, unknown> = { pageSize: 25 };
    const view = mount(gateway, props);
    await screen.findByText("Rows 1–25 of 71 (page 1 of 3)");
    const update = async (change: Record<string, unknown>, check: () => void) => {
      props = { ...props, ...change };
      view.rerender(props);
      await waitFor(check);
    };

    await update({ pageSize: 10 }, () => expect(lastQuery(gateway).get("limit")).toBe("10"));
    expect(screen.getByLabelText("Rows per page")).toHaveValue("10");
    await screen.findByText("Rows 1–10 of 71 (page 1 of 8)");

    await update({ minPriority: "High" }, () => expect(lastQuery(gateway).get("minPriority")).toBe("High"));
    expect(screen.getByLabelText("Lowest priority")).toHaveValue("High");
    await update({ maxPriority: "high" }, () => expect(lastQuery(gateway).get("maxPriority")).toBe("High"));
    expect(screen.getByLabelText("Highest priority")).toHaveValue("High");
    await update({ minPriority: "Low", maxPriority: "Critical" }, () => expect(lastQuery(gateway).has("maxPriority")).toBe(false));

    const flags: [string, string, string][] = [
      ["showActive", "Active", "active"],
      ["showCleared", "Cleared", "clear"],
      ["showAcked", "Acknowledged", "ack"],
      ["showShelved", "Shelved", "shelve"],
      ["showUnshelved", "Unshelved", "unshelve"],
    ];
    for (const [prop, label, type] of flags) {
      await update({ [prop]: false }, () => expect(lastQuery(gateway).get("eventType")!.split(",")).not.toContain(type));
      expect(screen.getByRole("checkbox", { name: label })).not.toBeChecked();
      await update({ [prop]: true }, () => expect(lastQuery(gateway).get("eventType")!.split(",")).toContain(type));
      expect(screen.getByRole("checkbox", { name: label })).toBeChecked();
    }

    await update({ newestFirst: false }, () => expect(lastQuery(gateway).get("order")).toBe("asc"));
    expect(screen.getByRole("columnheader", { name: "Event time" })).toHaveAttribute("aria-sort", "ascending");

    // A user change sticks until the prop changes again.
    fireEvent.change(screen.getByLabelText("Lowest priority"), { target: { value: "Critical" } });
    await waitFor(() => expect(lastQuery(gateway).get("minPriority")).toBe("Critical"));
    await update({ minPriority: "Medium" }, () => expect(lastQuery(gateway).get("minPriority")).toBe("Medium"));

    await screen.findByText(/^Rows 1–10/);
    const before = gateway.requests.length;
    props = { ...props, filter: "Pump" };
    view.rerender(props);
    expect(screen.getByLabelText("Search")).toHaveValue("Pump");
    expect(bodyRows().length).toBeGreaterThan(0);
    for (const row of bodyRows()) expect(row).toHaveTextContent("Pump/Trip");
    expect(gateway.requests.length).toBe(before);
  });

  it("queries a typed date after a pause or on blur, and clears the rows for an invalid one", async () => {
    const gateway = fakeGateway();
    const onChange = mount(gateway, { pageSize: 100 });
    await screen.findByText(/^Rows 1–71 of 71/);
    expect(gateway.requests).toHaveLength(1);
    const startInput = screen.getByLabelText("Start time");

    // Keystrokes of a half-typed date: no query, no error.
    for (const text of ["2", "2026-1", "2026-10-28T", "2026-10-28T08:3"]) fireEvent.change(startInput, { target: { value: text } });
    expect(screen.queryByRole("alert")).toBeNull();
    expect(gateway.requests).toHaveLength(1);
    fireEvent.change(startInput, { target: { value: "2026-10-28T08:30:00Z" } });
    await new Promise((resolve) => setTimeout(resolve, DATE_DEBOUNCE_MS / 2));
    expect(gateway.requests).toHaveLength(1);
    await waitFor(() => expect(gateway.requests).toHaveLength(2), { timeout: DATE_DEBOUNCE_MS * 3 });
    expect(lastQuery(gateway).get("start")).toBe("2026-10-28T08:30:00.000Z");
    expect(onChange).toHaveBeenCalledWith("startTime", "2026-10-28T08:30:00Z");
    expect(onChange).not.toHaveBeenCalledWith("startTime", "2026-1");
    await screen.findByText(/^Rows 1–\d+ of \d+/);
    expect(bodyRows().length).toBeGreaterThan(0);

    // An invalid date committed on blur: the error replaces the rows.
    fireEvent.change(startInput, { target: { value: "garbage" } });
    fireEvent.blur(startInput);
    expect(await screen.findByRole("alert")).toHaveTextContent('Start time "garbage" is not a valid date.');
    expect(bodyRows()).toHaveLength(0);
    expect(screen.queryByText("No journal events match the filters.")).toBeNull();
    expect(screen.getByText("Rows 0–0 of 0 (page 1 of 1)")).toBeInTheDocument();
    expect(gateway.requests).toHaveLength(2);

    // Enter commits a fixed date at once.
    fireEvent.change(startInput, { target: { value: "2026-10-28T00:00:00Z" } });
    fireEvent.keyDown(startInput, { key: "Enter" });
    await screen.findByText("Rows 1–71 of 71 (page 1 of 1)");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("keeps the last page after a failed poll but marks it as out of date", async () => {
    let fail = false;
    const rows = mockJournalRows();
    const fetch = vi.fn(async (url: string) => {
      if (fail) return Response.json({ detail: "journal unavailable" }, { status: 503 });
      const { status, body } = mockJournalResponse(rows, url);
      return Response.json(body, { status });
    });
    mount({ client: new GatewayClient({ fetch }) }, { rangeMode: "realtime", realtimeMinutes: 20_000_000, refreshRate: 1000, pageSize: 25 });
    await screen.findByText("Rows 1–25 of 71 (page 1 of 3)");
    fail = true;
    expect(await screen.findByRole("alert", undefined, { timeout: 3000 })).toHaveTextContent(
      "Could not load the alarm journal: 503 journal unavailable Showing the last loaded rows.",
    );
    expect(bodyRows()).toHaveLength(25);
    expect(document.querySelector(".oip-alarm-journal")).toHaveAttribute("data-stale", "true");
    fail = false;
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull(), { timeout: 3000 });
    expect(document.querySelector(".oip-alarm-journal")).not.toHaveAttribute("data-stale");
  });
});
