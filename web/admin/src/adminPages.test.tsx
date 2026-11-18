import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiKeysPage } from "./apiKeysPage";
import { ApiClient, type SessionState } from "./api";
import { BackupsPage } from "./backupsPage";
import { ModulesPage } from "./modulesPage";
import { UsersPage } from "./usersPage";
import { ADMIN_SECTIONS } from "./sections";

const admin: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["users.admin", "roles.admin", "apikeys.admin", "modules.admin", "backups.admin", "gateway.write", "gateway.read", "audit.read"],
  csrfToken: "csrf-1",
};

const viewer: SessionState = {
  username: "pat",
  roles: ["viewer"],
  permissions: ["gateway.access", "tags.read", "audit.read"],
  csrfToken: "csrf-1",
};

function section(id: string) {
  const found = ADMIN_SECTIONS.find((item) => item.id === id);
  if (!found) throw new Error(id);
  return found;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("users page", () => {
  it("does not render a password hash", async () => {
    const client = new ApiClient({
      fetch: async (input) => {
        const url = String(input);
        if (url.includes("/schemas/")) {
          return json(200, {
            schema: {
              type: "object",
              properties: {
                description: { type: "string" },
                permissions: { type: "array", items: { type: "string", enum: ["gateway.read"] } },
              },
            },
          });
        }
        if (url.includes("/security/roles")) {
          return json(200, { items: [{ name: "viewer", description: "Read-only", permissions: ["tags.read"], version: 1 }] });
        }
        return json(200, {
          items: [
            {
              username: "ada",
              displayName: "Ada",
              email: "",
              phone: "",
              enabled: true,
              roles: ["viewer"],
              passwordChangedAt: "2026-01-01T00:00:00Z",
              version: 2,
              password: "Correct-horse-1",
              passwordHash: "argon2id-HASHMARKER",
              passwordHistory: ["argon2id-HISTORYMARKER"],
            },
          ],
        });
      },
    });
    render(<UsersPage section={section("users")} client={client} session={admin} />);
    expect(await screen.findByRole("cell", { name: "ada" })).toBeVisible();
    expect(document.body.textContent).not.toContain("HASHMARKER");
    expect(document.body.textContent).not.toContain("HISTORYMARKER");
    expect(document.body.textContent).not.toContain("passwordHash");
    expect(document.body.textContent).not.toContain("Correct-horse-1");
  });
});

describe("API keys page", () => {
  it("shows a secret once and leaves it out of the list", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    const key = {
      name: "reader",
      description: "",
      enabled: true,
      keyId: "abcdabcdabcdabcd",
      scopes: ["gateway.read"],
      secureConnectionRequired: true,
      createdAt: "2026-01-01T00:00:00Z",
      version: 1,
      secret: "listed-secret-must-hide",
      keyHash: "HASHMARKER",
    };
    const fetchImpl: typeof fetch = async (input, init) => {
      const url = String(input);
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (url.includes("/schemas/")) {
        return json(200, {
          schema: { type: "object", properties: { scopes: { type: "array", items: { type: "string", enum: ["gateway.read"] } } } },
        });
      }
      if (url.endsWith("/security/api-keys") && init?.method === "POST") {
        return json(201, { ...key, name: "plant", version: 1, secret: "once-secret-value" });
      }
      return json(200, { items: [key] });
    };
    const client = new ApiClient({ fetch: fetchImpl });
    await client.login("admin", "secret");
    render(<ApiKeysPage section={section("api-keys")} client={client} session={admin} />);
    expect(await screen.findByRole("cell", { name: "reader" })).toBeVisible();
    expect(document.body.textContent).not.toContain("listed-secret-must-hide");
    expect(document.body.textContent).not.toContain("HASHMARKER");

    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), { target: { value: "plant" } });
    fireEvent.click(await screen.findByRole("checkbox", { name: "gateway.read" }));
    fireEvent.click(screen.getByRole("button", { name: "Create API key" }));

    expect(await screen.findByTestId("key-secret-value")).toHaveTextContent("once-secret-value");
    expect(screen.getByRole("table", { name: "API keys" })).not.toHaveTextContent("once-secret-value");
    fireEvent.click(screen.getByRole("button", { name: "Copy secret" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("once-secret-value"));
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByTestId("key-secret-value")).toBeNull();
    expect(document.body.textContent).not.toContain("once-secret-value");
  });
});

