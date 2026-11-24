import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { ApiClient, type SessionState } from "./api";
import { GatewaySettingsPage } from "./gatewaySettingsPage";
import { ADMIN_GROUPS, ADMIN_SECTIONS, sectionsByGroup } from "./sections";
import { SecuritySettingsPage } from "./securitySettingsPage";
import { ServiceConfigPage } from "./servicePages";
import { ZonesPage } from "./zonesPage";

/**
 * R1-F13: grouped admin nav (GAT-034, GAT-036), security zone and security settings pages (GAT-034), and the gateway
 * settings page (GAT-035).
 */

const ADMIN_PERMISSIONS = ["users.admin", "roles.admin", "apikeys.admin", "modules.admin", "backups.admin", "gateway.write", "gateway.read", "audit.read", "tags.read", "tags.write", "secrets.admin"];

const admin: SessionState = { username: "admin", roles: ["administrator"], permissions: ADMIN_PERMISSIONS, csrfToken: "csrf-1" };
const operator: SessionState = { username: "op", roles: ["operator"], permissions: ["gateway.read", "tags.read", "tags.write"], csrfToken: "csrf-1" };

type Call = { method: string; url: string; body: unknown; headers: Headers };

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** A client whose CSRF token is loaded, recording each request, answering with [route]. */
async function clientFor(route: (call: Call) => Response | undefined, session: SessionState = admin) {
  const calls: Call[] = [];
  const client = new ApiClient({
    fetch: async (input, init) => {
      const call: Call = {
        method: init?.method ?? "GET",
        url: String(input),
        body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
        headers: new Headers(init?.headers),
      };
      if (call.url === "/api/v1/auth/session") {
        return json(200, { principal: session.username, roles: session.roles, permissions: session.permissions, csrfToken: "csrf-1" });
      }
      calls.push(call);
      return route(call) ?? json(404, { title: "Not Found", detail: `no fake for ${call.method} ${call.url}` });
    },
  });
  await client.session();
  return { client, calls };
}

function section(id: string) {
  const found = ADMIN_SECTIONS.find((item) => item.id === id);
  if (!found) throw new Error(id);
  return found;
}

const zoneSchema = {
  type: "object",
  properties: {
    description: { type: "string", default: "" },
    priority: { type: "integer" },
    enabled: { type: "boolean", default: true },
    ipAddresses: { type: "array", items: { type: "string" }, default: [] },
    hostNames: { type: "array", items: { type: "string" }, default: [] },
    gatewayNames: { type: "array", items: { type: "string" }, default: [] },
  },
  required: ["priority", "ipAddresses", "hostNames", "gatewayNames"],
};

function zone(name: string, version: number, spec: Record<string, unknown>) {
  return { apiVersion: "oip/v1", kind: "SecurityZone", metadata: { name, module: "org.oip.security", version }, spec, scope: "gateway" };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.pushState(null, "", "/");
});

