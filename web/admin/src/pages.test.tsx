import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./api";
import { AdminPage, installAdminPages, registerAdminPage, resetAdminPages } from "./pages";
import { ADMIN_SECTIONS } from "./sections";

beforeEach(() => {
  // The network page reads its extra HTTPS fields with a same-origin fetch; keep these tests offline.
  vi.stubGlobal("fetch", async () => new Response(JSON.stringify({}), { status: 404 }));
});

afterEach(() => {
  cleanup();
  resetAdminPages();
  vi.unstubAllGlobals();
});

describe("admin page slots", () => {
  it("renders a placeholder until a later task registers a page", () => {
    const config = ADMIN_SECTIONS.find((section) => section.id === "config");
    if (!config) throw new Error("config section missing");
    const { unmount } = render(<AdminPage section={config} />);
    expect(screen.getByRole("heading", { name: "Config" })).toBeVisible();
    expect(screen.getByTestId("section-placeholder")).toBeVisible();
    unmount();

    registerAdminPage("config", function ConfigEditor({ section }) {
      return <h1>Editor for {section.label}</h1>;
    });
    render(<AdminPage section={config} />);
    expect(screen.getByRole("heading", { name: "Editor for Config" })).toBeVisible();
    expect(screen.queryByTestId("section-placeholder")).toBeNull();
  });

  it("installs the config editor in the config slot", async () => {
    resetAdminPages();
    installAdminPages();
    const config = ADMIN_SECTIONS.find((section) => section.id === "config");
    if (!config) throw new Error("config section missing");
    const client = new ApiClient({
      fetch: async () =>
        new Response(JSON.stringify({ items: [] }), { status: 200, headers: { "Content-Type": "application/json" } }),
    });
    render(<AdminPage section={config} client={client} search="" />);
    expect(await screen.findByTestId("config-resources")).toBeVisible();
    expect(screen.queryByTestId("section-placeholder")).toBeNull();
  });

  it("installs modules, users, API keys, audit, and backups", () => {
    installAdminPages();
    const client = new ApiClient({
      fetch: async (input) => {
        const url = String(input);
        const body = url.includes("/backups")
          ? { backups: [] }
          : url.includes("/audit")
            ? { items: [], total: 0, limit: 25, offset: 0 }
            : { items: [] };
        return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
      },
    });
    for (const id of ["modules", "users", "api-keys", "audit", "backups"]) {
      const section = ADMIN_SECTIONS.find((item) => item.id === id);
      if (!section) throw new Error(id);
      const view = render(<AdminPage section={section} client={client} />);
      expect(screen.getByRole("heading", { level: 1, name: section.label })).toBeVisible();
      expect(screen.queryByTestId("section-placeholder")).toBeNull();
      view.unmount();
    }
  });

  it("installs the network page and does not render a private key", async () => {
    installAdminPages();
    const secret = "-----BEGIN PRIVATE KEY-----\nSECRETKEYMATERIAL\n-----END PRIVATE KEY-----";
    const client = new ApiClient({
      fetch: async () =>
        new Response(
          JSON.stringify({
            enabled: false,
            listening: false,
            httpPort: 8090,
            httpsPort: 8043,
            httpsListenPort: null,
            publicAddress: null,
            publicHttpsPort: null,
            forceHttpsRedirect: false,
            protocols: ["TLSv1.2", "TLSv1.3"],
            certificate: null,
            privateKey: secret,
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
    });
    const section = ADMIN_SECTIONS.find((item) => item.id === "network");
    if (!section) throw new Error("network section missing");
    render(
      <AdminPage
        section={section}
        client={client}
        session={{ username: "admin", roles: ["administrator"], permissions: ["gateway.write"], csrfToken: "t" }}
      />,
    );
    expect((await screen.findByTestId("https-status")).textContent).toContain("off");
    expect(document.body.textContent).not.toContain("SECRETKEYMATERIAL");
    expect(document.body.textContent).not.toContain("PRIVATE KEY");
  });

  it("approves a pending incoming gateway from the network page", async () => {
    installAdminPages();
    const fingerprint = "ab".repeat(32);
    let approved = false;
    const client = new ApiClient({
      fetch: async (input) => {
        const url = String(input);
        if (url.endsWith("/login")) {
          return new Response(JSON.stringify({ username: "admin", roles: ["administrator"], csrfToken: "t" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          });
        }
        if (url.includes("/approve")) approved = true;
        if (url.includes("/gateway-network")) {
          return new Response(
            JSON.stringify({
              enabled: true,
              listening: true,
              port: 8060,
              gatewayName: "gateway",
              incoming: approved
                ? [{ fingerprint, gatewayName: "plant", status: "running", state: "approved", subject: "CN=plant" }]
                : [{ fingerprint, gatewayName: "plant", status: "pending", state: "pending", subject: "CN=plant" }],
              outgoing: [],
            }),
            { status: 200, headers: { "Content-Type": "application/json" } },
          );
        }
        return new Response(
          JSON.stringify({
            enabled: false,
            listening: false,
            httpPort: 8090,
            httpsPort: 8043,
            httpsListenPort: null,
            publicAddress: null,
            publicHttpsPort: null,
            forceHttpsRedirect: false,
            protocols: ["TLSv1.2", "TLSv1.3"],
            certificate: null,
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      },
    });
    const section = ADMIN_SECTIONS.find((item) => item.id === "network");
    if (!section) throw new Error("network section missing");
    await client.login("admin", "password");
    render(
      <AdminPage
        section={section}
        client={client}
        session={{ username: "admin", roles: ["administrator"], permissions: ["gateway.write"], csrfToken: "t" }}
      />,
    );
    expect(await screen.findByTestId("gateway-network-status")).toHaveTextContent("pending");
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await screen.findByText("running");
    expect(approved).toBe(true);
    expect(document.body.textContent).not.toContain("PRIVATE KEY");
  });
});
