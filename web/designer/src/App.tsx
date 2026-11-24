import { Profiler, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ComponentType } from "react";
import { createSession, detectDevice, findNode, type SessionModel, type StyleClassSet, type ViewDocument } from "@oip/hmi-runtime";
import { componentModules, componentRegistry, containerModules, defaultsFromSchema } from "@oip/components";
import { Canvas, renderedSize, selectedComponent } from "./Canvas";
import { Inspector } from "./Inspector";
import { ProjectTree } from "./ProjectTree";
import { appendComponent, appendContainer, placeAt, ancestorsOf, followDesignTabs, moveNode, removeNode, revealNode, updateNode, type DesignTabs } from "./project";
import { Outline } from "./Outline";
import { useGatewayScriptHost } from "./gatewayScripts";
import { sampleProject } from "./sampleProject";
import { HmiApp } from "./sessionApp";
import { StyleClassesPanel } from "./StyleClassesPanel";
import { ProjectSettingsPanel } from "./ProjectSettingsPanel";
import { ToolsPanel, type StudioTool } from "./ToolsPanel";
import { ScriptHooksPanel } from "./ScriptHooksPanel";
import { useTagRuntime, type TagRuntime } from "./runtime";
import { gateway } from "./gatewayClient";
import { ProjectGate, projectFromLocation, type Opened } from "./ProjectGate";
import { useSavedProject } from "./useSavedProject";
import { LoadErrorsNotice, NewViewForm, SaveControls } from "./PersistenceBar";
import { PageConfigPanel } from "./PageConfigPanel";
import { StudioFileMenu } from "./ProjectMenu";
import { projectUrl } from "./projectFiles";
import { TagBrowser } from "./TagBrowser";
import { applyDropConfig, bindDroppedTag } from "./tagDrop";
import { Palette } from "./Palette";
import { SvgImportPanel } from "./SvgImportPanel";
import { PipeToolPanel } from "./PipeTool";
import { appendPipe, followPipes, pipeAnchors, snapToAnchor } from "./pipeModel";
import { addSvgComponent } from "./svgModel";
import { groupPalette, type PaletteDrag, type PaletteItem } from "./paletteData";
import { ConsolePanel } from "./ConsolePanel";
import { DiagnosticsPanel } from "./DiagnosticsPanel";
import { FindReplacePanel } from "./FindReplacePanel";
import { DiagnosticsMeter, meteredTransport } from "./diagnostics";
import { SideTabs } from "./SideTabs";
import { DockHost, PanelRestore, useDockLayout } from "./DockHost";
import { ProjectStatusLine, useProjectStatus } from "./projectStatus";
import { useDesignerSession } from "./designerSession";
import { useProjectAccess, viewRef } from "./projectAccess";
import { ProjectAccessBar, saveBlockedReason } from "./ProjectAccessBar";
import { TagSourcesContext, TransportLiveTags, gatewayBrowse, localBrowse, type TagSources } from "./tagSource";
import { LogicWorkspace } from "./LogicWorkspace";
import { BetaNotice } from "./betaNotice";

const registry = componentRegistry();

export function App() {
  const runtime = useTagRuntime();
  const route = window.location.pathname;
  useDevHook(runtime);
  if (import.meta.env.DEV && isProbe(route)) {
    return <DevProbe route={route} runtime={runtime} />;
  }
  return <Routes runtime={runtime} route={route} />;
}

function useDevHook(runtime: TagRuntime) {
  useEffect(() => {
    if (!import.meta.env.DEV) return undefined;
    let disposed = false;
    let cleanup = () => {};
    void import("./devHook").then((mod) => {
      if (!disposed) cleanup = mod.installTestHook(runtime);
    });
    return () => {
      disposed = true;
      cleanup();
    };
  }, [runtime]);
}

function isProbe(route: string): boolean {
  return route === "/latency" || route === "/bench" || route === "/bench-embed";
}