describe("read-only sessions", () => {
  it("shows the server 403 and hides module and backup writes", async () => {
    const client = new ApiClient({
      fetch: async () => json(403, { detail: "gateway.read denied (PERMISSION_DENIED)" }),
    });
    const modules = render(<ModulesPage section={section("modules")} client={client} session={viewer} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("gateway.read denied (PERMISSION_DENIED)");
    expect(screen.queryByRole("button", { name: "Install" })).toBeNull();
    modules.unmount();

    const backups = render(<BackupsPage section={section("backups")} client={client} session={viewer} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("gateway.read denied (PERMISSION_DENIED)");
    expect(screen.getByTestId("backup-warning")).toHaveTextContent("secrets.key");
    expect(screen.queryByRole("button", { name: "Create backup" })).toBeNull();
    backups.unmount();
  });
});

describe("backups page", () => {
  const listing = () =>
    new ApiClient({
      fetch: async () =>
        json(200, {
          backups: [
            { name: "20260101-000000-000.oipbk", bytes: 42, encrypted: true },
            { name: "20250101-000000-000.zip", bytes: 40, encrypted: false },
          ],
        }),
    });

  it("lists backups to gateway readers without download, create, or restore", async () => {
    const operator: SessionState = {
      username: "oli",
      roles: ["operator"],
      permissions: ["gateway.read", "gateway.access", "tags.read", "tags.write", "audit.read"],
      csrfToken: "csrf-1",
    };
    render(<BackupsPage section={section("backups")} client={listing()} session={operator} />);
    expect(await screen.findByRole("cell", { name: "20260101-000000-000.oipbk" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Download / })).toBeNull();
    expect(screen.queryByRole("button", { name: "Create backup" })).toBeNull();
    expect(screen.queryByLabelText("Backup passphrase")).toBeNull();
    expect(screen.queryByRole("button", { name: /Restore / })).toBeNull();
  });

  it("offers download, create, and restore with backups.admin; a plaintext legacy file has no download", async () => {
    render(<BackupsPage section={section("backups")} client={listing()} session={admin} />);
    expect(await screen.findByRole("link", { name: "Download 20260101-000000-000.oipbk" })).toHaveAttribute(
      "href",
      "/api/v1/backups/20260101-000000-000.oipbk",
    );
    expect(screen.queryByRole("link", { name: "Download 20250101-000000-000.zip" })).toBeNull();
    expect(screen.getByRole("cell", { name: /Not encrypted/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create backup" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore 20260101-000000-000.oipbk" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore 20250101-000000-000.zip" })).toBeInTheDocument();
  });

  it("sends the passphrase to create and restore, and keeps the dialog open on a wrong passphrase", async () => {
    const calls: { method: string; url: string; body: unknown }[] = [];
    let restoreStatus = 422;
    const client = new ApiClient({
      fetch: async (input, init) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
        if (url === "/api/v1/auth/login") return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
        if (method === "POST" && url === "/api/v1/backups") {
          return new Response(new Uint8Array([1, 2, 3]), {
            status: 200,
            headers: { "Content-Type": "application/octet-stream", "X-Backup-Name": "20260101-000000-000.oipbk" },
          });
        }
        if (method === "POST" && url.endsWith("/restore")) {
          return restoreStatus === 200 ? json(200, { restored: true }) : json(422, { detail: "wrong passphrase" });
        }
        return json(200, { backups: [{ name: "20260101-000000-000.oipbk", bytes: 3, encrypted: true }] });
      },
    });
    await client.login("admin", "secret");
    render(<BackupsPage section={section("backups")} client={client} session={admin} />);
    const create = await screen.findByRole("button", { name: "Create backup" });
    expect(create).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Backup passphrase"), { target: { value: "too short" } });
    fireEvent.change(screen.getByLabelText("Confirm passphrase"), { target: { value: "too short" } });
    expect(create).toBeDisabled();
    expect(screen.getByText("At least 12 characters.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Backup passphrase"), { target: { value: "long enough phrase" } });
    expect(create).toBeDisabled();
    expect(screen.getByText("The passphrases do not match.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Confirm passphrase"), { target: { value: "long enough phrase" } });
    expect(create).toBeEnabled();
    fireEvent.click(create);
    expect(await screen.findByText("Created 20260101-000000-000.oipbk.")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST" && c.url === "/api/v1/backups")?.body).toEqual({
      passphrase: "long enough phrase",
    });
    expect(screen.getByLabelText("Backup passphrase")).toHaveValue("");

    fireEvent.click(await screen.findByRole("button", { name: "Restore 20260101-000000-000.oipbk" }));
    const confirm = screen.getByRole("button", { name: "Confirm restore" });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Restore passphrase"), { target: { value: "not it" } });
    fireEvent.click(confirm);
    expect(await screen.findByRole("alert")).toHaveTextContent("wrong passphrase");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    restoreStatus = 200;
    fireEvent.change(screen.getByLabelText("Restore passphrase"), { target: { value: "long enough phrase" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm restore" }));
    expect(await screen.findByText("Restored 20260101-000000-000.oipbk.")).toBeInTheDocument();
    const restores = calls.filter((c) => c.url.endsWith("/restore")).map((c) => c.body);
    expect(restores).toEqual([{ passphrase: "not it" }, { passphrase: "long enough phrase" }]);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});
