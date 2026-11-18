import type { Quality, QualityCode, TagValue } from "./types";

/** JSON tag-subscription frames.
 * SPEC §6: subscribe / unsubscribe / update batches, heartbeat, resume token.
 * A direct tag binding subscribes to a path and pushes each change into the bound property:
 */

export interface TagUpdate {
  path: string;
  value: TagValue;
  quality: Quality;
  code: QualityCode;
  timestamp: number;
  /** The gateway's own quality name (`Bad_AccessDenied`, ...), when the update came from a gateway. */
  label?: string;
}

export type ClientFrame =
  | { type: "hello"; nonce: string; sessionId?: string; resumeToken?: string }
  | { type: "subscribe"; paths: string[] }
  | { type: "unsubscribe"; paths: string[] }
  | { type: "write"; requestId: string; path: string; value: TagValue }
  | { type: "heartbeat"; time: number }
  | { type: "presence"; project: string; page: string; views: string[] };

export type ServerFrame =
  | { type: "welcome"; nonce: string; sessionId: string; resumeToken: string; serverTime: number }
  | { type: "update"; updates: TagUpdate[]; sent?: number }
  | { type: "writeResult"; requestId: string; ok: boolean; error?: string }
  | { type: "heartbeat"; time: number }
  | { type: "error"; message: string };

export interface StringTransport {
  send(data: string): void;
  onMessage(handler: (data: string) => void): () => void;
  onClose(handler: () => void): () => void;
  close(): void;
}

export interface WebSocketLike {
  send(data: string): void;
  addEventListener(type: string, listener: (event: { data?: unknown }) => void): void;
  removeEventListener(type: string, listener: (event: { data?: unknown }) => void): void;
  close(): void;
}

interface Side {
  inbox: Set<(data: string) => void>;
  closers: Set<() => void>;
  closed: boolean;
}

/** In-process pair. Frames are copied strings, delivered on a microtask, like a socket. */
export function createLinkedTransports(): [StringTransport, StringTransport] {
  const sides: [Side, Side] = [
    { inbox: new Set(), closers: new Set(), closed: false },
    { inbox: new Set(), closers: new Set(), closed: false },
  ];
  const make = (index: 0 | 1): StringTransport => {
    const self = sides[index];
    const peer = sides[index === 0 ? 1 : 0];
    return {
      send(data: string) {
        if (self.closed) throw new Error("transport closed");
        const frame = String(data);
        queueMicrotask(() => {
          if (self.closed || peer.closed) return;
          for (const listener of [...peer.inbox]) listener(frame);
        });
      },
      onMessage(handler) {
        self.inbox.add(handler);
        return () => {
          self.inbox.delete(handler);
        };
      },
      onClose(handler) {
        self.closers.add(handler);
        return () => {
          self.closers.delete(handler);
        };
      },
      close() {
        if (self.closed) return;
        self.closed = true;
        peer.closed = true;
        for (const handler of [...self.closers, ...peer.closers]) handler();
      },
    };
  };
  return [make(0), make(1)];
}

export function webSocketTransport(socket: WebSocketLike): StringTransport {
  return {
    send(data: string) {
      socket.send(data);
    },
    onMessage(handler) {
      const wrapped = (event: { data?: unknown }) => handler(String(event.data ?? ""));
      socket.addEventListener("message", wrapped);
      return () => socket.removeEventListener("message", wrapped);
    },
    onClose(handler) {
      const wrapped = () => handler();
      socket.addEventListener("close", wrapped);
      return () => socket.removeEventListener("close", wrapped);
    },
    close() {
      socket.close();
    },
  };
}

export interface TagClientOptions {
  sessionId?: string;
  resumeToken?: string;
}

interface SessionRecord {
  id: string;
  token: string;
  paths: Set<string>;
}

interface Connection {
  transport: StringTransport;
  session: SessionRecord | null;
}

function makeUpdate(path: string, value: TagValue, quality: Quality = "good", code?: QualityCode, label?: string): TagUpdate {
  const resolved: QualityCode = code ?? (quality === "good" ? "good" : quality === "bad" ? "bad" : "uncertain");
  const coarse: Quality = resolved === "good" ? "good" : resolved.startsWith("bad") ? "bad" : "uncertain";
  const update: TagUpdate = { path, value, quality: coarse, code: resolved, timestamp: Date.now() };
  if (label) update.label = label;
  return update;
}

function unknownUpdate(path: string): TagUpdate {
  return { path, value: null, quality: "uncertain", code: "uncertain_initial", timestamp: Date.now() };
}

/** In-memory tag server that speaks the same JSON frames as a gateway socket. */
export class MemoryTagServer {
  private values = new Map<string, TagUpdate>();
  private sessions = new Map<string, SessionRecord>();
  private connections = new Set<Connection>();
  private blocked = new Set<string>();
  private dirty = new Set<string>();
  private flushScheduled = false;
  private detachers: Array<() => void> = [];

  /** `label` stands in for the gateway's quality name (`Error_ExpressionEval`). */
  set(path: string, value: TagValue, quality: Quality = "good", code?: QualityCode, label?: string): void {
    this.values.set(path, makeUpdate(path, value, quality, code, label));
    this.dirty.add(path);
    this.scheduleFlush();
  }

