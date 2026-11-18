import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { ADMIN_SECTIONS, sectionsByGroup } from "./sections";
import { formatUsers, parseUsers, UserSourcesPage } from "./userSourcesPage";

/** SEC-007, SEC-010, SEC-021, SEC-025: the user sources page. */

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

const section = ADMIN_SECTIONS.find((s) => s.id === "user-sources")!;

const corp = {
  name: "corp",
  type: "ldap",
  enabled: true,
  ldapUrls: ["ldaps://dc1.example.test"],
  bindDn: "cn=gateway,ou=system,dc=example,dc=test",
  userBaseDn: "ou=people,dc=example,dc=test",
  userFilter: "(sAMAccountName={username})",
  roleMappings: [{ value: "Plant Operators", roles: ["operator"] }],
  defaultRoles: ["viewer"],
  bindPasswordSet: true,
  inUse: true,
  version: 4,
};

afterEach(() => cleanup());

describe("user sources page", () => {
  it("is listed under security", () => {
    const security = sectionsByGroup().find((entry) => entry.group.id === "security")!;
    expect(security.sections.map((s) => s.id)).toContain("user-sources");
  });

  it("lists sources, shows the login source, and never shows the bind password", async () => {
    const { client } = await clientFor((call) =>
      call.method === "GET" && call.url === "/api/v1/security/user-sources" ? json(200, { items: [corp], systemUserSource: "corp" }) : undefined,
    );
    render(<UserSourcesPage section={section} client={client} session={admin} />);
    const table = await screen.findByRole("table", { name: "User sources" });
    const row = within(table).getByText("corp").closest("tr")!;
    expect(within(row).getByText("LDAP / Active Directory")).toBeTruthy();
    expect(within(row).getByText("Set")).toBeTruthy();
    expect(screen.getByTestId("system-user-source").textContent).toBe("corp");
    // The source gateway logins use cannot be deleted from here.
    expect((screen.getByRole("button", { name: "Delete corp" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Edit corp" }));
    const form = screen.getByRole("form", { name: "Edit user source corp" });
    expect((within(form).getByLabelText("Service account password") as HTMLInputElement).value).toBe("");
    expect((within(form).getByLabelText(/Role mappings/) as HTMLTextAreaElement).value).toBe("Plant Operators = operator");
  });

  it("creates a directory source and sends the bind password once", async () => {
    let saved: unknown = null;
    const { client, calls } = await clientFor((call) => {
      if (call.method === "GET") return json(200, { items: [], systemUserSource: "internal" });
      if (call.method === "POST" && call.url === "/api/v1/security/user-sources") {
        saved = call.body;
        return json(201, { ...(call.body as object), bindPasswordSet: true, inUse: false, version: 1 });
      }
      return undefined;
    });
    render(<UserSourcesPage section={section} client={client} session={admin} />);
    await screen.findByText(/No user sources yet/);
    fireEvent.click(screen.getByRole("button", { name: "Add user source" }));
    const form = screen.getByRole("form", { name: "New user source" });
    fireEvent.change(within(form).getByLabelText("Source name"), { target: { value: "corp" } });
    fireEvent.change(within(form).getByLabelText(/Server URLs/), { target: { value: "ldaps://dc1.example.test ldaps://dc2.example.test" } });
    fireEvent.change(within(form).getByLabelText("Service account DN"), { target: { value: "cn=gateway,dc=example,dc=test" } });
    fireEvent.change(within(form).getByLabelText("Service account password"), { target: { value: "bind-secret" } });
    fireEvent.change(within(form).getByLabelText("User search base"), { target: { value: "dc=example,dc=test" } });
    fireEvent.click(within(form).getByLabelText("Follow nested groups"));
    fireEvent.change(within(form).getByLabelText(/Role mappings/), { target: { value: "CN=Ops,OU=Groups,DC=example,DC=test = operator, viewer" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create user source" }));
    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved).toMatchObject({
      name: "corp",
      type: "ldap",
      ldapUrls: ["ldaps://dc1.example.test", "ldaps://dc2.example.test"],
      bindDn: "cn=gateway,dc=example,dc=test",
      bindPassword: "bind-secret",
      nestedGroups: true,
      roleMappings: [{ value: "CN=Ops,OU=Groups,DC=example,DC=test", roles: ["operator", "viewer"] }],
    });
    expect(calls.filter((c) => JSON.stringify(c.body ?? "").includes("bind-secret"))).toHaveLength(1);
  });

  it("shows database fields for a database source and the users list for an internal-roles hybrid", async () => {
    const { client } = await clientFor((call) => (call.method === "GET" ? json(200, { items: [], systemUserSource: "internal" }) : undefined));
    render(<UserSourcesPage section={section} client={client} session={admin} />);
    await screen.findByText(/No user sources yet/);
    fireEvent.click(screen.getByRole("button", { name: "Add user source" }));
    const form = screen.getByRole("form", { name: "New user source" });
    fireEvent.change(within(form).getByLabelText("Type"), { target: { value: "database" } });
    expect(within(form).getByLabelText("Password hash scheme")).toBeTruthy();
    expect(within(form).queryByLabelText("Service account password")).toBeNull();
    fireEvent.change(within(form).getByLabelText("Type"), { target: { value: "ldap-internal" } });
    expect(within(form).getByLabelText(/Users and their roles/)).toBeTruthy();
    expect(within(form).queryByLabelText("Users table")).toBeNull();
  });

  it("checks a user on a source and shows the groups it read", async () => {
    const { client, calls } = await clientFor((call) => {
      if (call.method === "GET") return json(200, { items: [{ ...corp, inUse: false }], systemUserSource: "internal" });
      if (call.url === "/api/v1/security/user-sources/corp/check") {
        return json(200, {
          source: "corp", username: "alice", exists: true, enabled: true, roles: ["operator"], passwordChecked: true, passwordValid: true,
          groups: ["Plant Operators"], dn: "uid=alice,ou=people,dc=example,dc=test", unavailable: null,
        });
      }
      return undefined;
    });
    render(<UserSourcesPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Check user on corp" }));
    const form = screen.getByRole("form", { name: "Check user on corp" });
    fireEvent.change(within(form).getByLabelText("Username to check"), { target: { value: "alice" } });
    fireEvent.change(within(form).getByLabelText(/Password to test/), { target: { value: "pw" } });
    fireEvent.click(within(form).getByRole("button", { name: "Check user" }));
    const dl = await within(form).findByLabelText("Check result");
    expect(dl.textContent).toContain("Plant Operators");
    expect(dl.textContent).toContain("uid=alice,ou=people,dc=example,dc=test");
    expect(calls.find((c) => c.url.endsWith("/check"))?.body).toEqual({ username: "alice", password: "pw" });
  });

  it("needs users.admin", async () => {
    const { client } = await clientFor(() => undefined);
    render(<UserSourcesPage section={section} client={client} session={viewer} />);
    expect(screen.getByRole("alert").textContent).toContain("users.admin");
  });

  it("round-trips the users list of an internal-roles hybrid", () => {
    const users = [{ username: "alice", roles: ["operator", "viewer"] }];
    expect(parseUsers(formatUsers(users))).toEqual(users);
  });
});
