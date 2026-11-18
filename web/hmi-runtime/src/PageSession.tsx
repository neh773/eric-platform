import { useEffect, useMemo, useRef, useState } from "react";
import { OnScreenKeyboard } from "./OnScreenKeyboard";
import type { CSSProperties } from "react";
import type { NamedQuerySource, ScriptHost } from "./bindings";
import { docksFor, fillPageUrl, matchPage, resolveDock } from "./pages";
import { linkTarget } from "./links";
import type { SessionModel } from "./session";
import type { StringTransport } from "./tags";
import { HmiRoot, ViewInstance, ViewRefused, applySessionAction, type SessionPatch } from "./ViewRenderer";
import type { ComponentRegistry, DockSpec, HmiProject, NavigationAction, PageDocument, ViewDocument } from "./types";

/** Page URL, shared docks, and the primary view.
 * The session is mounted under /hmi so it does not replace the designer at /.
 */

export interface PageSessionProps {
  project: HmiProject;
  registry: ComponentRegistry;
  transport: StringTransport;
  session: SessionModel;
  /** Path and query relative to the session base, such as `/pumps?line=3`. */
  location: string;
  onLocationChange?: (location: string) => void;
  /** Prefix the host puts in front of session locations in the browser URL, such as `/hmi`. */
  basePath?: string;
  onBrowser?: (direction: "back" | "forward") => void;
  openExternal?: (url: string, newTab: boolean) => void;
  viewportWidth?: number;
  scriptHost?: ScriptHost;
  querySource?: NamedQuerySource;
  onSessionPatch?: (patch: SessionPatch) => void;
  onConnectionChange?: (connected: boolean) => void;
  /** Signs the user out for a logout action (HMP-117). */
  onLogout?: () => void;
}

