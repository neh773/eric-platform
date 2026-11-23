import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "./api";
import { FirstRun } from "./App";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("first run", () => {
  it("creates the administrator and signs in", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      if (url === "/api/v1/setup/admin") return jsonResponse(201, { username: "admin", roles: ["administrator"], csrfToken: "t" });
      if (url === "/api/v1/auth/session") return jsonResponse(200, { principal: "admin", roles: ["administrator"], permissions: ["*"], csrfToken: "t" });
      return jsonResponse(404, {});
    });
    const client = new ApiClient({ fetch });
    const signedIn = vi.fn();
    render(<FirstRun client={client} fromThisMachine onSignedIn={signedIn} />);
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "First-run-Pass-42" } });
    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "First-run-Pass-42" } });
    fireEvent.click(screen.getByRole("button", { name: "Create administrator" }));
    await waitFor(() => expect(signedIn).toHaveBeenCalled());
    expect(calls.find((c) => c.url === "/api/v1/setup/admin")?.body).toEqual({ username: "admin", password: "First-run-Pass-42" });
  });

  it("refuses mismatched passwords and a remote browser", () => {
    const client = new ApiClient({ fetch: vi.fn() });
    const { unmount } = render(<FirstRun client={client} fromThisMachine onSignedIn={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "a-Long-pass-1" } });
    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "different" } });
    fireEvent.click(screen.getByRole("button", { name: "Create administrator" }));
    expect(screen.getByRole("alert").textContent).toContain("do not match");
    unmount();
    render(<FirstRun client={client} fromThisMachine={false} onSignedIn={vi.fn()} />);
    expect((screen.getByRole("button", { name: "Create administrator" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
