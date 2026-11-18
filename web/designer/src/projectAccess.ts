import { useCallback, useEffect, useRef, useState } from "react";
import { GatewayError, type DesignerAction, type ProjectAccess, type ResourceLock, type ResourceRef } from "./gatewayClient";
import { DESIGNER_MODULE, VIEW_KIND } from "./persistence";

/** The gateway calls project security needs; `GatewayClient` implements them. */
export interface ProjectAccessClient {
  projectAccess(project: string, options?: { background?: boolean }): Promise<ProjectAccess>;
  acquireLock(project: string, ref: ResourceRef, session: string): Promise<ResourceLock>;
  releaseLock(project: string, ref: ResourceRef, force?: boolean): Promise<void>;
  setProtected(project: string, ref: ResourceRef, on: boolean): Promise<string[]>;
}

/** How often the Designer reads the project's locks and protection again, so others' locks show up. */
export const ACCESS_REFRESH_MS = 15_000;

/** A view's resource as locks and protection name it. */
export function viewRef(name: string): ResourceRef {
  return { module: DESIGNER_MODULE, kind: VIEW_KIND, name };
}

export function refPath(ref: ResourceRef): string {
  return `${ref.module}/${ref.kind}/${ref.name}`;
}

export interface ProjectAccessState {
  /** Null until the gateway answered (or when there is no gateway). */
  access: ProjectAccess | null;
  /** Whether the caller may take [action]; true until the gateway says otherwise. */
  may: (action: DesignerAction) => boolean;
  isProtected: (ref: ResourceRef) => boolean;
  /** The other Designer's lock on [ref], if one holds it. */
  lockedBy: (ref: ResourceRef) => ResourceLock | null;
  protect: (ref: ResourceRef, on: boolean) => Promise<void>;
  breakLock: (ref: ResourceRef) => Promise<void>;
  /** The last failed project-security call, shown beside the canvas. */
  error: string | null;
}

/**
 * Project permissions and resource locks in the Designer (SEC-020). Reads what the signed-in user may do with the
 * project, keeps it fresh, takes the lock of each resource the user has unsaved edits in (so other Designers see it
 * and cannot save over it), and releases a lock once its edits are gone without a save (a save releases it on the
 * gateway). The gateway enforces every rule itself; this only shows them.
 * Doc source: FEATURES SEC-020.
 */
export function useProjectAccess(
  client: ProjectAccessClient | null,
  project: string,
  session: string | null,
  /** Resources with unsaved edits. */
  editing: readonly ResourceRef[],
): ProjectAccessState {
  const [access, setAccess] = useState<ProjectAccess | null>(null);
  const [error, setError] = useState<string | null>(null);
  const held = useRef(new Set<string>());

  const refresh = useCallback(
    async (background = true) => {
      if (!client) return;
      try {
        setAccess(await client.projectAccess(project, { background }));
      } catch (failure) {
        // Not reachable or not permitted: the gateway still refuses what it must; nothing more to show.
        if (failure instanceof GatewayError && failure.status === 403) setError(failure.message);
      }
    },
    [client, project],
  );

  useEffect(() => {
    if (!client) return undefined;
    void refresh(false);
    const timer = setInterval(() => void refresh(), ACCESS_REFRESH_MS);
    return () => clearInterval(timer);
  }, [client, refresh]);

  // A new Designer session holds none of the old one's locks.
  useEffect(() => {
    held.current.clear();
  }, [session]);

  const editingKey = editing.map(refPath).sort().join("|");
  useEffect(() => {
    if (!client || !session) return;
    const wanted = new Map(editing.map((ref) => [refPath(ref), ref]));
    let changed = false;
    const work: Promise<void>[] = [];
    for (const [path, ref] of wanted) {
      if (held.current.has(path)) continue;
      // Another Designer holds it: try again once a refresh shows it free.
      if (access?.locks.some((lock) => lock.path === path && !(lock.mine && lock.session === session))) continue;
      held.current.add(path);
      work.push(
        client.acquireLock(project, ref, session).then(
          () => {
            changed = true;
          },
          (failure: unknown) => {
            held.current.delete(path);
            changed = true;
            if (failure instanceof GatewayError && failure.status !== 423) setError(failure.message);
          },
        ),
      );
    }
    for (const path of [...held.current]) {
      if (wanted.has(path)) continue;
      held.current.delete(path);
      const [module, kind, name] = path.split("/") as [string, string, string];
      // Saved (the gateway released it) or discarded: either way it is no longer ours. A 404 is already free.
      work.push(client.releaseLock(project, { module, kind, name }).catch(() => undefined).then(() => {
        changed = true;
      }));
    }
    if (work.length > 0) void Promise.all(work).then(() => (changed ? refresh() : undefined));
    // editingKey stands for editing's content; access re-runs this after each refresh.
  }, [client, project, session, editingKey, access, refresh]);

  const may = useCallback((action: DesignerAction) => access?.allowed[action] ?? true, [access]);
  const isProtected = useCallback((ref: ResourceRef) => access?.protected.includes(refPath(ref)) ?? false, [access]);
  const lockedBy = useCallback(
    (ref: ResourceRef) => access?.locks.find((lock) => lock.path === refPath(ref) && !(lock.mine && lock.session === session)) ?? null,
    [access, session],
  );

  const protect = useCallback(
    async (ref: ResourceRef, on: boolean) => {
      if (!client) return;
      try {
        setError(null);
        await client.setProtected(project, ref, on);
      } catch (failure) {
        setError(failure instanceof Error ? failure.message : String(failure));
      }
      await refresh(false);
    },
    [client, project, refresh],
  );

  const breakLock = useCallback(
    async (ref: ResourceRef) => {
      if (!client) return;
      try {
        setError(null);
        await client.releaseLock(project, ref, true);
      } catch (failure) {
        setError(failure instanceof Error ? failure.message : String(failure));
      }
      await refresh(false);
    },
    [client, project, refresh],
  );

  return { access, may, isProtected, lockedBy, protect, breakLock, error };
}
