import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OpcConnectionsPage } from "./opcConnectionsPage";
import { adminSession, fakeGateway, json, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const line = {
  name: "line",
  enabled: true,
  description: "plant",
  endpointUrl: "opc.tcp://10.0.0.8:4840/oip",
  state: "Connected",
  connected: true,
  serverState: "Running",
  sessionId: "ns=1;i=42",
  sessionName: "ERIC OPC UA Client",
  uptimeMs: 5000,
  subscriptionCount: 1,
  monitoredItemCount: 1,
  samplingIntervalMs: 250,
  publishIntervalMs: 250,
  requestCount: 12,
  errorCount: 0,
  timeoutCount: 0,
  averageResponseTimeMs: 3.5,
  lastError: null,
  subscriptions: [
    {
      name: "sampling-250ms",
      samplingIntervalMs: 250,
      requestedPublishingIntervalMs: 250,
      revisedPublishingIntervalMs: 250,
      monitoredItemCount: 1,
    },
  ],
};

describe("OPC UA connections page", () => {
  it("shows server state, the session, and only the monitored items the payload reports", async () => {
    const kit = fakeGateway(({ url }) => {
      if (url === "/api/v1/diagnostics/opcua/connections") return json(200, { connections: [line] });
      return undefined;
    });
    render(<OpcConnectionsPage section={sectionById("opc-connections")} client={kit.client} session={adminSession} />);
    const row = await screen.findByTestId("opc-connection-line");
    expect(row).toHaveTextContent("Connected");
    expect(screen.getByTestId("opc-server-state-line")).toHaveTextContent("Running");
    expect(screen.getByTestId("opc-session-line")).toHaveTextContent("ERIC OPC UA Client (ns=1;i=42)");
    expect(screen.getByTestId("opc-subscriptions-line")).toHaveTextContent("1");
    // The API already excluded an item the server refused. The page shows that count.
    expect(screen.getByTestId("opc-monitored-items-line")).toHaveTextContent("1");
    expect(screen.getByRole("table", { name: "Subscriptions for line" })).toHaveTextContent("sampling-250ms");
    expect(kit.hits("/api/v1/diagnostics/opcua/connections").length).toBeGreaterThan(0);
  });

  it("says when no connection is configured", async () => {
    const kit = fakeGateway(({ url }) => {
      if (url === "/api/v1/diagnostics/opcua/connections") return json(200, { connections: [] });
      return undefined;
    });
    render(<OpcConnectionsPage section={sectionById("opc-connections")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("opc-connections-empty")).toHaveTextContent("No OPC UA connections");
  });

  it("hides the table when the session cannot read the gateway", async () => {
    const kit = fakeGateway(() => json(403, { title: "Forbidden", status: 403, detail: "gateway.read denied" }));
    render(<OpcConnectionsPage section={sectionById("opc-connections")} client={kit.client} session={viewerSession} />);
    expect(await screen.findByTestId("opc-connections-no-read")).toBeVisible();
    expect(screen.queryByTestId("opc-connections")).toBeNull();
    expect(kit.hits("/api/v1/diagnostics/opcua/connections")).toHaveLength(0);
  });
});
