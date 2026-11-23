import { CoordinateScene, type SceneChild } from "./coordinateScene";
import { variantStyle } from "./componentModel";
import { mergeStyle } from "./styleSubset";
import { createContext, memo, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent, ReactNode } from "react";
import { bindMethodArgs, MAX_METHOD_DEPTH, resolveMethodArgs } from "./methods";
import { isCallMethod, isLogout, isMessage, isNavigation, isPopup, isScript, isTagWrite, planActions } from "./actions";
import { eventPayload } from "./eventPayload";
import {
  applyBindings,
  bindingForProp,
  collectSubscriptions,
  evaluateBinding,
  expressionPollMs,
  formatQueryResult,
  historyAggregateUrl,
  historyRequests,
  httpRequests,
  pickJsonPath,
  parseHistoryWindows,
  pagePath,
  qualityLabel,
  qualityLevel,
  queryRequests,
  resolveContext,
  resolveTagPath,
  sessionPath,
  setPath,
  type BindingExtras,
  type NamedQuerySource,
  type PageFields,
  type QueryCacheEntry,
  type ResolveContext,
  type DockAction,
  type SessionAction,
  type MethodAction,
  type ScriptHost,
  type SessionFields,
} from "./bindings";
import type { TranslationCatalog } from "./translate";
import {
  DEFAULT_COLUMN_BREAKPOINTS,
  activeBreakpoint,
  breakpointChild,
  breakpointSize,
  clampSplit,
  resolveSplitPosition,
  splitBarStyle,
  splitContainerStyle,
  splitPaneStyle,
  columnChildStyle,
  columnContainerStyle,
  coordinateChildStyle,
  coordinateContainerStyle,
  flexChildStyle,
  flexContainerStyle,
  placementFor,
  styleToCss,
} from "./layout";
import { walkNodes } from "./model";
import { clampPopup, popupShowsTitleBar, reducePopups, withViewParams, type PopupModel } from "./popups";
import { isLocaleTag, isTimeZoneId, sessionForScripts, type SessionModel } from "./session";
import { nodeStyleClassName, resolveStyle, styleClassCss } from "./styleClasses";
import type { CustomThemeSet } from "./types";
import { TagClient } from "./tags";
import type { StringTransport, TagUpdate } from "./tags";
import { themeBrand, themeStyle } from "./theme";
import type { ThemeBrand } from "./types";
import type {
  Binding,
  ComponentNode,
  ComponentRegistry,
  ContainerNode,
  MessageScope,
  NavigationAction,
  PopupAction,
  StyleClassSet,
  TagValue,
  ViewDocument,
  ViewNode,
} from "./types";

export interface ViewRendererProps {
  view: ViewDocument;
  registry: ComponentRegistry;
  transport: StringTransport;
  session: SessionModel;
  /** Overrides measured width for column breakpoints and dock auto display. Tests pass this. */
  viewportWidth?: number;
  onConnectionChange?: (connected: boolean) => void;
  views?: readonly ViewDocument[];
  paramOverrides?: Record<string, unknown>;
  scriptHost?: ScriptHost;
  querySource?: NamedQuerySource;
  /** Terms by locale for the `translate` expression function (HMP-125). */
  translations?: TranslationCatalog;
  onNavigate?: (action: NavigationAction) => void;
  /** Browser URL for a session page path; components use it for `href`. Defaults to the path itself. */
  pageHref?: (path: string) => string;
  onSessionPatch?: (patch: SessionPatch) => void;
  page?: PageFields;
  onPageTitle?: (title: string) => void;
  /** Project style classes available to every node in this session. A new set re-renders
   * the session with the new result (styleClasses.ts). */
  styleClasses?: StyleClassSet;
  /** Project themes. The session theme name may be one of these, or a built-in name. */
  themes?: CustomThemeSet;
  /**
   * Design mode (Designer canvas): bindings stay live, but event actions, navigation, popups, tag and
   * property writes and all pointer and keyboard input to the rendered components are suppressed.
   * The rendered tree is `inert`, so an editor overlay on top of it receives the pointer instead.
   */
  designMode?: boolean;
  /** Paths of project views the session's user may not open (HMP-092). Each shows a refusal wherever it is used. */
  deniedViews?: readonly string[];
  /** Runs a logout action (HMP-117): the host signs the user out and returns the session to sign-in. */
  onLogout?: () => void;
  /** A session script asked a dock to open, close, or toggle. */
  onDock?: (id: string, mode: DockAction["mode"]) => void;
}

/** A rendered view's message handlers, as the session's message bus calls them (HMP-117). */
interface MessageListener {
  receive(messageType: string, scope: MessageScope, payload: Record<string, unknown>, fromSameView: boolean): void;
}

export interface SessionPatch {
  theme?: string;
  locale?: string;
  timeZoneId?: string;
  custom?: Record<string, unknown>;
}

interface ScopeValue {
  writeTag: (path: string, value: TagValue) => void;
  retainPaths: (owner: string, paths: string[]) => void;
  releasePaths: (owner: string) => void;
  views: ReadonlyMap<string, ViewDocument>;
  deniedViews: ReadonlySet<string>;
  registry: ComponentRegistry;
  scriptHost?: ScriptHost;
  /** Bumped when an asynchronous script answer arrives, so bindings with script transforms evaluate again. */
  scriptVersion: number;
  refreshScripts: () => void;
  querySource?: NamedQuerySource;
  translations?: TranslationCatalog;
  session: SessionFields;
  patchSession: (patch: SessionPatch) => void;
  page?: PageFields;
  setPageTitle: (title: string) => void;
  navigate: (action: NavigationAction) => void;
  pageHref: (path: string) => string;
  openPopup: (action: PopupAction) => void;
  viewportWidth?: number;
  styleClasses?: StyleClassSet;
  designMode: boolean;
  /** Adds a view instance's message handlers; returns the removal. */
  listen: (owner: string, listener: MessageListener) => () => void;
  /** Delivers a message to every handler listening at its scope; `view` scope stays in the sending view. */
  sendMessage: (fromOwner: string, messageType: string, scope: MessageScope, payload: Record<string, unknown>) => void;
  logout: () => void;
  setDock: (id: string, mode: DockAction["mode"]) => void;
}

const Scope = createContext<ScopeValue | null>(null);

/**
 * The session's latest tag values. Kept out of [Scope] so a tag update reaches only what reads values: each
 * [ViewInstance], and through it the components whose bound paths changed (see [ComponentView]).
 */
const TagValues = createContext<ReadonlyMap<string, TagUpdate>>(new Map());

/** Paths of the views being rendered, outermost first. Each ViewInstance appends its own. */
const EmbedChain = createContext<readonly string[]>([]);

export function ViewRenderer(props: ViewRendererProps) {
  const parent = useContext(Scope);
  if (parent) {
    return <ViewInstance view={props.view} paramOverrides={props.paramOverrides} />;
  }
  return (
    <HmiRoot {...props}>
      <ViewInstance view={props.view} paramOverrides={props.paramOverrides} />
    </HmiRoot>
  );
}

interface ViewInstanceProps {
  view: ViewDocument;
  paramOverrides?: Record<string, unknown>;
  onParamChange?: (name: string, value: unknown) => void;
  instanceKey?: string;
}

