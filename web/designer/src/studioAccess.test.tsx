import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GatewayClient, type FetchLike } from "./gatewayClient";
import { ProjectGate } from "./ProjectGate";

/**
 * Review-4 F4 (SEC-020): the Studio needs `designer.login`, which the gateway's designer roles grant. A user without
 * it is told so at sign-in and the project is not opened; the gateway refuses that user's design writes as well.
 */

function gatewayWith(permissions: string[]) {
  const urls: string[] = [];
  const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
  const fetcher: FetchLike = async (url) => {
    urls.push(url);
    if (url === "/api/v1/auth/session") return json(200, { kind: "user", principal: "cfg", csrfToken: "c", permissions });
    if (url.startsWith("/api/v1/config/")) return json(200, { items: [] });
    return json(404, { title: "Not Found" });
  };
  return { urls, client: new GatewayClient(fetcher) };
}

describe("Studio access", () => {
  it("refuses a signed-in user without designer.login and opens nothing", async () => {
    const { client, urls } = gatewayWith(["gateway.write", "gateway.read", "gateway.access", "tags.read"]);
    render(
      <ProjectGate client={client} project="plant" starter={[]}>
        {() => <p>opened</p>}
      </ProjectGate>,
    );
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("not allowed to use Control Canvas"));
    expect(screen.getByRole("alert").textContent).toContain("cfg");
    expect(screen.queryByText("opened")).toBeNull();
    expect(urls.some((url) => url.startsWith("/api/v1/config/"))).toBe(false);
  });

  it("opens the project for a user with designer.login", async () => {
    const { client } = gatewayWith(["gateway.write", "designer.login"]);
    render(
      <ProjectGate client={client} project="plant" starter={[]}>
        {() => <p>opened</p>}
      </ProjectGate>,
    );
    await waitFor(() => expect(screen.getByText("opened")).toBeTruthy());
  });
});
