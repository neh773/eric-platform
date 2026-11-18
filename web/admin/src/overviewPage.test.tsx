import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CUTOFFS, OverviewPage, overviewBanners } from "./overviewPage";
import type { Performance } from "./statusApi";
import { adminSession, fakeGateway, json, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  vi.unstubAllGlobals();
});

function perf(overrides: Partial<Performance> = {}): Performance {
  return {
    cpuLoad: 0.25,
    processCpuLoad: 0.01,
    heapUsedBytes: 100 * 1024 * 1024,
    heapMaxBytes: 1024 * 1024 * 1024,
    disk: { path: "/data", usableBytes: 50 * 1024 ** 3, totalBytes: 100 * 1024 ** 3 },
    clockDrift: { intervalMs: 1000, drift: 0.001, lateMillis: 1, samples: 10 },
    samples: [],
    ...overrides,
  };
}

const health = { status: "UP", profile: "standard", version: "1.2.3", modules: 3, warnings: [] };

describe("overview banners", () => {
  it("stays quiet when every figure is inside its cutoff", () => {
    expect(overviewBanners(health, perf(), [{ id: "m", name: "M", version: "1", state: "RUNNING", error: null }])).toEqual([]);
  });

  it("raises one banner per figure past its cutoff", () => {
    const banners = overviewBanners(
      { ...health, status: "DEGRADED" },
      perf({
        cpuLoad: CUTOFFS.cpuLoad,
        heapUsedBytes: 950,
        heapMaxBytes: 1000,
        disk: { path: "/data", usableBytes: 5, totalBytes: 100 },
        clockDrift: { intervalMs: 1000, drift: 0.25, lateMillis: 250, samples: 4 },
      }),
      [{ id: "bad", name: "Bad Module", version: "1", state: "STOPPED", error: "boom" }],
    );
    expect(banners.map((banner) => banner.id)).toEqual(["health", "cpu", "heap", "disk", "drift", "module-bad"]);
    expect(banners.find((banner) => banner.id === "drift")?.text).toContain("25.0%");
    expect(banners.find((banner) => banner.id === "module-bad")?.text).toContain("boom");
  });

  it("does not treat a negative drift reading inside the cutoff as unmeasured", () => {
    const quiet = overviewBanners(null, perf({ clockDrift: { intervalMs: 1000, drift: -0.01, lateMillis: -10, samples: 3 } }), null);
    expect(quiet).toEqual([]);
    const unmeasured = overviewBanners(null, perf({ clockDrift: { intervalMs: 1000, drift: -1, lateMillis: -1, samples: 3 } }), null);
    expect(unmeasured.map((banner) => banner.id)).toEqual(["drift"]);
  });
});

describe("overview page", () => {
  it("shows health, version, profile, uptime, live figures, and cutoff banners", async () => {
    const started = new Date(Date.now() - 90 * 60 * 1000).toISOString();
    const kit = fakeGateway(({ url }) => {
      if (url === "/api/v1/health") return json(200, { ...health, startedAt: started });
      if (url === "/api/v1/diagnostics/performance") return json(200, perf({ cpuLoad: 0.97 }));
      if (url === "/api/v1/diagnostics/modules") {
        return json(200, { modules: [{ id: "a", name: "A", version: "1", state: "RUNNING", error: null }] });
      }
      return undefined;
    });
    render(<OverviewPage section={sectionById("overview")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("overview-version")).toHaveTextContent("1.2.3");
    expect(screen.getByTestId("overview-health")).toHaveTextContent("UP");
    expect(screen.getByTestId("overview-profile")).toHaveTextContent("standard");
    expect(screen.getByTestId("overview-modules")).toHaveTextContent("3");
    expect(await screen.findByTestId("overview-cpu")).toHaveTextContent("97.0%");
    expect(await screen.findByTestId("overview-uptime")).toHaveTextContent(/1h 30m/);
    expect(screen.getByTestId("overview-banners").querySelector('[data-banner="cpu"]')).not.toBeNull();
    expect(await screen.findByTestId("overview-module-states")).toHaveTextContent("1 of 1 modules running");
    expect(screen.getByTestId("overview-uptime")).toHaveTextContent(`started ${started}`);
    expect(kit.hits("/api/v1/logs")).toHaveLength(0);
  });

  it("skips gateway.read calls for a viewer and still shows public health and uptime", async () => {
    const started = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();
    const kit = fakeGateway(({ url }) => (url === "/api/v1/health" ? json(200, { ...health, startedAt: started }) : undefined));
    render(<OverviewPage section={sectionById("overview")} client={kit.client} session={viewerSession} />);
    expect(await screen.findByTestId("overview-version")).toHaveTextContent("1.2.3");
    expect(screen.getByTestId("overview-no-read")).toBeVisible();
    expect(screen.getByTestId("overview-uptime")).toHaveTextContent(/2h 0m/);
    expect(kit.hits("/api/v1/diagnostics")).toHaveLength(0);
    expect(kit.hits("/api/v1/logs")).toHaveLength(0);
  });

  it("says the uptime is not reported when health has no start time", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/health" ? json(200, health) : undefined));
    render(<OverviewPage section={sectionById("overview")} client={kit.client} session={viewerSession} />);
    expect(await screen.findByTestId("overview-version")).toHaveTextContent("1.2.3");
    expect(screen.getByTestId("overview-uptime")).toHaveTextContent("Not reported");
  });

  it("reads and switches the active deployment mode", async () => {
    let mode: string | null = null;
    const kit = fakeGateway(({ method, url, body }) => {
      if (url === "/api/v1/health") return json(200, health);
      if (url === "/api/v1/deployment-mode" && method === "GET") return json(200, { mode, modes: ["dev", "prod"] });
      if (url === "/api/v1/deployment-mode" && method === "PUT") {
        mode = (JSON.parse(String(body)) as { mode: string | null }).mode;
        return json(200, { mode, modes: ["dev", "prod"] });
      }
      return undefined;
    });
    await kit.signIn();
    render(<OverviewPage section={sectionById("overview")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("overview-deployment-mode")).toHaveTextContent("None");
    expect(await screen.findByTestId("overview-deployment-modes")).toHaveTextContent("dev, prod");
    fireEvent.change(screen.getByLabelText("Deployment mode"), { target: { value: "prod" } });
    fireEvent.click(screen.getByRole("button", { name: "Switch mode" }));
    expect(await screen.findByText("prod", { selector: '[data-testid="overview-deployment-mode"]' })).toBeVisible();
    const put = kit.calls.find((call) => call.method === "PUT");
    expect(put?.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(JSON.parse(String(put?.body))).toEqual({ mode: "prod" });
  });
});