describe("admin nav groups", () => {
  it("puts every section in exactly one group, in group order", () => {
    expect(ADMIN_GROUPS.map((group) => group.label)).toEqual(["Platform", "Security", "Services", "Connections"]);
    const grouped = sectionsByGroup();
    const ids = grouped.flatMap((entry) => entry.sections.map((item) => item.id));
    expect(new Set(ids).size).toBe(ADMIN_SECTIONS.length);
    expect([...ids].sort()).toEqual(ADMIN_SECTIONS.map((item) => item.id).sort());
    const byGroup = Object.fromEntries(grouped.map((entry) => [entry.group.id, entry.sections.map((item) => item.id)]));
    expect(byGroup.platform).toEqual(expect.arrayContaining(["overview", "gateway-settings", "modules", "backups"]));
    expect(byGroup.security).toEqual(expect.arrayContaining(["users", "api-keys", "zones", "security-settings", "audit", "opcua"]));
    expect(byGroup.services).toEqual(expect.arrayContaining(["tags", "historian", "alarming", "config"]));
    expect(byGroup.connections).toEqual(expect.arrayContaining(["databases", "network"]));
  });

  it("renders the nav as labelled groups in the shell", async () => {
    window.history.pushState(null, "", "/admin/overview");
    vi.stubGlobal("fetch", async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/auth/session") return json(200, { principal: "admin", roles: ["administrator"], permissions: ADMIN_PERMISSIONS, csrfToken: "c" });
      if (url === "/api/v1/health") return json(200, { status: "UP", warnings: [] });
      return json(200, { items: [] });
    });
    render(<App />);
    const nav = await screen.findByRole("navigation", { name: "Gateway sections" });
    const groups = within(nav).getAllByRole("group");
    expect(groups.map((group) => group.getAttribute("data-group"))).toEqual(["platform", "security", "services", "connections"]);
    const security = within(nav).getByRole("group", { name: "Security" });
    for (const label of ["Users & Roles", "API Keys", "Security Zones", "Security Settings", "Audit", "OPC UA Certificates"]) {
      expect(within(security).getByRole("link", { name: label })).toBeVisible();
    }
    expect(within(within(nav).getByRole("group", { name: "Platform" })).getByRole("link", { name: "Gateway Settings" })).toBeVisible();
    expect(within(within(nav).getByRole("group", { name: "Services" })).getByRole("link", { name: "Tags" })).toBeVisible();
    expect(within(within(nav).getByRole("group", { name: "Connections" })).getByRole("link", { name: "Databases" })).toBeVisible();
  });
});

describe("services pages", () => {
  it("lists only the historian module's resources and links each to the config editor", async () => {
    const { client } = await clientFor((call) =>
      call.url === "/api/v1/config"
        ? json(200, {
            items: [
              { kind: "HistoryProvider", metadata: { name: "local", module: "historian" }, scope: "gateway" },
              { kind: "Pipeline", metadata: { name: "page-ops", module: "alarm" }, scope: "gateway" },
              { kind: "User", metadata: { name: "admin", module: "org.oip.security" }, scope: "gateway" },
            ],
          })
        : undefined,
    );
    const visits: string[] = [];
    render(<ServiceConfigPage section={section("historian")} client={client} session={admin} onVisit={(to) => visits.push(to)} />);
    const link = await screen.findByRole("link", { name: "HistoryProvider local historian" });
    expect(screen.queryByText(/page-ops/)).toBeNull();
    expect(screen.queryByText(/User admin/)).toBeNull();
    fireEvent.click(link);
    expect(visits[0]).toContain("/admin/config?");
    expect(visits[0]).toContain("kind=HistoryProvider");
    cleanup();
    render(<ServiceConfigPage section={section("alarming")} client={client} session={admin} />);
    expect(await screen.findByRole("link", { name: "Pipeline page-ops alarm" })).toBeVisible();
    expect(screen.queryByText(/HistoryProvider/)).toBeNull();
  });
});

