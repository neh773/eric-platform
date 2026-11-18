import { useEffect, useState } from "react";
import { GatewayScriptHost, type ScriptHost, type StringTransport } from "@oip/hmi-runtime";

/** Where script frames go: a channel on the tab's gateway session socket (the tag bridge's), already past hello. */
export interface ScriptSocketSource {
  scriptTransport(): Promise<StringTransport>;
}

/**
 * The script host for a gateway project (D-5a): script transforms and event scripts run on the gateway as the
 * signed-in user. Script frames share the session socket the tag values use ([socket], the tag bridge), so a tab
 * holds one gateway socket. Undefined without a project or a socket source; script transforms then show a
 * not-connected quality.
 */
export function useGatewayScriptHost(project: string | null | undefined, socket: ScriptSocketSource | undefined): ScriptHost | undefined {
  const [host, setHost] = useState<GatewayScriptHost | undefined>(undefined);
  useEffect(() => {
    if (!project || !socket) {
      setHost(undefined);
      return;
    }
    const created = new GatewayScriptHost({ connect: () => socket.scriptTransport(), welcomed: true, project });
    setHost(created);
    return () => created.close();
  }, [project, socket]);
  return host;
}
