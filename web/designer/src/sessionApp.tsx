import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { PageSession, createSession, detectDevice, matchPage, themeNames, type HmiProject, type SessionModel } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import type { TagRuntime } from "./runtime";
import { GatewayError, GatewayUnreachable, gateway, type GatewayClient, type GatewaySession } from "./gatewayClient";
import { useGatewayStyleClasses } from "./gatewayStyleClasses";
import { validProjectName } from "./persistence";
import {
  HMI_BASE,
  HMI_POLL_MS,
  HMI_RETRY_MS,
  HMI_WAIT_MS,
  SAMPLE_PROJECT,
  chooseDefaultProject,
  hmiUrl,
  ProjectUnavailable,
  SESSION_REFUSED,
  loadHmiProject,
  parseHmiUrl,
  type HmiTarget,
  type LoadedHmiProject,
} from "./hmiProject";
import { sessionProject } from "./sessionProject";
import { useGatewayScriptHost } from "./gatewayScripts";
import { gatewayQuerySource } from "./gatewayQueries";

const registry = componentRegistry();

/**
 * Sessions mounted at /hmi (the Studio is at /studio). A session runs a project saved on the gateway as the signed-in
 * user and follows saved changes; see hmiProject.ts for the URL form. The built-in sample runs only at `/hmi/_sample/`
 * or when no gateway answers, and says so on screen.
 */
