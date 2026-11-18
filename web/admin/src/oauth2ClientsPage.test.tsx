import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EmailProfilesPage } from "./emailProfilesPage";
import { OAuth2ClientsPage } from "./oauth2ClientsPage";
import { ADMIN_SECTIONS } from "./sections";
import { adminSession, fakeGateway, json, readerSession, sectionById, viewerSession } from "./statusTestKit";

// Doc source: FEATURES SEC-029.
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const BASE = "/api/v1/security/oauth2-clients";
const mail = {
  name: "mail", description: "mail sender", enabled: true, clientId: "client-1", clientSecretSet: true, clientSecretRef: null,
  authorizationUrl: "https://login.example.test/authorize", tokenUrl: "https://login.example.test/token", scope: "mail.send",
  grantType: "refresh_token", refreshTokenSet: true, redirectBaseUrl: "", redirectUri: `https://gw.example.test${BASE}/mail/authorize/callback`,
  httpVersion: "HTTP_2", connectTimeoutMillis: 30000, requestTimeoutMillis: 120000, usedBy: ["plant"], tokenCachedUntil: null,
  problems: [], version: 2,
};

describe("OAuth2 clients page", () => {
  it("is a security section", () => {
    expect(ADMIN_SECTIONS.find((item) => item.id === "oauth2-clients")?.group).toBe("security");
  });

  it("lists registrations with whether each secret is set and who uses them", async () => {
    const kit = fakeGateway(({ url }) => (url === BASE ? json(200, { items: [mail] }) : undefined));
    render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={kit.client} session={adminSession} />);
    const row = await screen.findByTestId("oauth2-client-mail");
    expect(row).toHaveTextContent("client-1");
    expect(row).toHaveTextContent("Refresh token");
    expect(row).toHaveTextContent("Client secret set, refresh token set");
    expect(row).toHaveTextContent("plant");
  });

  it("creates a registration with the secret in the request and not on the page", async () => {
    let saved = "";
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === BASE && method === "GET") return json(200, { items: [] });
      if (url === `${BASE}/svc` && method === "PUT") {
        saved = String(body);
        return json(201, { ...mail, name: "svc" });
      }
      return undefined;
    });
    await kit.signIn();
    render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "Create OAuth2 client" }));
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "svc" } });
    fireEvent.change(screen.getByLabelText("Client ID"), { target: { value: "client-9" } });
    fireEvent.change(screen.getByLabelText("Client secret"), { target: { value: "Typed-client-secret" } });
    fireEvent.change(screen.getByLabelText("Token URL"), { target: { value: "https://login.example.test/token" } });
    fireEvent.change(screen.getByLabelText("HTTP version"), { target: { value: "HTTP_1_1" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Create OAuth2 client" }).at(-1)!);
    await waitFor(() => expect(saved).not.toBe(""));
    expect(JSON.parse(saved)).toMatchObject({
      clientId: "client-9", clientSecret: "Typed-client-secret", tokenUrl: "https://login.example.test/token",
      grantType: "client_credentials", httpVersion: "HTTP_1_1", connectTimeoutMillis: 30000, requestTimeoutMillis: 120000,
    });
    expect(JSON.parse(saved)).not.toHaveProperty("refreshToken");
    expect(document.body.textContent).not.toContain("Typed-client-secret");
  });

  it("can reference a stored secret instead of embedding one", async () => {
    let saved = "";
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === BASE && method === "GET") return json(200, { items: [mail] });
      if (url === `${BASE}/mail` && method === "PUT") {
        saved = String(body);
        return json(200, mail);
      }
      return undefined;
    });
    await kit.signIn();
    render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit mail" }));
    expect(screen.getByTestId("oauth2-redirect")).toHaveTextContent("/authorize/callback");
    fireEvent.change(screen.getByLabelText("Client secret source"), { target: { value: "referenced" } });
    fireEvent.change(screen.getByLabelText("Secret reference"), { target: { value: "secret://shared-client" } });
    fireEvent.click(screen.getByRole("button", { name: "Save OAuth2 client" }));
    await waitFor(() => expect(saved).not.toBe(""));
    expect(JSON.parse(saved)).toMatchObject({ clientSecretRef: "secret://shared-client" });
    expect(JSON.parse(saved)).not.toHaveProperty("clientSecret");
  });

  it("verifies a token and shows the answer without a token", async () => {
    let sent = "";
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === BASE && method === "GET") return json(200, { items: [mail] });
      if (url === `${BASE}/mail/verify-token`) {
        sent = String(body);
        return json(200, { ok: true, grantType: "client_credentials", tokenType: "Bearer", expiresInSeconds: 3600, scope: "mail.send", refreshTokenReceived: false, receivedAt: "2026-11-10T00:00:00Z", expiresAt: "2026-11-10T01:00:00Z" });
      }
      return undefined;
    });
    await kit.signIn();
    render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "Verify token for mail" }));
    fireEvent.change(screen.getByLabelText("Grant type"), { target: { value: "client_credentials" } });
    fireEvent.click(screen.getByRole("button", { name: "Request token" }));
    const result = await screen.findByTestId("oauth2-token-result");
    expect(result).toHaveTextContent("Bearer");
    expect(result).toHaveTextContent("3600 s");
    expect(result).toHaveTextContent("Not received");
    expect(JSON.parse(sent)).toMatchObject({ grantType: "client_credentials", scope: "mail.send" });
  });

  it("opens the provider's authorization page in a new window", async () => {
    const opened = { location: { href: "" }, close: vi.fn() };
    vi.stubGlobal("open", vi.fn(() => opened));
    const kit = fakeGateway(({ url, method }) => {
      if (url === BASE && method === "GET") return json(200, { items: [mail] });
      if (url === `${BASE}/mail/authorize`) return json(200, { authorizationUrl: "https://login.example.test/authorize?state=s1", redirectUri: mail.redirectUri });
      return undefined;
    });
    await kit.signIn();
    render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "Verify authorization for mail" }));
    await waitFor(() => expect(opened.location.href).toBe("https://login.example.test/authorize?state=s1"));
  });

  it("gives a reader the list but no edit controls, and a viewer nothing", async () => {
    const kit = fakeGateway(({ url }) => (url === BASE ? json(200, { items: [mail] }) : undefined));
    const { unmount } = render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={kit.client} session={readerSession} />);
    expect(await screen.findByTestId("oauth2-client-mail")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Create OAuth2 client" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Edit mail" })).toBeNull();
    unmount();
    const denied = fakeGateway(() => json(403, { title: "Forbidden", status: 403, detail: "gateway.read denied" }));
    render(<OAuth2ClientsPage section={sectionById("oauth2-clients")} client={denied.client} session={viewerSession} />);
    expect(await screen.findByTestId("oauth2-no-read")).toBeVisible();
    expect(denied.hits(BASE)).toHaveLength(0);
  });
});

describe("email profile with an OAuth2 client", () => {
  it("names the registration instead of carrying its own token settings", async () => {
    let saved = "";
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === "/api/v1/email/profiles" && method === "GET") return json(200, { items: [] });
      if (url === "/api/v1/email/profiles/graph" && method === "PUT") {
        saved = String(body);
        return json(201, { name: "graph", hostname: "smtp.mail.test", port: 587, authType: "oauth2", oauthClient: "mail" });
      }
      return undefined;
    });
    await kit.signIn();
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "New email profile" }));
    fireEvent.change(screen.getByLabelText("Profile name"), { target: { value: "graph" } });
    fireEvent.change(screen.getByLabelText("SMTP host"), { target: { value: "smtp.mail.test" } });
    fireEvent.change(screen.getByLabelText("Login method"), { target: { value: "oauth2" } });
    fireEvent.change(screen.getByLabelText("OAuth2 client"), { target: { value: "mail" } });
    expect(screen.queryByLabelText("Token URL")).toBeNull();
    expect(screen.queryByLabelText("Client secret")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(saved).not.toBe(""));
    expect(JSON.parse(saved)).toMatchObject({ authType: "oauth2", oauthClient: "mail" });
  });
});