export function HmiRoot({
  children,
  transport,
  session,
  registry,
  viewportWidth,
  onConnectionChange,
  views,
  scriptHost,
  querySource,
  translations,
  onNavigate,
  pageHref,
  onSessionPatch,
  page,
  onPageTitle,
  styleClasses,
  themes,
  designMode = false,
  deniedViews,
  onLogout,
  onDock,
}: ViewRendererProps & { children: ReactNode }) {
  const listeners = useRef(new Map<string, MessageListener>());
  const onLogoutRef = useRef(onLogout);
  onLogoutRef.current = onLogout;
  const [values, setValues] = useState<Map<string, TagUpdate>>(() => new Map());
  const [sessionPatch, setSessionPatch] = useState<SessionPatch>({});
  const [popups, setPopups] = useState<PopupModel[]>([]);
  const [scriptVersion, setScriptVersion] = useState(0);
  const [writeRefusal, setWriteRefusal] = useState<string | null>(null);
  useEffect(() => {
    if (!writeRefusal) return undefined;
    const timer = window.setTimeout(() => setWriteRefusal(null), 8_000);
    return () => window.clearTimeout(timer);
  }, [writeRefusal]);
  const [viewport, setViewport] = useState({ width: 1280, height: 800 });
  const clientRef = useRef<TagClient | null>(null);
  const owners = useRef(new Map<string, string[]>());
  const unsub = useRef<() => void>(() => {});
  const ready = useRef(false);
  const subscribed = useRef("");
  const scheduled = useRef(false);
  const onConnectionChangeRef = useRef(onConnectionChange);
  onConnectionChangeRef.current = onConnectionChange;
  const frameRef = useRef<HTMLDivElement>(null);
  const pending = useRef(new Map<string, TagUpdate>());
  const flushTimer = useRef<FrameTimer>(IDLE_TIMER);

  function unionPaths(): string[] {
    const set = new Set<string>();
    for (const paths of owners.current.values()) for (const path of paths) if (path) set.add(path);
    return [...set].sort();
  }

  function resubscribe() {
    const client = clientRef.current;
    if (!client || !ready.current) return;
    const paths = unionPaths();
    const key = paths.join("\n");
    if (key === subscribed.current) return;
    subscribed.current = key;
    unsub.current();
    unsub.current = client.subscribe(paths, (updates) => {
      // Update coalescing (HMP-111): updates that arrive before the next frame are kept by path, the last one wins,
      // and the session renders them once.
      for (const update of updates) pending.current.set(update.path, update);
      scheduleFlush();
    });
  }

  function flushUpdates() {
    flushTimer.current.cancel();
    if (pending.current.size === 0) return;
    const batch = pending.current;
    pending.current = new Map();
    setValues((current) => {
      const next = new Map(current);
      for (const [path, update] of batch) next.set(path, update);
      return next;
    });
  }

  function scheduleFlush() {
    if (flushTimer.current.active) return;
    flushTimer.current = nextFrame(() => flushUpdates());
  }

  function schedule() {
    if (scheduled.current) return;
    scheduled.current = true;
    queueMicrotask(() => {
      scheduled.current = false;
      resubscribe();
    });
  }

  const retainPaths = (owner: string, paths: string[]) => {
    owners.current.set(owner, paths);
    schedule();
  };
  const releasePaths = (owner: string) => {
    owners.current.delete(owner);
    schedule();
  };

  useEffect(() => {
    const client = new TagClient(transport);
    clientRef.current = client;
    ready.current = false;
    subscribed.current = "";
    let cancelled = false;
    void client.whenWelcome().then(() => {
      if (cancelled) return;
      ready.current = true;
      onConnectionChangeRef.current?.(true);
      resubscribe();
    });
    return () => {
      cancelled = true;
      ready.current = false;
      flushTimer.current.cancel();
      pending.current = new Map();
      unsub.current();
      unsub.current = () => {};
      client.release();
      if (clientRef.current === client) clientRef.current = null;
      onConnectionChangeRef.current?.(false);
    };
  }, [transport]);

  useEffect(() => {
    if (!scriptHost?.subscribe) return;
    return scriptHost.subscribe(() => setScriptVersion((current) => current + 1));
  }, [scriptHost]);

  useEffect(() => {
    const element = frameRef.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect;
      if (!rect) return;
      setViewport({ width: Math.max(1, rect.width), height: Math.max(1, rect.height) });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const mergedSession = useMemo<SessionFields>(() => {
    return {
      id: session.id,
      theme: sessionPatch.theme ?? session.theme,
      locale: sessionPatch.locale ?? session.locale,
      timeZoneId: sessionPatch.timeZoneId ?? session.timeZoneId,
      auth: session.auth,
      device: session.device,
      gateway: session.gateway,
      custom: { ...session.custom, ...(sessionPatch.custom ?? {}) },
    };
  }, [session, sessionPatch]);
  // Scripts read the session as it is when they run (HMP-084).
  const sessionRef = useRef(mergedSession);
  sessionRef.current = mergedSession;
  useEffect(() => {
    scriptHost?.setSessionProvider?.(() => sessionForScripts(sessionRef.current));
  }, [scriptHost]);

  const viewMap = useMemo(() => {
    const map = new Map<string, ViewDocument>();
    for (const entry of views ?? []) map.set(entry.metadata.path, entry);
    return map;
  }, [views]);

  const deniedSet = useMemo(() => new Set(deniedViews ?? []), [deniedViews]);

  function patchSession(patch: SessionPatch) {
    setSessionPatch((current) => ({
      ...current,
      ...patch,
      custom: patch.custom ? { ...(current.custom ?? {}), ...patch.custom } : current.custom,
    }));
    onSessionPatch?.(patch);
  }

  function openPopup(action: PopupAction) {
    if (designMode) return;
    const box = frameRef.current?.getBoundingClientRect();
    const size = { width: box?.width || viewport.width, height: box?.height || viewport.height };
    setPopups((current) => reducePopups(current, action, size));
  }

  // The scope changes only when what it carries changes, not on a tag update, so components that did not read a
  // changed tag are not rendered again (PRF-011: 50 sessions of 300 tag-bound components).
  const scope = useMemo<ScopeValue>(
    () => ({
      writeTag(path, value) {
        if (designMode) return;
        // A refused write (role, read-only tag, device) is shown, not dropped.
        clientRef.current?.write(path, value).catch((err: unknown) => {
          const reason = err instanceof Error ? err.message : String(err);
          if (reason === "closed") return;
          setWriteRefusal(`Not written to ${path}: ${reason}`);
        });
      },
      retainPaths,
      releasePaths,
      views: viewMap,
      deniedViews: deniedSet,
      registry,
      scriptHost,
      scriptVersion,
      refreshScripts() {
        setScriptVersion((version) => version + 1);
      },
      querySource,
      translations,
      session: mergedSession,
      patchSession,
      page,
      setPageTitle(title) {
        if (designMode) return;
        onPageTitle?.(title);
      },
      navigate(action) {
        if (designMode) return;
        onNavigate?.(action);
      },
      pageHref: pageHref ?? samePath,
      openPopup,
      viewportWidth,
      styleClasses,
      designMode,
      listen(owner, listener) {
        listeners.current.set(owner, listener);
        return () => {
          if (listeners.current.get(owner) === listener) listeners.current.delete(owner);
        };
      },
      sendMessage(fromOwner, messageType, scope, payload) {
        if (designMode) return;
        // This browser page is the whole session here: page and session scope reach every view it shows.
        for (const [owner, listener] of [...listeners.current]) {
          if (scope === "view" && owner !== fromOwner) continue;
          try {
            listener.receive(messageType, scope, payload, owner === fromOwner);
          } catch {
            // One failing handler does not stop the others.
          }
        }
      },
      logout() {
        if (designMode) return;
        onLogoutRef.current?.();
      },
      setDock(id, mode) {
        if (designMode) return;
        onDock?.(id, mode);
      },
    }),
    // retainPaths, releasePaths, patchSession and openPopup only read refs, setters and the values listed here.
    [viewMap, deniedSet, registry, scriptHost, scriptVersion, querySource, translations, mergedSession, page, onPageTitle, onNavigate, onSessionPatch, pageHref, viewport, viewportWidth, styleClasses, designMode, onDock],
  );

  const stateCss = styleClassCss(styleClasses);
  const brand = themeBrand(mergedSession.theme, themes);
  return (
    <Scope.Provider value={scope}>
      <TagValues.Provider value={values}>
      {writeRefusal ? (
        <div className="oip-write-refused" role="alert" style={{ position: "fixed", right: 16, bottom: 16, zIndex: 1000, padding: "8px 12px", borderRadius: 6, background: "var(--oip-alarm-1, #b3261e)", color: "#fff", maxWidth: 420 }}>
          {writeRefusal}
          <button type="button" aria-label="Dismiss" style={{ marginLeft: 8 }} onClick={() => setWriteRefusal(null)}>×</button>
        </div>
      ) : null}
      <div
        ref={frameRef}
        className="oip-session"
        data-theme={mergedSession.theme}
        data-oip-touch={mergedSession.custom.touchscreen === true ? "true" : undefined}
        data-session-id={session.id}
        data-locale={mergedSession.locale}
        data-design-mode={designMode ? "true" : undefined}
        inert={designMode}
        style={designMode ? { ...themeStyle(mergedSession.theme, themes), pointerEvents: "none" } : themeStyle(mergedSession.theme, themes)}
      >
        {stateCss ? <style data-style-states="true">{stateCss}</style> : null}
        {brand && !designMode ? <BrandBar brand={brand} /> : null}
        {children}
        <PopupLayer popups={popups} setPopups={setPopups} viewport={viewport} />
      </div>
      </TagValues.Provider>
    </Scope.Provider>
  );
}

/** Brand bar (HMP-085): the logo, name and graphic of the session theme's brand, above the page. */
function BrandBar({ brand }: { brand: ThemeBrand }) {
  return (
    <header className="oip-brand" data-brand={brand.name ?? ""}>
      {brand.graphic ? <img className="oip-brand-graphic" src={brand.graphic} alt="" aria-hidden="true" referrerPolicy="no-referrer" /> : null}
      {brand.logo ? <img className="oip-brand-logo" src={brand.logo} alt={brand.logoAlt ?? brand.name ?? "Logo"} referrerPolicy="no-referrer" /> : null}
      {brand.name ? <span className="oip-brand-name">{brand.name}</span> : null}
    </header>
  );
}

/**
 * Expression binding poll (HMP-118): a counter that steps every `intervalMs` (no timer when 0). A component or view
 * that reads it re-renders on each step, so its polled expression bindings re-evaluate.
 */
export function usePollTick(intervalMs: number): number {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (intervalMs <= 0) return;
    const timer = window.setInterval(() => setTick((n) => n + 1), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return tick;
}

export function ViewInstance({ view, paramOverrides, onParamChange, instanceKey }: ViewInstanceProps) {
  const scope = useContext(Scope);
  const values = useContext(TagValues);
  if (!scope) throw new Error("ViewInstance requires a session");
  const owner = useId();
  // Every rendered view (page, dock, popup or embedded) adds its own path, so an embed of any ancestor is refused.
  const parentChain = useContext(EmbedChain);
  const chain = useMemo(() => [...parentChain, view.metadata.path], [parentChain, view.metadata.path]);
  const [overlay, setOverlay] = useState<Map<string, Record<string, unknown>>>(() => new Map());
  const [customOverrides, setCustomOverrides] = useState<Record<string, unknown>>({});
  const [params, setParams] = useState<Record<string, unknown>>(() => mergeParams(view, paramOverrides));
  const [queries, setQueries] = useState<Map<string, QueryCacheEntry>>(() => new Map());
  const [histories, setHistories] = useState<Map<string, QueryCacheEntry>>(() => new Map());
  const overrideKey = JSON.stringify(paramOverrides ?? {});
  useEffect(() => {
    // An equal result keeps the current object, so a view edit (a Designer drag frame) does not re-render every binding.
    const next = mergeParams(view, paramOverrides);
    setParams((current) => (shallowEqual(current, next) ? current : next));
  }, [view, overrideKey]);

  const base = useMemo(() => resolveContext(view), [view]);
  const ctx = useMemo<ResolveContext>(() => {
    return { ...base, params, custom: { ...base.custom, ...customOverrides }, liveProps: overlay };
  }, [base, params, customOverrides, overlay]);

  const extras = useMemo<BindingExtras>(() => {
    return {
      queries,
      histories,
      session: scope.session,
      page: scope.page,
      scriptHost: scope.scriptHost,
      viewPath: view.metadata.path,
      scriptVersion: scope.scriptVersion,
      locale: scope.session.locale,
      translations: scope.translations,
      hasQuerySource: Boolean(scope.querySource),
    };
  }, [queries, histories, scope.session, scope.page, scope.scriptHost, scope.scriptVersion, scope.querySource, scope.translations, view.metadata.path]);

  // Polled expression bindings on the view's own custom properties and params (HMP-118).
  const viewPollMs = useMemo(
    () => expressionPollMs([...Object.values(view.spec.custom).map((prop) => prop.binding), ...Object.values(view.spec.params).map((param) => param.binding)]),
    [view],
  );
  const viewPollTick = usePollTick(viewPollMs);

  const custom = useMemo(() => {
    void viewPollTick;
    const entries = Object.entries(view.spec.custom).filter(([, prop]) => prop.binding);
    if (entries.length === 0) return ctx.custom;
    const next = { ...ctx.custom };
    for (const [key, prop] of entries) {
      if (!prop.binding) continue;
      const resolved = evaluateBinding(prop.binding, key, "custom", values, { ...ctx, custom: next }, extras);
      if (resolved.applied) next[key] = resolved.value;
    }
    return next;
  }, [view, ctx, values, extras, viewPollTick]);

  const boundParams = useMemo(() => {
    void viewPollTick;
    const entries = Object.entries(view.spec.params).filter(([, prop]) => prop.binding);
    if (entries.length === 0) return params;
    const next = { ...params };
    for (const [key, prop] of entries) {
      if (!prop.binding) continue;
      const resolved = evaluateBinding(prop.binding, key, "param", values, { ...ctx, params: next }, extras);
      if (resolved.applied) next[key] = resolved.value;
    }
    return next;
  }, [view, params, ctx, values, extras, viewPollTick]);

  const liveCtx = useMemo<ResolveContext>(() => ({ ...ctx, custom, params: boundParams }), [ctx, custom, boundParams]);
  const hasQuery = useMemo(() => containsQuery(view), [view]);
  const hasHistory = useMemo(() => containsHistory(view), [view]);
  const hasHttp = useMemo(() => containsBindingKind(view, "http"), [view]);
  const paths = useMemo(
    () => collectSubscriptions(view, { params: liveCtx.params, custom: liveCtx.custom, liveProps: liveCtx.liveProps }),
    [view, liveCtx.params, liveCtx.custom, liveCtx.liveProps],
  );
  const pathKey = paths.join("\n");
  const pathsRef = useRef(paths);
  pathsRef.current = paths;
  const scopeRef = useRef(scope);
  scopeRef.current = scope;

  useLayoutEffect(() => {
    scopeRef.current.retainPaths(owner, pathsRef.current);
    return () => scopeRef.current.releasePaths(owner);
  }, [owner, pathKey]);

  const requestKey = useMemo(() => {
    if (!hasQuery) return "";
    return JSON.stringify(queryRequests(view, liveCtx, values, extras).map((request) => ({
      key: request.key,
      path: request.path,
      parameters: request.parameters,
      returnFormat: request.returnFormat,
      pollMs: request.pollMs,
    })));
  }, [hasQuery, view, liveCtx, values, extras]);

  useEffect(() => {
    const source = scope.querySource;
    if (!source) return;
    let cancelled = false;
    const timers: number[] = [];
    const requests = queryRequests(view, liveCtx, values, extras);
    const run = (request: (typeof requests)[number]) => {
      Promise.resolve()
        .then(() => source.execute(request))
        .then(
          (data) => {
            if (cancelled) return;
            setQueries((current) => {
              const next = new Map(current);
              next.set(request.key, { ok: true, value: formatQueryResult(data, request.returnFormat) });
              return next;
            });
          },
          () => {
            if (cancelled) return;
            setQueries((current) => {
              const next = new Map(current);
              next.set(request.key, { ok: false, value: null });
              return next;
            });
          },
        );
    };
    for (const request of requests) {
      run(request);
      if (request.pollMs > 0) timers.push(window.setInterval(() => run(request), request.pollMs));
    }
    return () => {
      cancelled = true;
      for (const timer of timers) window.clearInterval(timer);
    };
  }, [requestKey, scope.querySource]);

  // HTTP bindings (HMP-114): the gateway fetches; an error or a missing `jsonPath` is the bad-quality state.
  const httpKey = useMemo(() => (hasHttp ? JSON.stringify(httpRequests(view)) : ""), [hasHttp, view]);
  useEffect(() => {
    const source = scope.querySource;
    if (!httpKey || !source) return undefined;
    let cancelled = false;
    const timers: number[] = [];
    const store = (key: string, entry: QueryCacheEntry) => {
      if (cancelled) return;
      setQueries((current) => new Map(current).set(key, entry));
    };
    for (const request of httpRequests(view)) {
      const run = () => {
        const fetcher = source.fetchHttp;
        if (!fetcher) return store(request.key, { ok: false, value: null });
        Promise.resolve()
          .then(() => fetcher.call(source, { source: request.source, path: request.path, query: request.query }))
          .then(
            (result) => {
              const picked = result.ok ? pickJsonPath(result.body, request.jsonPath) : undefined;
              store(request.key, picked === undefined ? { ok: false, value: null } : { ok: true, value: picked });
            },
            () => store(request.key, { ok: false, value: null }),
          );
      };
      run();
      if (request.pollMs > 0) timers.push(window.setInterval(run, request.pollMs));
    }
    return () => {
      cancelled = true;
      for (const timer of timers) window.clearInterval(timer);
    };
  }, [httpKey, scope.querySource]);

  const historyKey = useMemo(() => (hasHistory ? JSON.stringify(historyRequests(view)) : ""), [hasHistory, view]);
  useEffect(() => {
    if (!historyKey) return undefined;
    let cancelled = false;
    const timers: number[] = [];
    const requests = historyRequests(view);
    const run = (request: (typeof requests)[number]) => {
      const url = historyAggregateUrl(request, Date.now());
      fetch(url, { credentials: "same-origin" })
        .then(async (res) => {
          if (!res.ok) throw new Error(String(res.status));
          return res.json() as Promise<unknown>;
        })
        .then(
          (body) => {
            if (cancelled) return;
            const parsed = parseHistoryWindows(body);
            setHistories((current) => {
              const next = new Map(current);
              next.set(request.key, parsed.ok ? { ok: true, value: parsed.value } : { ok: false, value: null });
              return next;
            });
          },
          () => {
            if (cancelled) return;
            setHistories((current) => {
              const next = new Map(current);
              next.set(request.key, { ok: false, value: null });
              return next;
            });
          },
        );
    };
    for (const request of requests) {
      run(request);
      if (request.pollMs > 0) timers.push(window.setInterval(() => run(request), request.pollMs));
    }
    return () => {
      cancelled = true;
      for (const timer of timers) window.clearInterval(timer);
    };
  }, [historyKey, view]);

  // A gateway event script answers later: its navigations are dropped once this view unmounts or the page changes.
  const mountedRef = useRef(false);
  const methodRef = useRef<(method: MethodAction) => void>(() => {});
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);
  const runEvent = (
    events: ViewDocument["spec"]["events"],
    eventName: string,
    nodeId: string,
    domEvent?: { stopPropagation(): void },
    payloadOverride?: unknown,
  ) => {
    if (scope.designMode) return;
    const pageAt = scopeRef.current.page?.path;
    const stillCurrent = () => mountedRef.current && scopeRef.current.page?.path === pageAt;
    // Read now: currentTarget is only set while the DOM event is being handled.
    const payload = payloadOverride !== undefined ? payloadOverride : domEvent ? eventPayload(domEvent) : undefined;
    runPlan(events, eventName, domEvent, (action) => executeAction(action, scope, liveCtx, view, nodeId, eventName, stillCurrent, payload, owner, methodRef));
  };
  const runEventRef = useRef(runEvent);
  runEventRef.current = runEvent;

  // Message handlers of this view's components (HMP-117).
  const handlerNodes = useMemo(() => {
    const found: ComponentNode[] = [];
    walkNodes(view.spec.root, (node) => {
      if (node.kind === "component" && (node.messageHandlers?.length ?? 0) > 0) found.push(node);
    });
    return found;
  }, [view]);
  const liveRef = useRef(liveCtx);
  liveRef.current = liveCtx;
  useEffect(() => {
    if (handlerNodes.length === 0) return undefined;
    const listener: MessageListener = {
      receive(messageType, scope, payload) {
        const current = scopeRef.current;
        if (current.designMode) return;
        const pageAt = current.page?.path;
        const stillCurrent = () => mountedRef.current && scopeRef.current.page?.path === pageAt;
        for (const node of handlerNodes) {
          for (const handler of node.messageHandlers ?? []) {
            if (handler.messageType !== messageType || !handler.scopes.includes(scope) || handler.script.enabled === false) continue;
            executeAction(handler.script, current, liveRef.current, view, node.id, messageType, stillCurrent, payload, owner);
          }
        }
      },
    };
    return scopeRef.current.listen(owner, listener);
  }, [handlerNodes, owner, view]);

  useEffect(() => {
    runEventRef.current(view.spec.events, "onStartup", view.spec.root.id);
    return () => runEventRef.current(view.spec.events, "onShutdown", view.spec.root.id);
  }, [view]);

  function commit(nodeId: string, prop: string, value: unknown, fromScript = false) {
    // Design mode: components keep showing their bound values; nothing they emit is written anywhere.
    if (scope?.designMode) return;
    setOverlay((current) => {
      const next = new Map(current);
      const props = { ...(next.get(nodeId) ?? {}) };
      setPath(props, prop, value);
      next.set(nodeId, props);
      return next;
    });
    const node = liveCtx.nodes.get(nodeId);
    if (node?.kind === "component" && !fromScript) {
      runEvent(node.events, "onPropertyChange", nodeId, undefined, { prop, value });
    }
    if (!node || node.kind !== "component") return;
    const binding = bindingForProp(node, prop);
    if (!scope || !binding || (binding.kind !== "tag" && binding.kind !== "property") || !binding.bidirectional) return;
    if (binding.kind === "tag") {
      if (!isTagValue(value)) return;
      const path = resolveTagPath(binding, liveCtx);
      if (path) scope.writeTag(path, value);
      return;
    }
    writeSource(binding, value);
  }

  function writeSource(binding: Extract<Binding, { kind: "property" }>, value: unknown) {
    if (!scope) return;
    const source = binding.source;
    if (source.scope === "prop") {
      setOverlay((current) => {
        const next = new Map(current);
        const props = { ...(next.get(source.nodeId) ?? {}) };
        setPath(props, source.prop, value);
        next.set(source.nodeId, props);
        return next;
      });
      return;
    }
    if (source.scope === "param") {
      setParams((current) => ({ ...current, [source.name]: value }));
      const direction = view.spec.params[source.name]?.direction ?? "input";
      if (direction !== "input") onParamChange?.(source.name, value);
      return;
    }
    if (source.scope === "custom") {
      setCustomOverrides((current) => ({ ...current, [source.name]: value }));
      return;
    }
    if (source.scope === "session") {
      const found = sessionPath(scope.session, source.path);
      if (!found.writable) return;
      if (source.path === "theme" && typeof value === "string") scope.patchSession({ theme: value });
      else if (source.path === "locale" && isLocaleTag(value)) scope.patchSession({ locale: value });
      else if (source.path === "timeZoneId" && isTimeZoneId(value)) scope.patchSession({ timeZoneId: value });
      else if (source.path.startsWith("custom.")) {
        scope.patchSession({ custom: { [source.path.slice("custom.".length)]: value } });
      }
      return;
    }
    const found = scope.page ? pagePath(scope.page, source.path) : undefined;
    if (found?.writable && source.path === "title" && typeof value === "string") scope.setPageTitle(value);
  }

  methodRef.current = (method) => {
    if (method.method === "refresh") scope.refreshScripts();
    else if (method.method === "setProp" && method.name) commit(method.nodeId, method.name, method.value, true);
    else if (method.method === "call" && method.name) callMethod(method.nodeId, method.name, method.value, method.depth ?? 0);
  };

  /**
   * Runs custom method [name] (HMV-114) of component [nodeId], or of the view when [nodeId] is the view's root id or the
   * component has no method of that name. The method's actions run in order with the arguments put into their values.
   */
  function callMethod(nodeId: string, name: string, given: unknown, depth: number) {
    if (!scope || scope.designMode || depth >= MAX_METHOD_DEPTH) return;
    const node = liveCtx.nodes.get(nodeId);
    const has = (map: Record<string, unknown> | undefined) => map !== undefined && Object.prototype.hasOwnProperty.call(map, name);
    const own = node?.kind === "component" && has(node.methods) ? node.methods![name] : undefined;
    const def = own ?? (has(view.spec.methods) ? view.spec.methods![name] : undefined);
    if (!def) return;
    const args = resolveMethodArgs(def, given);
    const pageAt = scopeRef.current.page?.path;
    const stillCurrent = () => mountedRef.current && scopeRef.current.page?.path === pageAt;
    for (const entry of def.actions) {
      if (entry.enabled === false) continue;
      const action = bindMethodArgs(entry, args);
      if (isCallMethod(action)) {
        const target = action.target === "view" ? view.spec.root.id : (action.target ?? nodeId);
        callMethod(target, action.method, action.args, depth + 1);
      } else {
        executeAction(action, scope, liveCtx, view, nodeId, `method.${name}`, stillCurrent, args, owner, methodRef);
      }
    }
  }

  function onDom(node: ComponentNode, eventName: string, domEvent: { stopPropagation(): void }) {
    runEvent(node.events, eventName, node.id, domEvent);
  }

  // Stable handlers that call this render's commit and onDom, so a memoized component is not rendered again for them.
  const handlers = useRef({ commit, onDom });
  handlers.current = { commit, onDom };
  const stableCommit = useCallback((nodeId: string, prop: string, value: unknown) => handlers.current.commit(nodeId, prop, value), []);
  const stableDom = useCallback(
    (node: ComponentNode, eventName: string, domEvent: { stopPropagation(): void }) => handlers.current.onDom(node, eventName, domEvent),
    [],
  );

  return (
    <EmbedChain.Provider value={chain}>
      {nodeView({
        node: view.spec.root,
        parent: null,
        view,
        ctx: liveCtx,
        extras,
        values,
        instanceKey,
        onChange: stableCommit,
        onDom: stableDom,
      })}
    </EmbedChain.Provider>
  );
}

function PopupLayer({
  popups,
  setPopups,
  viewport,
}: {
  popups: PopupModel[];
  setPopups: (value: PopupModel[] | ((current: PopupModel[]) => PopupModel[])) => void;
  viewport: { width: number; height: number };
}) {
  const scope = useContext(Scope);
  const topModal = [...popups].reverse().find((popup) => popup.modal);
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key !== "Escape" || popups.length === 0) return;
      const top = popups[popups.length - 1]!;
      if (!top.showClose && !top.modal) return;
      setPopups((current) => current.filter((popup) => popup.id !== top.id));
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [popups, setPopups]);
  if (!scope || popups.length === 0) return null;
  return (
    <div className="oip-popup-layer">
      {topModal ? (
        <button
          type="button"
          className="oip-popup-backdrop"
          aria-label="Dismiss popup"
          onClick={() => {
            if (topModal.backgroundDismissible) setPopups((current) => current.filter((popup) => popup.id !== topModal.id));
          }}
        />
      ) : null}
      {popups.map((popup) => (
        <PopupCard key={popup.id} popup={popup} viewport={viewport} setPopups={setPopups} />
      ))}
    </div>
  );
}