describe("security zones page", () => {
  it("lists zones with the built-in Default, creates, edits and deletes at the loaded version", async () => {
    let zones = [zone("plant", 3, { priority: 5, enabled: true, ipAddresses: ["10.0.0.0/8"], hostNames: [], gatewayNames: [] })];
    const { client, calls } = await clientFor((call) => {
      if (call.url === "/api/v1/schemas/org.oip.security/SecurityZone") return json(200, { schema: zoneSchema });
      if (call.url === "/api/v1/config/org.oip.security/gateway/SecurityZone" && call.method === "GET") return json(200, { items: zones });
      if (call.url === "/api/v1/config/org.oip.security/gateway/SecurityZone" && call.method === "POST") {
        const body = call.body as { metadata: { name: string }; spec: Record<string, unknown> };
        zones = [...zones, zone(body.metadata.name, 1, body.spec)];
        return json(201, zones[zones.length - 1]);
      }
      if (call.url === "/api/v1/config/org.oip.security/gateway/SecurityZone/plant" && call.method === "GET") return json(200, zones[0]);
      if (call.url === "/api/v1/config/org.oip.security/gateway/SecurityZone/plant" && call.method === "DELETE") {
        zones = zones.filter((item) => item.metadata.name !== "plant");
        return new Response(null, { status: 204 });
      }
      return undefined;
    });
    render(<ZonesPage section={section("zones")} client={client} session={admin} />);
    const table = await screen.findByRole("table", { name: "Security zones" });
    expect(within(table).getByRole("cell", { name: "plant" })).toBeVisible();
    expect(within(table).getByRole("cell", { name: "10.0.0.0/8" })).toBeVisible();
    expect(screen.getByTestId("default-zone")).toHaveTextContent("Default");

    const form = await screen.findByRole("form", { name: "Create security zone" });
    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "office" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create zone" }));
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Security zones" })).getByRole("cell", { name: "office" })).toBeVisible());
    const created = calls.find((call) => call.method === "POST");
    expect(created?.body).toMatchObject({ kind: "SecurityZone", metadata: { name: "office", module: "org.oip.security", version: 0 }, spec: { priority: 1 } });
    expect(created?.headers.get("X-CSRF-Token")).toBe("csrf-1");

    fireEvent.click(screen.getByRole("button", { name: "Edit plant" }));
    expect(await screen.findByRole("region", { name: "Edit zone plant" })).toBeVisible();
    expect(await screen.findByTestId("resource-editor")).toHaveTextContent("version 3");

    fireEvent.click(screen.getByRole("button", { name: "Delete plant" }));
    await waitFor(() => expect(screen.queryByRole("cell", { name: "plant" })).toBeNull());
    const deleted = calls.find((call) => call.method === "DELETE");
    expect(deleted?.headers.get("If-Match")).toBe("3");
  });

  it("shows no write controls without the security administration permissions", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.url === "/api/v1/config/org.oip.security/gateway/SecurityZone") {
        return json(200, { items: [zone("plant", 1, { priority: 5, ipAddresses: [], hostNames: ["*-hmi"], gatewayNames: [] })] });
      }
      return undefined;
    }, operator);
    render(<ZonesPage section={section("zones")} client={client} session={operator} />);
    expect(await screen.findByRole("cell", { name: "*-hmi" })).toBeVisible();
    expect(screen.queryByRole("form", { name: "Create security zone" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Edit |Delete / })).toBeNull();
    expect(calls.some((call) => call.url.includes("/schemas/"))).toBe(false);
  });

  it("shows the gateway's refusal when the caller cannot read zones", async () => {
    const { client } = await clientFor(() => json(403, { title: "Forbidden", detail: "gateway.read denied" }));
    render(<ZonesPage section={section("zones")} client={client} session={{ ...operator, permissions: [] }} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("gateway.read denied");
  });
});

