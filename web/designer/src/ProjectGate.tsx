import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import type { StyleClassSet, ViewDocument } from "@oip/hmi-runtime";
import { DESIGNER_LOGIN, GatewayError, GatewayUnreachable, type GatewayClient, type GatewaySession } from "./gatewayClient";
import {
  loadPageConfig,
  loadProjectViews,
  loadScriptHooks,
  loadStyleClasses,
  validProjectName,
  type LoadedPageConfig,
  type LoadedScriptHooks,
  type LoadedStyleClasses,
  type LoadedView,
} from "./persistence";
import { rememberProject } from "./projectFiles";

/**
 * Opens Control Canvas on a gateway project. Signs in when the gateway asks for it, loads the project's
 * views, and falls back to an in-tab sample when no gateway answers (for example a bare `vite dev`).
 * The project is `?project=<name>` (default `default`).
 */

export const DEFAULT_PROJECT = "default";
// Stable default: a fresh `{}` per render would re-run the open effect.
const NO_STYLE_CLASSES: StyleClassSet = {};

export type Opened =
  | {
      mode: "gateway";
      project: string;
      views: LoadedView[];
      drafts: ViewDocument[];
      /** The project's stored style classes, or null when none are stored yet. */
      styleClasses: LoadedStyleClasses | null;
      /** Style classes shown but not stored yet (the starter set for an empty project). */
      styleClassesDraft: StyleClassSet | null;
      /** The project's stored script hooks, or null when none are stored yet. */
      scriptHooks: LoadedScriptHooks | null;
      /** The project's stored page configuration, or null when none is stored yet. */
      pageConfig: LoadedPageConfig | null;
      loadErrors: string[];
    }
  | { mode: "offline"; project: string; reason: string };

type Phase =
  | { kind: "connecting" }
  | { kind: "login"; error: string | null; busy: boolean }
  | { kind: "error"; message: string }
  /** Signed in, but without the designer permission (review-4 F4). */
  | { kind: "denied"; username: string }
  | { kind: "open"; opened: Opened };

/** Whether [session] may use Control Canvas. A gateway that does not report permissions is left to refuse each write. */
function mayDesign(session: GatewaySession): boolean {
  return session.permissions === undefined || session.permissions.includes(DESIGNER_LOGIN);
}

export function projectFromLocation(search: string): string {
  const requested = new URLSearchParams(search).get("project")?.trim();
  return requested ? requested : DEFAULT_PROJECT;
}

