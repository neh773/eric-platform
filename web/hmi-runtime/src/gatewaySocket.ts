import { webSocketTransport, type StringTransport, type WebSocketLike } from "./tags";

/**
 * The gateway's HMI session socket (M4.1). It speaks the same frames as {@link MemoryTagServer}, served from the
 * gateway tag engine as the signed-in user (the session cookie goes with the upgrade).
 */
export const GATEWAY_SOCKET_PATH = "/api/v1/ws";

/** `ws(s)://<host>/api/v1/ws` for a page served from (or proxied to) the gateway. */
export function gatewaySocketUrl(location: { protocol: string; host: string }): string {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${location.host}${GATEWAY_SOCKET_PATH}`;
}

type SocketFactory = (url: string) => WebSocketLike;

/**
 * Open a WebSocket and resolve with its transport once it is open. Rejects when the socket closes or fails first
 * (no gateway socket at this origin, not signed in, or no `tags.read`), or after [timeoutMs].
 */
export function openWebSocketTransport(
  url: string,
  timeoutMs = 5_000,
  create: SocketFactory = (target) => new WebSocket(target) as unknown as WebSocketLike,
): Promise<StringTransport> {
  return new Promise((resolve, reject) => {
    let settled = false;
    let socket: WebSocketLike;
    try {
      socket = create(url);
    } catch (error) {
      reject(error instanceof Error ? error : new Error(String(error)));
      return;
    }
    const finish = (error: Error | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.removeEventListener("open", onOpen);
      socket.removeEventListener("close", onFail);
      socket.removeEventListener("error", onFail);
      if (error) {
        try {
          socket.close();
        } catch {
          // already closed
        }
        reject(error);
      } else {
        resolve(webSocketTransport(socket));
      }
    };
    const onOpen = () => finish(null);
    const onFail = () => finish(new Error("gateway socket unavailable"));
    const timer = setTimeout(() => finish(new Error("gateway socket timed out")), timeoutMs);
    socket.addEventListener("open", onOpen);
    socket.addEventListener("close", onFail);
    socket.addEventListener("error", onFail);
  });
}
