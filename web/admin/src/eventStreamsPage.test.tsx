import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { EventStreamsPage } from "./eventStreamsPage";
import type { AdminSection } from "./sections";

const section: AdminSection = { id: "event-streams", label: "Event Streams", group: "services", keywords: [] };
const session: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["gateway.read", "gateway.write"],
  csrfToken: "csrf-1",
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("event streams page", () => {
  it("creates a stream from the form, keeps the JSON view, and sends a test event", async () => {
    const calls: { method: string; url: string; body?: unknown }[] = [];
    let stored = false;
    const client = new ApiClient({
      fetch: async (input, init) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        const body = typeof init?.body === "string" ? JSON.parse(init.body) as unknown : undefined;
        calls.push({ method, url, body });
        if (url === "/api/v1/auth/session") {
          return json({ principal: "admin", roles: ["administrator"], permissions: session.permissions, csrfToken: "csrf-1" });
        }
        if (url === "/api/v1/event-streams" && method === "GET") {
          return json({
            items: stored
              ? [{ name: "tank-level", enabled: true, source: "listener", valid: true, eventsReceived: 0, handlerErrors: 0 }]
              : [],
          });
        }
        if (url.endsWith("/EventStream") && method === "GET") return json({ items: stored ? [resource()] : [] });
        if (url.endsWith("/KafkaConnection") && method === "GET") return json({ items: [] });
        if (url.endsWith("/EventStream") && method === "POST") {
          stored = true;
          const sent = body as { spec: Record<string, unknown> };
          return json({ ...resource(), spec: sent.spec });
        }
        if (url.endsWith("/test") && method === "POST") return json({ accepted: true });
        return json({});
      },
    });
    await client.session();
    render(<EventStreamsPage section={section} client={client} session={session} />);
    expect(await screen.findByRole("heading", { level: 1, name: "Event Streams" })).toBeVisible();
    expect(screen.getByText("No event streams yet.")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "New event stream" }));
    fireEvent.change(screen.getByLabelText("Stream name"), { target: { value: "tank-level" } });
    fireEvent.change(screen.getByLabelText("Handler message"), { target: { value: "level {event.data}" } });
    fireEvent.click(screen.getByRole("button", { name: "Edit as JSON" }));
    expect((screen.getByLabelText("Stream JSON") as HTMLTextAreaElement).value).toContain("listener");
    expect((screen.getByLabelText("Stream JSON") as HTMLTextAreaElement).value).toContain("level {event.data}");
    fireEvent.click(screen.getByRole("button", { name: "Edit as form" }));
    fireEvent.click(screen.getByRole("button", { name: "Save stream" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Saved tank-level.");
    const created = calls.find((call) => call.method === "POST" && call.url.endsWith("/EventStream"));
    const spec = (created?.body as { spec: { source: { type: string }; handlers: { message: string }[] } }).spec;
    expect(spec.source.type).toBe("listener");
    expect(spec.handlers[0].message).toBe("level {event.data}");
    fireEvent.click(await screen.findByRole("button", { name: "Send test event" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Sent a test event to tank-level.");
    expect(calls.some((call) => call.method === "POST" && call.url.endsWith("/tank-level/test"))).toBe(true);
  });

  it("hides writing controls without gateway.write", async () => {
    const client = new ApiClient({
      fetch: async (input) => {
        const url = String(input);
        if (url === "/api/v1/auth/session") return json({ principal: "view", roles: ["viewer"], permissions: ["gateway.read"], csrfToken: "csrf-1" });
        if (url.includes("KafkaConnection") || url.includes("EventStream")) return json({ items: [] });
        return json({ items: [] });
      },
    });
    await client.session();
    render(
      <EventStreamsPage
        section={section}
        client={client}
        session={{ ...session, permissions: ["gateway.read"] }}
      />,
    );
    expect(await screen.findByText("No event streams yet.")).toBeVisible();
    expect(screen.queryByRole("button", { name: "New event stream" })).toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
}

function resource() {
  return {
    apiVersion: "oip/v1",
    kind: "EventStream",
    metadata: { name: "tank-level", module: "events", version: 1 },
    spec: {
      enabled: true,
      encoder: "json",
      source: { type: "listener" },
      handlers: [{ type: "logger", enabled: true, level: "info", message: "level {event.data}" }],
    },
  };
}
