import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminPage, installAdminPages } from "./pages";
import { STORE_FORWARD_POLL_MS, PROVIDERS_PATH } from "./storeForward";
import { StoreAndForwardPage } from "./storeForwardPage";
import { adminSession, fakeGateway, json, readerSession, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const payload = {
  collector: { pending: 1, dropped: 0 },
  providers: [
    {
      name: "plant",
      pending: 2,
      quarantined: 1,
      forwarded: 0,
      storeRate: 2.5,
      connected: false,
      storeAndForward: true,
      configured: true,
      lastError: "connection refused",
    },
  ],
};

const quarantine = {
  records: [
    {
      id: 7,
      provider: "plant",
      tagPath: "[default]Plant/Flow",
      timestamp: "2026-06-01T00:00:01Z",
      value: 500,
      quality: "Good",
      error: "22003: value out of range",
      quarantinedAt: "2026-06-01T00:00:02Z",
    },
  ],
};

const journal = { queued: 4, retrying: true, lostRows: 2 };

function gateway() {
  return fakeGateway((call) => {
    if (call.url.startsWith(PROVIDERS_PATH)) return json(200, payload);
    if (call.url.startsWith("/api/v1/diagnostics/history/quarantine") && call.method === "GET") return json(200, quarantine);
    if (call.url.startsWith("/api/v1/diagnostics/alarms/journal")) return json(200, journal);
    if (call.method === "POST" && call.url.endsWith("/retry")) return json(200, { retried: 1 });
    if (call.method === "POST" && call.url.endsWith("/delete")) return json(200, { deleted: 1 });
    if (call.method === "POST" && call.url.endsWith("/quarantine/import")) return json(200, { retried: 1 });
    if (call.method === "POST" && call.url.endsWith("/cache/load")) return json(200, { loaded: 2 });
    return undefined;
  });
}

describe("store and forward page", () => {
  it("shows pending, buffer fill, throughput, quarantine, and journal counters", async () => {
    const kit = gateway();
    render(<StoreAndForwardPage section={sectionById("store-and-forward")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("saf-pending-plant")).toHaveTextContent("2");
    expect(screen.getByTestId("saf-buffer-plant")).toHaveTextContent("2 records");
    expect(screen.getByTestId("saf-quarantined-plant")).toHaveTextContent("1");
    expect(screen.getByTestId("saf-store-rate-plant")).toHaveTextContent("2.5 /s");
    expect(screen.getByTestId("saf-archive-plant")).toBeVisible();
    expect(screen.getByTestId("saf-throughput-plant")).toHaveTextContent("—");
    expect(screen.getByTestId("memory-buffer")).toHaveTextContent("Memory buffer 1 records");
    expect(screen.getByTestId("journal-queued")).toHaveTextContent("4");
    expect(screen.getByTestId("journal-retrying")).toHaveTextContent("yes");
    expect(screen.getByTestId("journal-lost")).toHaveTextContent("2");
    expect(screen.getByRole("cell", { name: "[default]Plant/Flow" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Export JSON" }).getAttribute("href")).toContain("ids=7");
    expect(screen.getByRole("link", { name: "Export CSV" }).getAttribute("href")).toContain("format=csv");
  });

  it("polls once a second and does not remount the table when nothing changed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const kit = gateway();
    render(<StoreAndForwardPage section={sectionById("store-and-forward")} client={kit.client} session={adminSession} />);
    const table = await screen.findByRole("table", { name: "Store and forward engines" });
    expect(screen.queryByText("Loading store-and-forward status.")).toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(STORE_FORWARD_POLL_MS + 50);
    });
    await waitFor(() => expect(screen.getByTestId("saf-throughput-plant")).toHaveTextContent("0.0 /s"));
    expect(screen.getByRole("table", { name: "Store and forward engines" })).toBe(table);
    const stamp = table.getAttribute("data-updated");
    const calls = kit.hits(PROVIDERS_PATH).length;
    expect(calls).toBeLessThanOrEqual(3);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(STORE_FORWARD_POLL_MS + 50);
    });
    await waitFor(() => expect(kit.hits(PROVIDERS_PATH).length).toBeGreaterThan(calls));
    expect(screen.getByRole("table", { name: "Store and forward engines" })).toBe(table);
    expect(table.getAttribute("data-updated")).toBe(stamp);
    expect(screen.queryByText("Loading store-and-forward status.")).toBeNull();
    // Three seconds of timers must not turn into a tight loop.
    expect(kit.hits(PROVIDERS_PATH).length).toBeLessThan(8);
  });

  it("retries a selected record for a writer and hides the actions from a reader", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<StoreAndForwardPage section={sectionById("store-and-forward")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("saf-pending-plant");
    fireEvent.click(screen.getByRole("checkbox", { name: "Select 7" }));
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(kit.calls.some((call) => call.method === "POST" && call.url.endsWith("/retry"))).toBe(true));
    const post = kit.calls.find((call) => call.method === "POST" && call.url.endsWith("/retry"));
    expect(JSON.parse(String(post?.body))).toEqual({ ids: [7] });
    expect(post?.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(await screen.findByRole("status")).toHaveTextContent("Retried 1.");
  });

  it("hides retry, delete, and export from a read-only session", async () => {
    const kit = gateway();
    render(<StoreAndForwardPage section={sectionById("store-and-forward")} client={kit.client} session={readerSession} />);
    expect(await screen.findByTestId("saf-read-only")).toBeVisible();
    expect(screen.getByTestId("saf-pending-plant")).toHaveTextContent("2");
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Import" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Archive" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Load disk cache" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Export JSON" })).toBeNull();
  });

  it("hides the figures when the session cannot read the gateway", async () => {
    const kit = gateway();
    render(<StoreAndForwardPage section={sectionById("store-and-forward")} client={kit.client} session={viewerSession} />);
    expect(await screen.findByTestId("saf-no-read")).toBeVisible();
    expect(screen.queryByTestId("saf-engines")).toBeNull();
    expect(kit.hits(PROVIDERS_PATH)).toHaveLength(0);
  });

  it("imports a quarantine export file and posts it unchanged", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<StoreAndForwardPage section={sectionById("store-and-forward")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("saf-store-rate-plant");
    const button = screen.getByRole("button", { name: "Import" });
    expect(button).toBeDisabled();
    const file = new File(
      ['{"records":[{"provider":"plant","tagPath":"[default]Plant/Flow","timestamp":"2026-06-01T00:00:01Z","value":500,"quality":"Good"}]}'],
      "quarantine.json",
      { type: "application/json" },
    );
    fireEvent.change(screen.getByLabelText("Import quarantine file"), { target: { files: [file] } });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    await waitFor(() => expect(kit.calls.some((call) => call.method === "POST" && call.url.endsWith("/quarantine/import"))).toBe(true));
    const post = kit.calls.find((call) => call.method === "POST" && call.url.endsWith("/quarantine/import"));
    expect(post?.headers.get("Content-Type")).toBe("application/json");
    expect(post?.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(String(post?.body)).toContain('"value":500');
    expect(await screen.findByRole("status")).toHaveTextContent("Retried 1.");
  });

  it("is registered as the store and forward admin page", async () => {
    installAdminPages();
    const kit = gateway();
    render(<AdminPage section={sectionById("store-and-forward")} client={kit.client} session={adminSession} />);
    expect(await screen.findByRole("heading", { level: 1, name: "Store and Forward" })).toBeVisible();
    expect(screen.queryByTestId("section-placeholder")).toBeNull();
    expect(await screen.findByTestId("journal-lost")).toHaveTextContent("2");
  });
});