function PopupCard({
  popup,
  viewport,
  setPopups,
}: {
  popup: PopupModel;
  viewport: { width: number; height: number };
  setPopups: (value: PopupModel[] | ((current: PopupModel[]) => PopupModel[])) => void;
}) {
  const scope = useContext(Scope);
  const dialogRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    dialogRef.current?.focus();
  }, [popup.id]);
  if (!scope) return null;
  const view = scope.views.get(popup.view);
  const titleId = `${popup.id}-title`;
  const showTitle = popupShowsTitleBar(popup);
  function move(x: number, y: number) {
    setPopups((current) => current.map((item) => (item.id === popup.id ? clampPopup({ ...item, x, y, positioned: true }, viewport) : item)));
  }
  return (
    <div
      ref={dialogRef}
      className="oip-popup"
      role="dialog"
      aria-modal={popup.modal}
      aria-labelledby={popup.title ? titleId : undefined}
      aria-label={popup.title ? undefined : popup.id}
      tabIndex={-1}
      data-popup-id={popup.id}
      style={{ left: popup.x, top: popup.y, width: popup.width, height: popup.height }}
    >
      {showTitle ? (
        <div
          className="oip-popup-title"
          onPointerDown={(event) => {
            if (!popup.draggable) return;
            const startX = event.clientX;
            const startY = event.clientY;
            const originX = popup.x;
            const originY = popup.y;
            function onMove(next: PointerEvent) {
              move(originX + next.clientX - startX, originY + next.clientY - startY);
            }
            function onUp() {
              window.removeEventListener("pointermove", onMove);
              window.removeEventListener("pointerup", onUp);
            }
            window.addEventListener("pointermove", onMove);
            window.addEventListener("pointerup", onUp);
          }}
        >
          <span id={titleId}>{popup.title ?? ""}</span>
          {popup.showClose ? (
            <button type="button" aria-label={`Close ${popup.title ?? popup.id}`} onClick={() => setPopups((current) => current.filter((item) => item.id !== popup.id))}>
              Close
            </button>
          ) : null}
        </div>
      ) : null}
      <div className="oip-popup-body">
        {view ? (
          <ViewInstance view={view} paramOverrides={popup.params} instanceKey={`popup-${popup.id}`} />
        ) : scope.deniedViews.has(popup.view) ? (
          <ViewRefused path={popup.view} />
        ) : (
          <div role="alert">Unknown view: {popup.view}</div>
        )}
      </div>
      {popup.resizable ? (
        <button
          type="button"
          className="oip-popup-resize"
          aria-label={`Resize ${popup.title ?? popup.id}`}
          onPointerDown={(event) => {
            event.stopPropagation();
            const startX = event.clientX;
            const startY = event.clientY;
            const originW = popup.width;
            const originH = popup.height;
            function onMove(next: PointerEvent) {
              const width = Math.max(160, originW + next.clientX - startX);
              const height = Math.max(120, originH + next.clientY - startY);
              setPopups((current) => current.map((item) => (item.id === popup.id ? clampPopup({ ...item, width, height }, viewport) : item)));
            }
            function onUp() {
              window.removeEventListener("pointermove", onMove);
              window.removeEventListener("pointerup", onUp);
            }
            window.addEventListener("pointermove", onMove);
            window.addEventListener("pointerup", onUp);
          }}
        />
      ) : null}
    </div>
  );
}

