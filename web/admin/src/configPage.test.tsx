import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiClient } from "./api";
import { ConfigPage } from "./configPage";
import { ADMIN_SECTIONS } from "./sections";

const section = ADMIN_SECTIONS.find((item) => item.id === "config");
if (!section) throw new Error("config section missing");

const schema = {
  type: "object",
  properties: {
    greeting: { type: "string", minLength: 1, maxLength: 200 },
  },
};

function resource(greeting: string, version: number) {
  return {
    apiVersion: "oip/v1",
    kind: "ModuleConfig",
    metadata: { name: "default", module: "org.oip.hello-world", version },
    spec: { greeting },
  };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function clientFor(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  return new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      if (url.endsWith("/auth/login")) {
        return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      }
      return handler(url, init);
    },
  });
}

async function signedIn(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const client = clientFor(handler);
  await client.login("admin", "secret");
  return client;
}

describe("config editor", () => {
  it("shows schema errors for an empty greeting and does not save", async () => {
    let puts = 0;
    const client = await signedIn(async (url, init) => {
      if (init?.method === "PUT") puts += 1;
      if (url.includes("/schemas/")) return json(200, { module: "org.oip.hello-world", kind: "ModuleConfig", schema });
      return json(200, resource("Hello, world!", 1));
    });
    render(
      <ConfigPage
        section={section}
        client={client}
        search="?module=org.oip.hello-world&scope=gateway&kind=ModuleConfig&name=default"
      />,
    );
    const greeting = await screen.findByRole("textbox", { name: "Greeting" });
    expect(greeting).toHaveValue("Hello, world!");
    fireEvent.change(greeting, { target: { value: "" } });
    expect(screen.getByRole("alert")).toHaveTextContent("At least 1 character");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    fireEvent.submit(greeting.closest("form") as HTMLFormElement);
    expect(puts).toBe(0);
    fireEvent.change(greeting, { target: { value: "x".repeat(201) } });
    expect(screen.getByRole("alert")).toHaveTextContent("At most 200 characters");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("maps a 422 onto the field and shows a reload prompt on 409", async () => {
    let puts = 0;
    const client = await signedIn(async (url, init) => {
      if (url.includes("/schemas/")) return json(200, { module: "org.oip.hello-world", kind: "ModuleConfig", schema });
      if (init?.method === "PUT") {
        puts += 1;
        if (puts === 1) {
          return json(422, { detail: "invalid resource", errors: ["$.greeting: must be at least 1 characters long"] });
        }
        return json(409, { detail: "version conflict: expected 1, stored 2" });
      }
      return json(200, resource("Hello, world!", 1));
    });
    render(
      <ConfigPage
        section={section}
        client={client}
        search="?module=org.oip.hello-world&scope=gateway&kind=ModuleConfig&name=default"
      />,
    );
    const greeting = await screen.findByRole("textbox", { name: "Greeting" });
    fireEvent.change(greeting, { target: { value: "Hello, plant" } });
    fireEvent.submit(greeting.closest("form") as HTMLFormElement);
    expect(await screen.findByRole("alert")).toHaveTextContent("must be at least 1 characters long");
    fireEvent.submit(greeting.closest("form") as HTMLFormElement);
    expect(await screen.findByTestId("version-conflict")).toHaveTextContent("Reload");
    expect(puts).toBe(2);
  });

  it("does not render a password hash when search opens a user", async () => {
    const secret = "argon2id-secret-value";
    const client = await signedIn(async (url) => {
      if (url.includes("/schemas/")) {
        return json(200, {
          module: "org.oip.security",
          kind: "User",
          schema: {
            type: "object",
            properties: {
              enabled: { type: "boolean" },
              passwordHash: { type: "string" },
              passwordHistory: { type: "array", items: { type: "string" } },
            },
          },
        });
      }
      return json(200, {
        apiVersion: "oip/v1",
        kind: "User",
        metadata: { name: "admin", module: "org.oip.security", version: 1 },
        spec: { enabled: true, passwordHash: secret, passwordHistory: [secret] },
      });
    });
    render(
      <ConfigPage
        section={section}
        client={client}
        search="?module=org.oip.security&scope=gateway&kind=User&name=admin"
      />,
    );
    expect(await screen.findByText(/not shown/i)).toBeVisible();
    expect(document.body.textContent).not.toContain("passwordHash");
    expect(document.body.textContent).not.toContain(secret);
  });
});
