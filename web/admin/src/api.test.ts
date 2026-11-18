import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError, parseHealth } from "./api";

afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("ApiClient", () => {
  it("sends X-CSRF-Token on logout and does not touch web storage", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const calls: { url: string; init?: RequestInit }[] = [];
    const fetchImpl: typeof fetch = async (input, init) => {
      calls.push({ url: String(input), init });
      if (String(input).endsWith("/login")) {
        return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      }
      return new Response(null, { status: 204 });
    };
    const client = new ApiClient({ fetch: fetchImpl });
    await client.login("admin", "secret");
    await client.logout();
    const loginHeaders = new Headers(calls[0]?.init?.headers);
    const logoutHeaders = new Headers(calls[1]?.init?.headers);
    expect(loginHeaders.get("X-CSRF-Token")).toBeNull();
    expect(logoutHeaders.get("X-CSRF-Token")).toBe("csrf-1");
    expect(calls[1]?.init?.method).toBe("POST");
    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(client.csrfToken()).toBeNull();
  });

  it("sends a signed-in caller to login on 401 and leaves a failed login on the form", async () => {
    let redirected = 0;
    const fetchImpl: typeof fetch = async (input) => {
      if (String(input).endsWith("/login")) {
        return json(401, { title: "Unauthorized", detail: "invalid credentials" });
      }
      return json(401, { title: "Unauthorized", detail: "authentication required" });
    };
    const client = new ApiClient({
      fetch: fetchImpl,
      onUnauthorized: () => {
        redirected += 1;
      },
    });
    await expect(client.login("admin", "nope")).rejects.toMatchObject({ status: 401, message: "invalid credentials" });
    expect(redirected).toBe(0);
    await expect(client.session()).rejects.toBeInstanceOf(ApiError);
    expect(redirected).toBe(1);
  });
});

describe("config writes", () => {
  it("sends If-Match with the resource version and keeps 422 errors", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const fetchImpl: typeof fetch = async (input, init) => {
      calls.push({ url: String(input), init });
      if (String(input).endsWith("/login")) {
        return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      }
      return json(422, { detail: "invalid resource", errors: ["$.greeting: must be at least 1 characters long"] });
    };
    const client = new ApiClient({ fetch: fetchImpl });
    await client.login("admin", "secret");
    await expect(
      client.putResource("org.oip.hello-world", "gateway", "ModuleConfig", "default", {
        apiVersion: "oip/v1",
        kind: "ModuleConfig",
        metadata: { name: "default", module: "org.oip.hello-world", version: 3 },
        spec: { greeting: "" },
      }),
    ).rejects.toMatchObject({ status: 422, errors: ["$.greeting: must be at least 1 characters long"] });
    const headers = new Headers(calls[1]?.init?.headers);
    expect(headers.get("If-Match")).toBe("3");
    expect(headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(calls[1]?.init?.method).toBe("PUT");
    expect(String(calls[1]?.url)).toBe("/api/v1/config/org.oip.hello-world/gateway/ModuleConfig/default");
    const body = JSON.parse(String(calls[1]?.init?.body)) as { metadata: { version: number }; scope?: string };
    expect(body.metadata.version).toBe(3);
    expect(body.scope).toBeUndefined();
  });
});

