import { ApiError, type SessionState } from "./api";

export function allows(session: SessionState | undefined, permission: string): boolean {
  return session?.permissions.includes(permission) ?? false;
}

export function errorMessage(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.message : fallback;
}

/** Resource names accepted by the user, role, and API-key routes. */
export const RESOURCE_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