  setMany(entries: Array<{ path: string; value: TagValue }>): void {
    for (const entry of entries) this.set(entry.path, entry.value);
  }

  get(path: string): TagUpdate | undefined {
    return this.values.get(path);
  }

  /** Every path the server holds a value for, in the order they were first set. */
  paths(): string[] {
    return [...this.values.keys()];
  }

  blockWrites(path: string): void {
    this.blocked.add(path);
  }

  connect(transport: StringTransport): () => void {
    const connection: Connection = { transport, session: null };
    const offMessage = transport.onMessage((raw) => this.onMessage(connection, raw));
    const offClose = transport.onClose(() => {
      this.connections.delete(connection);
    });
    this.connections.add(connection);
    const detach = () => {
      offMessage();
      offClose();
      this.connections.delete(connection);
    };
    this.detachers.push(detach);
    return detach;
  }

  close(): void {
    for (const detach of this.detachers) detach();
    this.detachers = [];
    this.connections.clear();
  }

  private onMessage(connection: Connection, raw: string): void {
    let frame: ClientFrame;
    try {
      frame = JSON.parse(raw) as ClientFrame;
    } catch {
      this.send(connection, { type: "error", message: "malformed frame" });
      return;
    }
    if (!frame || typeof frame !== "object" || !("type" in frame)) {
      this.send(connection, { type: "error", message: "malformed frame" });
      return;
    }
    switch (frame.type) {
      case "hello": {
        const existing = frame.resumeToken ? this.sessions.get(frame.resumeToken) : undefined;
        const session: SessionRecord = existing ?? {
          id: crypto.randomUUID(),
          token: crypto.randomUUID(),
          paths: new Set<string>(),
        };
        this.sessions.set(session.token, session);
        connection.session = session;
        this.send(connection, {
          type: "welcome",
          nonce: frame.nonce,
          sessionId: session.id,
          resumeToken: session.token,
          serverTime: Date.now(),
        });
        if (session.paths.size > 0) this.sendSnapshot(connection, [...session.paths]);
        break;
      }
      case "subscribe": {
        if (!connection.session) {
          this.send(connection, { type: "error", message: "hello required" });
          return;
        }
        for (const path of frame.paths) connection.session.paths.add(path);
        this.sendSnapshot(connection, frame.paths);
        break;
      }
      case "unsubscribe": {
        if (!connection.session) return;
        for (const path of frame.paths) connection.session.paths.delete(path);
        break;
      }
      case "write": {
        if (this.blocked.has(frame.path)) {
          this.send(connection, { type: "writeResult", requestId: frame.requestId, ok: false, error: "rejected" });
          return;
        }
        this.set(frame.path, frame.value);
        this.send(connection, { type: "writeResult", requestId: frame.requestId, ok: true });
        break;
      }
      case "heartbeat":
        this.send(connection, { type: "heartbeat", time: Date.now() });
        break;
      default:
        this.send(connection, { type: "error", message: "unknown frame" });
    }
  }

  private sendSnapshot(connection: Connection, paths: string[]): void {
    const updates = paths.map((path) => this.values.get(path) ?? unknownUpdate(path));
    this.send(connection, { type: "update", updates });
  }

  private scheduleFlush(): void {
    if (this.flushScheduled) return;
    this.flushScheduled = true;
    queueMicrotask(() => {
      this.flushScheduled = false;
      const paths = [...this.dirty];
      this.dirty.clear();
      if (paths.length === 0) return;
      for (const connection of this.connections) {
        const session = connection.session;
        if (!session) continue;
        const updates = paths.filter((path) => session.paths.has(path)).map((path) => this.values.get(path)!);
        if (updates.length > 0) this.send(connection, { type: "update", updates });
      }
    });
  }

  private send(connection: Connection, frame: ServerFrame): void {
    connection.transport.send(JSON.stringify(frame));
  }
}

export class TagClient {
  private listeners = new Set<(updates: TagUpdate[]) => void>();
  private frameListeners = new Set<(frame: ServerFrame) => void>();
  private pathRefs = new Map<string, number>();
  private welcome: { sessionId: string; resumeToken: string } | null = null;
  private welcomeWaiters: Array<(value: { sessionId: string; resumeToken: string }) => void> = [];
  private pendingWrites = new Map<string, { resolve: () => void; reject: (error: Error) => void }>();
  private pendingUpdates = new Map<string, TagUpdate>();
  private queue: ClientFrame[] = [];
  private detachments: Array<() => void> = [];
  private flushScheduled = false;
  private ready = false;
  private closed = false;
  readonly nonce = crypto.randomUUID();

  constructor(
    private readonly transport: StringTransport,
    options: TagClientOptions = {},
  ) {
    this.detachments.push(transport.onMessage((raw) => this.onRaw(raw)));
    this.detachments.push(transport.onClose(() => this.failClosed()));
    this.dispatch({ type: "hello", nonce: this.nonce, sessionId: options.sessionId, resumeToken: options.resumeToken });
  }

