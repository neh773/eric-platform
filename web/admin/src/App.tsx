import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiClient, ApiError, type HealthReport, type LoginProvider, type SessionState } from "./api";
import { AdminPage, installAdminPages } from "./pages";
import { matchResources, matchSections } from "./search";
import { ADMIN_SECTIONS, resourceHref, routeFromPath, sectionPath, sectionsByGroup, type ConfigHit } from "./sections";
import { BetaNotice } from "./betaNotice";

const APPEARANCE_KEY = "oip-appearance";

function readAppearance(): "dark" | "light" {
  try {
    return localStorage.getItem(APPEARANCE_KEY) === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

/** Applies Dark or Light. Storage is written only when the person chooses, so a visit does not create a key. */
function chooseAppearance(appearance: "dark" | "light") {
  document.documentElement.dataset.oipTheme = appearance;
  try {
    localStorage.setItem(APPEARANCE_KEY, appearance);
  } catch {
    // Private mode: the choice lasts for this page view.
  }
}

function ThemeSwitcher() {
  const [appearance, setAppearance] = useState<"dark" | "light">(readAppearance);
  function choose(next: "dark" | "light") {
    setAppearance(next);
    chooseAppearance(next);
  }
  return (
    <div className="theme-switch" role="group" aria-label="Theme">
      <button type="button" aria-pressed={appearance === "dark"} onClick={() => choose("dark")}>
        Dark
      </button>
      <button type="button" aria-pressed={appearance === "light"} onClick={() => choose("light")}>
        Light
      </button>
    </div>
  );
}

installAdminPages();

export function App() {
  const [href, setHref] = useState(() => window.location.pathname + window.location.search);
  const [session, setSession] = useState<SessionState | null>(null);
  const [booting, setBooting] = useState(true);
  // A refused provider login lands on /admin/login?error=idp. The boot-time session check replaces the URL, so the
  // flag is read once here, before that happens.
  const [providerRefused] = useState(() => new URLSearchParams(window.location.search).get("error") === "idp");
  const clientRef = useRef<ApiClient | null>(null);
  if (!clientRef.current) {
    clientRef.current = new ApiClient({
      onUnauthorized: () => {
        setSession(null);
        visit("/admin/login");
      },
    });
  }
  const client = clientRef.current;

  function visit(to: string) {
    const current = window.location.pathname + window.location.search;
    if (current !== to) window.history.pushState(null, "", to);
    setHref(window.location.pathname + window.location.search);
  }

  useEffect(() => {
    const onPop = () => setHref(window.location.pathname + window.location.search);
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  useEffect(() => {
    let cancelled = false;
    client
      .session()
      .then((next) => {
        if (!cancelled) setSession(next);
      })
      .catch(() => {
        if (!cancelled) setSession(null);
      })
      .finally(() => {
        if (!cancelled) setBooting(false);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  useEffect(() => {
    if (booting) return;
    const route = routeFromPath(window.location.pathname);
    if (!session && route.kind !== "login") visit("/admin/login");
    if (session && route.kind === "login") visit("/admin/overview");
  }, [booting, session, href]);

  if (booting) {
    return <p className="boot">Checking the session.</p>;
  }

  const route = routeFromPath(window.location.pathname);
  if (!session || route.kind === "login") {
    return (
      <Login
        client={client}
        providerRefused={providerRefused}
        onSignedIn={(next) => {
          setSession(next);
          visit("/admin/overview");
        }}
      />
    );
  }

  return <Shell client={client} session={session} sectionId={route.section.id} onVisit={visit} onLoggedOut={() => setSession(null)} />;
}

/**
 * The sign-in page: a username and password for the internal user source, and one button per enabled identity
 * provider (SEC-013). A provider button is a plain link to the gateway's login start; the provider sends the browser
 * back to the gateway, which sets the session and returns here. A refused provider login comes back with `?error=idp`.
 * Doc source: FEATURES SEC-013.
 */
export function Login({
  client,
  onSignedIn,
  providerRefused = false,
}: {
  client: ApiClient;
  onSignedIn: (session: SessionState) => void;
  providerRefused?: boolean;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(() =>
    providerRefused || new URLSearchParams(window.location.search).get("error") === "idp"
      ? "Sign-in through the identity provider was refused. Try again or contact the gateway administrator."
      : null,
  );
  const [pending, setPending] = useState(false);
  const [providers, setProviders] = useState<LoginProvider[]>([]);
  const [setup, setSetup] = useState<{ needsAdmin: boolean; fromThisMachine: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    client
      .setupStatus()
      .then((next) => {
        if (!cancelled) setSetup(next);
      })
      .catch(() => {
        if (!cancelled) setSetup(null);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  useEffect(() => {
    let cancelled = false;
    client
      .loginProviders()
      .then((next) => {
        if (!cancelled) setProviders(next);
      })
      .catch(() => {
        if (!cancelled) setProviders([]);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await client.login(username.trim(), password);
      onSignedIn(await client.session());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sign-in failed");
    } finally {
      setPassword("");
      setPending(false);
    }
  }

  if (setup?.needsAdmin) {
    return <FirstRun client={client} fromThisMachine={setup.fromThisMachine} onSignedIn={onSignedIn} />;
  }

  return (
    <div className="gate">
      <Banners client={client} />
      <form className="gate-card" onSubmit={submit}>
        <p className="mark">ERIC</p>
        <ThemeSwitcher />
        <h1>Sign in</h1>
        <p className="lede">Gateway administration. The session cookie stays on the gateway; this page does not store the password.</p>
        <label>
          Username
          <input name="username" autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} required />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>
        {error ? <p role="alert">{error}</p> : null}
        <button type="submit" disabled={pending}>
          Sign in
        </button>
        {providers.length > 0 ? (
          <nav className="provider-logins" aria-label="Identity providers">
            {providers.map((provider) => (
              <a
                key={provider.name}
                className="button-link"
                href={`${provider.loginPath}?returnTo=${encodeURIComponent("/admin/overview")}`}
              >
                Sign in with {provider.displayName}
              </a>
            ))}
          </nav>
        ) : null}
      </form>
    </div>
  );
}

/**
 * First run (0.1.1): a gateway with no users asks for the administrator account here instead of showing a sign-in
 * that nobody can pass. The gateway accepts it only from a browser on the gateway machine, and only once.
 */
export function FirstRun({
  client,
  fromThisMachine,
  onSignedIn,
}: {
  client: ApiClient;
  fromThisMachine: boolean;
  onSignedIn: (session: SessionState) => void;
}) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (password !== confirm) {
      setError("The two passwords do not match.");
      return;
    }
    setPending(true);
    setError(null);
    try {
      await client.createFirstAdmin(username.trim(), password);
      onSignedIn(await client.session());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "The administrator was not created");
    } finally {
      setPassword("");
      setConfirm("");
      setPending(false);
    }
  }

  return (
    <div className="gate">
      <Banners client={client} />
      <form className="gate-card" onSubmit={submit} aria-label="Create the administrator">
        <p className="mark">ERIC</p>
        <ThemeSwitcher />
        <h1>Create administrator account</h1>
        <p className="lede">
          This gateway has no users yet. Create the administrator account. You will be signed in as this user, and you can add
          more users later under Users and roles.
        </p>
        {fromThisMachine ? null : (
          <p role="alert">
            Open this page in a browser on the gateway machine (http://127.0.0.1:8090/admin) to create the administrator, or set
            ERIC_ADMIN_PASSWORD before the first start.
          </p>
        )}
        <label>
          Administrator username
          <input name="username" autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} required />
        </label>
        <label>
          New password
          <input
            name="new-password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>
        <label>
          Confirm password
          <input
            name="confirm-password"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            required
          />
        </label>
        {error ? <p role="alert">{error}</p> : null}
        <button type="submit" disabled={pending || !fromThisMachine}>
          Create administrator
        </button>
      </form>
    </div>
  );
}

function Shell({
  client,
  session,
  sectionId,
  onVisit,
  onLoggedOut,
}: {
  client: ApiClient;
  session: SessionState;
  sectionId: string;
  onVisit: (to: string) => void;
  onLoggedOut: () => void;
}) {
  const section = ADMIN_SECTIONS.find((item) => item.id === sectionId) ?? ADMIN_SECTIONS[0]!;
  const [leaving, setLeaving] = useState(false);

  async function logout() {
    setLeaving(true);
    let endSession: string | null = null;
    try {
      endSession = await client.logout();
    } catch {
      // The cookie may already be gone. Drop the in-memory session either way.
    } finally {
      onLoggedOut();
      // An identity-provider session also ends at the provider, which sends the browser back to the sign-in page.
      if (endSession) window.location.assign(endSession);
      else onVisit("/admin/login");
      setLeaving(false);
    }
  }

  return (
    <div className="shell" data-testid="admin-shell">
      <aside>
        <p className="mark">ERIC <span>Gateway</span></p>
        <nav aria-label="Gateway sections">
          {sectionsByGroup().map(({ group, sections }) => (
            <div role="group" aria-labelledby={`nav-group-${group.id}`} className="nav-group" key={group.id} data-group={group.id}>
              <p className="nav-group-label" id={`nav-group-${group.id}`}>
                {group.label}
              </p>
              {sections.map((item) => (
                <a
                  key={item.id}
                  href={sectionPath(item.id)}
                  aria-current={item.id === section.id ? "page" : undefined}
                  onClick={(event) => {
                    event.preventDefault();
                    onVisit(sectionPath(item.id));
                  }}
                >
                  {item.label}
                </a>
              ))}
            </div>
          ))}
        </nav>
      </aside>
      <div className="workspace">
        <header>
          <GlobalSearch client={client} onVisit={onVisit} />
          <ThemeSwitcher />
          <p className="who">Signed in as {session.username}</p>
          <button type="button" className="quiet" onClick={() => void logout()} disabled={leaving}>
            Log out
          </button>
        </header>
        <Banners client={client} />
        <main>
          <AdminPage section={section} client={client} session={session} search={searchFrom(window.location.search)} onVisit={onVisit} />
        </main>
      </div>
    </div>
  );
}

function searchFrom(search: string): string {
  return search.startsWith("?") ? search : "";
}

function Banners({ client }: { client: ApiClient }) {
  const [health, setHealth] = useState<HealthReport | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    client
      .health()
      .then((report) => {
        if (!cancelled) setHealth(report);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  return (
    <div className="banners" data-testid="status-banners" aria-label="Gateway status">
      <BetaNotice />
      {failed ? <p role="status" className="banner banner-warn">Gateway health is unavailable.</p> : null}
      {health ? (
        <p role="status" className="banner banner-ok" data-status={health.status}>
          Gateway {health.status}
          {health.profile ? ` · ${health.profile}` : ""}
        </p>
      ) : null}
      {health && health.warnings.length > 1 ? (
        <p role="status" className="banner banner-warn">
          {health.warnings.length} things need attention. {health.warnings.join(" ")}
        </p>
      ) : (
        health?.warnings.map((warning) => (
          <p role="status" className="banner banner-warn" key={warning}>
            {warning}
          </p>
        ))
      )}
    </div>
  );
}

function GlobalSearch({ client, onVisit }: { client: ApiClient; onVisit: (to: string) => void }) {
  const [query, setQuery] = useState("");
  const [resources, setResources] = useState<ConfigHit[]>([]);

  useEffect(() => {
    let cancelled = false;
    client
      .configResources()
      .then((hits) => {
        if (!cancelled) setResources(hits);
      })
      .catch(() => {
        if (!cancelled) setResources([]);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  const sections = matchSections(query);
  const hits = matchResources(query, resources);
  const open = query.trim().length > 0;

  function openHit(to: string) {
    setQuery("");
    onVisit(to);
  }

  return (
    <div className="search">
      <input
        type="search"
        aria-label="Search the gateway"
        placeholder="Search sections and config"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />
      {open ? (
        <div role="listbox" aria-label="Search results" data-testid="search-results">
          {sections.map((section) => (
            <button type="button" role="option" key={section.id} onClick={() => openHit(sectionPath(section.id))}>
              {section.label}
            </button>
          ))}
          {hits.map((hit) => (
            <button
              type="button"
              role="option"
              key={`${hit.module}/${hit.scope}/${hit.kind}/${hit.name}`}
              onClick={() => openHit(resourceHref(hit))}
            >
              {hit.kind} {hit.name}
            </button>
          ))}
          {sections.length === 0 && hits.length === 0 ? <p className="empty">No matches</p> : null}
        </div>
      ) : null}
    </div>
  );
}
