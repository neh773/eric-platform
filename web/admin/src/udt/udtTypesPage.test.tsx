import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiClient } from "../api";
import { UdtEditor } from "./udtTypesPage";
import { UDT_NO_MEMBERS_MESSAGE } from "./udtModel";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("UDT definition editor", () => {
  it("warns when saving with no members is allowed", async () => {
    const client = new ApiClient({
      fetch: async (input) => {
        const url = String(input);
        if (url.includes("/tags/devices")) return json(200, { devices: [] });
        if (url.includes("/resources")) return json(200, { items: [] });
        return json(404, {});
      },
    });
    render(<UdtEditor client={client} existing={null} others={[]} onSaved={() => undefined} onClose={() => undefined} />);
    expect(await screen.findByTestId("udt-no-members-warning")).toHaveTextContent(UDT_NO_MEMBERS_MESSAGE);
    expect(screen.getByRole("button", { name: "Create definition" })).toBeEnabled();
  });
});
