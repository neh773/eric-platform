import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Login } from "./App";
import { ApiClient, type SessionState } from "./api";
import { formatMappings, IdentityProvidersPage, parseMappings } from "./identityProvidersPage";
import { ADMIN_SECTIONS, sectionsByGroup } from "./sections";
import { UserCheckForm } from "./usersPage";

/**
 * P1-32 sign-in buttons (SEC-013), P1-33 identity providers page (SEC-014), P1-34 user check form (SEC-027).
 */

const admin: SessionState = { username: "admin", roles: ["administrator"], permissions: ["users.admin", "gateway.read"], csrfToken: "csrf-1" };
const viewer: SessionState = { username: "view", roles: ["viewer"], permissions: ["tags.read"], csrfToken: "csrf-1" };

type Call = { method: string; url: string; body: unknown; headers: Headers };

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function clientFor(route: (call: Call) => Response | undefined) {
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
        return json(200, { principal: "admin", roles: ["administrator"], permissions: admin.permissions, csrfToken: "csrf-1" });
      }
      calls.push(call);
      return route(call) ?? json(404, { title: "Not Found", detail: `no fake for ${call.method} ${call.url}` });
    },
  });
  await client.session();
  return { client, calls };
}

const section = ADMIN_SECTIONS.find((s) => s.id === "identity-providers")!;

const corp = {
  name: "corp",
  type: "oidc",
  displayName: "Corporate",
  enabled: true,
  issuer: "https://idp.example.test",
  authorizationEndpoint: "https://idp.example.test/authorize",
  tokenEndpoint: "https://idp.example.test/token",
  jwksUri: "https://idp.example.test/jwks",
  endSessionEndpoint: "",
  clientId: "oip",
  roleMappings: [{ value: "plant-ops", roles: ["operator"] }],
  defaultRoles: ["viewer"],
  clientSecretSet: true,
  callbackPath: "/api/v1/auth/providers/corp/callback",
  version: 3,
};

afterEach(() => {
  cleanup();
  window.history.replaceState(null, "", "/");
});

