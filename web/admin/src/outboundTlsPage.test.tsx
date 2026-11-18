import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { OutboundTlsPage } from "./outboundTlsPage";
import { ADMIN_SECTIONS } from "./sections";

afterEach(() => cleanup());

// Doc source: FEATURES SEC-022.
const admin: SessionState = { username: "admin", roles: ["administrator"], permissions: ["gateway.write", "gateway.read"], csrfToken: "csrf-1" };
const operator: SessionState = { username: "olly", roles: ["operator"], permissions: ["gateway.read"], csrfToken: "csrf-1" };
const section = ADMIN_SECTIONS.find((item) => item.id === "outbound-tls")!;
const REJECTED = "c".repeat(64);
const TRUSTED = "d".repeat(64);

function cert(thumbprint: string, cn: string, seen?: { seenAt: string; client: string }) {
  return {
    thumbprint,
    subject: `CN=${cn}`,
    commonName: cn,
    issuer: `CN=${cn}`,
    notBefore: "2026-01-01T00:00:00Z",
    notAfter: "2029-01-01T00:00:00Z",
    expired: false,
    seenAt: seen?.seenAt ?? null,
    client: seen?.client ?? null,
    lastSeen: seen ? "2026-11-09T10:00:00Z" : null,
  };
}

async function fakeGateway() {
  const calls: { method: string; url: string; csrf: string | null; body?: string }[] = [];
  let trusted = [cert(TRUSTED, "historian-db")];
  let rejected = [cert(REJECTED, "broker", { seenAt: "10.0.0.9:8883", client: "mqtt" })];
  const client = new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const headers = new Headers(init?.headers);
      calls.push({ method, url, csrf: headers.get("X-CSRF-Token"), body: typeof init?.body === "string" ? init.body : undefined });
      const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (method === "GET" && url === "/api/v1/tls/outbound") return json(200, { trusted, rejected });
      if (method === "POST" && url === `/api/v1/tls/outbound/rejected/${REJECTED}/trust`) {
        const moved = rejected[0]!;
        rejected = [];
        trusted = [...trusted, { ...moved, seenAt: null, client: null, lastSeen: null }];
        return json(200, moved);
      }
      if (method === "DELETE" && url === `/api/v1/tls/outbound/trusted/${TRUSTED}`) {
        trusted = trusted.filter((c) => c.thumbprint !== TRUSTED);
        return new Response(null, { status: 204 });
      }
      return json(404, { detail: "nope" });
    },
  });
  await client.login("admin", "secret");
  calls.length = 0;
  return { client, calls };
}

describe("Outbound TLS certificates page", () => {
  it("is a security section beside the OPC UA certificates", () => {
    const ids = ADMIN_SECTIONS.filter((s) => s.group === "security").map((s) => s.id);
    expect(ids.indexOf("outbound-tls")).toBe(ids.indexOf("opcua") + 1);
  });

  it("lists rejected servers with where they were seen and trusts one with the CSRF token", async () => {
    const { client, calls } = await fakeGateway();
    render(<OutboundTlsPage section={section} client={client} session={admin} />);
    const rejectedTable = await screen.findByRole("table", { name: "Rejected server certificates" });
    expect(rejectedTable.textContent).toContain("10.0.0.9:8883");
    expect(rejectedTable.textContent).toContain("mqtt");
    expect(rejectedTable.textContent).toContain("Self-signed");
    fireEvent.click(screen.getByRole("button", { name: "Trust broker" }));
    expect(await screen.findByText("Trusted broker.")).toBeVisible();
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe(`/api/v1/tls/outbound/rejected/${REJECTED}/trust`);
    expect(post?.csrf).toBe("csrf-1");
    await waitFor(() => expect(screen.getByRole("table", { name: "Rejected server certificates" }).textContent).toContain("No rejected"));
    expect(screen.getByRole("table", { name: "Trusted server certificates" }).textContent).toContain(REJECTED);
  });

  it("confirms before removing trust", async () => {
    const { client, calls } = await fakeGateway();
    render(<OutboundTlsPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Remove historian-db" }));
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    expect(screen.getByRole("dialog").textContent).toContain("will be refused");
    fireEvent.click(screen.getByRole("button", { name: "Confirm removal" }));
    expect(await screen.findByText("Removed trust for historian-db.")).toBeVisible();
    expect(calls.find((c) => c.method === "DELETE")?.url).toBe(`/api/v1/tls/outbound/trusted/${TRUSTED}`);
  });

  it("shows no trust controls without gateway.write", async () => {
    const { client } = await fakeGateway();
    render(<OutboundTlsPage section={section} client={client} session={operator} />);
    expect(await screen.findByText("broker")).toBeVisible();
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByLabelText("Certificate file (PEM or DER)")).toBeNull();
  });

  it("refuses a thumbprint that is not SHA-256 before sending anything", async () => {
    const { client, calls } = await fakeGateway();
    await expect(client.trustOutboundCertificate("a".repeat(40))).rejects.toThrow("Invalid certificate thumbprint");
    expect(calls).toHaveLength(0);
  });
});