export function PageSession(props: PageSessionProps) {
  const match = useMemo(() => matchPage(props.project.pages, props.location), [props.project.pages, props.location]);
  const pageId = useRef(typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `page-${Date.now()}`);
  const [title, setTitle] = useState(match?.page.spec.title ?? match?.page.metadata.name ?? "");
  const [userOpen, setUserOpen] = useState<Record<string, boolean | undefined>>({});
  const [sizes, setSizes] = useState<Record<string, number>>({});
  const [override, setOverride] = useState<{ view: string; params: Record<string, unknown> } | null>(null);
  const [measured, setMeasured] = useState(props.viewportWidth ?? 1280);
  const frameRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setTitle(match?.page.spec.title ?? match?.page.metadata.name ?? "");
  }, [match?.page]);

  const scriptHost = props.scriptHost;
  // A startup script's session writes reach the host through this ref, so the effect does not run again when they change.
  const latest = useRef(props);
  latest.current = props;
  useEffect(() => {
    if (!scriptHost) return undefined;
    void Promise.resolve(scriptHost.action("sessionStartup", "sessionStartup", "session", "", {}, {}))
      .then((result) => {
        // A startup script may set the locale, time zone or theme before the first view is used.
        for (const change of result?.session ?? []) applySessionAction(change, (patch) => latest.current.onSessionPatch?.(patch));
      })
      .catch(() => {});
    return () => {
      void Promise.resolve(scriptHost.action("sessionShutdown", "sessionShutdown", "session", "", {}, {})).catch(() => {});
    };
  }, [scriptHost]);

  useEffect(() => {
    setOverride(null);
  }, [props.location]);

  useEffect(() => {
    if (title) document.title = title;
  }, [title]);

  useEffect(() => {
    if (props.viewportWidth !== undefined) return;
    const element = frameRef.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => setMeasured(entries[0]?.contentRect.width ?? 0));
    observer.observe(element);
    return () => observer.disconnect();
  }, [props.viewportWidth]);

  const width = props.viewportWidth ?? measured;
  const layout = match ? docksFor(match.page, props.project) : { docks: [], corner: "top-bottom" as const };
  const resolved = layout.docks.map((dock) => ({
    dock,
    state: resolveDock(dock, width, userOpen[dock.id]),
    size: sizes[dock.id] ?? dock.size,
  }));
  const modal = resolved.some((entry) => entry.state.modal);
  const primaryPath = override?.view ?? match?.page.spec.primaryView ?? "";
  const pageName = match?.page.metadata.name ?? "";
  useEffect(() => {
    if (!scriptHost || !pageName) return;
    void Promise.resolve(scriptHost.action("pageChange", "pageChange", "page", primaryPath, {}, {}, { page: pageName })).catch(() => {});
  }, [scriptHost, pageName, primaryPath]);
  const primary = props.project.views.find((view) => view.metadata.path === primaryPath);
  const primaryParams = override?.params ?? match?.params ?? {};

  // A component that navigates itself and its node's onClick action can request the identical location in
  // one click; the repeat is dropped so the browser history gets one entry. Different locations are not merged.
  const pendingLocation = useRef<string | null>(null);

  function goTo(location: string) {
    if (pendingLocation.current === location) return;
    pendingLocation.current = location;
    queueMicrotask(() => {
      pendingLocation.current = null;
    });
    props.onLocationChange?.(location);
  }

  function navigate(action: NavigationAction) {
    if (action.mode === "page") {
      const token = action.page ?? "";
      const page = findPage(props.project.pages, token);
      if (!page) {
        // A concrete page path (`/line/3?mode=auto`) from a component is followed when a page matches it.
        if (token.startsWith("/") && matchPage(props.project.pages, token)) goTo(token);
        return;
      }
      try {
        goTo(fillPageUrl(page.spec.url, action.params));
      } catch {
        // Invalid path characters stay on the current page.
      }
      return;
    }
    if (action.mode === "view") {
      setOverride({ view: action.view ?? "", params: { ...(action.params ?? {}) } });
      return;
    }
    if (action.mode === "url") {
      // Review-4 F3: the same filter as the link component and the menus. A page path stays on this origin; only
      // http(s) leaves it; `javascript:`, `data:`, `vbscript:` and any other scheme do nothing.
      const target = linkTarget(action.url ?? "");
      if (target.kind === "blocked") return;
      const url = target.kind === "page" ? target.path : target.url;
      if (props.openExternal) props.openExternal(url, action.newTab === true);
      else if (typeof window !== "undefined") {
        if (action.newTab) window.open(url, "_blank", "noopener");
        else window.location.assign(url);
      }
      return;
    }
    const direction = action.direction ?? "back";
    if (props.onBrowser) props.onBrowser(direction);
    else if (typeof window !== "undefined") {
      if (direction === "forward") window.history.forward();
      else window.history.back();
    }
  }

  const pageFields = match
    ? {
        pageId: pageId.current,
        path: match.path,
        primaryView: match.page.spec.primaryView,
        title,
        urlParams: match.urlParams,
      }
    : undefined;

  return (
    <HmiRoot
      view={primary ?? props.project.views[0]!}
      registry={props.registry}
      transport={props.transport}
      session={props.session}
      views={props.project.views}
      deniedViews={props.project.deniedViews}
      styleClasses={props.project.styleClasses}
      themes={props.project.themes}
      viewportWidth={props.viewportWidth}
      scriptHost={props.scriptHost}
      querySource={props.querySource}
      translations={props.project.translations}
      onSessionPatch={props.onSessionPatch}
      onConnectionChange={props.onConnectionChange}
      onLogout={props.onLogout}
      onNavigate={navigate}
      onDock={(id, mode) => {
        setUserOpen((current) => {
          const dock = layout.docks.find((candidate) => candidate.id === id);
          if (!dock) return current;
          const openNow = resolveDock(dock, width, current[id]).open;
          const next = mode === "toggle" ? !openNow : mode === "open";
          return { ...current, [id]: next };
        });
      }}
      pageHref={(path) => `${props.basePath ?? ""}${path}`}
      page={pageFields}
      onPageTitle={setTitle}
    >
      <div ref={frameRef} className="oip-page" data-page-url={match?.path ?? ""} data-corner={layout.corner} data-primary-view={primaryPath} data-oip-touch={props.project.touchscreenMode === true || props.session.custom.touchscreen === true ? "true" : undefined}>
        {match ? (
          <div
            className="oip-page-grid"
            style={gridStyle(layout.corner, resolved)}
          >
            <div className="oip-page-center" style={{ gridArea: "center" }} inert={modal ? true : undefined} aria-hidden={modal || undefined}>
              {primary ? (
                <ViewInstance view={primary} paramOverrides={primaryParams} />
              ) : props.project.deniedViews?.includes(primaryPath) ? (
                <ViewRefused path={primaryPath} />
              ) : (
                <div role="alert">Unknown view: {primaryPath}</div>
              )}
            </div>
            {resolved.map((entry) => (
              <DockPane
                key={entry.dock.id}
                dock={entry.dock}
                open={entry.state.open}
                content={entry.state.content}
                showHandle={entry.state.showHandle}
                size={entry.size}
                view={props.project.views.find((candidate) => candidate.metadata.path === entry.dock.view)}
                denied={props.project.deniedViews?.includes(entry.dock.view) ?? false}
                onToggle={() => setUserOpen((current) => ({ ...current, [entry.dock.id]: !entry.state.open }))}
                onResize={(next) => setSizes((current) => ({ ...current, [entry.dock.id]: next }))}
              />
            ))}
          </div>
        ) : (
          <div role="alert">Page not found</div>
        )}
        <OnScreenKeyboard enabled={props.project.touchscreenMode === true || props.session.custom.touchscreen === true} />
      </div>
    </HmiRoot>
  );
}

