import { useEffect, useState } from "react";
import { GatewayError, GatewayUnreachable, type GatewayClient, type ProjectInfo } from "./gatewayClient";

/**
 * The open project's settings as the gateway holds them now (PRO-004): polled, so a rename, delete, disable or parent
 * change made on the admin Projects page shows in an open Designer without a reload.
 */
export const PROJECT_STATUS_POLL_MS = 3000;

export type ProjectStatus =
  | { kind: "unknown" }
  | { kind: "present"; info: ProjectInfo }
  /** The project was on the gateway while this Designer had it open, and is gone now: renamed or deleted. */
  | { kind: "absent" };

export function useProjectStatus(client: GatewayClient | null, project: string, pollMs = PROJECT_STATUS_POLL_MS): ProjectStatus {
  const [status, setStatus] = useState<ProjectStatus>({ kind: "unknown" });
  useEffect(() => {
    if (!client) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    // A project not saved yet has no settings; only one that disappears after it was seen counts as gone.
    let seen = false;
    const poll = async () => {
      try {
        // A background read: an idle Designer does not stay signed in because of this poll.
        const info = await client.projectInfo(project, { background: true });
        if (info) seen = true;
        if (!stopped) setStatus(info ? { kind: "present", info } : seen ? { kind: "absent" } : { kind: "unknown" });
      } catch (error) {
        // Unreachable, signed out or not allowed: the save controls already say so; keep the last known state.
        if (!(error instanceof GatewayUnreachable) && !(error instanceof GatewayError)) throw error;
      }
      if (!stopped) timer = setTimeout(() => void poll(), pollMs);
    };
    void poll();
    return () => {
      stopped = true;
      if (timer !== null) clearTimeout(timer);
    };
  }, [client, project, pollMs]);
  return status;
}

/** One line under the project name: its parent, and whether sessions can run it. */
export function ProjectStatusLine({ status }: { status: ProjectStatus }) {
  if (status.kind === "unknown") return null;
  if (status.kind === "absent") {
    return (
      <p className="oip-project-status" role="alert" data-testid="project-status" data-project-state="absent">
        This project is no longer on the gateway. It was renamed or deleted.
      </p>
    );
  }
  const { info } = status;
  const notes: string[] = [];
  if (info.title && info.title !== info.name) notes.push(info.title);
  if (info.parent) notes.push(`inherits from ${info.parent}`);
  if (!info.enabled) notes.push("disabled: sessions do not run it");
  else if (!info.runnable) notes.push("inheritance only: sessions do not run it");
  if (notes.length === 0) return null;
  return (
    <p className="oip-project-status" data-testid="project-status" data-project-state={info.enabled ? "enabled" : "disabled"}>
      {notes.join(" · ")}
    </p>
  );
}