export function HmiApp({ runtime, client = gateway, pollMs = HMI_POLL_MS, retryMs = HMI_RETRY_MS, waitMs = HMI_WAIT_MS }: HmiAppProps) {
  const [target, setTarget] = useState<HmiTarget>(currentTarget);
  useEffect(() => {
    // The earlier `/hmi/?project=<name>` form becomes `/hmi/<name>/` in the address bar.
    const initial = currentTarget();
    const bare = window.location.pathname === HMI_BASE || window.location.pathname === `${HMI_BASE}/`;
    if (bare && initial.kind === "project") window.history.replaceState({}, "", hmiUrl(initial.project, initial.location));
    const onPop = () => setTarget(currentTarget());
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  if (target.kind === "sample") {
    return <SampleSession runtime={runtime} client={client} location={target.location} onTarget={setTarget} note="sample" />;
  }
  return <GatewaySession runtime={runtime} client={client} target={target} onTarget={setTarget} pollMs={pollMs} retryMs={retryMs} waitMs={waitMs} />;
}

export interface HmiAppProps {
  runtime: TagRuntime;
  client?: GatewayClient;
  /** How often an open project is checked for saved changes. */
  pollMs?: number;
  /** Retry delay while the gateway is unreachable or refuses the project. */
  retryMs?: number;
  /** How long one project read waits at the gateway for a saved change (F12). */
  waitMs?: number;
}

function currentTarget(): HmiTarget {
  return parseHmiUrl(window.location.pathname, window.location.search);
}

type Auth =
  | { kind: "checking" }
  | { kind: "offline"; reason: string }
  | { kind: "signedOut"; error: string | null; busy: boolean }
  | { kind: "signedIn"; user: SessionUser }
  | { kind: "error"; message: string };

/** Who is signed in, as the session's `auth` properties show it (HMP-084). */
type SessionUser = { userName: string; roles: string[] };

function userOf(session: GatewaySession): SessionUser {
  return { userName: session.username, roles: session.roles ?? [] };
}

type ProjectPhase =
  | { kind: "loading" }
  | { kind: "open"; loaded: LoadedHmiProject; sync: "live" | "offline" }
  | { kind: "missing" }
  /** The project exists but is disabled or not runnable (PRO-004); polling continues, so re-enabling shows it. */
  | { kind: "unavailable"; reason: "disabled" | "not-runnable" }
  | { kind: "denied" }
  /** The project's session security refuses this user (HMP-092); [reason] is the gateway's. Polling continues. */
  | { kind: "refused"; reason: string }
  | { kind: "invalid" }
  | { kind: "offline"; reason: string }
  | { kind: "error"; message: string };

function GatewaySession({
  runtime,
  client,
  target,
  onTarget,
  pollMs,
  retryMs,
  waitMs,
}: {
  runtime: TagRuntime;
  client: GatewayClient;
  target: Exclude<HmiTarget, { kind: "sample" }>;
  onTarget: (target: HmiTarget) => void;
  pollMs: number;
  retryMs: number;
  waitMs: number;
}) {
  const [auth, setAuth] = useState<Auth>({ kind: "checking" });
  const [projects, setProjects] = useState<string[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  // The phase belongs to one project and one login: it is shown only for [projectName], and a sign-out clears it,
  // so neither another project nor a project from before a sign-in is drawn, even for one frame.
  const [held, setHeld] = useState<{ project: string | null; phase: ProjectPhase }>({ project: null, phase: { kind: "loading" } });
  const projectName = target.kind === "project" ? target.project : null;
  const defaultSearch = target.kind === "default" ? target.search : null;

  // Whatever ends the login (a 401 anywhere, a failed check) drops the project before the next sign-in.
  useEffect(() => {
    if (auth.kind === "signedOut") setHeld({ project: null, phase: { kind: "loading" } });
  }, [auth.kind]);

  // Who is signed in. Without a gateway the built-in sample runs, and the gateway is tried again every [retryMs].
  useEffect(() => {
    if (auth.kind !== "checking" && auth.kind !== "offline") return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const check = async () => {
      try {
        const session = await client.session();
        if (!stopped) setAuth(session ? { kind: "signedIn", user: userOf(session) } : { kind: "signedOut", error: null, busy: false });
      } catch (error) {
        if (stopped) return;
        if (error instanceof GatewayUnreachable) {
          setAuth((current) => (current.kind === "offline" ? current : { kind: "offline", reason: error.message }));
          timer = setTimeout(() => void check(), retryMs);
        } else {
          setAuth({ kind: "error", message: describe(error) });
        }
      }
    };
    if (auth.kind === "checking") void check();
    else timer = setTimeout(() => void check(), retryMs);
    return () => {
      stopped = true;
      if (timer !== null) clearTimeout(timer);
    };
  }, [auth.kind, client, retryMs]);

  // `/hmi/`: open the default or only project, or list the projects.
  useEffect(() => {
    if (auth.kind !== "signedIn" || defaultSearch === null) return undefined;
    let stopped = false;
    setProjects(null);
    setListError(null);
    void (async () => {
      try {
        const names = await client.hmiProjects();
        if (stopped) return;
        const chosen = chooseDefaultProject(names.filter(validProjectName));
        if (chosen) {
          const location = `/${defaultSearch}`;
          window.history.replaceState({}, "", hmiUrl(chosen, location));
          onTarget({ kind: "project", project: chosen, location });
        } else {
          setProjects(names);
        }
      } catch (error) {
        if (stopped) return;
        if (error instanceof GatewayError && error.status === 401) setAuth({ kind: "signedOut", error: null, busy: false });
        else if (error instanceof GatewayUnreachable) setAuth({ kind: "offline", reason: error.message });
        else if (error instanceof GatewayError && error.status === 403) setListError("You are signed in, but not allowed to read this gateway's projects.");
        else setListError(describe(error));
      }
    })();
    return () => {
      stopped = true;
    };
  }, [auth.kind, client, defaultSearch, onTarget]);

  // The project: read once, then a read that the gateway holds until a saved change (or [waitMs]) answers it.
  useEffect(() => {
    if (auth.kind !== "signedIn" || projectName === null) return undefined;
    const show = (phase: ProjectPhase) => setHeld({ project: projectName, phase });
    const update = (next: (current: ProjectPhase) => ProjectPhase) =>
      setHeld((held) => (held.project === projectName ? { project: projectName, phase: next(held.phase) } : held));
    if (!validProjectName(projectName)) {
      show({ kind: "invalid" });
      return undefined;
    }
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const abort = new AbortController();
    let shown: string | undefined;
    show({ kind: "loading" });
    const poll = async () => {
      let delay = pollMs;
      const started = Date.now();
      try {
        const loaded = await loadHmiProject(client, projectName, shown === undefined ? { signal: abort.signal } : { after: shown, waitMs, signal: abort.signal });
        if (stopped) return;
        if (loaded === "unchanged") {
          update((current) => (current.kind === "open" && current.sync !== "live" ? { ...current, sync: "live" } : current));
          // The gateway held the read until its wait ended: ask again at once. An early answer waits [pollMs].
          if (Date.now() - started >= Math.min(1000, waitMs)) delay = 0;
        } else if (!loaded) {
          shown = undefined;
          show({ kind: "missing" });
        } else {
          if (loaded.signature !== shown) show({ kind: "open", loaded, sync: "live" });
          shown = loaded.signature;
          delay = 0;
        }
      } catch (error) {
        if (stopped) return;
        if (error instanceof ProjectUnavailable) {
          // Nothing from the project stays on screen; the poll goes on at the normal rate.
          shown = undefined;
          show({ kind: "unavailable", reason: error.reason });
          if (!stopped) timer = setTimeout(() => void poll(), delay);
          return;
        }
        delay = retryMs;
        if (error instanceof GatewayUnreachable) {
          // A running session keeps its pages while the gateway is away.
          update((current) => (current.kind === "open" ? { ...current, sync: "offline" } : { kind: "offline", reason: error.message }));
        } else if (error instanceof GatewayError && error.status === 401) {
          // Signed out (logout, timeout): back to the sign-in form; polling stops until then.
          setHeld({ project: null, phase: { kind: "loading" } });
          setAuth({ kind: "signedOut", error: null, busy: false });
          return;
        } else if (error instanceof GatewayError && error.status === 403 && error.code === SESSION_REFUSED) {
          // HMP-092: the project's session roles or identity provider refuse this user; nothing of it stays on screen.
          shown = undefined;
          show({ kind: "refused", reason: error.message });
        } else if (error instanceof GatewayError && error.status === 403) {
          // Not permitted: nothing from the project stays on screen.
          shown = undefined;
          show({ kind: "denied" });
        } else {
          update((current) => (current.kind === "open" ? current : { kind: "error", message: describe(error) }));
        }
      }
      if (!stopped) timer = setTimeout(() => void poll(), delay);
    };
    void poll();
    return () => {
      stopped = true;
      abort.abort();
      if (timer !== null) clearTimeout(timer);
    };
  }, [auth.kind, client, projectName, pollMs, retryMs, waitMs]);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setAuth({ kind: "signedOut", error: null, busy: true });
    try {
      const login = await client.login(String(form.get("username") ?? ""), String(form.get("password") ?? ""));
      // The login answer carries no roles; the session read does. Without it the user is still signed in, with no roles.
      const session = await client.session().catch(() => null);
      setAuth({ kind: "signedIn", user: userOf(session ?? login) });
    } catch (error) {
      if (error instanceof GatewayUnreachable) {
        setAuth({ kind: "offline", reason: error.message });
        return;
      }
      const message = error instanceof GatewayError && error.status === 401 ? "Wrong username or password." : describe(error);
      setAuth({ kind: "signedOut", error: message, busy: false });
    }
  }

  const location = target.kind === "project" ? target.location : `/${target.search}`;
  switch (auth.kind) {
    case "checking":
      return <Notice status="connecting">Connecting to the gateway…</Notice>;
    case "error":
      return <Notice status="error">{auth.message}</Notice>;
    case "offline":
      // Review-4 F7: no sample plant values under a real project's URL. The built-in sample runs only at
      // `/hmi/_sample/`; here the session says the gateway is away and keeps trying.
      return (
        <Notice status="gateway-offline">
          No gateway answered ({auth.reason}), so {projectName ? <>project &quot;{projectName}&quot;</> : "no project"} cannot be shown. Retrying…{" "}
          <a href={`${HMI_BASE}/${SAMPLE_PROJECT}/`}>Open the built-in sample</a>.
        </Notice>
      );
    case "signedOut":
      return (
        <form className="oip-gate oip-login" aria-label="Sign in to the gateway" data-hmi-state="login" onSubmit={(event) => void signIn(event)}>
          <h1>ERIC Session</h1>
          <p>{projectName ? `Sign in to open project ${projectName}.` : "Sign in to open a project."}</p>
          <label>
            Username
            <input name="username" autoComplete="username" required />
          </label>
          <label>
            Password
            <input name="password" type="password" autoComplete="current-password" required />
          </label>
          {auth.error ? <p role="alert">{auth.error}</p> : null}
          <button type="submit" disabled={auth.busy}>
            Sign in
          </button>
        </form>
      );
    case "signedIn":
      break;
  }

  if (target.kind === "default") {
    if (listError) return <Notice status="error">{listError}</Notice>;
    if (projects === null) return <Notice status="connecting">Looking for projects…</Notice>;
    return <ProjectList projects={projects} />;
  }
  const project = target.project;
  const phase: ProjectPhase = held.project === project ? held.phase : { kind: "loading" };
  const studio = `/studio?project=${encodeURIComponent(project)}`;
  switch (phase.kind) {
    case "loading":
      return <Notice status="connecting">Opening project {project}…</Notice>;
    case "invalid":
      return (
        <Notice status="invalid">
          &quot;{project}&quot; is not a valid project name (lowercase letters, digits, and &apos;-&apos;). <a href={`${HMI_BASE}/`}>See all projects</a>.
        </Notice>
      );
    case "missing":
      return (
        <Notice status="missing">
          Project &quot;{project}&quot; was not found on this gateway, or it has no saved views yet. <a href={studio}>Open it in Control Canvas</a> or{" "}
          <a href={`${HMI_BASE}/`}>see all projects</a>.
        </Notice>
      );
    case "unavailable":
      return (
        <Notice status="unavailable">
          {phase.reason === "disabled"
            ? `Project "${project}" is disabled on this gateway.`
            : `Project "${project}" is an inheritance-only project and does not run on its own.`}{" "}
          <a href={`${HMI_BASE}/`}>See all projects</a>.
        </Notice>
      );
    case "denied":
      return <Notice status="denied">You are signed in, but not allowed to read project &quot;{project}&quot;.</Notice>;
    case "refused":
      return (
        <Notice status="refused">
          You do not have access to project &quot;{project}&quot;: {phase.reason.replace(/^project '[^']*': /, "")}. Sign in as a user who
          does, or <a href={`${HMI_BASE}/`}>see all projects</a>.
        </Notice>
      );
    case "offline":
      return <Notice status="offline">The gateway stopped answering ({phase.reason}). Retrying…</Notice>;
    case "error":
      return <Notice status="error">{phase.message}</Notice>;
    case "open":
      return (
        <SavedSession
          runtime={runtime}
          client={client}
          user={auth.user}
          onLogout={() => {
            // HMP-117: a logout action ends the login and the session returns to the sign-in form.
            setHeld({ project: null, phase: { kind: "loading" } });
            void client.logout().then(
              () => setAuth({ kind: "signedOut", error: null, busy: false }),
              (error: unknown) => setAuth({ kind: "signedOut", error: `Sign-out did not finish: ${describe(error)}`, busy: false }),
            );
          }}
          loaded={phase.loaded}
          sync={phase.sync}
          location={location}
          onLocation={(next) => {
            window.history.pushState({}, "", hmiUrl(project, next));
            onTarget({ kind: "project", project, location: next });
          }}
        />
      );
  }
}

function SavedSession({
  runtime,
  client,
  user,
  onLogout,
  loaded,
  sync,
  location,
  onLocation,
}: {
  runtime: TagRuntime;
  client: GatewayClient;
  user: SessionUser;
  onLogout: () => void;
  loaded: LoadedHmiProject;
  sync: "live" | "offline";
  location: string;
  onLocation: (location: string) => void;
}) {
  const project = loaded.project;
  const match = useMemo(() => matchPage(project.pages, location), [project.pages, location]);
  const base = `${HMI_BASE}/${encodeURIComponent(project.name)}`;
  const attributes = {
    "data-hmi-source": "gateway",
    "data-project": project.name,
    "data-pages-from": loaded.pagesFrom,
    "data-sync": sync,
    "data-signature": loaded.signature,
  };
  if (project.views.length === 0 && (project.deniedViews ?? []).length === 0) {
    return (
      <Notice status="empty" attributes={attributes}>
        Project &quot;{project.name}&quot; has no views yet. <a href={`/studio?project=${encodeURIComponent(project.name)}`}>Open it in Control Canvas</a>.
      </Notice>
    );
  }
  return (
    <div className="oip-hmi-host" style={{ display: "contents" }} {...attributes}>
      {loaded.errors.length > 0 ? (
        <p role="status" className="oip-hmi-load-errors" style={bannerStyle}>
          Some saved entries could not be read and are left out: {loaded.errors.join("; ")}
        </p>
      ) : null}
      {match ? (
        <Session
          key={project.name}
          project={project}
          runtime={runtime}
          client={client}
          location={location}
          basePath={base}
          onLocation={onLocation}
          scriptProject={project.name}
          user={user}
          onLogout={onLogout}
        />
      ) : (
        <Notice status="no-page">
          <p>
            Project &quot;{project.name}&quot; has no page at &quot;{pathOf(location)}&quot;.
          </p>
          {project.pages.length > 0 ? (
            <nav aria-label="Pages">
              <ul>
                {project.pages.map((page) => (
                  <li key={page.spec.url}>
                    {page.spec.url.includes(":") ? (
                      <span>
                        {page.spec.title ?? page.metadata.name} ({page.spec.url})
                      </span>
                    ) : (
                      <a
                        href={`${base}${page.spec.url}`}
                        onClick={(event) => {
                          event.preventDefault();
                          onLocation(page.spec.url);
                        }}
                      >
                        {page.spec.title ?? page.metadata.name}
                      </a>
                    )}
                  </li>
                ))}
              </ul>
            </nav>
          ) : null}
        </Notice>
      )}
    </div>
  );
}

/** The built-in sample: at `/hmi/_sample/`, or when no gateway answers. `?project=` names saved style classes to follow. */
function SampleSession({
  runtime,
  client,
  location,
  onTarget,
  note,
}: {
  runtime: TagRuntime;
  client: GatewayClient;
  location: string;
  onTarget: (target: HmiTarget) => void;
  note: "sample";
}) {
  const [styleProject] = useState(() => new URLSearchParams(window.location.search).get("project")?.trim() ?? "");
  const { styleClasses, status } = useGatewayStyleClasses(client, styleProject, sessionProject.styleClasses);
  const merged = useMemo<HmiProject>(() => ({ ...sessionProject, styleClasses }), [styleClasses]);
  const base = `${HMI_BASE}/${SAMPLE_PROJECT}`;
  return (
    <div
      className="oip-hmi-host"
      style={{ display: "contents" }}
      data-hmi-source={note}
      data-style-classes={status}
      data-project={styleProject || undefined}
    >
      <p role="note" className="oip-hmi-sample-note" style={sampleNoteStyle}>
        Built-in sample project, not a saved project.
      </p>
      <Session
        project={merged}
        runtime={runtime}
        location={location}
        basePath={base}
        onLocation={(next) => {
          window.history.pushState({}, "", `${base}${next}`);
          onTarget({ kind: "sample", location: next });
        }}
      />
    </div>
  );
}

function Session({
  project,
  runtime,
  client,
  location,
  basePath,
  onLocation,
  scriptProject = null,
  onLogout,
  user,
}: {
  project: HmiProject;
  runtime: TagRuntime;
  client?: GatewayClient;
  location: string;
  basePath: string;
  onLocation: (location: string) => void;
  /** The saved gateway project whose script transforms and event scripts run on the gateway (D-5a); none for the sample. */
  scriptProject?: string | null;
  /** Signs the user out for a logout action (HMP-117); the built-in sample has no login and leaves it out. */
  onLogout?: () => void;
  /** The signed-in user for the session's `auth` properties (HMP-084); the built-in sample has none. */
  user?: SessionUser;
}) {
  const scriptHost = useGatewayScriptHost(scriptProject, runtime.bridge);
  useEffect(() => {
    runtime.bridge?.announce(scriptProject ?? project.name, location, [location]);
  }, [runtime, scriptProject, project.name, location]);
  const querySource = useMemo(() => (client ? gatewayQuerySource(client, scriptProject ?? undefined) : undefined), [client, scriptProject]);
  const [session, setSession] = useState<SessionModel>(() =>
    createSession({
      id: "hmi",
      theme: project.sessionTheme || initialTheme(),
      locale: project.sessionLocale || "en-US",
      gatewayTimeZone: project.gatewayTimeZone,
      device: detectDevice("browser"),
      // `?touchscreen=on` turns touchscreen mode (on-screen keyboard, HMV-126) on for this session.
      custom: typeof window !== "undefined" && ["on", "1", "true"].includes(new URLSearchParams(window.location.search).get("touchscreen") ?? "") ? { touchscreen: true } : {},
      gateway: { connected: false },
      auth: user ? { authenticated: true, userName: user.userName, roles: user.roles } : { authenticated: false, userName: null, roles: [] },
    }),
  );
  return (
    <PageSession
      project={project}
      registry={registry}
      transport={runtime.transport}
      session={session}
      location={location}
      basePath={basePath}
      scriptHost={scriptHost}
      querySource={querySource}
      viewportWidth={typeof window === "undefined" ? 1280 : window.innerWidth}
      onLocationChange={onLocation}
      onSessionPatch={(patch) =>
        setSession((current) => ({
          ...current,
          ...patch,
          custom: patch.custom ? { ...current.custom, ...patch.custom } : current.custom,
        }))
      }
      onConnectionChange={(connected) => setSession((current) => ({ ...current, gateway: { connected } }))}
      onLogout={onLogout}
    />
  );
}

function ProjectList({ projects }: { projects: string[] }) {
  const runnable = projects.filter(validProjectName);
  return (
    <section className="oip-gate" data-hmi-state="choose" aria-label="Projects">
      <h1>Choose a project</h1>
      {runnable.length === 0 ? (
        <p>
          This gateway has no saved projects yet. Save one from the <a href="/studio">Studio</a>.
        </p>
      ) : (
        <ul>
          {runnable.map((name) => (
            <li key={name}>
              <a href={`${HMI_BASE}/${encodeURIComponent(name)}/`}>{name}</a>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Notice({ status, children, attributes }: { status: string; children: ReactNode; attributes?: Record<string, string> }) {
  return (
    <div className="oip-gate" data-hmi-state={status} role={status === "connecting" ? "status" : "alert"} {...attributes}>
      {children}
    </div>
  );
}

const sampleNoteStyle = {
  position: "fixed",
  right: 8,
  bottom: 8,
  zIndex: 1000,
  margin: 0,
  padding: "2px 8px",
  borderRadius: 4,
  fontSize: 12,
  background: "rgba(28, 36, 48, 0.85)",
  color: "#fff",
  pointerEvents: "none",
} as const;

const bannerStyle = { margin: 0, padding: "4px 8px", fontSize: 12, background: "#fff4d6", color: "#4a3500" } as const;

/** `?theme=nightwatch` on the first URL picks the session's starting theme. */
function initialTheme(): string {
  const requested = new URLSearchParams(window.location.search).get("theme");
  return requested && themeNames.includes(requested) ? requested : "nightwatch";
}

function pathOf(location: string): string {
  const query = location.indexOf("?");
  return query < 0 ? location : location.slice(0, query);
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
