import { describe, expect, it } from "vitest";
import { MemoryTagServer, TagClient, connectPair, createLinkedTransports, webSocketTransport } from "./tags";
import type { WebSocketLike } from "./tags";

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe("tag subscription protocol", () => {
  it("delivers a subscribed tag change and stops after unsubscribe", async () => {
    const server = new MemoryTagServer();
    server.set("plant/tank/level", 1);
    const { client, release } = await connectPair(server);
    const seen: unknown[] = [];
    const off = client.subscribe(["plant/tank/level"], (updates) => {
      for (const update of updates) seen.push(update.value);
    });
    await settle();
    expect(seen).toEqual([1]);
    server.set("plant/tank/level", 8);
    await settle();
    expect(seen).toEqual([1, 8]);
    off();
    server.set("plant/tank/level", 9);
    await settle();
    expect(seen).toEqual([1, 8]);
    release();
    server.close();
  });

  it("batches many writes into one update", async () => {
    const server = new MemoryTagServer();
    const { client, release } = await connectPair(server);
    let batches = 0;
    let count = 0;
    client.subscribe(
      Array.from({ length: 20 }, (_, index) => `bench/${index}`),
      (updates) => {
        batches += 1;
        count += updates.length;
      },
    );
    await settle();
    batches = 0;
    count = 0;
    for (let index = 0; index < 20; index += 1) server.set(`bench/${index}`, index + 3);
    await settle();
    expect(batches).toBe(1);
    expect(count).toBe(20);
    release();
    server.close();
  });

  it("writes a value and rejects a blocked write", async () => {
    const server = new MemoryTagServer();
    server.blockWrites("plant/locked");
    const { client, release } = await connectPair(server);
    await client.write("plant/tank/note", "hold");
    await settle();
    expect(server.get("plant/tank/note")?.value).toBe("hold");
    await expect(client.write("plant/locked", 1)).rejects.toThrow(/rejected/);
    expect(server.get("plant/locked")).toBeUndefined();
    release();
    server.close();
  });

  it("restores subscriptions when a client resumes with its token", async () => {
    const server = new MemoryTagServer();
    server.set("plant/pump/running", false);
    const { client, release } = await connectPair(server);
    const welcome = await client.whenWelcome();
    client.subscribe(["plant/pump/running"], () => {});
    await settle();
    release();
    server.set("plant/pump/running", true);
    const [clientTransport, serverTransport] = createLinkedTransports();
    server.connect(serverTransport);
    const resumed = new TagClient(clientTransport, { resumeToken: welcome.resumeToken });
    const again = await resumed.whenWelcome();
    expect(again.sessionId).toBe(welcome.sessionId);
    const values: unknown[] = [];
    resumed.subscribe(["plant/pump/running"], (updates) => {
      values.push(updates[0]?.value);
    });
    await settle();
    expect(values).toContain(true);
    resumed.close();
    server.close();
  });

  it("answers a heartbeat and ignores a malformed frame", async () => {
    const server = new MemoryTagServer();
    const [clientTransport, serverTransport] = createLinkedTransports();
    server.connect(serverTransport);
    const client = new TagClient(clientTransport);
    await client.whenWelcome();
    const replied = await client.ping();
    expect(replied).toBeGreaterThan(0);
    const errors: string[] = [];
    client.onFrame((frame) => {
      if (frame.type === "error") errors.push(frame.message);
    });
    clientTransport.send("{");
    await settle();
    expect(errors).toContain("malformed frame");
    client.close();
    server.close();
  });

  it("speaks the same frames through the WebSocket transport adapter", async () => {
    const [clientSocket, serverSocket] = socketPair();
    const server = new MemoryTagServer();
    server.set("sim/level", 4);
    server.connect(webSocketTransport(serverSocket));
    const client = new TagClient(webSocketTransport(clientSocket));
    await client.whenWelcome();
    const seen: unknown[] = [];
    client.subscribe(["sim/level"], (updates) => seen.push(updates[0]?.value));
    await settle();
    server.set("sim/level", 5);
    await settle();
    expect(seen).toEqual([4, 5]);
    client.close();
    server.close();
  });
});

class FakeSocket implements WebSocketLike {
  peer: FakeSocket | null = null;
  private listeners = new Map<string, Set<(event: { data?: unknown }) => void>>();

  send(data: string): void {
    queueMicrotask(() => this.peer?.emit("message", { data }));
  }

  addEventListener(type: string, listener: (event: { data?: unknown }) => void): void {
    const set = this.listeners.get(type) ?? new Set();
    set.add(listener);
    this.listeners.set(type, set);
  }

  removeEventListener(type: string, listener: (event: { data?: unknown }) => void): void {
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.emit("close", {});
    this.peer?.emit("close", {});
  }

  emit(type: string, event: { data?: unknown }): void {
    for (const listener of this.listeners.get(type) ?? []) listener(event);
  }
}

function socketPair(): [FakeSocket, FakeSocket] {
  const left = new FakeSocket();
  const right = new FakeSocket();
  left.peer = right;
  right.peer = left;
  return [left, right];
}