export function ProjectGate({
  client,
  project,
  starter,
  starterStyleClasses = NO_STYLE_CLASSES,
  children,
}: {
  client: GatewayClient;
  project: string;
  /** Views offered, unsaved, when the gateway project is empty or no gateway answers. */
  starter: ViewDocument[];
  /** Style classes offered, unsaved, when the gateway project is empty. */
  starterStyleClasses?: StyleClassSet;
  children: (opened: Opened) => ReactNode;
}) {
  const [phase, setPhase] = useState<Phase>({ kind: "connecting" });

  useEffect(() => {
    let cancelled = false;
    const set = (next: Phase) => {
      if (!cancelled) setPhase(next);
    };
    if (!validProjectName(project)) {
      set({ kind: "error", message: `"${project}" is not a valid project name (lowercase letters, digits, and '-').` });
      return undefined;
    }
    void (async () => {
      try {
        const session = await client.session();
        if (!session) {
          set({ kind: "login", error: null, busy: false });
          return;
        }
        if (!mayDesign(session)) {
          set({ kind: "denied", username: session.username });
          return;
        }
        const opened = await open(client, project, starter, starterStyleClasses);
        if (opened.mode === "gateway") rememberProject(window.localStorage, opened.project);
        set({ kind: "open", opened });
      } catch (error) {
        if (error instanceof GatewayUnreachable) {
          set({ kind: "open", opened: { mode: "offline", project, reason: error.message } });
          return;
        }
        set({ kind: "error", message: error instanceof Error ? error.message : String(error) });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [client, project, starter, starterStyleClasses]);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPhase({ kind: "login", error: null, busy: true });
    try {
      await client.login(String(form.get("username") ?? ""), String(form.get("password") ?? ""));
      const session = await client.session();
      if (session && !mayDesign(session)) {
        setPhase({ kind: "denied", username: session.username });
        return;
      }
      const opened = await open(client, project, starter, starterStyleClasses);
      if (opened.mode === "gateway") rememberProject(window.localStorage, opened.project);
      setPhase({ kind: "open", opened });
    } catch (error) {
      const message = error instanceof GatewayError && error.status === 401 ? "Wrong username or password." : error instanceof Error ? error.message : String(error);
      setPhase({ kind: "login", error: message, busy: false });
    }
  }

  if (phase.kind === "connecting") {
    return <p className="oip-gate" role="status">Connecting to the gateway…</p>;
  }
  if (phase.kind === "error") {
    return (
      <p className="oip-gate" role="alert">
        {phase.message}
      </p>
    );
  }
  if (phase.kind === "denied") {
    return (
      <p className="oip-gate" role="alert" data-studio-state="denied">
        {phase.username ? `${phase.username} is signed in, but` : "You are signed in, but"} not allowed to use Control Canvas: the gateway&apos;s designer
        roles do not include any of your roles ({DESIGNER_LOGIN}).
      </p>
    );
  }
  if (phase.kind === "login") {
    return (
      <form className="oip-gate oip-login" aria-label="Sign in to the gateway" onSubmit={(event) => void signIn(event)}>
        <h1>Control Canvas</h1>
        <p>Sign in to open project {project}.</p>
        <label>
          Username
          <input name="username" autoComplete="username" required />
        </label>
        <label>
          Password
          <input name="password" type="password" autoComplete="current-password" required />
        </label>
        {phase.error ? <p role="alert">{phase.error}</p> : null}
        <button type="submit" disabled={phase.busy}>
          Sign in
        </button>
      </form>
    );
  }
  return <>{children(phase.opened)}</>;
}

async function open(client: GatewayClient, project: string, starter: ViewDocument[], starterStyleClasses: StyleClassSet): Promise<Opened> {
  const loaded = await loadProjectViews(client, project);
  const errors = [...loaded.errors];
  let styleClasses: LoadedStyleClasses | null = null;
  try {
    styleClasses = await loadStyleClasses(client, project);
  } catch (error) {
    if (error instanceof GatewayError || error instanceof GatewayUnreachable) throw error;
    // Stored but not parseable: left untouched on the gateway, like an unreadable view.
    errors.push(`style classes: ${error instanceof Error ? error.message : String(error)}`);
  }
  let scriptHooks: LoadedScriptHooks | null = null;
  try {
    scriptHooks = await loadScriptHooks(client, project);
  } catch (error) {
    if (error instanceof GatewayError || error instanceof GatewayUnreachable) throw error;
    errors.push(`script hooks: ${error instanceof Error ? error.message : String(error)}`);
  }
  let pageConfig: LoadedPageConfig | null = null;
  try {
    pageConfig = await loadPageConfig(client, project);
  } catch (error) {
    if (error instanceof GatewayError || error instanceof GatewayUnreachable) throw error;
    errors.push(`page configuration: ${error instanceof Error ? error.message : String(error)}`);
  }
  const empty = loaded.views.length === 0 && errors.length === 0 && styleClasses === null && scriptHooks === null && pageConfig === null;
  return {
    mode: "gateway",
    project,
    views: loaded.views,
    drafts: loaded.views.length === 0 && loaded.errors.length === 0 ? starter : [],
    styleClasses,
    styleClassesDraft: empty ? starterStyleClasses : null,
    scriptHooks,
    pageConfig,
    loadErrors: errors,
  };
}