  whenWelcome(): Promise<{ sessionId: string; resumeToken: string }> {
    if (this.welcome) return Promise.resolve(this.welcome);
    return new Promise((resolve) => this.welcomeWaiters.push(resolve));
  }

  /** Tells the gateway which project, page, and views this session is showing. */
  announce(project: string, page: string, views: string[]): void {
    this.enqueue({ type: "presence", project, page, views: views.slice(0, 32) });
  }

  subscribe(paths: string[], listener: (updates: TagUpdate[]) => void): () => void {
    this.listeners.add(listener);
    const fresh: string[] = [];
    for (const path of paths) {
      const count = this.pathRefs.get(path) ?? 0;
      this.pathRefs.set(path, count + 1);
      if (count === 0) fresh.push(path);
    }
    if (fresh.length > 0) this.enqueue({ type: "subscribe", paths: fresh });
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      this.listeners.delete(listener);
      const drop: string[] = [];
      for (const path of paths) {
        const count = (this.pathRefs.get(path) ?? 1) - 1;
        if (count <= 0) {
          this.pathRefs.delete(path);
          drop.push(path);
        } else {
          this.pathRefs.set(path, count);
        }
      }
      if (drop.length > 0 && !this.closed) this.enqueue({ type: "unsubscribe", paths: drop });
    };
  }

  write(path: string, value: TagValue): Promise<void> {
    if (this.closed) return Promise.reject(new Error("closed"));
    const requestId = crypto.randomUUID();
    const promise = new Promise<void>((resolve, reject) => {
      this.pendingWrites.set(requestId, { resolve, reject });
    });
    this.enqueue({ type: "write", requestId, path, value });
    return promise;
  }

  ping(): Promise<number> {
    const time = Date.now();
    this.enqueue({ type: "heartbeat", time });
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        off();
        reject(new Error("heartbeat timeout"));
      }, 2000);
      const off = this.onFrame((frame) => {
        if (frame.type === "heartbeat") {
          clearTimeout(timeout);
          off();
          resolve(frame.time);
        }
      });
    });
  }

  onFrame(listener: (frame: ServerFrame) => void): () => void {
    this.frameListeners.add(listener);
    return () => this.frameListeners.delete(listener);
  }

  /** Detach without closing the shared transport. */
  release(): void {
    if (this.closed) return;
    if (this.ready && this.pathRefs.size > 0) {
      this.dispatch({ type: "unsubscribe", paths: [...this.pathRefs.keys()] });
    }
    this.failClosed();
  }

  close(): void {
    this.release();
    this.transport.close();
  }

  private enqueue(frame: ClientFrame): void {
    if (this.closed) return;
    if (this.ready || frame.type === "hello") this.dispatch(frame);
    else this.queue.push(frame);
  }

  private dispatch(frame: ClientFrame): void {
    this.transport.send(JSON.stringify(frame));
  }

  private onRaw(raw: string): void {
    if (this.closed) return;
    let frame: ServerFrame;
    try {
      frame = JSON.parse(raw) as ServerFrame;
    } catch {
      return;
    }
    if (frame.type === "welcome") {
      if (frame.nonce !== this.nonce) return;
      this.welcome = { sessionId: frame.sessionId, resumeToken: frame.resumeToken };
      this.ready = true;
      const waiters = this.welcomeWaiters;
      this.welcomeWaiters = [];
      for (const waiter of waiters) waiter(this.welcome);
      const queued = this.queue;
      this.queue = [];
      for (const item of queued) this.dispatch(item);
    }
    if (frame.type === "update") this.pushUpdates(frame.updates);
    if (frame.type === "writeResult") {
      const pending = this.pendingWrites.get(frame.requestId);
      if (pending) {
        this.pendingWrites.delete(frame.requestId);
        if (frame.ok) pending.resolve();
        else pending.reject(new Error(frame.error ?? "write rejected"));
      }
    }
    for (const listener of [...this.frameListeners]) listener(frame);
  }

  private pushUpdates(updates: TagUpdate[]): void {
    for (const update of updates) this.pendingUpdates.set(update.path, update);
    if (this.flushScheduled) return;
    this.flushScheduled = true;
    queueMicrotask(() => {
      this.flushScheduled = false;
      if (this.closed) return;
      const batch = [...this.pendingUpdates.values()];
      this.pendingUpdates.clear();
      if (batch.length === 0) return;
      for (const listener of [...this.listeners]) listener(batch);
    });
  }

  private failClosed(): void {
    if (this.closed) return;
    this.closed = true;
    for (const detach of this.detachments) detach();
    this.detachments = [];
    for (const pending of this.pendingWrites.values()) pending.reject(new Error("closed"));
    this.pendingWrites.clear();
    this.queue = [];
  }
}

export async function connectPair(server: MemoryTagServer, options?: TagClientOptions): Promise<{ client: TagClient; release: () => void }> {
  const [clientTransport, serverTransport] = createLinkedTransports();
  const detach = server.connect(serverTransport);
  const client = new TagClient(clientTransport, options);
  await client.whenWelcome();
  return {
    client,
    release() {
      client.release();
      detach();
      clientTransport.close();
    },
  };
}