interface NodeViewProps {
  node: ViewNode;
  parent: ContainerNode | null;
  view: ViewDocument;
  ctx: ResolveContext;
  extras: BindingExtras;
  values: ReadonlyMap<string, TagUpdate>;
  instanceKey?: string;
  onChange: (nodeId: string, prop: string, value: unknown) => void;
  onDom: (node: ComponentNode, eventName: string, event: { type?: string; stopPropagation(): void }) => void;
}

/** A node's element. Called as a function, so a tag update does not add a wrapper fiber per node to render and commit. */
function nodeView(props: NodeViewProps, key?: string) {
  if (props.node.kind === "container") return <ContainerView key={key} {...props} node={props.node} />;
  return <ComponentView key={key} {...props} node={props.node} />;
}

function ContainerView({ node, ...rest }: NodeViewProps & { node: ContainerNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [measured, setMeasured] = useState(0);
  const scope = useContext(Scope);
  const [tab, setTab] = useState(node.props.currentTab ?? 0);
  useEffect(() => {
    if (node.props.currentTab !== undefined) setTab(node.props.currentTab);
  }, [node.props.currentTab]);
  useEffect(() => {
    if (rest.view && scope?.viewportWidth !== undefined) return;
    if (node.layout !== "column") return;
    const element = ref.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => setMeasured(entries[0]?.contentRect.width ?? 0));
    observer.observe(element);
    return () => observer.disconnect();
  }, [scope?.viewportWidth, node.layout, rest.view]);
  const width = scope?.viewportWidth ?? measured;
  const placementStyle = childStyle(node, rest.parent, width);
  const resolvedStyle = resolveStyle(node.styleClasses, scope?.styleClasses, node.style);
  const className = nodeStyleClassName(node.styleClasses);
  // The view root fills its host; a nested container keeps the size and position its parent gives it (its frame in a
  // coordinate parent), so the container's own fill-the-parent size must not override that placement.
  const style: CSSProperties = { ...containerStyle(node, rest.view), ...placementStyle, ...styleToCss(resolvedStyle) };
  const breakpoint = node.layout === "column" ? activeBreakpoint(width, node.props.breakpoints ?? DEFAULT_COLUMN_BREAKPOINTS) : undefined;
  if (node.layout === "breakpoint") return <BreakpointContainerView {...rest} node={node} style={style} />;
  if (node.layout === "split") return <SplitContainerView {...rest} node={node} style={style} />;
  if (node.layout === "tab") {
    const active = Math.min(tab, Math.max(0, node.children.length - 1));
    const labels = node.children.map((child, index) => node.props.tabs?.[index]?.label ?? child.tabLabel ?? child.name);
    return (
      <div ref={ref} className={`${className} oip-tab`} data-layout="tab" data-node-id={domId(rest.instanceKey, node.id)} data-active-tab={active} style={style}>
        <div role="tablist" aria-label={node.name} onKeyDown={(event) => onTabKey(event, active, node.children.length, setTab)}>
          {labels.map((label, index) => (
            <button
              key={`${node.id}-tab-${index}`}
              type="button"
              role="tab"
              id={`${domId(rest.instanceKey, node.id)}-tab-${index}`}
              aria-selected={index === active}
              aria-controls={`${domId(rest.instanceKey, node.id)}-panel-${index}`}
              tabIndex={index === active ? 0 : -1}
              onClick={() => setTab(index)}
            >
              {label}
            </button>
          ))}
        </div>
        {node.children.map((child, index) => (
          <div
            key={child.id}
            role="tabpanel"
            id={`${domId(rest.instanceKey, node.id)}-panel-${index}`}
            aria-labelledby={`${domId(rest.instanceKey, node.id)}-tab-${index}`}
            hidden={index !== active}
          >
            {nodeView({ ...rest, node: child, parent: node })}
          </div>
        ))}
      </div>
    );
  }
  const body = (
    <div ref={ref} className={`${className} oip-${node.layout}`} data-layout={node.layout} data-node-id={domId(rest.instanceKey, node.id)} data-breakpoint={breakpoint} style={style}>
      {node.children.map((child) => (
        nodeView({ ...rest, node: child, parent: node }, child.id)
      ))}
    </div>
  );
  if (node.layout !== "coordinate") return body;
  const sceneChildren: SceneChild[] = [];
  for (const child of node.children) {
    if (child.kind !== "component") continue;
    sceneChildren.push({ id: child.id, type: child.type, name: child.name, frame: child.frame, props: child.props });
  }
  const sceneWidth = !rest.parent && rest.view ? rest.view.spec.width : node.frame?.width ?? rest.view.spec.width;
  const sceneHeight = !rest.parent && rest.view ? rest.view.spec.height : node.frame?.height ?? rest.view.spec.height;
  return (
    <CoordinateScene.Provider value={{ width: sceneWidth, height: sceneHeight, children: sceneChildren }}>
      {body}
    </CoordinateScene.Provider>
  );
}