function DockPane({
  dock,
  open,
  content,
  showHandle,
  size,
  view,
  denied,
  onToggle,
  onResize,
}: {
  dock: DockSpec;
  open: boolean;
  content: "push" | "cover";
  showHandle: boolean;
  size: number;
  view: ViewDocument | undefined;
  /** The dock's view is one the user may not open (HMP-092). */
  denied: boolean;
  onToggle: () => void;
  onResize: (size: number) => void;
}) {
  const pushed = open && content === "push";
  const style: CSSProperties = pushed
    ? { gridArea: dock.edge, position: "relative", minWidth: 0, minHeight: 0 }
    : {
        position: "absolute",
        zIndex: 4,
        ...(open ? edgeBox(dock.edge, size) : handleAnchor(dock.edge)),
      };
  return (
    <div className="oip-dock-slot" style={style}>
      {open ? (
        <section
          className="oip-dock"
          data-edge={dock.edge}
          data-dock-id={dock.id}
          data-open="yes"
          data-content={content}
          aria-label={`${dock.id} dock`}
        >
          {view ? (
            <ViewInstance view={view} paramOverrides={dock.params} instanceKey={`dock-${dock.id}`} />
          ) : denied ? (
            <ViewRefused path={dock.view} />
          ) : (
            <div role="alert">Unknown view: {dock.view}</div>
          )}
          {dock.resizable ? (
            <button
              type="button"
              className={`oip-dock-resize oip-dock-resize-${dock.edge}`}
              aria-label={`Resize ${dock.id} dock`}
              onPointerDown={(event) => startResize(event, dock.edge, size, onResize)}
            />
          ) : null}
        </section>
      ) : null}
      {showHandle ? (
        <button type="button" className={`oip-dock-handle oip-dock-handle-${dock.edge}`} aria-expanded={open} aria-controls={dock.id} aria-label={open ? `Close ${dock.id} menu` : `Open ${dock.id} menu`} onClick={onToggle}>
          <span aria-hidden="true">{open ? "×" : "☰"}</span>
        </button>
      ) : null}
    </div>
  );
}

function startResize(event: { clientX: number; clientY: number; stopPropagation(): void }, edge: DockSpec["edge"], size: number, onResize: (size: number) => void) {
  event.stopPropagation();
  const start = edge === "left" || edge === "right" ? event.clientX : event.clientY;
  function onMove(next: PointerEvent) {
    const delta = (edge === "left" || edge === "right" ? next.clientX : next.clientY) - start;
    const grown = edge === "left" || edge === "top" ? delta : -delta;
    onResize(Math.max(48, size + grown));
  }
  function onUp() {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  }
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}

function gridStyle(
  corner: "top-bottom" | "left-right",
  docks: { dock: DockSpec; state: { open: boolean; content: "push" | "cover" }; size: number }[],
): CSSProperties {
  const track = (edge: DockSpec["edge"]) => {
    const found = docks.find((entry) => entry.dock.edge === edge);
    if (!found || !found.state.open || found.state.content !== "push") return 0;
    return found.size;
  };
  return {
    display: "grid",
    gridTemplateColumns: `${track("left")}px minmax(0, 1fr) ${track("right")}px`,
    gridTemplateRows: `${track("top")}px minmax(0, 1fr) ${track("bottom")}px`,
    gridTemplateAreas: corner === "left-right"
      ? '"left top right" "left center right" "left bottom right"'
      : '"top top top" "left center right" "bottom bottom bottom"',
    position: "relative",
    width: "100%",
    minHeight: "100%",
  };
}

function handleAnchor(edge: DockSpec["edge"]): CSSProperties {
  if (edge === "left") return { top: 8, left: 0 };
  if (edge === "right") return { top: 8, right: 0 };
  if (edge === "top") return { top: 0, left: 8 };
  return { bottom: 0, left: 8 };
}

function edgeBox(edge: DockSpec["edge"], size: number): CSSProperties {
  if (edge === "left") return { top: 0, bottom: 0, left: 0, width: size };
  if (edge === "right") return { top: 0, bottom: 0, right: 0, width: size };
  if (edge === "top") return { top: 0, left: 0, right: 0, height: size };
  return { bottom: 0, left: 0, right: 0, height: size };
}

function findPage(pages: readonly PageDocument[], token: string): PageDocument | undefined {
  return pages.find((page) => page.spec.url === token || page.metadata.name === token);
}
