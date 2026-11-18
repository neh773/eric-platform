import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { certificateText, OpcUaPage } from "./opcUaPage";
import { ADMIN_SECTIONS } from "./sections";

afterEach(() => cleanup());

const admin: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["gateway.write", "gateway.read"],
  csrfToken: "csrf-1",
};
const operator: SessionState = { username: "olly", roles: ["operator"], permissions: ["gateway.read"], csrfToken: "csrf-1" };

const section = ADMIN_SECTIONS.find((item) => item.id === "opcua")!;
const QUARANTINED = "a".repeat(40);
const TRUSTED = "b".repeat(40);

function cert(thumbprint: string, cn: string) {
  return {
    thumbprint,
    subject: `CN=${cn},O=Plant`,
    commonName: cn,
    issuer: `CN=${cn}`,
    applicationUri: `urn:${cn}`,
    notBefore: "2026-01-01T00:00:00Z",
    notAfter: "2029-01-01T00:00:00Z",
    expired: false,
    privateKey: "-----BEGIN PRIVATE KEY-----SECRETKEY",
  };
}

async function fakeGateway() {
  const calls: { method: string; url: string; csrf: string | null; body?: string }[] = [];
  let trusted = [cert(TRUSTED, "Historian")];
  let quarantined = [cert(QUARANTINED, "Scada Client")];
  const client = new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const headers = new Headers(init?.headers);
      calls.push({ method, url, csrf: headers.get("X-CSRF-Token"), body: typeof init?.body === "string" ? init.body : undefined });
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (method === "GET") {
        return json(200, { serverEnabled: true, serverRunning: true, endpointUrl: "opc.tcp://gw:4840/oip", trusted, quarantined });
      }
      if (method === "POST" && url.endsWith(`/quarantined/${QUARANTINED}/trust`)) {
        const moved = quarantined[0]!;
        quarantined = [];
        trusted = [...trusted, moved];
        return json(200, moved);
      }
      if (method === "DELETE" && url.endsWith(`/trusted/${TRUSTED}`)) {
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

describe("OPC UA certificates page", () => {
  it("lists quarantined and trusted certificates and trusts one with the CSRF token", async () => {
    const { client, calls } = await fakeGateway();
    render(<OpcUaPage section={section} client={client} session={admin} />);
    expect(await screen.findByText("Scada Client")).toBeVisible();
    expect(screen.getByTestId("opcua-server-status").textContent).toContain("opc.tcp://gw:4840/oip");
    expect(screen.getByRole("table", { name: "Trusted certificates" }).textContent).toContain(TRUSTED);
    expect(document.body.textContent).not.toContain("SECRETKEY");

    fireEvent.click(screen.getByRole("button", { name: "Trust Scada Client" }));
    expect(await screen.findByText("Trusted Scada Client.")).toBeVisible();
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe(`/api/v1/opcua/certificates/quarantined/${QUARANTINED}/trust`);
    expect(post?.csrf).toBe("csrf-1");
    await waitFor(() => expect(screen.getByRole("table", { name: "Quarantined certificates" }).textContent).toContain("No quarantined"));
  });

  it("asks for confirmation before removing trust", async () => {
    const { client, calls } = await fakeGateway();
    render(<OpcUaPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Remove Historian" }));
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    expect(screen.getByRole("dialog").textContent).toContain("quarantine again");
    fireEvent.click(screen.getByRole("button", { name: "Confirm removal" }));
    expect(await screen.findByText("Removed trust for Historian.")).toBeVisible();
    expect(calls.find((c) => c.method === "DELETE")?.url).toBe(`/api/v1/opcua/certificates/trusted/${TRUSTED}`);
  });

  it("shows no trust controls without gateway.write", async () => {
    const { client } = await fakeGateway();
    render(<OpcUaPage section={section} client={client} session={operator} />);
    expect(await screen.findByText("Scada Client")).toBeVisible();
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByLabelText("Certificate file (PEM or DER)")).toBeNull();
  });

  it("sends PEM as text and DER as base64", () => {
    const pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
    expect(certificateText(new TextEncoder().encode(pem))).toBe(pem);
    expect(certificateText(new Uint8Array([0x30, 0x82, 0x01]))).toBe("MIIB");
  });
});