function onTabKey(event: ReactKeyboardEvent<HTMLDivElement>, active: number, count: number, setTab: (index: number) => void) {
  if (count === 0) return;
  let next = active;
  if (event.key === "ArrowRight") next = (active + 1) % count;
  else if (event.key === "ArrowLeft") next = (active - 1 + count) % count;
  else if (event.key === "Home") next = 0;
  else if (event.key === "End") next = count - 1;
  else return;
  event.preventDefault();
  setTab(next);
  const tabs = event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]');
  tabs[next]?.focus();
}

type ComponentViewProps = NodeViewProps & { node: ComponentNode };

/** Tag paths each component read in its last render, by the props it rendered with; [ALL_PATHS] when unknown. */
const componentReads = new WeakMap<ComponentViewProps, ReadonlySet<string> | typeof ALL_PATHS>();
const ALL_PATHS = Symbol("all paths");

/**
 * A values map that notes which paths are read. Bindings read tag values only through `get` (and `has`); anything
 * that walks the whole map marks the component as reading every path.
 */
class TrackedValues implements ReadonlyMap<string, TagUpdate> {
  readonly reads = new Set<string>();
  all = false;
  constructor(private readonly source: ReadonlyMap<string, TagUpdate>) {}
  get size() {
    this.all = true;
    return this.source.size;
  }
  get(path: string) {
    this.reads.add(path);
    return this.source.get(path);
  }
  has(path: string) {
    this.reads.add(path);
    return this.source.has(path);
  }
  forEach(callback: (value: TagUpdate, key: string, map: ReadonlyMap<string, TagUpdate>) => void, thisArg?: unknown) {
    this.all = true;
    this.source.forEach((value, key) => callback.call(thisArg, value, key, this));
  }
  entries() {
    this.all = true;
    return this.source.entries();
  }
  keys() {
    this.all = true;
    return this.source.keys();
  }
  values() {
    this.all = true;
    return this.source.values();
  }
  [Symbol.iterator]() {
    return this.entries();
  }
}

