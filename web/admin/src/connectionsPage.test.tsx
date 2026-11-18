import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ConnectionsPage } from "./connectionsPage";
import { ADMIN_SECTIONS, sectionsByGroup } from "./sections";
import { adminSession, fakeGateway, json, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const document = {
  items: [
    { id: "device:modbus:plc", kind: "device", type: "modbus", name: "plc", status: "Connected", health: "ok", detail: "", section: "config" },
    { id: "device:modbus:spare", kind: "device", type: "modbus", name: "spare", status: "Disabled", health: "disabled", detail: "", section: "config" },
    { id: "opc:opcua-client:line", kind: "opc", type: "opcua-client", name: "line", status: "Disconnected", health: "fault", detail: "refused", section: "opc-connections" },
    { id: "database:sql:plant", kind: "database", type: "sql", name: "plant", status: "Valid", health: "ok", detail: "sqlite", section: "databases" },
    { id: "service:gateway-network:east", kind: "service", type: "gateway-network", name: "east", status: "Connecting", health: "pending", detail: "10.0.0.2:8060", section: "network" },
  ],
  summary: { total: 5, ok: 2, pending: 1, fault: 1, disabled: 1 },
};

describe("Connections section", () => {
  it("is in the Connections nav group", () => {
    const group = sectionsByGroup().find((entry) => entry.group.id === "connections")!;
    expect(group.sections.map((item) => item.id)).toContain("connections");
    expect(ADMIN_SECTIONS.filter((item) => item.id === "connections")).toHaveLength(1);
  });

  it("lists every kind with its status and filters by kind", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/connections" ? json(200, document) : undefined));
    render(<ConnectionsPage section={sectionById("connections")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("connections-summary")).toHaveTextContent("5 connections: 2 connected, 1 connecting, 1 faulted, 1 disabled");
    expect(screen.getByTestId("connection-device:modbus:plc")).toHaveAttribute("data-health", "ok");
    expect(screen.getByTestId("connection-opc:opcua-client:line")).toHaveTextContent("refused");
    expect(screen.getByTestId("connection-database:sql:plant")).toHaveTextContent("Valid");
    expect(screen.getByTestId("connection-service:gateway-network:east")).toHaveTextContent("Connecting");

    fireEvent.click(screen.getByRole("button", { name: /^Database$/ }));
    const table = screen.getByRole("table", { name: "Connections list" });
    expect(within(table).getAllByRole("row")).toHaveLength(2);
    expect(within(table).queryByText("plc")).toBeNull();
  });

  it("opens the section that edits a connection", async () => {
    const visit = vi.fn();
    const kit = fakeGateway(({ url }) => (url === "/api/v1/connections" ? json(200, document) : undefined));
    render(<ConnectionsPage section={sectionById("connections")} client={kit.client} session={adminSession} onVisit={visit} />);
    fireEvent.click(await screen.findByRole("link", { name: "Open plant" }));
    expect(visit).toHaveBeenCalledWith("/admin/databases");
  });

  it("hides the list without gateway.read", async () => {
    const kit = fakeGateway(() => json(403, { title: "Forbidden", status: 403, detail: "gateway.read denied" }));
    render(<ConnectionsPage section={sectionById("connections")} client={kit.client} session={viewerSession} />);
    expect(await screen.findByTestId("connections-no-read")).toBeVisible();
    expect(screen.queryByTestId("connections-table")).toBeNull();
    expect(kit.hits("/api/v1/connections")).toHaveLength(0);
  });
});
