import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DiagnosticsPage, PERFORMANCE_REFRESH_MS } from "./diagnosticsPage";
import { adminSession, fakeGateway, json, sectionById } from "./statusTestKit";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const performance = {
  cpuLoad: 0.5,
  processCpuLoad: 0.02,
  heapUsedBytes: 64 * 1024 * 1024,
  heapMaxBytes: 1024 * 1024 * 1024,
  disk: { path: "/data", usableBytes: 10 * 1024 ** 3, totalBytes: 20 * 1024 ** 3 },
  clockDrift: { intervalMs: 1000, drift: 0.002, lateMillis: 2, samples: 5 },
  samples: [{ cpuLoad: 0.4, heapUsedBytes: 1024, diskUsableBytes: 2048, clockDrift: 0.001 }],
};

function gateway() {
  return fakeGateway(({ url }) => {
    if (url === "/api/v1/diagnostics/performance") return json(200, performance);
    if (url === "/api/v1/diagnostics/threads") {
      return json(200, {
        threads: [
          { id: 1, name: "main", state: "RUNNABLE", daemon: false, cpuNanos: 5_000_000, blockedCount: 0, waitedCount: 2 },
          { id: 7, name: "diagnostics-sampler", state: "TIMED_WAITING", daemon: true, cpuNanos: -1, blockedCount: 1, waitedCount: 9 },
        ],
      });
    }
    if (url === "/api/v1/diagnostics/tasks") {
      return json(200, { tasks: [{ name: "diagnostics-sampler", rateMs: 5000, state: "IDLE", lastDurationMillis: 1.5, runs: 12, lastError: null }] });
    }
    if (url === "/api/v1/diagnostics/scripts") {
      return json(200, {
        scripts: [{ name: "pump-timer", type: "timer", running: 1, executions: 40, failures: 2, lastMillis: 12, avgMillis: 9.5, maxMillis: 80, lastError: "boom", lastFinishedAt: 1, recentLog: ["INFO tick done"] }],
      });
    }
    if (url === "/api/v1/diagnostics/memory") {
      return json(200, {
        heap: { usedBytes: 1024 * 1024, committedBytes: 2 * 1024 * 1024, maxBytes: 4 * 1024 * 1024 },
        nonHeap: { usedBytes: 512, committedBytes: 1024 },
        processors: 8,
      });
    }
    if (url === "/api/v1/diagnostics/modules") {
      return json(200, { modules: [{ id: "org.oip.driver-modbus", name: "Modbus Driver", version: "0.1.0", state: "RUNNING", error: null }] });
    }
    if (url === "/api/v1/diagnostics/environment") {
      return json(200, {
        gatewayVersion: "0.1.0", profile: "edge", javaVersion: "21.0.5", javaVendor: "Test", os: "Linux 6", architecture: "amd64",
        processors: 4, timeZone: "UTC", dataDirectory: "/data", startedAt: "2026-11-09T00:00:00Z", uptimeMillis: 61000,
        deploymentMode: "", configResources: { resources: 12, loadErrors: 0, byModule: {} },
      });
    }
    if (url === "/api/v1/connections") {
      return json(200, {
        items: [{ id: "device:modbus:plc", kind: "device", type: "modbus", name: "plc", status: "Connected", health: "ok", detail: "", section: "config" }],
        summary: { total: 1, ok: 1, pending: 0, fault: 0, disabled: 0 },
      });
    }
    if (url === "/api/v1/architectures") {
      return json(200, {
        node: { profile: "edge", bindHost: "127.0.0.1", redundancyMode: "independent", historyMode: "partial" },
        architectures: [{ id: "edge-panel", feature: "GAN-011", title: "Edge panel", summary: "Small.", fits: true, checks: [] }],
        subsystems: [],
      });
    }
    return undefined;
  });
}