describe("security settings page", () => {
  const schemas: Record<string, unknown> = {
    GatewaySecurity: { type: "object", properties: { inactivityTimeoutMinutes: { type: "integer", minimum: 0 } } },
    PasswordPolicy: { type: "object", properties: { minLength: { type: "integer", minimum: 1 } } },
    AuditProfile: { type: "object", properties: { retentionDays: { type: "integer", minimum: 0 }, enabled: { type: "boolean" } } },
  };
  const specs: Record<string, [string, Record<string, unknown>]> = {
    GatewaySecurity: ["gateway", { inactivityTimeoutMinutes: 15 }],
    PasswordPolicy: ["default", { minLength: 8 }],
    AuditProfile: ["internal", { retentionDays: 90, enabled: true }],
  };
  function route(call: Call) {
    const schema = /\/api\/v1\/schemas\/org\.oip\.security\/(\w+)$/.exec(call.url);
    if (schema) return json(200, { schema: schemas[schema[1]!] });
    const item = /\/api\/v1\/config\/org\.oip\.security\/gateway\/(\w+)\/([\w-]+)$/.exec(call.url);
    if (item) {
      const [name, spec] = specs[item[1]!]!;
      const next = call.method === "PUT" ? (call.body as { spec: Record<string, unknown> }).spec : spec;
      return json(200, { apiVersion: "oip/v1", kind: item[1], metadata: { name, module: "org.oip.security", version: call.method === "PUT" ? 5 : 4 }, spec: next });
    }
    return undefined;
  }

  it("edits gateway security, the password policy and the audit profile retention", async () => {
    const { client, calls } = await clientFor(route);
    render(<SecuritySettingsPage section={section("security-settings")} client={client} session={admin} />);
    const audit = await screen.findByRole("region", { name: "Audit profile" });
    expect(screen.getByRole("region", { name: "Gateway security" })).toBeVisible();
    expect(screen.getByRole("region", { name: "Password policy" })).toBeVisible();
    const retention = await within(audit).findByLabelText(/retention/i);
    fireEvent.change(retention, { target: { value: "30" } });
    fireEvent.click(within(audit).getByRole("button", { name: "Save" }));
    expect(await within(audit).findByRole("status")).toHaveTextContent("Saved.");
    const put = calls.find((call) => call.method === "PUT");
    expect(put?.url).toBe("/api/v1/config/org.oip.security/gateway/AuditProfile/internal");
    expect(put?.headers.get("If-Match")).toBe("4");
    expect(put?.body).toMatchObject({ spec: { retentionDays: 30 } });
  });

  it("is read only without the security administration permissions", async () => {
    const { client } = await clientFor(route, operator);
    render(<SecuritySettingsPage section={section("security-settings")} client={client} session={operator} />);
    const policy = await screen.findByRole("region", { name: "Password policy" });
    expect(await within(policy).findByTestId("read-only-note")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
  });
});

describe("gateway settings page", () => {
  const document = (systemName: string, defaultProject = "") => ({
    systemName,
    effectiveSystemName: systemName || "host-1",
    scriptMessageEncoding: "json",
    scriptMessageEncodings: ["json"],
    launch: { defaultProject },
    version: 1,
  });

  it("loads, validates and saves the system name, encoding and default project", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.url !== "/api/v1/gateway/settings") return undefined;
      if (call.method === "PUT") {
        const body = call.body as { systemName: string; launch: { defaultProject: string } };
        return json(200, document(body.systemName, body.launch.defaultProject));
      }
      return json(200, document(""));
    });
    render(<GatewaySettingsPage section={section("gateway-settings")} client={client} session={admin} />);
    expect(await screen.findByTestId("effective-system-name")).toHaveTextContent("host-1");
    const form = screen.getByRole("form", { name: "Gateway settings" });
    expect(within(form).getByRole("combobox", { name: "Serialization format" })).toHaveValue("json");

    fireEvent.change(within(form).getByLabelText("System name"), { target: { value: "North|Plant" } });
    expect(within(form).getByRole("alert")).toHaveTextContent("cannot contain '|'");
    expect(within(form).getByRole("button", { name: "Save settings" })).toBeDisabled();

    fireEvent.change(within(form).getByLabelText("System name"), { target: { value: "North Plant" } });
    fireEvent.change(within(form).getByLabelText("Default HMI project"), { target: { value: "line-1" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save settings" }));
    expect(await within(form).findByRole("status")).toHaveTextContent("Saved.");
    expect(screen.getByTestId("effective-system-name")).toHaveTextContent("North Plant");
    const put = calls.find((call) => call.method === "PUT");
    expect(put?.body).toEqual({ systemName: "North Plant", scriptMessageEncoding: "json", launch: { defaultProject: "line-1" } });
  });

  it("is read only without gateway.write", async () => {
    const { client } = await clientFor((call) => (call.url === "/api/v1/gateway/settings" ? json(200, document("North")) : undefined), operator);
    render(<GatewaySettingsPage section={section("gateway-settings")} client={client} session={operator} />);
    expect(await screen.findByTestId("read-only-note")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Save settings" })).toBeNull();
    expect(screen.getByLabelText("System name")).toHaveAttribute("readonly");
  });
});