/**
 * Whether a component can keep its last render: every input but the values map is the same object, and each tag path
 * it read last time has the same update object in the new map. A new update for any other path does not concern it.
 */
export function sameComponentInputs(prev: ComponentViewProps, next: ComponentViewProps): boolean {
  if (
    prev.node !== next.node ||
    prev.parent !== next.parent ||
    prev.view !== next.view ||
    prev.ctx !== next.ctx ||
    prev.extras !== next.extras ||
    prev.instanceKey !== next.instanceKey ||
    prev.onChange !== next.onChange ||
    prev.onDom !== next.onDom
  ) {
    return false;
  }
  if (prev.values === next.values) return true;
  const reads = componentReads.get(prev);
  if (reads === undefined || reads === ALL_PATHS) return false;
  for (const path of reads) if (prev.values.get(path) !== next.values.get(path)) return false;
  return true;
}

const ComponentView = memo(function ComponentView(props: ComponentViewProps) {
  const { node, parent, ctx, extras, values, instanceKey, onChange, onDom } = props;
  const scope = useContext(Scope);
  // Expression binding poll (HMP-118): this component re-renders, and so re-evaluates its bindings, on the interval.
  usePollTick(useMemo(() => expressionPollMs(Object.values(node.bindings ?? {})), [node.bindings]));
  if (parent?.layout === "flex" && node.flex && node.flex.display === false) {
    componentReads.set(props, new Set());
    return null;
  }
  // Meta (HMP-127): a hidden component is not in the session; the Studio canvas shows it faded so it can be selected.
  const hidden = node.meta?.visible === false;
  if (hidden && !scope?.designMode) {
    componentReads.set(props, new Set());
    return null;
  }
  const width = scope?.viewportWidth ?? 0;
  const tracked = new TrackedValues(values);
  const resolved = applyBindings(node, tracked, ctx, { ...extras, selfId: node.id });
  componentReads.set(props, tracked.all ? ALL_PATHS : tracked.reads);
  const dom = domId(instanceKey, node.id);
  const module = scope?.registry.get(node.type);
  // Theme < variant (HMP-115) < style classes < the node's own style.
  const resolvedStyle = mergeStyle(variantStyle(node, module) ?? {}, resolveStyle(node.styleClasses, scope?.styleClasses, node.style));
  const style: CSSProperties = {
    ...childStyle(node, parent, width),
    ...styleToCss(resolvedStyle),
    position: parent?.layout === "coordinate" ? "absolute" : "relative",
    ...(parent?.layout === "coordinate" ? { zIndex: node.type === "pipe" ? 0 : 1 } : {}),
    ...(node.type === "pipe" ? { pointerEvents: "none" as const } : {}),
    ...(hidden ? { opacity: 0.35 } : {}),
  };
  const body = node.type === "embedded-view" ? (
    <EmbeddedSlot node={node} props={resolved.props} onChange={onChange} />
  ) : node.type === "view-canvas" ? (
    <ViewCanvas instances={typeof resolved.props.instances === "string" ? resolved.props.instances : ""} />
  ) : scope?.registry.get(node.type) ? (
    <RegistryComponent type={node.type} nodeId={dom} props={resolved.props} locale={scope.session.locale} placement={node.frame} onChange={(prop, value) => onChange(node.id, prop, value)} onEvent={(name) => onDom(node, name, { stopPropagation() {} })} />
  ) : (
    <div className="oip-unknown" role="alert">Unknown component: {node.type}</div>
  );
  return (
    <div
      className={nodeStyleClassName(node.styleClasses)}
      data-node-id={dom}
      data-component={node.type}
      data-variant={node.variant}
      data-disabled={resolved.props.disabled === true ? "true" : undefined}
      data-hidden={hidden ? "true" : undefined}
      title={node.meta?.tooltip || undefined}
      data-quality={resolved.quality}
      style={style}
      onClick={(event) => onDom(node, "onClick", event)}
      onChange={(event) => onDom(node, "onChange", { type: event.type, stopPropagation() {} })}
    >
      {resolved.quality !== "good" ? (
        // Quality overlay (HMP-111): over the whole component, naming the worst quality of its bindings.
        <span className="oip-quality-overlay" data-quality={resolved.quality} data-level={qualityLevel(resolved)} data-code={resolved.code} data-label={resolved.label}>
          <span className="oip-quality-flag" role="status" data-code={resolved.code}>
            {qualityLabel(resolved)}
          </span>
        </span>
      ) : null}
      {body}
      {typeof resolved.props.tabIndex === "number" || resolved.props.requestFocus !== undefined ? (
        <FocusControl tabIndex={resolved.props.tabIndex} requestFocus={resolved.props.requestFocus} />
      ) : null}
    </div>
  );
}, sameComponentInputs);

const FOCUSABLE = "button, input, select, textarea, a[href], [tabindex]";

/**
 * Keyboard focus (HMV-118). A component with a numeric `tabIndex` prop puts its first focusable element at that place
 * in the tab order (1 and up come first, in order; 0 is document order; -1 is skipped). A change of its
 * `requestFocus` prop (a bound tag, or a script writing the prop) moves focus to that element, except the first
 * value seen, which is only the starting state. Both props are plain properties, so bindings and scripts drive them.
 */
export function FocusControl({ tabIndex, requestFocus }: { tabIndex: unknown; requestFocus: unknown }) {
  const marker = useRef<HTMLSpanElement>(null);
  const seen = useRef<{ value: unknown } | null>(null);
  function target(): HTMLElement | null {
    return marker.current?.parentElement?.querySelector<HTMLElement>(FOCUSABLE) ?? null;
  }
  useEffect(() => {
    const el = target();
    if (el && typeof tabIndex === "number" && Number.isFinite(tabIndex)) el.tabIndex = Math.trunc(tabIndex);
  }, [tabIndex]);
  useEffect(() => {
    const first = seen.current === null;
    const changed = !first && !Object.is(seen.current?.value, requestFocus);
    seen.current = { value: requestFocus };
    if (changed && requestFocus !== undefined && requestFocus !== false) target()?.focus();
  }, [requestFocus]);
  return <span ref={marker} hidden data-focus-control="true" />;
}

function RegistryComponent({
  type,
  nodeId,
  props,
  locale,
  onChange,
  onEvent,
  placement,
}: {
  type: string;
  nodeId: string;
  props: Record<string, unknown>;
  locale: string;
  onChange: (prop: string, value: unknown) => void;
  onEvent?: (name: string) => void;
  placement?: { x: number; y: number; width: number; height: number };
}) {
  const scope = useContext(Scope);
  const module = scope?.registry.get(type);
  if (!module) return null;
  const Component = module.Component;
  const navigate = (path: string) => scope?.navigate({ kind: "navigation", mode: "page", page: path });
  return <Component nodeId={nodeId} props={props} locale={locale} onChange={onChange} onEvent={onEvent} navigate={navigate} pageHref={scope?.pageHref} placement={placement} />;
}

function ViewCanvas({ instances }: { instances: string }) {
  const placed = instances.split("\n").flatMap((line) => {
    const parts = line.trim().split(/\s+/);
    if (parts.length < 5) return [];
    const [path, x, y, width, height] = parts;
    const numbers = [x, y, width, height].map((part) => Number(part));
    if (!path || numbers.some((value) => !Number.isFinite(value))) return [];
    return [{ path, x: numbers[0]!, y: numbers[1]!, width: numbers[2]!, height: numbers[3]! }];
  });
  return (
    <div className="oip-view-canvas" style={{ position: "relative", width: "100%", height: "100%", minHeight: 40 }}>
      {placed.map((item) => (
        <EmbeddedView
          key={`${item.path}-${item.x}-${item.y}`}
          path={item.path}
          instanceKey={`canvas-${item.path}-${item.x}-${item.y}`}
          useDefaultViewWidth={false}
          useDefaultViewHeight={false}
          style={{ position: "absolute", left: item.x, top: item.y, width: item.width, height: item.height }}
        />
      ))}
    </div>
  );
}

