import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./api";
import { AdminPage, installAdminPages, resetAdminPages } from "./pages";
import { ADMIN_SECTIONS } from "./sections";

const STATUS = {
  enabled: true,
  listening: true,
  httpPort: 8090,
  httpsPort: 8043,
  httpsListenPort: 8043,
  publicAddress: null,
  publicHttpsPort: null,
  forceHttpsRedirect: false,
  protocols: ["TLSv1.2", "TLSv1.3"],
  configuredProtocols: ["TLSv1.2", "TLSv1.3"],
  cipherSuites: ["TLS_AES_128_GCM_SHA256"],
  configuredCipherSuites: null,
  availableCipherSuites: ["TLS_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"],
  certificate: null,
  csr: null as null | Record<string, unknown>,
  savedSettings: null,
};

interface Call {
  method: string;
  url: string;
  body: unknown;
  csrf: string | undefined;
}

function json(body: unknown, status = 200): Response {
  const type = status >= 400 ? "application/problem+json" : "application/json";
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": type } });
}

let calls: Call[] = [];
let status = structuredClone(STATUS);
let reply: (call: Call) => Response | undefined = () => undefined;

beforeEach(() => {
  calls = [];
  status = structuredClone(STATUS);
  reply = () => undefined;
  vi.stubGlobal("fetch", async (input: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const call: Call = {
      method: init?.method ?? "GET",
      url: String(input),
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
      csrf: headers["X-CSRF-Token"],
    };
    calls.push(call);
    return reply(call) ?? json(call.method === "GET" ? status : {});
  });
});

afterEach(() => {
  cleanup();
  resetAdminPages();
  vi.unstubAllGlobals();
});

function renderPage(permissions: string[]) {
  installAdminPages();
  const client = new ApiClient({ fetch: async () => json(status) });
  const section = ADMIN_SECTIONS.find((item) => item.id === "network");
  if (!section) throw new Error("network section missing");
  render(
    <AdminPage
      section={section}
      client={client}
      session={{ username: "admin", roles: ["administrator"], permissions, csrfToken: "csrf-1" }}
    />,
  );
}

describe("network page web server, TLS, and CSR sections", () => {
  it("saves the web server settings to the settings route with the CSRF token", async () => {
    renderPage(["gateway.read", "gateway.write"]);
    const form = await screen.findByTestId("web-server-settings");
    fireEvent.change(within(form).getByLabelText("Public address"), { target: { value: "gw.example.test" } });
    fireEvent.change(within(form).getByLabelText("HTTPS port"), { target: { value: "9443" } });
    fireEvent.click(within(form).getByLabelText("Send plain HTTP requests to HTTPS"));
    fireEvent.click(within(form).getByRole("button", { name: "Save web server settings" }));
    await within(form).findByRole("status");
    const put = calls.find((call) => call.method === "PUT" && call.url === "/api/v1/https/settings");
    expect(put?.csrf).toBe("csrf-1");
    expect(put?.body).toEqual({
      httpPort: 8090,
      httpsEnabled: true,
      httpsPort: 9443,
      publicAddress: "gw.example.test",
      publicHttpsPort: null,
      forceHttpsRedirect: true,
    });
  });

  it("shows the server's reason when TLS settings are refused", async () => {
    reply = (call) =>
      call.method === "PUT" && call.url === "/api/v1/https/tls"
        ? json({ title: "Unprocessable", status: 422, detail: "TLSv1.3 is enabled but none of the chosen cipher suites work with it" }, 422)
        : undefined;
    renderPage(["gateway.read", "gateway.write"]);
    const form = await screen.findByTestId("tls-policy");
    fireEvent.click(within(form).getByLabelText("Only the suites checked below"));
    fireEvent.click(within(form).getByLabelText("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
    fireEvent.click(within(form).getByRole("button", { name: "Apply TLS settings" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("none of the chosen cipher suites");
    const put = calls.find((call) => call.url === "/api/v1/https/tls");
    expect(put?.body).toEqual({ protocols: ["TLSv1.2", "TLSv1.3"], cipherSuites: ["TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"] });
  });

  it("creates a CSR, offers the download, and installs the reply", async () => {
    renderPage(["gateway.read", "gateway.write"]);
    const section = await screen.findByTestId("https-csr");
    expect(within(section).getByTestId("csr-none")).toBeVisible();
    fireEvent.change(within(section).getByLabelText("Common name"), { target: { value: "gw.example.test" } });
    fireEvent.change(within(section).getByLabelText(/Other names/), { target: { value: "gw.example.test\n10.0.0.5" } });
    fireEvent.change(within(section).getByLabelText("Key"), { target: { value: "ec256" } });
    status.csr = { subject: "CN=gw.example.test", sans: ["DNS:gw.example.test", "IP:10.0.0.5"], publicKeySha256: "ab12" };
    fireEvent.click(within(section).getByRole("button", { name: "Create request" }));
    const pending = await screen.findByTestId("csr-pending");
    const post = calls.find((call) => call.method === "POST" && call.url === "/api/v1/https/csr");
    expect(post?.body).toMatchObject({ commonName: "gw.example.test", sans: ["gw.example.test", "10.0.0.5"], keyType: "EC" });
    expect(within(pending).getByTestId("csr-download")).toHaveAttribute("href", "/api/v1/https/csr?download=true");
    expect(pending).toHaveTextContent("IP:10.0.0.5");

    fireEvent.change(within(pending).getByLabelText("Signed certificate and chain (PEM)"), {
      target: { value: "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----" },
    });
    status.csr = null;
    fireEvent.click(within(pending).getByRole("button", { name: "Install signed certificate" }));
    await waitFor(() => expect(calls.some((call) => call.url === "/api/v1/https/csr/install")).toBe(true));
    const install = calls.find((call) => call.url === "/api/v1/https/csr/install");
    expect(install?.body).toEqual({ certificatePem: "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----" });
    expect(await screen.findByTestId("csr-none")).toBeVisible();
  });

  it("gives read-only users no forms that change anything", async () => {
    status.csr = { subject: "CN=gw", sans: ["DNS:gw"], publicKeySha256: "ab12" };
    renderPage(["gateway.read"]);
    await screen.findByTestId("csr-pending");
    expect(screen.queryByRole("button", { name: "Save web server settings" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Apply TLS settings" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Discard request" })).toBeNull();
    expect(screen.queryByTestId("csr-form")).toBeNull();
    expect(screen.getByTestId("csr-download")).toBeVisible();
    expect(calls.every((call) => call.method === "GET")).toBe(true);
  });
});