describe("diagnostics page", () => {
  it("refreshes the performance panel on a timer", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const kit = gateway();
    render(<DiagnosticsPage section={sectionById("diagnostics")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("performance-panel")).toHaveTextContent("50.0%");
    expect(kit.hits("/api/v1/diagnostics/performance")).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PERFORMANCE_REFRESH_MS + 50);
    });
    await waitFor(() => expect(kit.hits("/api/v1/diagnostics/performance").length).toBeGreaterThanOrEqual(2));
  });

  it("lists threads with a dump download, tasks, memory, and modules", async () => {
    const kit = gateway();
    render(<DiagnosticsPage section={sectionById("diagnostics")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("performance-panel");

    fireEvent.click(screen.getByRole("tab", { name: "Threads" }));
    expect(await screen.findByTestId("thread-summary")).toHaveTextContent("2 threads: 1 RUNNABLE, 1 TIMED_WAITING");
    expect(screen.getByRole("cell", { name: "diagnostics-sampler" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Download thread dump" })).toHaveAttribute("href", "/api/v1/diagnostics/threads/dump");
    fireEvent.change(screen.getByLabelText("Thread name"), { target: { value: "main" } });
    expect(screen.queryByRole("cell", { name: "diagnostics-sampler" })).toBeNull();

    fireEvent.click(screen.getByRole("tab", { name: "Scheduled tasks" }));
    const tasks = await screen.findByRole("table", { name: "Scheduled tasks list" });
    expect(tasks).toHaveTextContent("diagnostics-sampler");
    expect(tasks).toHaveTextContent("5000 ms");

    fireEvent.click(screen.getByRole("tab", { name: "Memory" }));
    expect(await screen.findByTestId("memory-panel")).toHaveTextContent("4.0 MiB");

    fireEvent.click(screen.getByRole("tab", { name: "Modules" }));
    expect(await screen.findByRole("table", { name: "Module states" })).toHaveTextContent("Modbus Driver");
  });

  it("lists running scripts with counts, timing and recent log lines", async () => {
    const kit = gateway();
    render(<DiagnosticsPage section={sectionById("diagnostics")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("performance-panel");
    fireEvent.click(screen.getByRole("tab", { name: "Scripts" }));
    const row = await screen.findByTestId("script-pump-timer");
    expect(row).toHaveTextContent("timer");
    expect(row).toHaveTextContent("40 (2 failed)");
    expect(row).toHaveTextContent("80 ms");
    expect(row).toHaveTextContent("INFO tick done");
    expect(row).toHaveTextContent("Last error: boom");
  });

  it("groups the four diagnostics status views", async () => {
    const kit = gateway();
    render(<DiagnosticsPage section={sectionById("diagnostics")} client={kit.client} session={adminSession} />);
    const groups = screen.getByRole("group", { name: "Diagnostics groups" });
    expect(Array.from(groups.querySelectorAll("button")).map((b) => b.textContent)).toEqual([
      "Architecture",
      "Connections",
      "Performance",
      "Environment",
    ]);
    // Performance is the default group and keeps its tab strip.
    expect(await screen.findByTestId("performance-panel")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: /^Architecture$/ }));
    expect(await screen.findByTestId("architecture-edge-panel")).toHaveTextContent("Matches this gateway");
    expect(screen.queryByTestId("performance-panel")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /^Connections$/ }));
    expect(await screen.findByTestId("connection-device:modbus:plc")).toHaveTextContent("Connected");
    expect(screen.getByRole("link", { name: "Open the Connections section" })).toHaveAttribute("href", "/admin/connections");

    fireEvent.click(screen.getByRole("button", { name: /^Environment$/ }));
    expect(await screen.findByTestId("environment-panel")).toHaveTextContent("21.0.5");
    expect(screen.getByTestId("environment-panel")).toHaveTextContent("61 seconds");
  });

  it("opens the group the link names", async () => {
    const kit = gateway();
    render(<DiagnosticsPage section={sectionById("diagnostics")} client={kit.client} session={adminSession} search="?group=environment" />);
    expect(await screen.findByTestId("environment-panel")).toBeVisible();
  });

  it("shows the API denial when the session lacks gateway.read", async () => {
    const kit = fakeGateway(() => json(403, { title: "Forbidden", status: 403, detail: "gateway.read denied (no role grants it)" }));
    render(
      <DiagnosticsPage
        section={sectionById("diagnostics")}
        client={kit.client}
        session={{ ...adminSession, permissions: ["gateway.access"] }}
      />,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("denied");
    expect(screen.getByTestId("diagnostics-no-read")).toBeVisible();
  });
});