describe("security responses", () => {
  it("never returns a password hash, and returns an API key secret only from create or rotate", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const user = {
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
    };
    const key = {
      name: "reader",
      description: "",
      enabled: true,
      keyId: "abcdabcdabcdabcd",
      scopes: ["gateway.read"],
      secureConnectionRequired: true,
      createdAt: "2026-01-01T00:00:00Z",
      version: 4,
      keyHash: "HASHMARKER",
      secret: "listed-secret-must-hide",
    };
    const fetchImpl: typeof fetch = async (input, init) => {
      calls.push({ url: String(input), init });
      const url = String(input);
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (url.endsWith("/security/users")) return json(200, { items: [user] });
      if (url.endsWith("/security/api-keys") && init?.method === "POST") {
        return json(200, { ...key, secret: "once-secret-value" });
      }
      if (url.endsWith("/rotate")) return json(200, { ...key, version: 5, secret: "rotated-secret-value" });
      if (url.endsWith("/security/api-keys")) return json(200, { items: [key] });
      return json(404, { detail: "missing" });
    };
    const client = new ApiClient({ fetch: fetchImpl });
    await client.login("admin", "secret");
    const listedUsers = await client.listUsers();
    expect(JSON.stringify(listedUsers)).not.toContain("HASHMARKER");
    expect(JSON.stringify(listedUsers)).not.toContain("HISTORYMARKER");
    expect(JSON.stringify(listedUsers)).not.toContain("Correct-horse-1");
    expect(listedUsers[0]?.username).toBe("ada");

    const listedKeys = await client.listApiKeys();
    expect(JSON.stringify(listedKeys)).not.toContain("listed-secret-must-hide");
    expect(JSON.stringify(listedKeys)).not.toContain("HASHMARKER");

    const created = await client.createApiKey({
      name: "reader",
      description: "",
      scopes: ["gateway.read"],
      secureConnectionRequired: true,
    });
    expect(created.secret).toBe("once-secret-value");
    expect(JSON.stringify(created.account)).not.toContain("once-secret-value");
    const createHeaders = new Headers(calls.at(-1)?.init?.headers);
    expect(createHeaders.get("X-CSRF-Token")).toBe("csrf-1");

    const rotated = await client.rotateApiKey("reader", 4);
    expect(rotated.secret).toBe("rotated-secret-value");
    expect(rotated.secret).not.toBe(created.secret);
    const rotateHeaders = new Headers(calls.at(-1)?.init?.headers);
    expect(rotateHeaders.get("If-Match")).toBe("4");
  });
});

describe("parseHealth", () => {
  it("keeps status and warning strings", () => {
    expect(parseHealth({ status: "UP", profile: "standard", warnings: ["plain HTTP", 3] })).toEqual({
      status: "UP",
      profile: "standard",
      version: undefined,
      modules: undefined,
      warnings: ["plain HTTP"],
    });
  });

  it("keeps a parseable startedAt and drops anything else", () => {
    expect(parseHealth({ status: "UP", warnings: [], startedAt: "2026-10-28T08:00:00.123456Z" }).startedAt).toBe(
      "2026-10-28T08:00:00.123456Z",
    );
    expect(parseHealth({ status: "UP", warnings: [], startedAt: "yesterday" }).startedAt).toBeUndefined();
    expect(parseHealth({ status: "UP", warnings: [], startedAt: 12 }).startedAt).toBeUndefined();
  });
});

describe("tag configuration export and import (TAG-005)", () => {
  it("exports as text and imports the document as is, with the policy and CSRF token", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const doc = '{"version":1,"provider":"default","udts":[],"tags":[]}';
    const fetchImpl: typeof fetch = async (input, init) => {
      calls.push({ url: String(input), init });
      if (String(input).endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-9" });
      if (String(input).includes("/tags/export")) return new Response(doc, { status: 200 });
      return json(200, { policy: "merge", created: ["tags/gateway/Tag/a"], updated: [], skipped: ["tags/gateway/Tag/b"], failed: [] });
    };
    const client = new ApiClient({ fetch: fetchImpl });
    await client.login("admin", "secret");
    expect(await client.exportTags()).toBe(doc);
    const report = await client.importTags(doc, "merge");
    expect(report).toEqual({ ok: true, created: ["tags/gateway/Tag/a"], updated: [], skipped: ["tags/gateway/Tag/b"], failed: [] });
    const sent = calls[2];
    expect(sent?.url).toContain("/api/v1/tags/import?policy=merge");
    expect(sent?.init?.body).toBe(doc);
    expect(new Headers(sent?.init?.headers).get("X-CSRF-Token")).toBe("csrf-9");
  });

  it("resolves an import with invalid entries as not ok, and throws on a refused one", async () => {
    let status = 422;
    const fetchImpl: typeof fetch = async (input) =>
      String(input).endsWith("/login")
        ? json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-9" })
        : status === 422
        ? json(422, { policy: "overwrite", created: [], updated: [], skipped: [], failed: [{ resource: "tags/gateway/Tag/x", message: "unknown UDT 'T'" }] })
        : json(403, { title: "Forbidden", detail: "gateway.write required" });
    const client = new ApiClient({ fetch: fetchImpl });
    await client.login("admin", "secret");
    const report = await client.importTags("{}", "overwrite");
    expect(report.ok).toBe(false);
    expect(report.failed).toEqual([{ resource: "tags/gateway/Tag/x", message: "unknown UDT 'T'" }]);
    status = 403;
    await expect(client.importTags("{}", "overwrite")).rejects.toMatchObject({ status: 403 });
  });
});
