/** Session properties available to a running HMI view.
 * Subset of the public session property list (id, theme, locale, time zone, auth, device, gateway connection).
 */

export const DEVICE_ID_KEY = "oip.session.device-id";

const LOCALE_RE = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;

export interface SessionModel {
  id: string;
  theme: string;
  /** BCP-47 language tag. Used for number formatting. */
  locale: string;
  timeZoneId: string;
  auth: {
    authenticated: boolean;
    userName: string | null;
    roles: string[];
  };
  device: DeviceInfo;
  gateway: {
    connected: boolean;
  };
  custom: Record<string, unknown>;
}

/** Device information a session reports about its browser (doc source: FEATURES HMP-084). Read-only for bindings and scripts. */
export interface DeviceInfo {
  type: "browser" | "designer" | "unknown";
  /** A random id this browser keeps between sessions, or "" when it cannot keep one. */
  id: string;
  userAgent: string;
  /** Screen size in CSS pixels, 0 when unknown. */
  screenWidth: number;
  screenHeight: number;
  /** The browser's own IANA time zone. The session's `timeZoneId` is separate and writable. */
  timeZone: string;
}

export class SessionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SessionError";
  }
}

/**
 * Starts a session. The time zone is the session's own `timeZoneId` when given; otherwise the gateway's default zone
 * (`gatewayTimeZone`, when it is a usable zone), otherwise UTC (HMP-101). A component formats in its own zone through
 * a format transform's `timeZone`, which neither of these changes.
 */
export function createSession(input: Partial<SessionModel> & { id: string; gatewayTimeZone?: string }): SessionModel {
  const locale = input.locale ?? "en-US";
  if (!LOCALE_RE.test(locale)) throw new SessionError(`locale is not a BCP-47 tag: ${locale}`);
  return {
    id: input.id,
    theme: input.theme ?? "nightwatch",
    locale,
    timeZoneId: input.timeZoneId ?? (isTimeZoneId(input.gatewayTimeZone) ? input.gatewayTimeZone : "UTC"),
    auth: {
      authenticated: input.auth?.authenticated ?? false,
      userName: input.auth?.userName ?? null,
      roles: input.auth?.roles ? [...input.auth.roles] : [],
    },
    device: {
      type: input.device?.type ?? "browser",
      id: input.device?.id ?? "",
      userAgent: input.device?.userAgent ?? "",
      screenWidth: input.device?.screenWidth ?? 0,
      screenHeight: input.device?.screenHeight ?? 0,
      timeZone: input.device?.timeZone ?? "",
    },
    gateway: { connected: input.gateway?.connected ?? false },
    custom: { ...(input.custom ?? {}) },
  };
}

export function isLocaleTag(value: unknown): value is string {
  return typeof value === "string" && value.length <= 35 && LOCALE_RE.test(value);
}

/** True for an IANA zone name the browser's date formatting accepts, such as `Europe/Berlin` or `UTC`. */
export function isTimeZoneId(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0 || value.length > 64) return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

/** Reads this browser's device information. A stable id is kept in local storage when the browser allows it. */
export function detectDevice(type: DeviceInfo["type"] = "browser"): DeviceInfo {
  let id = "";
  try {
    id = window.localStorage.getItem(DEVICE_ID_KEY) ?? "";
    if (!id) {
      id = typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `device-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
      window.localStorage.setItem(DEVICE_ID_KEY, id);
    }
  } catch {
    // Storage is blocked: the id lasts only for this page.
  }
  let timeZone = "";
  try {
    timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone ?? "";
  } catch {
    timeZone = "";
  }
  return {
    type,
    id,
    userAgent: typeof navigator === "undefined" ? "" : navigator.userAgent,
    screenWidth: typeof screen === "undefined" ? 0 : Math.round(screen.width),
    screenHeight: typeof screen === "undefined" ? 0 : Math.round(screen.height),
    timeZone,
  };
}

/** The session properties a script reads as `session.props`, shaped like the binding paths. The gateway replaces `auth` with the authenticated login; the rest is as the page reports it. */
export function sessionForScripts(session: {
  id: string;
  theme: string;
  locale: string;
  timeZoneId: string;
  auth: { authenticated: boolean; userName: string | null; roles: string[] };
  device: DeviceInfo | { type: string };
}): Record<string, unknown> {
  return {
    id: session.id,
    theme: session.theme,
    locale: session.locale,
    timeZoneId: session.timeZoneId,
    auth: { authenticated: session.auth.authenticated, userName: session.auth.userName, roles: [...session.auth.roles] },
    device: { ...session.device },
  };
}