function EmbeddedSlot({
  node,
  props,
  onChange,
}: {
  node: ComponentNode;
  props: Record<string, unknown>;
  onChange: (nodeId: string, prop: string, value: unknown) => void;
}) {
  const path = typeof props.path === "string" ? props.path : "";
  const passed = isRecord(props.params) ? props.params : {};
  const views = useContext(Scope)?.views;
  return (
    <EmbeddedView
      path={path}
      params={passed}
      instanceKey={node.id}
      useDefaultViewWidth={props.useDefaultViewWidth !== false}
      useDefaultViewHeight={props.useDefaultViewHeight !== false}
      onParamChange={(name, value) => {
        const direction = views?.get(path)?.spec.params[name]?.direction ?? "input";
        if (direction === "input") return;
        onChange(node.id, "params", { ...passed, [name]: value });
      }}
    />
  );
}

export interface EmbeddedViewProps {
  /** View path inside the session's project. */
  path: string;
  /** Input values for the view's params. A new object re-applies them. */
  params?: Record<string, unknown>;
  /** Prefix for the embedded nodes' DOM ids; must be unique per embedded instance. */
  instanceKey: string;
  /** When true (default) the view keeps its own width or height; when false it fills the slot. */
  useDefaultViewWidth?: boolean;
  useDefaultViewHeight?: boolean;
  /** An output or in-out param of the embedded view changed. */
  onParamChange?: (name: string, value: unknown) => void;
  className?: string;
  style?: CSSProperties;
}

/** Renders a project view inside a component, sharing the session's tags and actions.
 * Embedding components (embedded view, flex repeater, carousel, accordion) use this.
 * A view that embeds itself, directly or through other views, shows an error instead of recursing.
 */
export function EmbeddedView({
  path,
  params,
  instanceKey,
  useDefaultViewWidth = true,
  useDefaultViewHeight = true,
  onParamChange,
  className,
  style,
}: EmbeddedViewProps) {
  const scope = useContext(Scope);
  const chain = useContext(EmbedChain);
  const view = path ? scope?.views.get(path) : undefined;
  if (!scope) return <div className="oip-unknown" role="alert">Embedded views need a session</div>;
  if (!view && scope.deniedViews.has(path)) return <ViewRefused path={path} />;
  if (!view) {
    return (
      <div className="oip-unknown" role="alert">
        Unknown view: {path || "(none)"}
      </div>
    );
  }
  if (chain.includes(path)) {
    return (
      <div className="oip-unknown" role="alert">
        View embeds itself: {path}
      </div>
    );
  }
  return (
    <div
      className={className ? `oip-embedded ${className}` : "oip-embedded"}
      data-embedded-view={path}
      style={{
        width: useDefaultViewWidth ? view.spec.width : "100%",
        height: useDefaultViewHeight ? view.spec.height : "100%",
        overflow: "auto",
        ...style,
      }}
    >
      <ViewInstance view={view} instanceKey={instanceKey} paramOverrides={params ?? {}} onParamChange={onParamChange} />
    </div>
  );
}

/** An element's content box (inside padding and border), the same box ResizeObserver's contentRect reports. */
function contentBox(element: HTMLElement): { width: number; height: number } {
  const css = getComputedStyle(element);
  const px = (value: string) => Number.parseFloat(value) || 0;
  return {
    width: Math.max(0, element.clientWidth - px(css.paddingLeft) - px(css.paddingRight)),
    height: Math.max(0, element.clientHeight - px(css.paddingTop) - px(css.paddingBottom)),
  };
}

/** Measures an element's content box. Zero until the first observation (and always in DOM-less tests). */
function useMeasuredSize(ref: { current: HTMLElement | null }): { width: number; height: number } {
  const [size, setSize] = useState({ width: 0, height: 0 });
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const box = contentBox(element);
    if (box.width || box.height) setSize(box);
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect;
      if (box) setSize({ width: box.width, height: box.height });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);
  return size;
}

type ContainerRest = Omit<NodeViewProps, "node" | "parent">;

/** - */
function BreakpointContainerView({ node, style, ...rest }: ContainerRest & { node: ContainerNode; style: CSSProperties }) {
  const ref = useRef<HTMLDivElement>(null);
  const scope = useContext(Scope);
  const measured = useMeasuredSize(ref);
  const determinant = node.props.determinant ?? "width";
  // The container breaks on its own measured size. The session's viewportWidth only stands in before the first
  // measurement (and in DOM-less tests), because it is a session-wide value that does not track this container.
  const value = determinant === "width" ? measured.width || (scope?.viewportWidth ?? 0) : measured.height;
  const size = breakpointSize(value, node.props.breakpoint ?? 640);
  const child = breakpointChild(node.children, size);
  return (
    <div
      ref={ref}
      className="oip-breakpoint"
      data-layout="breakpoint"
      data-node-id={domId(rest.instanceKey, node.id)}
      data-current-breakpoint={size}
      style={style}
    >
      {child ? nodeView({ ...rest, node: child, parent: node }, child.id) : null}
    </div>
  );
}

/** - */
function SplitContainerView({ node, style, ...rest }: ContainerRest & { node: ContainerNode; style: CSSProperties }) {
  const ref = useRef<HTMLDivElement>(null);
  const scope = useContext(Scope);
  const measured = useMeasuredSize(ref);
  const vertical = node.props.orientation === "vertical";
  const bar = node.props.split ?? { position: "50%", size: 6, visible: true, draggable: true, minFirst: 0, minSecond: 0 };
  const barSize = bar.visible ? bar.size : 0;
  const total = vertical ? measured.height : measured.width || (scope?.viewportWidth ?? 0);
  const [dragged, setDragged] = useState<number | null>(null);
  const positionKey = JSON.stringify(bar.position);
  useEffect(() => setDragged(null), [positionKey]);
  const limits = { size: barSize, minFirst: bar.minFirst, minSecond: bar.minSecond };
  const position = clampSplit(dragged ?? resolveSplitPosition(bar.position, Math.max(0, total - barSize)), total, limits);
  const max = total > 0 ? clampSplit(Number.POSITIVE_INFINITY, total, limits) : undefined;
  const [first, second] = node.children;
  const dom = domId(rest.instanceKey, node.id);

  // The drag lives on the bar itself through pointer capture, so no window listeners outlive it. The container is
  // measured on every move, so a resize during the drag clamps against the current size.
  const drag = useRef<{ pointerId: number; start: number; origin: number } | null>(null);
  const liveTotal = () => {
    const element = ref.current;
    const box = element ? contentBox(element) : { width: 0, height: 0 };
    return (vertical ? box.height : box.width) || total;
  };

  function onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (!bar.draggable) return;
    event.preventDefault();
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // No capture support (or an inactive pointer): moves still arrive while the pointer is over the bar.
    }
    drag.current = { pointerId: event.pointerId, start: vertical ? event.clientY : event.clientX, origin: position };
  }

  function onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const active = drag.current;
    if (!active || active.pointerId !== event.pointerId) return;
    const delta = (vertical ? event.clientY : event.clientX) - active.start;
    setDragged(clampSplit(active.origin + delta, liveTotal(), limits));
  }

  function endDrag(event: ReactPointerEvent<HTMLDivElement>) {
    if (!drag.current || drag.current.pointerId !== event.pointerId) return;
    drag.current = null;
    try {
      if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    } catch {
      // Already released.
    }
  }

  useEffect(() => () => {
    drag.current = null;
  }, []);

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (!bar.draggable) return;
    const step = event.shiftKey ? 50 : 10;
    let next: number | undefined;
    if (event.key === (vertical ? "ArrowUp" : "ArrowLeft")) next = position - step;
    else if (event.key === (vertical ? "ArrowDown" : "ArrowRight")) next = position + step;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") {
      // Before the container is measured there is no far end to move to.
      if (!(total > 0)) return;
      next = Number.POSITIVE_INFINITY;
    }
    if (next === undefined) return;
    event.preventDefault();
    setDragged(clampSplit(next, total, limits));
  }

  return (
    <div
      ref={ref}
      className="oip-split"
      data-layout="split"
      data-node-id={dom}
      data-orientation={vertical ? "vertical" : "horizontal"}
      data-split-position={Math.round(position)}
      style={style}
    >
      <div className="oip-split-pane" data-pane="first" style={splitPaneStyle(true, position, node.props)}>
        {first ? nodeView({ ...rest, node: first, parent: node }) : null}
      </div>
      <div
        className="oip-split-bar"
        role="separator"
        aria-label={`Resize ${node.name}`}
        aria-orientation={vertical ? "horizontal" : "vertical"}
        aria-valuenow={Math.round(position)}
        aria-valuemin={bar.minFirst}
        aria-valuemax={max === undefined ? undefined : Math.round(max)}
        tabIndex={bar.draggable ? 0 : -1}
        hidden={!bar.visible}
        style={splitBarStyle(node.props)}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onLostPointerCapture={endDrag}
        onKeyDown={onKeyDown}
      />
      <div className="oip-split-pane" data-pane="second" style={splitPaneStyle(false, position, node.props)}>
        {second ? nodeView({ ...rest, node: second, parent: node }) : null}
      </div>
    </div>
  );
}

