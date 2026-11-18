import { TagClient, type MemoryTagServer, type Quality, type QualityCode, type ServerFrame, type StringTransport, type TagValue } from "@oip/hmi-runtime";
import type { TagReading } from "./gatewayClient";

/**
 * Feeds gateway tag values into the Designer's preview and session tag server.
 *
 * The preview speaks the hmi-runtime frame protocol to an in-process [MemoryTagServer]. A binding
 * whose path names a provider (`[default]folder/tag`, the gateway's path form, SPEC §5) is a gateway
 * tag: this bridge watches the subscribe/unsubscribe frames the preview sends and sets the gateway's values on
 * the server, which pushes them to the bound components. Paths without a provider stay local sample tags.
 *
 * Where the gateway's session socket (`/api/v1/ws`, M4.1) is reachable, gateway paths are subscribed over it: the
 * gateway pushes each change as the signed-in user (per-tag read permissions apply), and writes to gateway paths
 * go to the gateway as that user and answer with the gateway's result. Without the socket (not signed in, an older
 * gateway, a dev server without the WebSocket proxy) the bridge reads those paths through `POST /api/v1/tags/read`
 * on an interval, as before, and tries the socket again with a growing delay.
 */

/** Opens the gateway session socket; rejects when there is none. */
export type ConnectSocket = () => Promise<StringTransport>;

/** First and longest wait before trying the gateway socket again. */
export const SOCKET_RETRY_MS = 1_000;
export const SOCKET_RETRY_MAX_MS = 30_000;
/** How long an open socket may take to answer `hello`. */
export const SOCKET_WELCOME_MS = 5_000;

export type ReadTags = (paths: string[]) => Promise<TagReading[]>;

export const GATEWAY_POLL_MS = 250;

export function isGatewayPath(path: string): boolean {
  return /^\[[^\]]+\]/.test(path);
}

export function qualityFromGateway(label: string): { quality: Quality; code: QualityCode } {
  const lower = label.toLowerCase();
  if (lower.startsWith("good")) return { quality: "good", code: "good" };
  if (lower === "bad_notconnected") return { quality: "bad", code: "bad_not_connected" };
  if (lower.startsWith("bad") || lower.startsWith("error")) return { quality: "bad", code: "bad" };
  if (lower === "uncertain_initialvalue") return { quality: "uncertain", code: "uncertain_initial" };
  return { quality: "uncertain", code: "uncertain" };
}

/** Gateway values may be any JSON; the preview tag model carries scalars only. */
export function toTagValue(value: unknown): TagValue {
  if (value === null || value === undefined) return null;
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return value;
  return JSON.stringify(value);
}

interface LiveSocket {
  client: TagClient;
  /** The socket itself, for script frames (D-5a) that share it. */
  transport: StringTransport;
  /** Unsubscribe handle per gateway path held on the socket. */
  subs: Map<string, () => void>;
}

export class GatewayTagBridge {
  private refs = new Map<string, number>();
  private last = new Map<string, string>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight = false;
  private disposed = false;
  private online = true;
  private socket: LiveSocket | null = null;
  private connecting: Promise<boolean> | null = null;
  /** A script host has asked for the socket, so it is reopened after a drop even with no gateway paths watched. */
  private wantScripts = false;
  private presence: { project: string; page: string; views: string[] } | null = null;
  private retryMs = SOCKET_RETRY_MS;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  /** Frames injected toward the preview (gateway write results). */
  private injected = new Set<(data: string) => void>();

  constructor(
    private readonly server: MemoryTagServer,
    private readonly read: ReadTags,
    private readonly periodMs = GATEWAY_POLL_MS,
    private readonly connect?: ConnectSocket,
  ) {}

  /**
   * Wrap the preview's client transport so its subscriptions are seen by the bridge. While the socket is live a
   * write to a gateway path goes to the gateway, and its result comes back on this transport.
   */
  wrap(transport: StringTransport): StringTransport {
    return {
      send: (data: string) => {
        if (this.interceptWrite(data)) return;
        this.observe(data);
        transport.send(data);
      },
      onMessage: (handler) => {
        const off = transport.onMessage(handler);
        this.injected.add(handler);
        return () => {
          off();
          this.injected.delete(handler);
        };
      },
      onClose: (handler) => transport.onClose(handler),
      close: () => transport.close(),
    };
  }

  paths(): string[] {
    return [...this.refs.keys()];
  }

  /** The page this session is showing, recorded on the gateway session list. */
  announce(project: string, page: string, views: string[]): void {
    this.presence = { project, page, views };
    this.socket?.client.announce(project, page, views);
    if (!this.socket && this.online && !this.disposed) void this.connectSocket();
  }

