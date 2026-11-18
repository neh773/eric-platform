import { describe, expect, it } from "vitest";
import { gatewaySocketUrl, openWebSocketTransport } from "./gatewaySocket";
import type { WebSocketLike } from "./tags";

class FakeSocket implements WebSocketLike {
  listeners = new Map<string, Set<(event: { data?: unknown }) => void>>();
  sent: string[] = [];
  closed = false;
  send(data: string) {
    this.sent.push(data);
  }
  addEventListener(type: string, listener: (event: { data?: unknown }) => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(listener);
  }
  removeEventListener(type: string, listener: (event: { data?: unknown }) => void) {
    this.listeners.get(type)?.delete(listener);
  }
  close() {
    this.closed = true;
  }
  emit(type: string, data?: unknown) {
    for (const listener of [...(this.listeners.get(type) ?? [])]) listener({ data });
  }
}

describe("gateway socket", () => {
  it("uses the page's scheme and host", () => {
    expect(gatewaySocketUrl({ protocol: "http:", host: "gw:8088" })).toBe("ws://gw:8088/api/v1/ws");
    expect(gatewaySocketUrl({ protocol: "https:", host: "gw:8043" })).toBe("wss://gw:8043/api/v1/ws");
  });

  it("resolves with a transport once open, and rejects when the socket fails first", async () => {
    const good = new FakeSocket();
    const opening = openWebSocketTransport("ws://x/api/v1/ws", 1_000, () => good);
    good.emit("open");
    const transport = await opening;
    const received: string[] = [];
    transport.onMessage((data) => received.push(data));
    good.emit("message", '{"type":"heartbeat","time":1}');
    transport.send("ping");
    expect(received).toEqual(['{"type":"heartbeat","time":1}']);
    expect(good.sent).toEqual(["ping"]);

    const bad = new FakeSocket();
    const failing = openWebSocketTransport("ws://x/api/v1/ws", 1_000, () => bad);
    bad.emit("close");
    await expect(failing).rejects.toThrow("unavailable");
    expect(bad.closed).toBe(true);

    await expect(openWebSocketTransport("ws://x/api/v1/ws", 10, () => new FakeSocket())).rejects.toThrow("timed out");
  });
});
