import { createContext, useContext, type ReactNode } from "react";

/**
 * Browser client for live components (alarm status table, power chart).
 *
 * Same pattern as the admin client (web/admin/src/api.ts): the HttpOnly session cookie goes with
 * every request, and the CSRF token from GET /api/v1/auth/session is kept only in memory and sent
 * as X-CSRF-Token on POST. A 403 whose problem type is `csrf` refreshes the token once and retries.
 * See docs/ops/http-auth.md.
 */

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export class GatewayError extends Error {
  readonly status: number;
  readonly problemType: string;

  constructor(message: string, status: number, problemType = "") {
    super(message);
    this.name = "GatewayError";
    this.status = status;
    this.problemType = problemType;
  }
}

export interface GatewayClientOptions {
  /** Prefix for API paths. Empty means same origin. */
  baseUrl?: string;
  fetch?: FetchLike;
}

export class GatewayClient {
  private csrf: string | null = null;
  private csrfPending: Promise<string> | null = null;
  private readonly baseUrl: string;
  private readonly fetchImpl: FetchLike;

  constructor(opts: GatewayClientOptions = {}) {
    this.baseUrl = (opts.baseUrl ?? "").replace(/\/$/, "");
    this.fetchImpl = opts.fetch ?? ((input, init) => fetch(input, init));
  }

  async get(path: string): Promise<unknown> {
    const res = await this.fetchImpl(this.baseUrl + path, {
      method: "GET",
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) throw await problemError(res);
    return res.json() as Promise<unknown>;
  }

  async post(path: string, body: unknown): Promise<unknown> {
    let res = await this.sendPost(path, body, await this.token());
    if (res.status === 403) {
      const error = await problemError(res);
      if (error.problemType !== "csrf") throw error;
      this.csrf = null;
      res = await this.sendPost(path, body, await this.token());
    }
    if (!res.ok) throw await problemError(res);
    return res.json() as Promise<unknown>;
  }

  /** Sends one file as the raw body of a POST, with its media type and name; answers the parsed JSON reply. Retries once on a stale CSRF token. */
  async postFile(path: string, file: Blob, name: string): Promise<unknown> {
    const send = (token: string) =>
      this.fetchImpl(this.baseUrl + path, {
        method: "POST",
        credentials: "same-origin",
        headers: {
          Accept: "application/json",
          "Content-Type": file.type || "application/octet-stream",
          "X-File-Name": encodeURIComponent(name),
          "X-CSRF-Token": token,
        },
        body: file,
      });
    let res = await send(await this.token());
    if (res.status === 403) {
      const error = await problemError(res);
      if (error.problemType !== "csrf") throw error;
      this.csrf = null;
      res = await send(await this.token());
    }
    if (!res.ok) throw await problemError(res);
    return res.json() as Promise<unknown>;
  }

  private sendPost(path: string, body: unknown, token: string): Promise<Response> {
    return this.fetchImpl(this.baseUrl + path, {
      method: "POST",
      credentials: "same-origin",
      headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-Token": token },
      body: JSON.stringify(body),
    });
  }

  private token(): Promise<string> {
    if (this.csrf) return Promise.resolve(this.csrf);
    if (!this.csrfPending) {
      this.csrfPending = (async () => {
        try {
          const session = (await this.get("/api/v1/auth/session")) as { csrfToken?: unknown };
          const token = typeof session.csrfToken === "string" ? session.csrfToken : "";
          this.csrf = token;
          return token;
        } finally {
          this.csrfPending = null;
        }
      })();
    }
    return this.csrfPending;
  }
}

async function problemError(res: Response): Promise<GatewayError> {
  let detail = "";
  let type = "";
  try {
    const body = (await res.json()) as { detail?: unknown; title?: unknown; type?: unknown };
    if (typeof body.detail === "string") detail = body.detail;
    else if (typeof body.title === "string") detail = body.title;
    if (typeof body.type === "string") type = body.type.replace(/^urn:oip:problem:/, "");
  } catch {
    detail = "";
  }
  const reason = detail || res.statusText || "request failed";
  return new GatewayError(`${res.status} ${reason}`, res.status, type);
}

const GatewayContext = createContext<GatewayClient | null>(null);
let sharedClient: GatewayClient | null = null;

/** Supplies the client that live components use. Without a provider they share one same-origin client. */
export function GatewayProvider({ client, children }: { client: GatewayClient; children: ReactNode }) {
  return <GatewayContext.Provider value={client}>{children}</GatewayContext.Provider>;
}

export function useGatewayClient(): GatewayClient {
  const provided = useContext(GatewayContext);
  if (provided) return provided;
  if (!sharedClient) sharedClient = new GatewayClient();
  return sharedClient;
}