  /** `socket` while gateway paths come over the session socket, else `poll`. */
  mode(): "socket" | "poll" {
    return this.socket ? "socket" : "poll";
  }

  /**
   * Whether a gateway is there to read from. Offline (no gateway answered), the bridge stops polling, drops the
   * socket, and marks watched paths not connected; going online again resumes.
   */
  setOnline(online: boolean): void {
    if (this.online === online) return;
    this.online = online;
    if (!online) {
      this.stop();
      this.dropSocket();
      this.markNotConnected(this.paths());
    } else if (this.refs.size > 0) {
      this.retryMs = SOCKET_RETRY_MS;
      this.activate();
    }
  }

  dispose(): void {
    this.disposed = true;
    this.stop();
    this.dropSocket();
    if (this.retryTimer !== null) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  /** One read of every watched path. Exposed for tests; the timer calls it. Does nothing while the socket is live. */
  async poll(): Promise<void> {
    const paths = this.paths();
    if (paths.length === 0 || this.inFlight || this.disposed || !this.online || this.socket) return;
    this.inFlight = true;
    try {
      const rows = await this.read(paths);
      if (this.socket) return;
      for (const row of rows) {
        if (!this.refs.has(row.path)) continue;
        const { quality, code } = qualityFromGateway(row.quality);
        const value = toTagValue(row.value);
        const key = JSON.stringify([value, row.quality, row.timestamp]);
        if (this.last.get(row.path) === key) continue;
        this.last.set(row.path, key);
        // The gateway's quality name goes with the value, so the quality overlay can name the subcode (TAG-002).
        this.server.set(row.path, value, quality, code, row.quality);
      }
    } catch {
      if (!this.socket) this.markNotConnected(paths);
    } finally {
      this.inFlight = false;
    }
  }

  /**
   * A channel for script frames (D-5a) on the session socket the tag values use, so a tab holds one gateway socket
   * and one reconnect delay. Resolves once that socket has answered hello. Rejects while there is no socket: offline,
   * not signed in, or waiting out the reconnect delay (it doubles from 1 s up to 30 s after each failed try).
   * Closing the channel only detaches its listeners; the socket stays open for the tags.
   */
  async scriptTransport(): Promise<StringTransport> {
    this.wantScripts = true;
    if (!this.connect || this.disposed || !this.online) throw new Error("no gateway socket");
    if (!this.socket) {
      if (this.retryTimer !== null && !this.connecting) throw new Error("gateway socket unavailable; trying again shortly");
      await this.connectSocket();
    }
    const live = this.socket;
    if (!live) throw new Error("gateway socket unavailable");
    const detach: Array<() => void> = [];
    return {
      send: (data: string) => live.transport.send(data),
      onMessage: (handler) => {
        const off = live.transport.onMessage(handler);
        detach.push(off);
        return off;
      },
      onClose: (handler) => {
        const off = live.transport.onClose(handler);
        detach.push(off);
        return off;
      },
      close: () => {
        for (const off of detach.splice(0)) off();
      },
    };
  }

  /** Try the socket now (tests; the bridge also does this when gateway paths are first watched). Concurrent calls share one try. */
  connectSocket(): Promise<boolean> {
    if (this.connecting) return this.connecting;
    if (!this.connect || this.socket || this.disposed || !this.online) return Promise.resolve(this.socket !== null);
    const attempt = this.openSocket().finally(() => {
      if (this.connecting === attempt) this.connecting = null;
    });
    this.connecting = attempt;
    return attempt;
  }

  private async openSocket(): Promise<boolean> {
    const connect = this.connect!;
    try {
      const transport = await connect();
      if (this.disposed || !this.online) {
        transport.close();
        return false;
      }
      const client = new TagClient(transport);
      const live: LiveSocket = { client, transport, subs: new Map() };
      transport.onClose(() => this.socketClosed(live));
      let timer: ReturnType<typeof setTimeout> | undefined;
      const welcomed = await Promise.race([
        client.whenWelcome().then(() => true),
        new Promise<boolean>((resolve) => {
          timer = setTimeout(() => resolve(false), SOCKET_WELCOME_MS);
        }),
      ]);
      clearTimeout(timer);
      if (!welcomed) {
        client.close();
        throw new Error("gateway socket did not answer hello");
      }
      if (this.disposed || !this.online) {
        client.close();
        return false;
      }
      this.socket = live;
      this.retryMs = SOCKET_RETRY_MS;
      if (this.presence) live.client.announce(this.presence.project, this.presence.page, this.presence.views);
      this.stop();
      client.onFrame((frame) => this.onSocketFrame(frame));
      for (const path of this.refs.keys()) live.subs.set(path, client.subscribe([path], () => {}));
      return true;
    } catch {
      this.scheduleRetry();
      return false;
    }
  }

  private onSocketFrame(frame: ServerFrame): void {
    if (frame.type !== "update") return;
    for (const update of frame.updates) {
      if (!this.refs.has(update.path)) continue;
      this.last.delete(update.path);
      this.server.set(update.path, update.value, update.quality, update.code, update.label);
    }
  }

  private socketClosed(live: LiveSocket): void {
    if (this.socket !== live) return;
    this.socket = null;
    if (this.disposed || !this.online) return;
    // Fall back to reads until the socket is back.
    if (this.refs.size > 0) {
      this.start();
      void this.poll();
    }
    this.scheduleRetry();
  }

  private scheduleRetry(): void {
    if (!this.connect || this.disposed || !this.online || this.retryTimer !== null) return;
    const wait = this.retryMs;
    this.retryMs = Math.min(this.retryMs * 2, SOCKET_RETRY_MAX_MS);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (this.refs.size > 0 || this.wantScripts) void this.connectSocket();
    }, wait);
  }

  private dropSocket(): void {
    const live = this.socket;
    this.socket = null;
    live?.client.close();
  }

  /** Watching gateway paths: use the socket when it is (or may become) live, polling meanwhile. */
  private activate(): void {
    if (!this.online || this.disposed) return;
    if (!this.socket) {
      this.start();
      void this.poll();
      if (this.retryTimer === null) void this.connectSocket();
    }
  }

  /** Keep the last value and mark it not connected, as a device tag does on comms loss (SPEC §5). */
  private markNotConnected(paths: string[]): void {
    for (const path of paths) {
      const current = this.server.get(path);
      const key = "not-connected";
      if (this.last.get(path) === key) continue;
      this.last.set(path, key);
      this.server.set(path, current?.value ?? null, "bad", "bad_not_connected");
    }
  }

  private interceptWrite(data: string): boolean {
    const live = this.socket;
    if (!live) return false;
    let frame: { type?: string; requestId?: unknown; path?: unknown; value?: unknown };
    try {
      frame = JSON.parse(data) as typeof frame;
    } catch {
      return false;
    }
    if (frame.type !== "write" || typeof frame.path !== "string" || !isGatewayPath(frame.path)) return false;
    const requestId = String(frame.requestId ?? "");
    live.client.write(frame.path, toTagValue(frame.value)).then(
      () => this.inject({ type: "writeResult", requestId, ok: true }),
      (error: unknown) =>
        this.inject({ type: "writeResult", requestId, ok: false, error: error instanceof Error ? error.message : String(error) }),
    );
    return true;
  }

  private inject(frame: ServerFrame): void {
    const data = JSON.stringify(frame);
    queueMicrotask(() => {
      for (const handler of [...this.injected]) handler(data);
    });
  }

  private observe(data: string): void {
    let frame: { type?: string; paths?: unknown };
    try {
      frame = JSON.parse(data) as { type?: string; paths?: unknown };
    } catch {
      return;
    }
    if ((frame.type !== "subscribe" && frame.type !== "unsubscribe") || !Array.isArray(frame.paths)) return;
    const gatewayPaths = frame.paths.filter((path): path is string => typeof path === "string" && isGatewayPath(path));
    if (gatewayPaths.length === 0) return;
    if (frame.type === "subscribe") {
      const fresh: string[] = [];
      for (const path of gatewayPaths) {
        const count = this.refs.get(path) ?? 0;
        this.refs.set(path, count + 1);
        if (count === 0) fresh.push(path);
      }
      if (!this.online) {
        this.markNotConnected(gatewayPaths);
        return;
      }
      const live = this.socket;
      if (live) {
        for (const path of fresh) if (!live.subs.has(path)) live.subs.set(path, live.client.subscribe([path], () => {}));
      } else {
        this.activate();
      }
    } else {
      for (const path of gatewayPaths) {
        const count = (this.refs.get(path) ?? 1) - 1;
        if (count <= 0) {
          this.refs.delete(path);
          this.last.delete(path);
          const off = this.socket?.subs.get(path);
          if (off) {
            off();
            this.socket?.subs.delete(path);
          }
        } else {
          this.refs.set(path, count);
        }
      }
      if (this.refs.size === 0) this.stop();
    }
  }

  private start(): void {
    if (this.timer !== null || this.disposed || !this.online || this.socket) return;
    this.timer = setInterval(() => void this.poll(), this.periodMs);
  }

  private stop(): void {
    if (this.timer === null) return;
    clearInterval(this.timer);
    this.timer = null;
  }
}
