import { useEffect, useState } from "react";
import type { StyleClassSet } from "@oip/hmi-runtime";
import { GatewayError, GatewayUnreachable, type GatewayClient } from "./gatewayClient";
import { loadStyleClasses, validProjectName } from "./persistence";

/**
 * Running /hmi sessions take their style classes from the gateway (M4.2 with M4.4 persistence).
 *
 * The Designer saves a project's style classes as the config resource
 * `designer/project/<project>/StyleClasses/style-classes`. A session reads that resource when it opens and
 * then polls it, so a save from any Designer, in any browser, restyles every running session of the
 * project. Unsaved Designer edits stay in the Designer. Reading goes through the config API, so it needs
 * the same permission as reading the project's views (gateway.read).
 *
 * The gateway has no config change feed yet, so this polls, like the Designer's tag bridge does for tag
 * values. A poll only changes the session when the stored version changes.
 */

/** How often a session checks the gateway for saved style classes. */
export const STYLE_CLASS_POLL_MS = 2000;
/** Slower retry while the gateway is unreachable or the caller may not read the project. */
export const STYLE_CLASS_RETRY_MS = 15_000;

export type StyleClassStatus =
  /** Not read yet, or the project name is not valid: the session uses its built-in classes. */
  | "pending"
  /** Showing the stored classes. */
  | "gateway"
  /** Nothing is stored for the project: the session uses its built-in classes. */
  | "none"
  /** Not signed in, or the caller lacks the permission to read the project. */
  | "denied"
  /** No gateway answered. The session keeps what it shows. */
  | "offline";

export interface FollowedStyleClasses {
  styleClasses: StyleClassSet | undefined;
  status: StyleClassStatus;
}

export interface FollowOptions {
  periodMs?: number;
  retryMs?: number;
}

/** Follow the stored style classes of [project]. [fallback] is shown until, and whenever, nothing is stored. */
export function useGatewayStyleClasses(
  client: GatewayClient,
  project: string,
  fallback: StyleClassSet | undefined,
  options: FollowOptions = {},
): FollowedStyleClasses {
  const periodMs = options.periodMs ?? STYLE_CLASS_POLL_MS;
  const retryMs = options.retryMs ?? STYLE_CLASS_RETRY_MS;
  const [state, setState] = useState<FollowedStyleClasses>({ styleClasses: fallback, status: "pending" });

  useEffect(() => {
    if (!validProjectName(project)) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    // Version of the classes shown; null while showing [fallback] because nothing is stored.
    let shown: number | null | undefined;

    const poll = async () => {
      let delay = periodMs;
      try {
        // A background read: polling must not keep the login alive.
        const loaded = await loadStyleClasses(client, project, { background: true });
        if (stopped) return;
        if (!loaded) {
          if (shown !== null) setState({ styleClasses: fallback, status: "none" });
          else setState((current) => (current.status === "none" ? current : { ...current, status: "none" }));
          shown = null;
        } else if (loaded.ref.version !== shown) {
          shown = loaded.ref.version;
          setState({ styleClasses: loaded.styleClasses, status: "gateway" });
        } else {
          // Same version as shown, but the last poll may have been offline: clear that status.
          setState((current) => (current.status === "gateway" ? current : { ...current, status: "gateway" }));
        }
      } catch (error) {
        if (stopped) return;
        delay = retryMs;
        if (error instanceof GatewayUnreachable) {
          setState((current) => (current.status === "offline" ? current : { ...current, status: "offline" }));
        } else if (error instanceof GatewayError && (error.status === 401 || error.status === 403)) {
          // Signed out or not permitted: nothing from the project stays on screen.
          shown = undefined;
          setState((current) => (current.status === "denied" ? current : { styleClasses: fallback, status: "denied" }));
        } else if (!(error instanceof GatewayError)) {
          // A stored set that does not parse is ignored; the session keeps the last good one.
          delay = periodMs;
        }
      }
      if (!stopped) timer = setTimeout(() => void poll(), delay);
    };

    void poll();
    return () => {
      stopped = true;
      if (timer !== null) clearTimeout(timer);
    };
  }, [client, project, fallback, periodMs, retryMs]);

  return state;
}
