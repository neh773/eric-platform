import { vi } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { ADMIN_SECTIONS, type AdminSection } from "./sections";

/** Shared fixtures for the status page tests. Not used by the app bundle. */

export const adminSession: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["gateway.access", "gateway.read", "gateway.write", "audit.read"],
  csrfToken: "csrf-1",
};

/** An operator can read gateway status but not change it. */
export const readerSession: SessionState = {
  username: "olga",
  roles: ["operator"],
  permissions: ["gateway.access", "gateway.read", "tags.read"],
  csrfToken: "csrf-1",
};

/** The default viewer role has neither gateway.read nor gateway.write. */
export const viewerSession: SessionState = {
  username: "pat",
  roles: ["viewer"],
  permissions: ["gateway.access", "tags.read", "audit.read"],
  csrfToken: "csrf-1",
};

export function sectionById(id: string): AdminSection {
  const found = ADMIN_SECTIONS.find((item) => item.id === id);
  if (!found) throw new Error(id);
  return found;
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export interface Call {
  method: string;
  url: string;
  headers: Headers;
  body: BodyInit | null | undefined;
}

export type Route = (call: Call) => Response | Promise<Response> | undefined;

/**
 * A fake gateway. The ApiClient and the status helpers (which use global fetch) both go through it.
 * Call `await kit.signIn()` before a write so the client holds a CSRF token.
 */
export function fakeGateway(route: Route) {
  const calls: Call[] = [];
  const impl = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const call: Call = {
      method: init?.method ?? "GET",
      url: String(input),
      headers: new Headers(init?.headers),
      body: init?.body,
    };
    calls.push(call);
    if (call.url === "/api/v1/auth/session") {
      return json(200, { principal: "admin", roles: [], permissions: [], csrfToken: "csrf-1" });
    }
    const res = await route(call);
    return res ?? json(404, { title: "Not Found", status: 404, detail: `no fake for ${call.method} ${call.url}` });
  };
  vi.stubGlobal("fetch", vi.fn(impl));
  const client = new ApiClient({ fetch: impl });
  return {
    calls,
    client,
    signIn: () => client.session(),
    hits: (prefix: string) => calls.filter((call) => call.url.startsWith(prefix)),
  };
}