function DevProbe({ route, runtime }: { route: string; runtime: TagRuntime }) {
  const [Probe, setProbe] = useState<ComponentType<{ route: string; runtime: TagRuntime }> | null>(null);
  useEffect(() => {
    let cancelled = false;
    void import("./devOnly").then((mod) => {
      if (!cancelled) setProbe(() => mod.DevRoutes);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  if (!Probe) return null;
  return <Probe route={route} runtime={runtime} />;
}

function Routes({ runtime, route }: { runtime: TagRuntime; route: string }) {
  const tagSources = useTagSources(runtime);
  if (route === "/hmi" || route.startsWith("/hmi/")) return <HmiApp runtime={runtime} />;
  return (
    <TagSourcesContext.Provider value={tagSources}>
      <BetaNotice />
      <ProjectGate
        client={gateway}
        project={projectFromLocation(window.location.search)}
        starter={SAMPLE_STARTER ? sampleProject.views : NO_STARTER_VIEWS}
        starterStyleClasses={SAMPLE_STARTER ? sampleProject.styleClasses : undefined}
      >
        {(opened) => <Studio key={opened.project} runtime={runtime} opened={opened} />}
      </ProjectGate>
    </TagSourcesContext.Provider>
  );
}

const browseGatewayTags = gatewayBrowse(gateway);

/**
 * A new gateway project opens empty, as the Blank template promises: no unsaved sample views to save by mistake.
 * The sample project still opens when the Studio runs without a gateway.
 */
const NO_STARTER_VIEWS: ViewDocument[] = [];

/** `?starter=sample` opens a new empty project with the sample views as unsaved drafts (used by the end-to-end specs). */
const SAMPLE_STARTER = typeof window !== "undefined" && new URLSearchParams(window.location.search).get("starter") === "sample";

/** Tag sources for the tag browser and picker: the gateway (tried even when the Studio opened offline, so Retry works), the Studio's local tags, and live values through the tag runtime. */
function useTagSources(runtime: TagRuntime): TagSources {
  return useMemo(
    () => ({ gateway: browseGatewayTags, local: localBrowse(runtime.server), live: new TransportLiveTags(runtime.openTransport) }),
    [runtime],
  );
}

function Studio({ runtime, opened }: { runtime: TagRuntime; opened: Opened }) {
  const saved = useSavedProject(
    opened.mode === "gateway"
      ? {
          name: opened.project,
          views: opened.views,
          drafts: opened.drafts,
          styleClasses: opened.styleClasses,
          styleClassesDraft: opened.styleClassesDraft,
          scriptHooks: opened.scriptHooks,
          pageConfig: opened.pageConfig,
        }
      : { name: sampleProject.name, views: [], drafts: sampleProject.views, styleClassesDraft: sampleProject.styleClasses },
    opened.mode === "gateway" ? gateway : null,
    opened.project,
  );
  const project = saved.project;
  const projectStatus = useProjectStatus(opened.mode === "gateway" ? gateway : null, opened.project);
  // Listed on the gateway's Canvas sessions page while a gateway project is open (GAT-028).
  const designerSession = useDesignerSession(opened.mode === "gateway" ? gateway : null, opened.mode === "gateway" ? opened.project : null);
  // SEC-020: what this user may do in the project, protected views, and locks on the views being edited.
  const { dirty: dirtyPaths, storedName } = saved;
  const editingRefs = useMemo(
    () => [...dirtyPaths].flatMap((path) => {
      const name = storedName(path);
      return name ? [viewRef(name)] : [];
    }),
    [dirtyPaths, storedName],
  );
  const access = useProjectAccess(opened.mode === "gateway" ? gateway : null, opened.project, designerSession, editingRefs);
  const saveBlocked = opened.mode === "gateway" ? saveBlockedReason(access) : null;
  const treeMarks = useMemo(() => {
    const marks = new Map<string, string>();
    for (const view of project.views) {
      const name = storedName(view.metadata.path);
      if (!name) continue;
      const lock = access.lockedBy(viewRef(name));
      const text = [access.isProtected(viewRef(name)) ? "protected" : null, lock ? `locked by ${lock.user}` : null].filter(Boolean).join(", ");
      if (text) marks.set(view.metadata.path, text);
    }
    return marks;
  }, [project.views, storedName, access]);
  // Script transforms in the preview run on the gateway (saved hook sources) as the signed-in user.
  const scriptHost = useGatewayScriptHost(opened.mode === "gateway" ? opened.project : null, runtime.bridge);
  // DES-005: render time of the canvas and tag messages the preview receives.
  const diagnostics = useMemo(() => new DiagnosticsMeter(), []);
  const meteredTransportFor = useMemo(() => meteredTransport(runtime.transport, diagnostics), [runtime, diagnostics]);
  const [activePath, setActivePath] = useState(project.views[0]?.metadata.path ?? "");
  const [selectedId, setSelectedId] = useState<string | null>("title");
  // Which panel each tab container shows while designing. Kept out of the view, so it is never saved.
  const [designTabs, setDesignTabs] = useState<DesignTabs>({});
  const [dockLayout, setDockLayout] = useDockLayout();
  const [panel, setPanel] = useState("add");
  const [section, setSection] = useState<"screens" | "logic">("screens");
  const [drawingPipe, setDrawingPipe] = useState(false);
  // The pipe tool belongs to the view it was turned on in.
  useEffect(() => setDrawingPipe(false), [activePath]);
  const [toolsTool, setToolsTool] = useState<StudioTool>("query");
  const [session, setSession] = useState<SessionModel>(() =>
    createSession({
      id: "studio-preview",
      theme: "nightwatch",
      locale: "en-US",
      timeZoneId: "UTC",
      device: detectDevice("designer"),
      gateway: { connected: false },
      auth: { authenticated: false, userName: null, roles: [] },
    }),
  );
  useEffect(() => {
    const custom = project.themes?.[session.theme];
    const light = session.theme === "daylight" || session.theme === "light" || custom?.extends === "daylight";
    document.documentElement.dataset.oipTheme = light ? "light" : "dark";
  }, [session.theme, project.themes]);
  // No gateway answered: the preview stops polling it for `[provider]` tag paths.
  useEffect(() => {
    runtime.bridge?.setOnline(opened.mode === "gateway");
  }, [runtime, opened.mode]);
  const onConnectionChange = useCallback(
    (connected: boolean) =>
      setSession((current) => (current.gateway.connected === connected ? current : { ...current, gateway: { connected } })),
    [],
  );
  const loadErrors = opened.mode === "gateway" ? opened.loadErrors : [];
  const viewPaths = useMemo(() => project.views.map((view) => view.metadata.path), [project.views]);
  const hookNames = useMemo(() => new Set(Object.keys(saved.scriptHooks)), [saved.scriptHooks]);
  const active = useMemo(
    () => project.views.find((view) => view.metadata.path === activePath) ?? project.views[0],
    [project, activePath],
  );
  // Callbacks the outline and canvas keep across renders (the outline is memoised); they read the latest view here.
  const live = useRef({ active, saved });
  live.current = { active, saved };
  const commit = useCallback((view: ViewDocument) => {
    const before = live.current.active;
    live.current.saved.replaceView(view);
    // Design-time tab choices follow their panels, and give way when the author changes a saved current tab.
    if (before) setDesignTabs((tabs) => followDesignTabs(before, view, tabs));
  }, []);
  /** Selects node [id] and shows it: a node on a hidden tab switches that tab at design time. */
  const select = useCallback((id: string | null) => {
    setSelectedId(id);
    const view = live.current.active;
    if (id && view) setDesignTabs((tabs) => revealNode(view, id, tabs));
  }, []);
  const moveInOutline = useCallback(
    (id: string, parentId: string, index: number) => {
      const view = live.current.active;
      if (!view) return;
      const moved = moveNode(view, id, parentId, index, { rendered: renderedSize(id) });
      commit(moved);
      setSelectedId(id);
      setDesignTabs((tabs) => revealNode(moved, id, tabs));
    },
    [commit],
  );
  const deleteInOutline = useCallback(
    (id: string) => {
      const view = live.current.active;
      if (!view) return;
      const parent = ancestorsOf(view.spec.root, id).at(-1);
      commit(removeNode(view, id));
      setSelectedId(parent?.id ?? null);
    },
    [commit],
  );

  function openProject(name: string) {
    if (name === opened.project) return;
    saved.allowLeave();
    window.location.assign(projectUrl(name));
  }

  function openResource(id: "styles" | "scripts" | "pages" | "translation" | "images" | "project" | "libraries" | "queries") {
    if (id === "translation" || id === "images" || id === "libraries" || id === "queries") {
      setToolsTool(id);
      setPanel("tools");
      return;
    }
    setPanel(id);
  }

  const fileMenu = (
    <StudioFileMenu saved={saved} client={opened.mode === "gateway" ? gateway : null} project={opened.project} onOpenProject={openProject} />
  );
  const workspaceSwitch = (
    <div role="group" aria-label="Workspace">
      <button type="button" aria-pressed={section === "screens"} onClick={() => setSection("screens")}>Screens</button>
      <button type="button" aria-pressed={section === "logic"} onClick={() => setSection("logic")}>Logic</button>
    </div>
  );

  if (section === "logic") {
    return (
      <div className="oip-shell">
        <header className="oip-topbar">
          <div className="oip-top-identity">
            <div>
              <p className="oip-product">Control Canvas</p>
              <p className="oip-project-name">{project.name}</p>
            </div>
            {workspaceSwitch}
            {fileMenu}
          </div>
        </header>
        {opened.mode === "gateway" ? <LogicWorkspace client={gateway} /> : <p className="oip-gate">Logic needs a gateway project.</p>}
      </div>
    );
  }

  if (!active) {
    return (
      <div className="oip-shell">
        <header className="oip-topbar">
          <div className="oip-top-identity">
            <div>
              <p className="oip-product">Control Canvas</p>
              <p className="oip-project-name">{project.name}</p>
            </div>
            {workspaceSwitch}
            {fileMenu}
          </div>
          <div className="oip-top-actions">
            <SaveControls saved={saved} offlineReason={opened.mode === "offline" ? opened.reason : null} blockedReason={saveBlocked} />
          </div>
        </header>
        <div>
          <LoadErrorsNotice errors={loadErrors} />
          <p className="oip-gate">Project {project.name} has no views.</p>
          <NewViewForm onCreate={(path) => createAndOpen(path)} />
        </div>
      </div>
    );
  }
  const selected = selectedComponent(active, selectedId);
  const inheritedFrom = saved.inherited.get(active.metadata.path);

  function openView(path: string) {
    setActivePath(path);
    setSelectedId(null);
    setDesignTabs({});
  }

  function createAndOpen(path: string): string | null {
    const result = saved.createView(path);
    if (result.error) return result.error;
    openView(result.path);
    return null;
  }

  /** Adds a palette entry to the open view; [at] is a drop point on a coordinate root, in view pixels. */
  function addFromPalette(item: PaletteItem, at?: { x: number; y: number }) {
    if (!active) return;
    const added =
      item.kind === "component"
        ? appendComponent(active, item.type, item.title, defaultsFromSchema(item.module.schema), item.module.position)
        : appendContainer(active, item.layout, item.title, item.module.defaults, item.module.children);
    commit(at ? placeAt(added.view, added.id, at) : added.view);
    setSelectedId(added.id);
  }

  /** A drawn pipe becomes a pipe component, selected, and the tool turns off (HMP-128). */
  function finishPipe(points: Parameters<typeof appendPipe>[1]) {
    setDrawingPipe(false);
    if (!active) return;
    try {
      const anchors = pipeAnchors(active);
      const start = points[0] ? snapToAnchor(points[0], anchors, 1.5).anchor : undefined;
      const end = points.length > 1 ? snapToAnchor(points[points.length - 1]!, anchors, 1.5).anchor : undefined;
      const added = appendPipe(active, points, undefined, { start, end });
      commit(added.view);
      setSelectedId(added.id);
    } catch {
      // A pipe needs two points in a fixed-coordinate view; the layer only finishes with two or more.
    }
  }

  /** Adds an imported SVG as a component (HMV-151). */
  function addSvgFromImport(imported: Parameters<typeof addSvgComponent>[1], name: string) {
    if (!active) return;
    const added = addSvgComponent(active, imported, name);
    commit(added.view);
    setSelectedId(added.id);
  }

  function commitStyleClasses(styleClasses: StyleClassSet) {
    saved.replaceStyleClasses(styleClasses);
  }

  function commitThemes(themes: NonNullable<typeof project.themes>) {
    saved.replaceThemes(themes);
  }

  return (
    <div className="oip-shell" data-active-view={active.metadata.path}>
      <header className="oip-topbar">
        <div className="oip-top-identity">
          <div>
            <p className="oip-product">Control Canvas</p>
            <p className="oip-project-name">{project.name}</p>
            <ProjectStatusLine status={projectStatus} />
          </div>
          {workspaceSwitch}
          {fileMenu}
        </div>
        <div className="oip-top-actions">
          <SaveControls saved={saved} offlineReason={opened.mode === "offline" ? opened.reason : null} blockedReason={saveBlocked} />
          <PanelRestore layout={dockLayout} onLayout={setDockLayout} />
          <span className="oip-connection" data-connected={session.gateway.connected ? "yes" : "no"}>
            {session.gateway.connected ? "Session live" : "Session idle"}
          </span>
          <div role="group" aria-label="Theme">
            <button
              type="button"
              aria-pressed={session.theme === "nightwatch" || session.theme === "process" || session.theme === "graphite" || session.theme === "dark"}
              onClick={() => setSession((current) => ({ ...current, theme: "nightwatch" }))}
            >
              Dark
            </button>
            <button
              type="button"
              aria-pressed={session.theme === "daylight" || session.theme === "light"}
              onClick={() => setSession((current) => ({ ...current, theme: "daylight" }))}
            >
              Light
            </button>
            {Object.keys(project.themes ?? {}).map((theme) => (
              <button
                key={theme}
                type="button"
                aria-pressed={session.theme === theme}
                onClick={() => setSession((current) => ({ ...current, theme }))}
              >
                {theme}
              </button>
            ))}
          </div>
        </div>
      </header>
      <LoadErrorsNotice errors={loadErrors} />
      <DockHost
        layout={dockLayout}
        onLayout={setDockLayout}
        panels={{
          inspector: (
            <Inspector
              view={active}
              selected={selected}
              styleClasses={project.styleClasses ?? {}}
              tags={runtime.server}
              viewPaths={viewPaths}
              scriptHooks={hookNames}
              onView={commit}
              onSelect={select}
            />
          ),
          project: (
            <>
              <ProjectTree
                project={project}
                activePath={active.metadata.path}
                onOpen={openView}
                inherited={saved.inherited}
                marks={treeMarks}
                onOpenResource={opened.mode === "gateway" ? openResource : undefined}
              />
              <NewViewForm onCreate={createAndOpen} />
            </>
          ),
          outline: (
            <Outline
              view={active}
              selectedId={selectedId}
              designTabs={designTabs}
              onSelect={select}
              onMove={moveInOutline}
              onDelete={deleteInOutline}
            />
          ),
          tools: (
            <SideTabs
              selected={panel}
              onSelect={setPanel}
              tabs={[
                {
                  id: "add",
                  label: "Add",
                  content: (
                    <>
                      <Palette components={componentModules} containers={containerModules} onAdd={(item) => addFromPalette(item)} />
                      <PipeToolPanel view={active} active={drawingPipe} onToggle={() => setDrawingPipe((on) => !on)} />
                      <SvgImportPanel onAdd={addSvgFromImport} />
                    </>
                  ),
                },
                { id: "tags", label: "Tags", content: <TagBrowser /> },
                {
                  id: "styles",
                  label: "Styles",
                  content: <StyleClassesPanel styleClasses={project.styleClasses ?? {}} themes={project.themes ?? {}} onChange={commitStyleClasses} onThemes={commitThemes} />,
                },
                { id: "scripts", label: "Scripts", content: <ScriptHooksPanel hooks={saved.scriptHooks} onChange={saved.replaceScriptHooks} /> },
                {
                  id: "pages",
                  label: "Pages",
                  content: <PageConfigPanel spec={saved.pageConfig} views={project.views} onChange={saved.replacePageConfig} />,
                },
                ...(opened.mode === "gateway"
                  ? [
                      { id: "project", label: "Project", content: <ProjectSettingsPanel client={gateway} project={opened.project} /> },
                      {
                        id: "tools",
                        label: "Tools",
                        content: (
                          <ToolsPanel
                            client={gateway}
                            project={opened.project}
                            tool={toolsTool}
                            onTool={setToolsTool}
                            onUseProcessTheme={() => setSession((current) => ({ ...current, theme: "nightwatch" }))}
                          />
                        ),
                      },
                    ]
                  : []),
                {
                  id: "find",
                  label: "Find",
                  content: (
                    <FindReplacePanel
                      views={project.views}
                      onReplaceViews={(views) => views.forEach((view) => saved.replaceView(view))}
                      onReveal={(path, id) => {
                        openView(path);
                        setSelectedId(id);
                      }}
                    />
                  ),
                },
                {
                  id: "diagnostics",
                  label: "Diagnostics",
                  content: (
                    <DiagnosticsPanel
                      view={active}
                      meter={diagnostics}
                      client={opened.mode === "gateway" ? gateway : null}
                      onOpenConsole={() => setPanel("console")}
                    />
                  ),
                },
                { id: "console", label: "Console", content: <ConsolePanel client={gateway} /> },
              ]}
            />
          ),
        }}
      >
        <section className="oip-stage" aria-label="Canvas">
          {opened.mode === "gateway" ? (
            <ProjectAccessBar
              state={access}
              resource={(() => {
                const name = storedName(active.metadata.path);
                return name ? viewRef(name) : null;
              })()}
              label={active.metadata.name}
            />
          ) : null}
          {inheritedFrom ? (
            <div className="oip-inherited-banner" role="status" data-testid="inherited-banner">
              <span>
                Inherited from project {inheritedFrom}. Read-only here until you override it in {project.name}.
              </span>
              <button type="button" onClick={() => saved.overrideView(active.metadata.path)}>
                Override in this project
              </button>
            </div>
          ) : null}
          <Profiler id="canvas" onRender={(_id, _phase, actual) => diagnostics.render(actual)}>
          <Canvas
            view={active}
            views={project.views}
            registry={registry}
            transport={meteredTransportFor}
            session={session}
            styleClasses={project.styleClasses}
            themes={project.themes}
            scriptHost={scriptHost}
            onConnectionChange={onConnectionChange}
            selectedId={selectedId}
            onSelect={select}
            designTabs={designTabs}
            onDesignTab={(id, index) => {
              const node = selectedComponent(active, id);
              if (node?.kind !== "container") return;
              setDesignTabs((tabs) => ({ ...tabs, [id]: { index, base: node.props.currentTab ?? 0 } }));
            }}
            pipeTool={drawingPipe ? { onFinish: finishPipe, onCancel: () => setDrawingPipe(false) } : undefined}
            onFrame={(id, frame) => {
              const moved = updateNode(active, id, { frame });
              const node = findNode(moved.spec.root, id);
              commit(node?.kind === "component" && node.type === "pipe" ? moved : followPipes(moved));
            }}
            onDropPalette={(drag, at) => {
              const item = paletteItemFor(drag);
              if (item) addFromPalette(item, at);
            }}
            onDropTagOnView={(tag, at) => {
              const made = applyDropConfig(active, tag, at, (type) => registry.get(type), defaultsFromSchema);
              if (!made) return;
              commit(made.view);
              setSelectedId(made.id);
            }}
            onDropTag={(id, tag) => {
              const bound = bindDroppedTag(active, id, tag, (type) => registry.get(type)?.schema);
              // A type with no default property is selected, so the tag can be dropped on one of its property rows.
              if (bound) commit(bound.view);
              setSelectedId(id);
            }}
          />
          </Profiler>
        </section>
      </DockHost>
    </div>
  );
}

/** The palette entry a canvas drop names, or null for a type the palette does not offer. */
function paletteItemFor(drag: PaletteDrag): PaletteItem | null {
  const items = groupPalette(componentModules, containerModules).flatMap((group) => group.items);
  return items.find((item) => (drag.kind === "component" ? item.kind === "component" && item.type === drag.type : item.kind === "container" && item.layout === drag.layout)) ?? null;
}
