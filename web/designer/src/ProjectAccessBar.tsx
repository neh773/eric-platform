import { useState } from "react";
import type { ProjectAccessState } from "./projectAccess";
import type { ResourceRef } from "./gatewayClient";

/** Why the signed-in user may not save this project, or null when they may (SEC-020). */
export function saveBlockedReason(state: ProjectAccessState): string | null {
  if (state.may("save")) return null;
  const roles = state.access?.requiredRoles.save ?? [];
  return `Saving this project needs one of the Designer roles ${roles.join(", ")}`;
}

/**
 * The open view's project security (SEC-020): a protected view (with Protect and Unprotect for users holding the
 * protect roles, and a locking error for the others), another Designer's lock with Break lock for an administrator,
 * and a notice when the user may not save the project at all.
 * Doc source: FEATURES SEC-020.
 */
export function ProjectAccessBar({ state, resource, label }: { state: ProjectAccessState; resource: ResourceRef | null; label: string }) {
  const [continued, setContinued] = useState<string | null>(null);
  if (!state.access) return null;
  const blocked = saveBlockedReason(state);
  const isProtected = resource ? state.isProtected(resource) : false;
  const lock = resource ? state.lockedBy(resource) : null;
  const mayProtect = state.may("protect");
  const key = resource ? `${resource.module}/${resource.kind}/${resource.name}` : null;
  return (
    <div className="oip-access-bar" data-testid="project-access">
      {blocked ? (
        <p role="status" data-access="save-blocked">
          {blocked}. You can look at the views, but the gateway will not take a save.
        </p>
      ) : null}
      {resource && isProtected && !mayProtect ? (
        <p role="alert" data-access="locking-error">
          Locking error: view {label} is protected. Changing it needs one of the Designer roles {state.access.requiredRoles.protect.join(", ")}.
        </p>
      ) : null}
      {resource && isProtected && mayProtect && continued !== key ? (
        <div role="alertdialog" aria-label="Protected view" data-access="protected">
          <p>View {label} is protected. Changes you save apply to a protected resource.</p>
          <button type="button" onClick={() => void state.protect(resource, false)}>
            Unprotect
          </button>
          <button type="button" onClick={() => setContinued(key)}>
            Continue editing
          </button>
        </div>
      ) : null}
      {resource && !isProtected && mayProtect && state.may("save") ? (
        <button type="button" data-access="protect" onClick={() => void state.protect(resource, true)}>
          Protect view
        </button>
      ) : null}
      {lock ? (
        <p role="status" data-access="locked">
          {lock.mine ? "Your other Designer window" : lock.user} is editing view {label}. Your changes to it cannot be saved until that
          Designer saves or closes it.
          {state.access.breakLocks ? (
            <button type="button" onClick={() => void state.breakLock(lock)}>
              Break lock
            </button>
          ) : null}
        </p>
      ) : null}
      {state.error ? <p role="alert">{state.error}</p> : null}
    </div>
  );
}