/** Applies a script's session write. A locale or time zone the browser cannot use is ignored. */
export function applySessionAction(action: SessionAction, patchSession: (patch: SessionPatch) => void): void {
  if (action.prop === "theme") patchSession({ theme: action.value });
  else if (action.prop === "locale" && isLocaleTag(action.value)) patchSession({ locale: action.value });
  else if (action.prop === "timeZoneId" && isTimeZoneId(action.value)) patchSession({ timeZoneId: action.value });
}

function executeAction(
  action: import("./types").Action,
  scope: ScopeValue,
  ctx: ResolveContext,
  view: ViewDocument,
  nodeId: string,
  eventName: string,
  stillCurrent: () => boolean = () => true,
  payload?: unknown,
  owner = "",
  methods?: { current: (method: MethodAction) => void },
) {
  // Design mode never runs actions, event scripts included (startup and shutdown too).
  if (scope.designMode) return;
  try {
    if (isScript(action)) {
      const host = scope.scriptHost;
      const args = [action.hook, eventName, nodeId, view.metadata.path, ctx.params, ctx.custom] as const;
      // Startup and shutdown have no DOM event, so no payload argument.
      const pending = host ? host.action(...args, payload, componentTree(view)) : undefined;
      // A gateway event script answers later; the navigations it asked for run then. A failure does not block the rest.
      if (pending && typeof pending.then === "function") {
        pending.then(
          (result) => {
            if (!stillCurrent()) return;
            for (const navigation of result?.navigate ?? []) scope.navigate(navigation);
            for (const popup of result?.popup ?? []) scope.openPopup(popup);
            for (const message of result?.message ?? []) scope.sendMessage(owner, message.messageType, message.scope, { ...(message.payload ?? {}) });
            for (const method of result?.method ?? []) methods?.current(method);
            for (const dock of result?.dock ?? []) scope.setDock(dock.id, dock.mode);
            for (const change of result?.session ?? []) applySessionAction(change, scope.patchSession);
          },
          () => {},
        );
      }
      return;
    }
    if (isNavigation(action)) {
      scope.navigate(action);
      return;
    }
    if (isPopup(action)) {
      scope.openPopup(withViewParams(action, ctx.params));
      return;
    }
    if (isTagWrite(action)) {
      scope.writeTag(action.path, action.value);
      return;
    }
    if (isMessage(action)) {
      scope.sendMessage(owner, action.messageType, action.scope, { ...(action.payload ?? {}) });
      return;
    }
    if (isCallMethod(action)) {
      methods?.current({ kind: "method", method: "call", nodeId: action.target === "view" ? view.spec.root.id : (action.target ?? nodeId), name: action.method, value: action.args ?? {} });
      return;
    }
    if (isLogout(action)) scope.logout();
  } catch {
    // A failing action does not block the rest of the list.
  }
}

function runPlan(
  events: import("./types").ComponentEvent[] | undefined,
  eventName: string,
  domEvent: { stopPropagation(): void } | undefined,
  run: (action: import("./types").Action) => void,
) {
  const plan = planActions(events, eventName);
  if (plan.stopPropagation) domEvent?.stopPropagation();
  for (const action of plan.actions) run(action);
}

function mergeParams(view: ViewDocument, overrides: Record<string, unknown> | undefined): Record<string, unknown> {
  const params: Record<string, unknown> = {};
  for (const [key, param] of Object.entries(view.spec.params)) {
    const direction = param.direction ?? "input";
    if (overrides && (direction === "input" || direction === "inout") && Object.prototype.hasOwnProperty.call(overrides, key)) {
      params[key] = overrides[key];
    } else {
      params[key] = param.value;
    }
  }
  for (const [key, value] of Object.entries(overrides ?? {})) {
    if (!(key in params)) params[key] = value;
  }
  return params;
}

function domId(instanceKey: string | undefined, id: string): string {
  return instanceKey ? `${instanceKey}__${id}` : id;
}

function isTagValue(value: unknown): value is TagValue {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean" || value === null;
}

function samePath(path: string): string {
  return path;
}

function shallowEqual(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) return false;
  return keys.every((key) => Object.prototype.hasOwnProperty.call(b, key) && Object.is(a[key], b[key]));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function containsHistory(view: ViewDocument): boolean {
  return containsBindingKind(view, "history");
}

function containsQuery(view: ViewDocument): boolean {
  return containsBindingKind(view, "query");
}

function containsBindingKind(view: ViewDocument, kind: "query" | "history" | "http"): boolean {
  if (Object.values(view.spec.custom).some((prop) => prop.binding?.kind === kind)) return true;
  if (Object.values(view.spec.params).some((prop) => prop.binding?.kind === kind)) return true;
  let found = false;
  const walk = (node: ViewNode) => {
    if (found) return;
    if (node.kind === "component") {
      if (Object.values(node.bindings ?? {}).some((binding) => binding.kind === kind)) found = true;
      return;
    }
    for (const child of node.children) walk(child);
  };
  walk(view.spec.root);
  return found;
}

function containerStyle(node: ContainerNode, view: ViewDocument): CSSProperties {
  if (node.layout === "coordinate") return coordinateContainerStyle(node.props, view.spec.width, view.spec.height);
  if (node.layout === "flex") return flexContainerStyle(node.props);
  if (node.layout === "column") return columnContainerStyle(node.props);
  if (node.layout === "breakpoint") return { width: "100%", height: "100%", minWidth: 0, minHeight: 0, position: "relative" };
  if (node.layout === "split") return splitContainerStyle(node.props);
  return { display: "flex", flexDirection: "column", width: "100%", height: "100%", minHeight: 0 };
}

function childStyle(node: ViewNode, parent: ContainerNode | null, width: number): CSSProperties {
  if (!parent) return {};
  if (parent.layout === "coordinate" && node.frame) return coordinateChildStyle(node.frame, parent.props.mode ?? "fixed");
  if (parent.layout === "flex" && node.flex) return flexChildStyle(node.flex);
  if (parent.layout === "column" && node.columns) return columnChildStyle(placementFor(width, node.columns, parent.props.breakpoints));
  // A tab's child is the whole panel: without a size of its own it would shrink to its content, so a tab container
  // drew only its strip and the first line of the panel however tall its frame was.
  if (parent.layout === "tab") return TAB_CHILD_STYLE;
  return {};
}

const TAB_CHILD_STYLE: CSSProperties = { width: "100%", height: "100%", minWidth: 0, minHeight: 0 };

/**
 * What shows in place of a view the session's user may not open (HMP-092). The gateway sends nothing of such a view
 * but its path, so this is all there is to show.
 */
export function ViewRefused({ path }: { path: string }) {
  return (
    <div className="oip-view-refused" role="alert" data-view-refused={path}>
      You do not have access to view &quot;{path}&quot;.
    </div>
  );
}

/** The view tree a session script can walk. Live bound values are not included; the saved props are. */
export function componentTree(view: ViewDocument): Array<{ id: string; name: string; type: string; parent: string | null; props: Record<string, string | number | boolean | null> }> {
  const out: Array<{ id: string; name: string; type: string; parent: string | null; props: Record<string, string | number | boolean | null> }> = [];
  const walk = (node: ViewNode, parent: string | null) => {
    if (out.length >= 200) return;
    const props: Record<string, string | number | boolean | null> = {};
    const source = node.kind === "component" ? node.props : {};
    for (const [key, value] of Object.entries(source)) {
      if (Object.keys(props).length >= 16) break;
      if (typeof value === "string" || typeof value === "number" || typeof value === "boolean" || value === null) props[key] = value;
    }
    out.push({ id: node.id, name: node.name, type: node.kind === "component" ? node.type : node.layout, parent, props });
    if (node.kind === "container") for (const child of node.children) walk(child, node.id);
  };
  walk(view.spec.root, null);
  return out;
}

interface FrameTimer {
  readonly active: boolean;
  cancel(): void;
}

const IDLE_TIMER: FrameTimer = { active: false, cancel() {} };

/** Longest wait for a frame: a hidden page gets no animation frames, so a timer flushes its updates. */
export const COALESCE_FALLBACK_MS = 50;

/**
 * Runs [callback] once, at the next animation frame or after [COALESCE_FALLBACK_MS], whichever comes first. Tag updates
 * that arrive in between are rendered together (HMP-111).
 */
function nextFrame(callback: () => void): FrameTimer {
  let active = true;
  let frame: number | null = null;
  const run = () => {
    if (!active) return;
    timer.cancel();
    callback();
  };
  const fallback = setTimeout(run, COALESCE_FALLBACK_MS);
  if (typeof requestAnimationFrame === "function") frame = requestAnimationFrame(run);
  const timer: FrameTimer = {
    get active() {
      return active;
    },
    cancel() {
      active = false;
      clearTimeout(fallback);
      if (frame !== null && typeof cancelAnimationFrame === "function") cancelAnimationFrame(frame);
    },
  };
  return timer;
}
