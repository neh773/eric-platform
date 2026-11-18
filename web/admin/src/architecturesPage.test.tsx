import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { ArchitecturesPage } from "./architecturesPage";
import { ADMIN_SECTIONS } from "./sections";

afterEach(() => cleanup());

const session: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["gateway.read", "gateway.write"],
  csrfToken: "csrf-1",
};

const section = ADMIN_SECTIONS.find((item) => item.id === "architectures")!;

describe("architectures page", () => {
  it("shows a live match and the redundancy storage table", async () => {
    const client = new ApiClient({
      fetch: async () =>
        new Response(
          JSON.stringify({
            node: { profile: "standard", bindHost: "127.0.0.1", redundancyMode: "independent", historyMode: "partial" },
            architectures: [
              {
                id: "single-server",
                feature: "GAN-006",
                title: "Single server",
                summary: "One gateway.",
                fits: true,
                checks: [{ id: "independent", label: "Redundancy is independent", met: true, detail: "Mode is independent." }],
              },
              {
                id: "enterprise",
                feature: "GAN-007",
                title: "Enterprise",
                summary: "More than one gateway.",
                fits: false,
                checks: [{ id: "peer", label: "At least one gateway peer", met: false, detail: "0 outgoing, 0 incoming." }],
              },
            ],
            subsystems: [
              {
                id: "alarm-journal",
                title: "Alarm journal",
                redundancy: "active-node",
                database: "local-file",
                detail: "The journal appends only on the active node.",
              },
            ],
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
    });
    render(<ArchitecturesPage section={section} client={client} session={session} />);
    expect(await screen.findByTestId("architecture-single-server")).toHaveTextContent("Matches this gateway");
    expect(screen.getByTestId("architecture-enterprise")).toHaveTextContent("Does not match this gateway");
    expect(screen.getByTestId("redundancy-subsystems")).toHaveTextContent("Alarm journal");
    expect(screen.getByTestId("redundancy-subsystems")).toHaveTextContent("active-node");
  });
});
