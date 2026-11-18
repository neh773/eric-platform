import { describe, expect, it, vi } from "vitest";
import { MemoryTagServer, createLinkedTransports, type ServerFrame, type StringTransport } from "@oip/hmi-runtime";
import { createTagRuntime, seedStudio } from "./runtime";
import { SOCKET_RETRY_MS } from "./gatewayTags";
import type { TagReading } from "./gatewayClient";

/**
 * C-4 (M4.1): with the gateway session socket available, the Designer's bridge subscribes `[provider]` paths over it
 * instead of polling, sends writes to the gateway, and falls back to polling when the socket goes away. A
 * MemoryTagServer stands in for the gateway: it speaks the same frames as `/api/v1/ws`.
 */
function fakeGateway() {
  const gateway = new MemoryTagServer();
  const sockets: StringTransport[] = [];
  let available = true;
  const connect = vi.fn(async () => {
    if (!available) throw new Error("no socket");
    const [client, server] = createLinkedTransports();
    gateway.connect(server);
    sockets.push(client);
    return client;
  });
  return {
    gateway,
    connect,
    sockets,
    setAvailable(next: boolean) {
      available = next;
    },
  };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

async function until(check: () => boolean, label: string): Promise<void> {
  for (let i = 0; i < 200; i += 1) {
    if (check()) return;
    await flush();
  }
  throw new Error(`timed out waiting for ${label}`);
}

function frames(transport: StringTransport): ServerFrame[] {
  const seen: ServerFrame[] = [];
  transport.onMessage((raw) => seen.push(JSON.parse(raw) as ServerFrame));
  return seen;
}

describe("gateway socket bridge", () => {
  it("subscribes gateway paths over the socket and pushes each change, without polling", async () => {
    const fake = fakeGateway();
    fake.gateway.set("[default]line/level", 41.5);
    const read = vi.fn(async (paths: string[]): Promise<TagReading[]> => paths.map((path) => ({ path, value: 0, quality: "Good", timestamp: "t" })));
    const runtime = createTagRuntime(seedStudio, read, fake.connect);
    runtime.transport.send(JSON.stringify({ type: "hello", nonce: "n" }));
    runtime.transport.send(JSON.stringify({ type: "subscribe", paths: ["[default]line/level", "plant/tank/level"] }));
    await until(() => runtime.bridge!.mode() === "socket", "socket mode");
    await until(() => runtime.server.get("[default]line/level")?.value === 41.5, "first value");
    fake.gateway.set("[default]line/level", 77.25, "bad", "bad_not_connected");
    await until(() => runtime.server.get("[default]line/level")?.value === 77.25, "pushed change");
    expect(runtime.server.get("[default]line/level")).toMatchObject({ quality: "bad", code: "bad_not_connected" });
    // Local sample paths never go to the gateway.
    expect(fake.gateway.get("plant/tank/level")).toBeUndefined();
    const pollsBefore = read.mock.calls.length;
    await runtime.bridge!.poll();
    expect(read.mock.calls.length).toBe(pollsBefore);

    runtime.transport.send(JSON.stringify({ type: "unsubscribe", paths: ["[default]line/level"] }));
    expect(runtime.bridge!.paths()).toEqual([]);
    runtime.bridge!.dispose();
    runtime.server.close();
  });

  it("sends writes to gateway paths through the socket and returns the gateway's answer", async () => {
    const fake = fakeGateway();
    fake.gateway.set("[default]line/setpoint", 1);
    fake.gateway.set("[default]line/locked", 2);
    fake.gateway.blockWrites("[default]line/locked");
    const runtime = createTagRuntime(seedStudio, async () => [], fake.connect);
    const seen = frames(runtime.transport);
    runtime.transport.send(JSON.stringify({ type: "hello", nonce: "n" }));
    runtime.transport.send(JSON.stringify({ type: "subscribe", paths: ["[default]line/setpoint", "[default]line/locked"] }));
    await until(() => runtime.bridge!.mode() === "socket", "socket mode");

    runtime.transport.send(JSON.stringify({ type: "write", requestId: "w1", path: "[default]line/setpoint", value: 55 }));
    runtime.transport.send(JSON.stringify({ type: "write", requestId: "w2", path: "[default]line/locked", value: 9 }));
    runtime.transport.send(JSON.stringify({ type: "write", requestId: "w3", path: "plant/tank/level", value: 3 }));
    await until(() => seen.filter((f) => f.type === "writeResult").length === 3, "write results");
    const results = Object.fromEntries(seen.flatMap((f) => (f.type === "writeResult" ? [[f.requestId, f]] : [])));
    expect(results.w1).toMatchObject({ ok: true });
    expect(results.w2).toMatchObject({ ok: false, error: "rejected" });
    expect(results.w3).toMatchObject({ ok: true });
    expect(fake.gateway.get("[default]line/setpoint")?.value).toBe(55);
    expect(fake.gateway.get("[default]line/locked")?.value).toBe(2);
    // The local write stayed local.
    expect(fake.gateway.get("plant/tank/level")).toBeUndefined();
    expect(runtime.server.get("plant/tank/level")?.value).toBe(3);
    await until(() => runtime.server.get("[default]line/setpoint")?.value === 55, "written value pushed back");
    runtime.bridge!.dispose();
  });

  it("polls when there is no socket, and moves to the socket when it comes back", async () => {
    vi.useFakeTimers();
    try {
      const fake = fakeGateway();
      fake.setAvailable(false);
      fake.gateway.set("[default]a", 8);
      const read = vi.fn(async (paths: string[]): Promise<TagReading[]> => paths.map((path) => ({ path, value: 3, quality: "Good", timestamp: "t" })));
      const runtime = createTagRuntime(seedStudio, read, fake.connect);
      runtime.transport.send(JSON.stringify({ type: "subscribe", paths: ["[default]a"] }));
      await vi.advanceTimersByTimeAsync(600);
      expect(runtime.bridge!.mode()).toBe("poll");
      expect(read).toHaveBeenCalled();
      expect(runtime.server.get("[default]a")).toMatchObject({ value: 3, code: "good" });

      fake.setAvailable(true);
      await vi.advanceTimersByTimeAsync(SOCKET_RETRY_MS * 4);
      expect(runtime.bridge!.mode()).toBe("socket");
      expect(runtime.server.get("[default]a")?.value).toBe(8);

      // The socket drops: reads take over again until it is back.
      read.mockClear();
      fake.setAvailable(false);
      fake.sockets.at(-1)!.close();
      await vi.advanceTimersByTimeAsync(600);
      expect(runtime.bridge!.mode()).toBe("poll");
      expect(read).toHaveBeenCalled();
      runtime.bridge!.dispose();
    } finally {
      vi.useRealTimers();
    }
  });
  it("carries script frames on the same session socket as the tags, and shares its reconnect delay (D-5a)", async () => {
    const fake = fakeGateway();
    fake.gateway.set("[default]line/level", 3);
    const runtime = createTagRuntime(seedStudio, async () => [], fake.connect);
    runtime.transport.send(JSON.stringify({ type: "hello", nonce: "n" }));
    runtime.transport.send(JSON.stringify({ type: "subscribe", paths: ["[default]line/level"] }));
    await until(() => runtime.bridge!.mode() === "socket", "socket mode");
    const [first, second] = await Promise.all([runtime.bridge!.scriptTransport(), runtime.bridge!.scriptTransport()]);
    expect(fake.connect).toHaveBeenCalledTimes(1);
    const answers: string[] = [];
    first.onMessage((raw) => answers.push((JSON.parse(raw) as { type: string }).type));
    // The stand-in gateway does not run scripts; its answer shows the frame went out on the shared socket.
    first.send(JSON.stringify({ type: "script", requestId: "r1" }));
    await until(() => answers.includes("error"), "answer on the shared socket");
    // Closing a script channel leaves the socket to the tags.
    first.close();
    second.close();
    expect(runtime.bridge!.mode()).toBe("socket");
    runtime.bridge!.dispose();
    runtime.server.close();
  });

  it("refuses a script channel while waiting out the reconnect delay, without opening another socket", async () => {
    const fake = fakeGateway();
    fake.setAvailable(false);
    const runtime = createTagRuntime(seedStudio, async () => [], fake.connect);
    await expect(runtime.bridge!.scriptTransport()).rejects.toThrow();
    expect(fake.connect).toHaveBeenCalledTimes(1);
    await expect(runtime.bridge!.scriptTransport()).rejects.toThrow("trying again shortly");
    expect(fake.connect).toHaveBeenCalledTimes(1);
    fake.setAvailable(true);
    // The bridge's own retry reopens the socket for scripts even with no gateway paths watched.
    await new Promise((resolve) => setTimeout(resolve, SOCKET_RETRY_MS + 100));
    expect(fake.connect).toHaveBeenCalledTimes(2);
    await until(() => runtime.bridge!.mode() === "socket", "socket back");
    await expect(runtime.bridge!.scriptTransport()).resolves.toBeDefined();
    runtime.bridge!.dispose();
    runtime.server.close();
  }, 10_000);
});
