import { expect, test } from "@playwright/test";
import { createHash, generateKeyPairSync, randomBytes, sign, type KeyObject } from "node:crypto";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { csrf, login, openSection, PASSWORD, stamp } from "./gateway";

/**
 * P1-32 / P1-33 / P1-34 in the browser: an administrator adds an OIDC provider on the Identity Providers page (filled
 * from its discovery document), a user signs in with the provider's button on the sign-in page and lands signed in
 * with the mapped roles, logout ends the session at the gateway and at the provider, and the Users page checks a user.
 *
 * The provider is a small test server in this file (node crypto only): discovery, JWK set, an authorization endpoint
 * that signs "alice" in at once, a token endpoint that checks PKCE and the client secret, and an end-session endpoint.
 */

const b64url = (buf: Buffer) => buf.toString("base64url");

interface Provider {
  issuer: string;
  close: () => Promise<void>;
  tokenCalls: () => number;
}

async function startProvider(clientId: string, clientSecret: string): Promise<Provider> {
  const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const jwk = (publicKey as KeyObject).export({ format: "jwk" });
  const codes = new Map<string, URLSearchParams>();
  let tokenCalls = 0;
  let issuer = "";
  const server: Server = createServer((req, res) => {
    const url = new URL(req.url ?? "/", issuer);
    const send = (status: number, body: unknown) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    if (url.pathname === "/.well-known/openid-configuration") {
      send(200, {
        issuer,
        authorization_endpoint: `${issuer}/authorize`,
        token_endpoint: `${issuer}/token`,
        jwks_uri: `${issuer}/jwks`,
        end_session_endpoint: `${issuer}/logout`,
        response_types_supported: ["code"],
        code_challenge_methods_supported: ["S256"],
        id_token_signing_alg_values_supported: ["RS256"],
      });
    } else if (url.pathname === "/jwks") {
      send(200, { keys: [{ ...jwk, kid: "e2e", use: "sig", alg: "RS256" }] });
    } else if (url.pathname === "/authorize") {
      const code = b64url(randomBytes(16));
      codes.set(code, url.searchParams);
      const back = new URL(url.searchParams.get("redirect_uri") ?? "");
      back.searchParams.set("code", code);
      back.searchParams.set("state", url.searchParams.get("state") ?? "");
      res.writeHead(302, { Location: back.toString() });
      res.end();
    } else if (url.pathname === "/logout") {
      res.writeHead(302, { Location: url.searchParams.get("post_logout_redirect_uri") ?? "/" });
      res.end();
    } else if (url.pathname === "/token" && req.method === "POST") {
      tokenCalls += 1;
      let raw = "";
      req.on("data", (chunk: Buffer) => (raw += chunk.toString()));
      req.on("end", () => {
        const form = new URLSearchParams(raw);
        const request = codes.get(form.get("code") ?? "");
        codes.delete(form.get("code") ?? "");
        const basic = `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString("base64")}`;
        const challenge = b64url(createHash("sha256").update(form.get("code_verifier") ?? "").digest());
        if (!request || req.headers.authorization !== basic || challenge !== request.get("code_challenge")) {
          send(400, { error: "invalid_grant" });
          return;
        }
        const now = Math.floor(Date.now() / 1000);
        const header = b64url(Buffer.from(JSON.stringify({ alg: "RS256", kid: "e2e", typ: "JWT" })));
        const claims = b64url(
          Buffer.from(
            JSON.stringify({
              iss: issuer,
              aud: clientId,
              sub: "u-alice",
              preferred_username: "alice",
              groups: ["plant-ops"],
              nonce: request.get("nonce"),
              iat: now,
              exp: now + 300,
            }),
          ),
        );
        const signature = b64url(sign("sha256", Buffer.from(`${header}.${claims}`), privateKey));
        send(200, { token_type: "Bearer", id_token: `${header}.${claims}.${signature}`, access_token: "opaque" });
      });
    } else {
      send(404, { error: "not_found" });
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  issuer = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return {
    issuer,
    tokenCalls: () => tokenCalls,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

test("an administrator adds an OIDC provider and a user signs in with it", async ({ page, browser }) => {
  const name = stamp("e2e-idp");
  const secret = `secret-${stamp("s")}`;
  const provider = await startProvider("oip-e2e", secret);
  try {
    await login(page);
    await openSection(page, "Identity Providers");
    await page.getByRole("button", { name: "Add identity provider" }).click();
    const form = page.getByRole("form", { name: "New identity provider" });
    await form.getByLabel("Provider name").fill(name);
    await form.getByLabel("Display name").fill("E2E Provider");
    await form.getByLabel("Issuer").fill(provider.issuer);
    await form.getByRole("button", { name: "Import discovery document" }).click();
    await expect(form.getByLabel("Token endpoint")).toHaveValue(`${provider.issuer}/token`);
    await form.getByLabel("Client ID").fill("oip-e2e");
    await form.getByLabel("Client secret").fill(secret);
    await form.getByLabel("Groups claim").fill("groups");
    await form.getByLabel(/Role mappings/).fill("plant-ops = operator");
    await form.getByRole("button", { name: "Create identity provider" }).click();
    const row = page.getByRole("table", { name: "Identity providers" }).getByRole("row", { name: new RegExp(name) });
    await expect(row).toBeVisible();
    await expect(row.getByRole("cell", { name: "Set", exact: true })).toBeVisible();
    // The page never receives the secret.
    expect(await page.content()).not.toContain(secret);

    const context = await browser.newContext();
    const user = await context.newPage();
    try {
      await user.goto("/admin/login");
      await user.getByRole("link", { name: "Sign in with E2E Provider" }).click();
      await expect(user.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
      await expect(user.getByText(`Signed in as ${name}:alice`)).toBeVisible();
      const session = await user.request.get("/api/v1/auth/session");
      const body = (await session.json()) as { roles: string[]; provider: string };
      expect(body.roles).toEqual(["operator"]);
      expect(body.provider).toBe(name);
      expect(provider.tokenCalls()).toBe(1);
      // Logout ends the gateway session, visits the provider's end-session endpoint, and comes back to sign-in.
      await user.getByRole("button", { name: "Log out" }).click();
      await expect(user.getByRole("heading", { name: "Sign in" })).toBeVisible();
      expect((await user.request.get("/api/v1/auth/session")).status()).toBe(401);
    } finally {
      await context.close();
    }

    const audit = await page.request.get(`/api/v1/audit?action=login&actor=${encodeURIComponent(`${name}:alice`)}`);
    const rows = (await audit.json()) as { items: { detail: string }[] };
    expect(rows.items.some((item) => item.detail.includes(`provider=${name}`))).toBeTruthy();
  } finally {
    const token = await csrf(page).catch(() => "");
    await page.request.delete(`/api/v1/security/identity-providers/${name}`, { headers: { "X-CSRF-Token": token } });
    await provider.close();
  }
});

test("a refused provider login returns to the sign-in page with a message", async ({ page }) => {
  await page.goto("/admin/login?error=idp");
  await expect(page.getByRole("alert")).toContainText("identity provider was refused");
});

test("the Users page checks a user on the user source", async ({ page }) => {
  await login(page);
  await openSection(page, "Users & Roles");
  const form = page.getByRole("form", { name: "Check user" });
  await form.getByLabel("Username to check").fill("admin");
  await form.getByLabel("Password to test (optional)").fill(PASSWORD);
  await form.getByRole("button", { name: "Check user" }).click();
  const result = form.getByLabel("Check result");
  await expect(result).toContainText("admin exists");
  await expect(result).toContainText("administrator");
  await expect(result).toContainText("Correct");
  await expect(form.getByLabel("Password to test (optional)")).toHaveValue("");

  await form.getByLabel("Username to check").fill(stamp("nobody"));
  await form.getByLabel("Password to test (optional)").fill("Not-the-password-1");
  await form.getByRole("button", { name: "Check user" }).click();
  await expect(result).toContainText("was not found");
  await expect(result).toContainText("Incorrect");
});
