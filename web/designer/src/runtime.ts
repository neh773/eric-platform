import { useRef } from "react";
import { MemoryTagServer, createLinkedTransports, gatewaySocketUrl, openWebSocketTransport, type StringTransport } from "@oip/hmi-runtime";
import { gateway } from "./gatewayClient";
import { GATEWAY_POLL_MS, GatewayTagBridge, type ConnectSocket, type ReadTags } from "./gatewayTags";

export interface TagRuntime {
  server: MemoryTagServer;
  transport: StringTransport;
  /** Present when `[provider]` tag paths are read from the gateway. */
  bridge?: GatewayTagBridge;
  /**
   * Another client transport to the same tag server, seen by the gateway bridge like the canvas's own, so
   * `[provider]` paths subscribed on it come over the gateway session socket (the tag browser's live values).
   */
  openTransport: () => StringTransport;
}

export function createTagRuntime(
  seed: (server: MemoryTagServer) => void,
  readGatewayTags?: ReadTags,
  connectSocket?: ConnectSocket,
): TagRuntime {
  const server = new MemoryTagServer();
  seed(server);
  const linked = (): StringTransport => {
    const [clientSide, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    return clientSide;
  };
  if (!readGatewayTags) return { server, transport: linked(), openTransport: linked };
  const bridge = new GatewayTagBridge(server, readGatewayTags, GATEWAY_POLL_MS, connectSocket);
  return { server, transport: bridge.wrap(linked()), bridge, openTransport: () => bridge.wrap(linked()) };
}

export function seedStudio(server: MemoryTagServer): void {
  server.set("plant/tank/level", 62.4);
  server.set("plant/pump/running", true);
  server.set("plant/pump/command", true);
  server.set("plant/tank/note", "North tank");
  server.set("sim/level", 0);
  for (let index = 0; index < 500; index += 1) server.set(`bench/${index}`, 0);
}

export function useTagRuntime(): TagRuntime {
  const runtime = useRef<TagRuntime | null>(null);
  if (runtime.current === null) runtime.current = createTagRuntime(seedStudio, (paths) => gateway.readTags(paths), browserSocket());
  return runtime.current;
}

/** The gateway session socket at this page's origin (served by the gateway, or proxied to it in dev). */
function browserSocket(): ConnectSocket | undefined {
  if (typeof window === "undefined" || typeof WebSocket === "undefined") return undefined;
  return () => openWebSocketTransport(gatewaySocketUrl(window.location));
}
