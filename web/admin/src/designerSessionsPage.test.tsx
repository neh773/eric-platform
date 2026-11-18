import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DesignerSessionsPage } from "./designerSessionsPage";
import { ADMIN_SECTIONS } from "./sections";
import { adminSession, fakeGateway, json, readerSession, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const document = {
  sessions: [{ id: "s-1", user: "dana", project: "plant", address: "10.0.0.9", openedAt: "2026-11-09T10:00:00Z", lastSeen: "2026-11-09T10:05:00Z" }],
  ttlSeconds: 45,
  launch: { label: "Launch Control Canvas", href: "/studio" },
  download: { label: "Download Control Canvas shortcut", href: "/api/v1/designer/launcher", filename: "control-canvas.url" },
};

describe("Designer sessions page", () => {
  it("is a platform section", () => {
    expect(ADMIN_SECTIONS.find((item) => item.id === "designer-sessions")?.group).toBe("platform");
  });

  it("lists connected Designers with the launch link and the shortcut download", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/designer/sessions" ? json(200, document) : undefined));
    render(<DesignerSessionsPage section={sectionById("designer-sessions")} client={kit.client} session={adminSession} />);
    const row = await screen.findByTestId("designer-session-s-1");
    expect(row).toHaveTextContent("dana");
    expect(row).toHaveTextContent("plant");
    expect(row).toHaveTextContent("10.0.0.9");
    expect(screen.getByTestId("designer-launch")).toHaveAttribute("href", "/studio");
    expect(screen.getByTestId("designer-download")).toHaveAttribute("href", "/api/v1/designer/launcher");
  });

  it("says when no Designer is connected", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/designer/sessions" ? json(200, { ...document, sessions: [] }) : undefined));
    render(<DesignerSessionsPage section={sectionById("designer-sessions")} client={kit.client} session={adminSession} />);
    expect(await screen.findByTestId("designer-sessions-empty")).toHaveTextContent("No Control Canvas session is connected");
  });

  it("shows the list but no launch links to a reader without gateway.write", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/designer/sessions" ? json(200, document) : undefined));
    render(<DesignerSessionsPage section={sectionById("designer-sessions")} client={kit.client} session={readerSession} />);
    expect(await screen.findByTestId("designer-session-s-1")).toBeVisible();
    expect(screen.queryByTestId("designer-launch")).toBeNull();
  });

  it("hides everything without gateway.read", async () => {
    const kit = fakeGateway(() => json(403, { title: "Forbidden", status: 403, detail: "gateway.read denied" }));
    render(<DesignerSessionsPage section={sectionById("designer-sessions")} client={kit.client} session={viewerSession} />);
    expect(await screen.findByTestId("designer-sessions-no-read")).toBeVisible();
    expect(kit.hits("/api/v1/designer/sessions")).toHaveLength(0);
  });
});