describe("identity providers section", () => {
  it("sits in the Security nav group", () => {
    const security = sectionsByGroup().find((entry) => entry.group.id === "security")!;
    expect(security.sections.map((s) => s.id)).toContain("identity-providers");
  });

  it("lists providers without any secret and says whether one is set", async () => {
    const { client } = await clientFor((call) => (call.url === "/api/v1/security/identity-providers" ? json(200, { items: [corp] }) : undefined));
    render(<IdentityProvidersPage section={section} client={client} session={admin} />);
    const table = await screen.findByRole("table", { name: "Identity providers" });
    const row = within(table).getByRole("row", { name: /corp/ });
    expect(within(row).getByText("OIDC")).toBeInTheDocument();
    expect(within(row).getByText("Set")).toBeInTheDocument();
    expect(within(row).getByText("/api/v1/auth/providers/corp/callback")).toBeInTheDocument();
  });

  it("needs users.admin", async () => {
    const { client, calls } = await clientFor(() => undefined);
    render(<IdentityProvidersPage section={section} client={client} session={viewer} />);
    expect(screen.getByRole("alert")).toHaveTextContent("users.admin");
    expect(calls).toHaveLength(0);
  });

  it("imports discovery, then creates the provider with the secret sent once", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.url === "/api/v1/security/identity-providers" && call.method === "GET") return json(200, { items: [] });
      if (call.url === "/api/v1/security/identity-providers/import") {
        return json(200, { fields: { type: "oidc", issuer: "https://idp.example.test", authorizationEndpoint: "https://idp.example.test/authorize", tokenEndpoint: "https://idp.example.test/token", jwksUri: "https://idp.example.test/jwks" } });
      }
      if (call.url === "/api/v1/security/identity-providers" && call.method === "POST") return json(201, { ...corp, ...(call.body as object) });
      return undefined;
    });
    render(<IdentityProvidersPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add identity provider" }));
    const form = screen.getByRole("form", { name: "New identity provider" });
    fireEvent.change(within(form).getByLabelText("Provider name"), { target: { value: "corp" } });
    fireEvent.change(within(form).getByLabelText("Issuer"), { target: { value: "https://idp.example.test" } });
    fireEvent.click(within(form).getByRole("button", { name: "Import discovery document" }));
    await waitFor(() => expect(within(form).getByLabelText("Token endpoint")).toHaveValue("https://idp.example.test/token"));
    expect(calls.find((c) => c.url.endsWith("/import"))?.body).toEqual({ type: "oidc", issuer: "https://idp.example.test" });
    fireEvent.change(within(form).getByLabelText("Client ID"), { target: { value: "oip" } });
    fireEvent.change(within(form).getByLabelText("Client secret"), { target: { value: "shh-1" } });
    fireEvent.change(within(form).getByLabelText(/Role mappings/), { target: { value: "plant-ops = operator" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create identity provider" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url === "/api/v1/security/identity-providers")).toBe(true));
    const post = calls.find((c) => c.method === "POST" && c.url === "/api/v1/security/identity-providers")!;
    expect(post.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(post.body).toMatchObject({
      name: "corp",
      type: "oidc",
      clientId: "oip",
      clientSecret: "shh-1",
      tokenEndpoint: "https://idp.example.test/token",
      roleMappings: [{ value: "plant-ops", roles: ["operator"] }],
    });
  });

  it("an edit without a new secret keeps the stored one (no clientSecret sent)", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.method === "GET") return json(200, { items: [corp] });
      if (call.method === "PUT") return json(200, { ...corp, version: 4 });
      return undefined;
    });
    render(<IdentityProvidersPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit corp" }));
    const form = screen.getByRole("form", { name: "Edit identity provider corp" });
    expect(within(form).getByLabelText("Client secret")).toHaveValue("");
    fireEvent.click(within(form).getByRole("button", { name: "Save identity provider" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe("/api/v1/security/identity-providers/corp");
    expect(put.headers.get("If-Match")).toBe("3");
    expect(put.body).not.toHaveProperty("clientSecret");
    expect(put.body).not.toHaveProperty("clientSecretSet");
    expect(put.body).not.toHaveProperty("version");
  });

  it("shows the 422 problems of an invalid import", async () => {
    const { client } = await clientFor((call) => {
      if (call.method === "GET") return json(200, { items: [] });
      if (call.url.endsWith("/import")) return json(422, { title: "Unprocessable Content", detail: "metadata is not valid: metadata has no signing certificate" });
      return undefined;
    });
    render(<IdentityProvidersPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add identity provider" }));
    const form = screen.getByRole("form", { name: "New identity provider" });
    fireEvent.change(within(form).getByLabelText("Type"), { target: { value: "saml" } });
    fireEvent.change(within(form).getByLabelText("Metadata XML"), { target: { value: "<x/>" } });
    fireEvent.click(within(form).getByRole("button", { name: "Import metadata" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("no signing certificate");
  });

  it("round-trips role mappings text", () => {
    const parsed = parseMappings("plant-ops = operator, viewer\n\nadmins=administrator");
    expect(parsed).toEqual([
      { value: "plant-ops", roles: ["operator", "viewer"] },
      { value: "admins", roles: ["administrator"] },
    ]);
    expect(formatMappings(parsed)).toBe("plant-ops = operator, viewer\nadmins = administrator");
  });
});

describe("sign-in page", () => {
  it("shows a button per enabled provider that starts the provider login", async () => {
    const client = new ApiClient({
      fetch: async (input) => {
        if (String(input) === "/api/v1/auth/providers") {
          return json(200, { items: [{ name: "corp", displayName: "Corporate", type: "oidc", loginPath: "/api/v1/auth/providers/corp/login" }] });
        }
        return json(200, { status: "RUNNING", warnings: [] });
      },
    });
    render(<Login client={client} onSignedIn={() => undefined} />);
    const link = await screen.findByRole("link", { name: "Sign in with Corporate" });
    expect(link).toHaveAttribute("href", "/api/v1/auth/providers/corp/login?returnTo=%2Fadmin%2Foverview");
  });

  it("saves name and email claims and shows a failed login's setup check", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.url === "/api/v1/security/identity-providers" && call.method === "GET") return json(200, { items: [corp] });
      if (call.url.endsWith("/corp") && call.method === "PUT") return json(200, { ...corp, nameClaim: "given_name", emailClaim: "mail", version: 4 });
      if (call.url.endsWith("/test") && call.method === "POST") return json(200, { testId: "t1", loginPath: "/api/v1/auth/providers/corp/login?test=t1" });
      if (call.url.endsWith("/test/t1")) {
        return json(200, {
          testId: "t1",
          status: "failure",
          reason: "token expired",
          diagnosis: { code: "clock-skew", summary: "The token is outside its validity time.", hint: "Check that the gateway and the provider keep the same time.", facts: {} },
        });
      }
      return undefined;
    });
    vi.stubGlobal("open", () => null);
    render(<IdentityProvidersPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit corp" }));
    const form = await screen.findByRole("form", { name: "Edit identity provider corp" });
    fireEvent.change(within(form).getByLabelText("Name claim"), { target: { value: "given_name" } });
    fireEvent.change(within(form).getByLabelText("Email claim"), { target: { value: "mail" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save identity provider" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    const put = calls.find((call) => call.method === "PUT");
    expect(put?.body).toMatchObject({ nameClaim: "given_name", emailClaim: "mail" });
    fireEvent.click(screen.getByRole("button", { name: "Test login corp" }));
    fireEvent.click(await screen.findByRole("button", { name: "Check setup" }));
    expect(await screen.findByTestId("idp-setup-check")).toHaveTextContent("outside its validity time");
    expect(screen.getByTestId("idp-setup-check")).toHaveTextContent("same time");
    vi.unstubAllGlobals();
  });

  it("says when a provider login was refused", async () => {
    window.history.replaceState(null, "", "/admin/login?error=idp");
    const client = new ApiClient({ fetch: async () => json(200, { items: [], status: "RUNNING", warnings: [] }) });
    render(<Login client={client} onSignedIn={() => undefined} />);
    expect(screen.getByRole("alert")).toHaveTextContent("identity provider was refused");
  });
});

describe("user check", () => {
  it("reports existence, roles and the password result, and clears the password", async () => {
    const { client, calls } = await clientFor((call) =>
      call.url === "/api/v1/security/user-sources/internal/check"
        ? json(200, { source: "internal", username: "carol", exists: true, enabled: true, roles: ["operator"], passwordChecked: true, passwordValid: false })
        : undefined,
    );
    render(<UserCheckForm client={client} />);
    const form = screen.getByRole("form", { name: "Check user" });
    fireEvent.change(within(form).getByLabelText("Username to check"), { target: { value: "carol" } });
    fireEvent.change(within(form).getByLabelText("Password to test (optional)"), { target: { value: "Wrong-1" } });
    fireEvent.click(within(form).getByRole("button", { name: "Check user" }));
    const dl = await within(form).findByLabelText("Check result");
    expect(dl).toHaveTextContent("carol exists");
    expect(dl).toHaveTextContent("operator");
    expect(dl).toHaveTextContent("Incorrect");
    expect(within(form).getByLabelText("Password to test (optional)")).toHaveValue("");
    expect(calls[0]!.body).toEqual({ username: "carol", password: "Wrong-1" });
  });

  it("sends no password when none is entered and reports a missing user", async () => {
    const { client, calls } = await clientFor(() =>
      json(200, { source: "internal", username: "nobody", exists: false, enabled: null, roles: [], passwordChecked: false, passwordValid: null }),
    );
    render(<UserCheckForm client={client} />);
    const form = screen.getByRole("form", { name: "Check user" });
    fireEvent.change(within(form).getByLabelText("Username to check"), { target: { value: "nobody" } });
    fireEvent.click(within(form).getByRole("button", { name: "Check user" }));
    const dl = await within(form).findByLabelText("Check result");
    expect(dl).toHaveTextContent("nobody was not found");
    expect(dl).toHaveTextContent("Not tested");
    expect(calls[0]!.body).toEqual({ username: "nobody" });
  });

  /** SEC-018: the page starts a test login for a provider and opens its login path in a new window. */
  it("starts a test login and opens the provider login path", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.method === "GET" && call.url === "/api/v1/security/identity-providers") return json(200, { items: [corp] });
      if (call.method === "POST" && call.url === "/api/v1/security/identity-providers/corp/test") {
        return json(201, { testId: "t1", loginPath: "/api/v1/auth/providers/corp/login?test=t1" });
      }
      return undefined;
    });
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    render(<IdentityProvidersPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Test login corp" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("/api/v1/auth/providers/corp/login?test=t1", "_blank", "noopener"));
    expect(calls.some((c) => c.method === "POST" && c.url.endsWith("/corp/test"))).toBe(true);
    open.mockRestore();
  });
});
