import { useEffect, useState } from "react";
import { GatewayError } from "./gatewayClient";

/** The three gateway calls the Designer session needs; `GatewayClient` implements them. */
export interface DesignerSessionClient {
  openDesignerSession(project: string | null): Promise<{ id: string; heartbeatSeconds: number }>;
  heartbeatDesignerSession(id: string): Promise<void>;
  closeDesignerSession(id: string): Promise<void>;
  /** Optional: send the session id with every save (SEC-020). */
  setDesignerSession?(id: string | null): void;
}

/**
 * Keeps this Designer in the gateway's list of active Designer sessions (GAT-028) while a gateway project is open:
 * register on open, heartbeat on the period the gateway gives, register again if the gateway forgot the session, and
 * end it on close. A user without designer.login, or a gateway that is down, simply stays unlisted; the Designer
 * works the same.
 *
 * Returns the live session's id (null while there is none): resource locks (SEC-020) belong to it, and
 * [DesignerSessionClient.setDesignerSession] sends it with every save so a lock this Designer holds is its own.
 * Doc source: FEATURES GAT-028.
 * Doc source: FEATURES SEC-020.
 */
export function useDesignerSession(client: DesignerSessionClient | null, project: string | null): string | null {
  const [current, setCurrent] = useState<string | null>(null);
  useEffect(() => {
    if (!client) return undefined;
    let stopped = false;
    let id: string | null = null;
    let timer: ReturnType<typeof setInterval> | undefined;

    const open = async () => {
      try {
        const opened = await client.openDesignerSession(project);
        if (stopped) {
          await client.closeDesignerSession(opened.id).catch(() => undefined);
          return;
        }
        id = opened.id;
        setId(opened.id);
        if (!timer) timer = setInterval(() => void beat(), Math.max(1, opened.heartbeatSeconds) * 1000);
      } catch {
        // Not permitted or unreachable: stay unlisted and try again at the next beat.
        id = null;
        setId(null);
      }
    };
    const beat = async () => {
      if (!id) return open();
      try {
        await client.heartbeatDesignerSession(id);
      } catch (error) {
        if (error instanceof GatewayError && error.status === 404) {
          id = null;
          setId(null);
          await open();
        }
      }
    };
    const setId = (next: string | null) => {
      client.setDesignerSession?.(next);
      if (!stopped) setCurrent(next);
    };
    const end = () => {
      if (id) void client.closeDesignerSession(id).catch(() => undefined);
      id = null;
      client.setDesignerSession?.(null);
    };

    void open();
    window.addEventListener("pagehide", end);
    return () => {
      stopped = true;
      if (timer) clearInterval(timer);
      window.removeEventListener("pagehide", end);
      end();
      setCurrent(null);
    };
  }, [client, project]);
  return current;
}
