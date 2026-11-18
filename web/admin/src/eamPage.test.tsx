import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EamPage } from "./eamPage";
import { adminSession, fakeGateway, json, readerSession, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  vi.unstubAllGlobals();
});

const controllerStatus = {
  role: "controller",
  gatewayName: "hq",
  settings: { role: "controller", controller: "", backupController: "", group: "", checkInMillis: 5000, autoApprove: false, keepBackups: 5 },
  agent: null,
  controller: { serving: true, agents: 2, online: 1, listenPort: 8060, fingerprint: "ab".repeat(32) },
};

const agents = {
  agents: [
    {
      name: "site-a", approved: true, group: "north", maintenance: false, status: "online", online: true,
      lastCheckInMillis: 1_700_000_000_000, version: "0.1.0", queued: 0, running: 0, failed: 1, thresholds: ["Cpu"], backups: 2,
    },
    {
      name: "site-b", approved: false, group: "", maintenance: false, status: "pending", online: false,
      lastCheckInMillis: null, version: null, queued: 0, running: 0, failed: 0, thresholds: [], backups: 0,
    },
  ],
  pendingCertificates: [{ fingerprint: "cd".repeat(32), subject: "CN=site-c", gatewayName: null }],
};

const runs = {
  runs: [{ id: "r1", task: "nightly", agent: "site-a", kind: "backup", state: "failed", attempts: 3, maxAttempts: 3, createdMillis: 1, message: "disk full" }],
};

function gateway(status: unknown = controllerStatus) {
  return fakeGateway((call) => {
    if (call.url === "/api/v1/eam" && call.method === "GET") return json(200, status);
    if (call.url === "/api/v1/eam/agents") return json(200, agents);
    if (call.url === "/api/v1/eam/tasks" && call.method === "GET") {
      return json(200, { tasks: [{ id: "nightly", kind: "backup", agents: ["site-a"], groups: [], schedule: "0 2 * * *", enabled: true }] });
    }
    if (call.url.startsWith("/api/v1/eam/runs?")) return json(200, runs);
    if (call.url.startsWith("/api/v1/eam/events")) return json(200, { events: [{ timeMillis: 1, agent: "site-a", kind: "threshold-raised", detail: "Cpu" }] });
    if (call.method === "POST" && call.url === "/api/v1/eam/agents/site-b/approve") return json(200, agents.agents[1]);
    if (call.method === "POST" && call.url.startsWith("/api/v1/gateway-network/incoming/")) return json(200, {});
    if (call.method === "POST" && call.url === "/api/v1/eam/tasks") return json(201, { runs: [] });
    if (call.method === "POST" && call.url === "/api/v1/eam/runs/r1/retry") return json(200, runs.runs[0]);
    if (call.method === "PUT" && call.url === "/api/v1/eam/settings") return json(200, status);
    return undefined;
  });
}

describe("enterprise admin page", () => {
  it("lists agents, pending certificates, tasks, runs, and events on a controller", async () => {
    const kit = gateway();
    render(<EamPage section={sectionById("enterprise-admin")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("eam-status-site-a")).toHaveTextContent("online");
    expect(screen.getByTestId("eam-role")).toHaveTextContent("controller");
    expect(screen.getByTestId("eam-controller-summary")).toHaveTextContent("1 of 2 agents online");
    expect(screen.getByTestId("eam-status-site-b")).toHaveTextContent("pending");
    expect(screen.getByTestId("eam-agent-site-a")).toHaveTextContent("Cpu");
    expect(screen.getByRole("link", { name: "Download inventory (CSV)" }).getAttribute("href")).toBe("/api/v1/eam/inventory?format=csv");
    expect(screen.getByText(/schedule 0 2 \* \* \*/)).toBeVisible();
    expect(screen.getByTestId("eam-run-nightly-site-a")).toHaveTextContent("disk full");
    expect(screen.getByTestId("eam-events")).toHaveTextContent("threshold-raised Cpu");
  });

  it("approves a certificate and an agent, creates a task, and retries a run", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<EamPage section={sectionById("enterprise-admin")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "Approve certificate" }));
    await waitFor(() => expect(kit.hits("/api/v1/gateway-network/incoming/" + "cd".repeat(32) + "/approve")).toHaveLength(1));
    fireEvent.click(screen.getByRole("button", { name: "Approve site-b" }));
    await waitFor(() => expect(kit.hits("/api/v1/eam/agents/site-b/approve")).toHaveLength(1));

    const form = screen.getByTestId("eam-task-form");
    fireEvent.change(screen.getByLabelText("Task id"), { target: { value: "push" } });
    fireEvent.change(screen.getByLabelText("Kind"), { target: { value: "send-project" } });
    fireEvent.change(screen.getByLabelText("Project"), { target: { value: "plant" } });
    fireEvent.change(screen.getByLabelText("Groups"), { target: { value: "north, south" } });
    fireEvent.submit(form);
    await waitFor(() => expect(kit.calls.filter((c) => c.method === "POST" && c.url === "/api/v1/eam/tasks")).toHaveLength(1));
    const sent = JSON.parse(String(kit.calls.find((c) => c.method === "POST" && c.url === "/api/v1/eam/tasks")!.body));
    expect(sent).toEqual({ id: "push", kind: "send-project", agents: [], groups: ["north", "south"], schedule: "", params: { project: "plant" } });

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(kit.hits("/api/v1/eam/runs/r1/retry")).toHaveLength(1));
  });

  it("designates this gateway as a controller", async () => {
    const kit = gateway({ ...controllerStatus, role: "none", settings: { ...controllerStatus.settings, role: "none" }, controller: null });
    await kit.signIn();
    render(<EamPage section={sectionById("enterprise-admin")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("eam-role")).toHaveTextContent("none");
    fireEvent.change(screen.getByLabelText(/^Role/), { target: { value: "controller" } });
    fireEvent.click(screen.getByRole("button", { name: "Save role" }));
    await waitFor(() => expect(kit.calls.filter((c) => c.method === "PUT")).toHaveLength(1));
    expect(JSON.parse(String(kit.calls.find((c) => c.method === "PUT")!.body))).toEqual({ role: "controller" });
  });

  it("shows a reader the agents without any change buttons", async () => {
    const kit = gateway();
    render(<EamPage section={sectionById("enterprise-admin")} client={kit.client} session={readerSession} />);
    expect(await screen.findByTestId("eam-status-site-a")).toBeVisible();
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByTestId("eam-role-form")).toBeNull();
  });

  it("tells a viewer without gateway.read that the page is closed", () => {
    const kit = gateway();
    render(<EamPage section={sectionById("enterprise-admin")} client={kit.client} session={viewerSession} />);
    expect(screen.getByText("Enterprise administration needs gateway.read.")).toBeVisible();
    expect(kit.calls).toHaveLength(0);
  });
});
