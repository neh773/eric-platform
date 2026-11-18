import { memo, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent, MutableRefObject, RefObject } from "react";
import type { TagUpdate } from "@oip/hmi-runtime";
import type { BrowseNode } from "./gatewayClient";
import { TAG_DRAG_TYPE, encodeDraggedTag, type DraggedTag } from "./tagDrop";
import { ROOT_PATH, TagSourcesContext, isUnreachable, type Browse, type LiveTags, type TagSources } from "./tagSource";
import { showValue } from "./bindingModel";
import "./persistence.css";

/**
 * Tag browser: a lazy tree of tag providers, folders and tags. A folder loads its children the first time it is
 * expanded, one page at a time. Each tag row shows its data type and, while the row is on screen, its live value
 * and quality. Only rows in view are watched: a row scrolled out of the list, inside a collapsed folder, or in a
 * hidden side tab is not subscribed. A tag the user may not read (`readable: false`) is listed greyed, with no value
 * and no data type.
 *
 * The tree is one tab stop. Up and Down move between rows, Home and End jump to the first and last, Right opens a
 * folder or moves into it, Left closes it or moves to its parent, and Enter opens a folder, picks a tag (picker
 * mode) or loads the next page.
 *
 * Tag rows are drag sources: dropped on a component on the canvas they bind its default property, dropped on a
 * property row in the Inspector they bind that property. In picker mode (the binding editor's "Browse…"), a click
 * or Enter on a tag picks it instead.
 *
 * When no gateway answers, the browser shows the Studio's local tags if there are any, else a "Gateway not
 * reachable" state, both with a retry.
 */

export type { Browse } from "./tagSource";

export const BROWSE_PAGE = 200;
/** Upper bound on rows watched at once, whatever the list height. */
export const MAX_LIVE_ROWS = 150;
/** Live values reach the rows at most this often. */
export const LIVE_FLUSH_MS = 200;

/** The parts of IntersectionObserver the browser uses; tests pass their own. */
export interface RowObserver {
  observe(element: Element): void;
  unobserve(element: Element): void;
  disconnect(): void;
}
export type RowObserverFactory = (onChange: (entries: Array<{ target: Element; isIntersecting: boolean }>) => void, root: Element) => RowObserver;

const defaultObserver: RowObserverFactory | undefined =
  typeof IntersectionObserver === "undefined"
    ? undefined
    : (onChange, root) => new IntersectionObserver((entries) => onChange(entries), { root, rootMargin: "40px 0px" });

interface Children {
  nodes: BrowseNode[];
  more: boolean;
  loading: boolean;
  error: string | null;
}

type Source = "gateway" | "local" | "none";

/** One line of the flattened tree. Folders, tags and "Load more" take part in keyboard navigation. */
type Row =
  | { type: "folder"; key: string; node: BrowseNode; depth: number; open: boolean; parent: string | null }
  | { type: "tag"; key: string; node: BrowseNode; depth: number; parent: string | null }
  | { type: "more"; key: string; path: string; offset: number; depth: number; parent: string | null }
  | { type: "status"; key: string; text: string; alert: boolean; depth: number };

type NavRow = Exclude<Row, { type: "status" }>;

/** Folders whose loaded subtree holds a node matching [needle], computed in one pass. */
export function foldersWithMatches(tree: Record<string, Children>, needle: string): ReadonlySet<string> {
  const found = new Set<string>();
  if (needle === "") return found;
  const seen = new Set<string>();
  const visit = (path: string): boolean => {
    if (seen.has(path)) return found.has(path);
    seen.add(path);
    let hit = false;
    for (const node of tree[path]?.nodes ?? []) {
      if (matchesNeedle(node, needle)) hit = true;
      if (node.hasChildren && visit(node.path)) hit = true;
    }
    if (hit) found.add(path);
    return hit;
  };
  visit(ROOT_PATH);
  return found;
}

function matchesNeedle(node: BrowseNode, needle: string): boolean {
  return needle === "" || node.name.toLowerCase().includes(needle) || node.path.toLowerCase().includes(needle);
}

function flatten(tree: Record<string, Children>, expanded: ReadonlySet<string>, needle: string): Row[] {
  const holding = foldersWithMatches(tree, needle);
  const rows: Row[] = [];
  const walk = (path: string, depth: number, parent: string | null) => {
    const children = tree[path];
    if (!children) return;
    const shown = needle === "" ? children.nodes : children.nodes.filter((node) => matchesNeedle(node, needle) || (node.hasChildren && holding.has(node.path)));
    for (const node of shown) {
      if (node.hasChildren) {
        const open = expanded.has(node.path) || holding.has(node.path);
        rows.push({ type: "folder", key: node.path, node, depth, open, parent });
        if (open) walk(node.path, depth + 1, node.path);
      } else {
        rows.push({ type: "tag", key: node.path, node, depth, parent });
      }
    }
    if (children.loading) rows.push({ type: "status", key: `loading:${path}`, text: "Loading…", alert: false, depth });
    if (children.error) rows.push({ type: "status", key: `error:${path}`, text: children.error, alert: true, depth });
    if (!children.loading && !children.error && shown.length === 0) {
      rows.push({ type: "status", key: `empty:${path}`, text: needle === "" ? "No tags" : "No loaded tag matches", alert: false, depth });
    }
    if (children.more && !children.loading) rows.push({ type: "more", key: `more:${path}`, path, offset: children.nodes.length, depth, parent });
  };
  walk(ROOT_PATH, 1, null);
  return rows;
}

export interface TagBrowserProps {
  /** Defaults to the sources from [TagSourcesContext]. */
  sources?: TagSources;
  /** Picker mode: a click or Enter on a tag calls this, and rows are not dragged. */
  onPick?: (tag: DraggedTag) => void;
  /** Visibility observer; defaults to IntersectionObserver. Without one, every rendered row counts as visible. */
  observer?: RowObserverFactory;
  /** Accessible name of the tree. */
  label?: string;
  autoFocusFilter?: boolean;
}

/** Row handlers, read through a ref so memoised rows keep stable props. */
interface RowActions {
  activate(row: NavRow): void;
  focused(key: string): void;
}

export function TagBrowser({ sources: given, onPick, observer = defaultObserver, label = "Tags", autoFocusFilter }: TagBrowserProps) {
  const fromContext = useContext(TagSourcesContext);
  const sources = given ?? fromContext;
  const [source, setSource] = useState<Source>(sources.gateway ? "gateway" : sources.local ? "local" : "none");
  const [unreachable, setUnreachable] = useState(!sources.gateway);
  const [tree, setTree] = useState<Record<string, Children>>({});
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [filter, setFilter] = useState("");
  const [attempt, setAttempt] = useState(0);
  const browse: Browse | undefined = source === "gateway" ? sources.gateway : source === "local" ? sources.local : undefined;
  // A load from a source the browser has since left is dropped.
  const generation = useRef(0);

  const fallBack = useCallback(() => {
    setUnreachable(true);
    setSource(sources.local ? "local" : "none");
    setTree({});
    setExpanded(new Set());
  }, [sources.local]);

  const load = useCallback(
    async (path: string, offset: number) => {
      if (!browse) return;
      const gen = generation.current;
      setTree((current) => ({
        ...current,
        [path]: { nodes: offset === 0 ? [] : (current[path]?.nodes ?? []), more: false, loading: true, error: null },
      }));
      try {
        const page = await browse(path, offset, BROWSE_PAGE);
        if (gen !== generation.current) return;
        setTree((current) => ({
          ...current,
          [path]: {
            nodes: [...(offset === 0 ? [] : (current[path]?.nodes ?? [])), ...page.nodes],
            more: page.more,
            loading: false,
            error: null,
          },
        }));
      } catch (error) {
        if (gen !== generation.current) return;
        if (source === "gateway" && isUnreachable(error)) {
          generation.current += 1;
          fallBack();
          return;
        }
        setTree((current) => ({
          ...current,
          [path]: { nodes: current[path]?.nodes ?? [], more: false, loading: false, error: error instanceof Error ? error.message : String(error) },
        }));
      }
    },
    [browse, source, fallBack],
  );

  useEffect(() => {
    void load(ROOT_PATH, 0);
  }, [load, attempt]);

  function retry() {
    generation.current += 1;
    setTree({});
    setExpanded(new Set());
    if (sources.gateway) {
      setUnreachable(false);
      setSource("gateway");
    }
    setAttempt((value) => value + 1);
  }

  function setOpen(path: string, opening: boolean) {
    setExpanded((current) => {
      if (current.has(path) === opening) return current;
      const next = new Set(current);
      if (opening) next.add(path);
      else next.delete(path);
      return next;
    });
    // A folder whose load failed is loaded again when it is reopened.
    if (opening && (!tree[path] || tree[path]!.error)) void load(path, 0);
  }

  const needle = filter.trim().toLowerCase();
  const rows = useMemo(() => flatten(tree, expanded, needle), [tree, expanded, needle]);
  const nav = useMemo(() => rows.filter((row): row is NavRow => row.type !== "status"), [rows]);

  // Roving tab stop: one row of the tree is tabbable.
  const [active, setActive] = useState<string | null>(null);
  const activeKey = active !== null && nav.some((row) => row.key === active) ? active : (nav[0]?.key ?? null);
  const elements = useRef(new Map<string, HTMLElement>());

  // Visibility: rows in view, from the observer. Rows that unmount (collapsed, filtered, tab switched away) leave it.
  const [scrollRoot, setScrollRoot] = useState<HTMLDivElement | null>(null);
  const [visible, setVisible] = useState<ReadonlySet<string>>(() => new Set());
  const watcher = useRef<RowObserver | null>(null);
  const tagElements = useRef(new Map<string, Element>());
  const observerRef = useRef(observer);
  observerRef.current = observer;
  useEffect(() => {
    if (!observer || !scrollRoot) return undefined;
    const current = observer((entries) => {
      setVisible((before) => {
        let next: Set<string> | null = null;
        for (const entry of entries) {
          const path = (entry.target as HTMLElement).dataset.tagPath;
          if (!path || !tagElements.current.has(path)) continue;
          const has = before.has(path);
          if (entry.isIntersecting === has) continue;
          next ??= new Set(before);
          if (entry.isIntersecting) next.add(path);
          else next.delete(path);
        }
        return next ?? before;
      });
    }, scrollRoot);
    watcher.current = current;
    for (const element of tagElements.current.values()) current.observe(element);
    return () => {
      current.disconnect();
      if (watcher.current === current) watcher.current = null;
      // The rows are observed again by the next observer; until then none counts as in view.
      setVisible((before) => (before.size === 0 ? before : new Set()));
    };
  }, [observer, scrollRoot]);

  // One stable ref callback per row. It is dropped when the row unmounts, so the cache only holds rows on screen.
  const rowRefs = useRef(new Map<string, (element: HTMLElement | null) => void>());
  const rowRef = useCallback((key: string, isTag: boolean) => {
    const cached = rowRefs.current.get(key);
    if (cached) return cached;
    let mounted: HTMLElement | null = null;
    const ref = (element: HTMLElement | null) => {
      if (mounted && mounted !== element) {
        if (elements.current.get(key) === mounted) elements.current.delete(key);
        if (isTag && tagElements.current.get(key) === mounted) {
          watcher.current?.unobserve(mounted);
          tagElements.current.delete(key);
          setVisible((current) => {
            if (!current.has(key)) return current;
            const next = new Set(current);
            next.delete(key);
            return next;
          });
        }
      }
      mounted = element;
      if (!element) {
        if (rowRefs.current.get(key) === ref) rowRefs.current.delete(key);
        return;
      }
      elements.current.set(key, element);
      if (isTag) {
        tagElements.current.set(key, element);
        if (watcher.current) watcher.current.observe(element);
        else if (!observerRef.current) setVisible((current) => (current.has(key) ? current : new Set(current).add(key)));
      }
    };
    rowRefs.current.set(key, ref);
    return ref;
  }, []);

  const readable = useMemo(() => {
    const out = new Set<string>();
    for (const children of Object.values(tree)) for (const node of children.nodes) if (!node.hasChildren && node.readable !== false) out.add(node.path);
    return out;
  }, [tree]);
  const watched = useMemo(() => [...visible].filter((path) => readable.has(path)).slice(0, MAX_LIVE_ROWS), [visible, readable]);
  const watchedSet = useMemo(() => new Set(watched), [watched]);
  const values = useLiveValues(sources.live, watched);

  function focusRow(key: string | undefined) {
    if (!key) return;
    setActive(key);
    elements.current.get(key)?.focus();
  }

  const actions = useRef<RowActions>({ activate: () => {}, focused: () => {} });
  actions.current = {
    activate(row) {
      setActive(row.key);
      if (row.type === "folder") setOpen(row.node.path, !row.open);
      else if (row.type === "more") void load(row.path, row.offset);
      else if (onPick) onPick({ path: row.node.path, dataType: row.node.dataType });
    },
    focused(key) {
      if (key !== active) setActive(key);
    },
  };

  function onTreeKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    // The row the key was pressed on; it is also the active row except right after a pointer focus change.
    let fromKey = activeKey;
    for (const [key, element] of elements.current) if (element === event.target) fromKey = key;
    const index = nav.findIndex((row) => row.key === fromKey);
    const row = nav[index];
    if (!row) return;
    let handled = true;
    switch (event.key) {
      case "ArrowDown":
        focusRow(nav[Math.min(index + 1, nav.length - 1)]?.key);
        break;
      case "ArrowUp":
        focusRow(nav[Math.max(index - 1, 0)]?.key);
        break;
      case "Home":
        focusRow(nav[0]?.key);
        break;
      case "End":
        focusRow(nav[nav.length - 1]?.key);
        break;
      case "ArrowRight":
        if (row.type === "folder" && !row.open) setOpen(row.node.path, true);
        else if (row.type === "folder" && nav[index + 1] && nav[index + 1]!.depth > row.depth) focusRow(nav[index + 1]!.key);
        break;
      case "ArrowLeft":
        if (row.type === "folder" && row.open && expanded.has(row.node.path)) setOpen(row.node.path, false);
        else if (row.parent) focusRow(row.parent);
        break;
      case "Enter":
      case " ":
        actions.current.activate(row);
        break;
      default:
        handled = false;
    }
    if (handled) event.preventDefault();
  }

  const picker = Boolean(onPick);
  return (
    <section className="oip-tagbrowser" aria-label={picker ? "Tag picker" : "Tag browser"} data-source={source}>
      <div className="oip-tagbrowser-head">
        <p>{source === "local" ? "Local tags" : "Tags"}</p>
        <button type="button" onClick={retry}>
          Refresh
        </button>
      </div>
      {unreachable ? (
        <div className="oip-tagbrowser-offline" role="status" data-testid="tags-offline">
          <span>{source === "local" ? "Gateway not reachable. Showing the Studio's local tags." : "Gateway not reachable."}</span>
          {sources.gateway || source === "none" ? (
            <button type="button" onClick={retry}>
              Retry
            </button>
          ) : null}
        </div>
      ) : null}
      {source !== "none" ? (
        <>
          <input
            type="search"
            className="oip-tagbrowser-filter"
            aria-label="Filter tags"
            placeholder="Filter loaded tags"
            value={filter}
            autoFocus={autoFocusFilter}
            onChange={(event) => setFilter(event.target.value)}
          />
          <div ref={setScrollRoot} className="oip-tagtree-scroll" role="tree" aria-label={label} data-live-count={watched.length} onKeyDown={onTreeKeyDown}>
            {rows.map((row) => {
              if (row.type === "status") {
                return (
                  <div key={row.key} className="oip-tagtree-status" role={row.alert ? "alert" : "none"} style={indent(row.depth)}>
                    {row.text}
                  </div>
                );
              }
              const tabbable = row.key === activeKey;
              if (row.type === "more") {
                return (
                  <MoreRow key={row.key} row={row} tabbable={tabbable} rowRef={rowRef(row.key, false)} actions={actions} />
                );
              }
              if (row.type === "folder") {
                return <FolderRow key={row.key} row={row} tabbable={tabbable} rowRef={rowRef(row.key, false)} actions={actions} />;
              }
              const canRead = row.node.readable !== false;
              return (
                <TagRow
                  key={row.key}
                  row={row}
                  tabbable={tabbable}
                  picker={picker}
                  live={canRead ? values.get(row.key) : undefined}
                  watched={canRead && watchedSet.has(row.key)}
                  rowRef={rowRef(row.key, true)}
                  actions={actions}
                />
              );
            })}
          </div>
        </>
      ) : null}
    </section>
  );
}

const indent = (depth: number) => ({ paddingLeft: `${4 + (depth - 1) * 14}px` });

type Actions = MutableRefObject<RowActions>;

const FolderRow = memo(function FolderRow({
  row,
  tabbable,
  rowRef,
  actions,
}: {
  row: Extract<Row, { type: "folder" }>;
  tabbable: boolean;
  rowRef: (element: HTMLElement | null) => void;
  actions: Actions;
}) {
  return (
    <div
      ref={rowRef}
      role="treeitem"
      aria-expanded={row.open}
      aria-level={row.depth}
      tabIndex={tabbable ? 0 : -1}
      className="oip-tagtree-folder"
      data-tag-path={row.node.path}
      style={indent(row.depth)}
      onClick={() => actions.current.activate(row)}
      onFocus={() => actions.current.focused(row.key)}
    >
      <span aria-hidden="true">{row.open ? "▾" : "▸"}</span> {row.node.name}
    </div>
  );
});

const MoreRow = memo(function MoreRow({
  row,
  tabbable,
  rowRef,
  actions,
}: {
  row: Extract<Row, { type: "more" }>;
  tabbable: boolean;
  rowRef: (element: HTMLElement | null) => void;
  actions: Actions;
}) {
  return (
    <button
      ref={rowRef}
      type="button"
      tabIndex={tabbable ? 0 : -1}
      className="oip-tagtree-more"
      style={indent(row.depth)}
      onClick={() => actions.current.activate(row)}
      onFocus={() => actions.current.focused(row.key)}
      // Enter and Space are handled by the tree, like on every other row.
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") event.preventDefault();
      }}
    >
      Load more
    </button>
  );
});

const TagRow = memo(function TagRow({
  row,
  tabbable,
  picker,
  live,
  watched,
  rowRef,
  actions,
}: {
  row: Extract<Row, { type: "tag" }>;
  tabbable: boolean;
  picker: boolean;
  live: TagUpdate | undefined;
  watched: boolean;
  rowRef: (element: HTMLElement | null) => void;
  actions: Actions;
}) {
  const node = row.node;
  const canRead = node.readable !== false;
  return (
    <div
      ref={rowRef}
      role="treeitem"
      aria-level={row.depth}
      aria-disabled={canRead ? undefined : true}
      tabIndex={tabbable ? 0 : -1}
      draggable={!picker}
      className="oip-tagtree-tag"
      style={indent(row.depth)}
      data-tag-path={node.path}
      data-readable={canRead ? undefined : "false"}
      data-live={watched ? "yes" : undefined}
      title={!canRead ? `${node.path}: you may not read this tag` : picker ? `Use ${node.path}` : `Drag ${node.path} onto a component or a property to bind it`}
      onClick={() => actions.current.activate(row)}
      onFocus={() => actions.current.focused(row.key)}
      onDragStart={(event) => {
        event.dataTransfer.setData(TAG_DRAG_TYPE, encodeDraggedTag({ path: node.path, dataType: node.dataType }));
        event.dataTransfer.setData("text/plain", node.path);
        event.dataTransfer.effectAllowed = "link";
      }}
    >
      <span className="oip-tagtree-name">{node.name}</span>
      {canRead && node.dataType ? <span className="oip-tagtree-type">{node.dataType}</span> : null}
      {canRead ? <RowValue node={node} live={live} /> : <span className="oip-tagtree-denied">no access</span>}
    </div>
  );
});

function qualityOf(live: TagUpdate | undefined, node: BrowseNode): { level: "good" | "bad" | "uncertain"; label: string } | null {
  const raw = live ? (live.label ?? live.code) : node.quality;
  if (!raw) return null;
  const lower = raw.toLowerCase();
  const level = lower.startsWith("good") ? "good" : lower.startsWith("bad") || lower.startsWith("error") ? "bad" : "uncertain";
  return { level, label: raw };
}

function RowValue({ node, live }: { node: BrowseNode; live: TagUpdate | undefined }) {
  const quality = qualityOf(live, node);
  const value = live ? live.value : node.value;
  if (!quality && value === undefined) return null;
  return (
    <span className="oip-tagtree-value" data-quality={quality?.level}>
      {quality ? <span className="oip-quality-dot" data-quality={quality.level} title={quality.label} aria-label={quality.label} /> : null}
      <output>{value === undefined ? "—" : showValue(value)}</output>
    </span>
  );
}

/**
 * Current values of [paths], watched while they are listed. Updates are gathered and handed to React at most every
 * [LIVE_FLUSH_MS], so a fast tag does not re-render the panel on every change. When [live] changes, every watch moves
 * to the new source and the old source's values are dropped.
 */
function useLiveValues(live: LiveTags | undefined, paths: string[]): ReadonlyMap<string, TagUpdate> {
  const [values, setValues] = useState<ReadonlyMap<string, TagUpdate>>(() => new Map());
  const pending = useRef(new Map<string, TagUpdate>());
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const subs = useRef<{ live: LiveTags | undefined; offs: Map<string, () => void> }>({ live: undefined, offs: new Map() });
  const key = paths.join("\n");

  useEffect(() => {
    const state = subs.current;
    if (state.live !== live) {
      for (const off of state.offs.values()) off();
      state.offs.clear();
      state.live = live;
      pending.current = new Map();
      setValues((current) => (current.size === 0 ? current : new Map()));
    }
    if (!live) return;
    const want = new Set(key === "" ? [] : key.split("\n"));
    for (const [path, off] of state.offs) {
      if (!want.has(path)) {
        off();
        state.offs.delete(path);
      }
    }
    for (const path of want) {
      if (state.offs.has(path)) continue;
      state.offs.set(
        path,
        live.subscribe(path, (update) => {
          if (subs.current.live !== live) return;
          pending.current.set(path, update);
          if (timer.current !== null) return;
          timer.current = setTimeout(() => {
            timer.current = null;
            const batch = pending.current;
            pending.current = new Map();
            setValues((current) => {
              const next = new Map(current);
              for (const [p, u] of batch) next.set(p, u);
              return next;
            });
          }, LIVE_FLUSH_MS);
        }),
      );
    }
  }, [live, key]);

  useEffect(
    () => () => {
      const state = subs.current;
      for (const off of state.offs.values()) off();
      state.offs.clear();
      state.live = undefined;
      if (timer.current !== null) clearTimeout(timer.current);
      timer.current = null;
    },
    [],
  );
  return values;
}

const FOCUSABLE = "button, input, select, textarea, [tabindex]";

function focusables(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
    (element) => element.tabIndex >= 0 && !(element as HTMLButtonElement).disabled && !element.closest("[hidden]"),
  );
}

/**
 * The tag browser in a modal dialog, for picking a tag path with the keyboard. Focus stays inside: Tab and Shift+Tab
 * cycle through the dialog, focus that would leave (or is lost because its element went away) comes back to the
 * close button, and Escape closes it from anywhere.
 */
export function TagPickerDialog({
  onPick,
  onClose,
  returnFocus,
}: {
  onPick: (tag: DraggedTag) => void;
  onClose: () => void;
  /** Focused when the dialog closes (the button that opened it). */
  returnFocus?: RefObject<HTMLElement | null>;
}) {
  const dialog = useRef<HTMLDivElement | null>(null);
  const close = useRef<HTMLButtonElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const returnRef = useRef(returnFocus);
  returnRef.current = returnFocus;

  useEffect(() => {
    const root = dialog.current;
    if (!root) return undefined;
    const ensureInside = () => {
      const current = dialog.current;
      if (!current || current.contains(document.activeElement)) return;
      // The filter box when there is one, else the close button.
      (current.querySelector<HTMLElement>(".oip-tagbrowser-filter") ?? close.current ?? current).focus();
    };
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusables(root);
      event.preventDefault();
      if (items.length === 0) {
        root.focus();
        return;
      }
      const index = items.indexOf(document.activeElement as HTMLElement);
      const next = index < 0 ? (event.shiftKey ? items.length - 1 : 0) : (index + (event.shiftKey ? -1 : 1) + items.length) % items.length;
      items[next]!.focus();
    };
    const onFocusIn = (event: FocusEvent) => {
      if (!root.contains(event.target as Node | null)) ensureInside();
    };
    // A focused row or field that unmounts (a source falls back, a filter hides it) leaves focus on the page body.
    const mutations = typeof MutationObserver === "undefined" ? null : new MutationObserver(() => queueMicrotask(ensureInside));
    mutations?.observe(root, { childList: true, subtree: true });
    document.addEventListener("keydown", onKey, true);
    document.addEventListener("focusin", onFocusIn);
    ensureInside();
    return () => {
      mutations?.disconnect();
      document.removeEventListener("keydown", onKey, true);
      document.removeEventListener("focusin", onFocusIn);
      // After the trap is gone, so the focus may leave the dialog.
      returnRef.current?.current?.focus();
    };
  }, []);

  return (
    <div className="oip-tagpicker">
      <div ref={dialog} className="oip-tagpicker-panel" role="dialog" aria-modal="true" aria-label="Pick a tag" tabIndex={-1}>
        <header className="oip-tagpicker-head">
          <h3>Pick a tag</h3>
          <button ref={close} type="button" aria-label="Close tag picker" onClick={onClose}>
            ×
          </button>
        </header>
        <TagBrowser
          label="Tags to pick"
          autoFocusFilter
          onPick={(tag) => {
            onPick(tag);
            onClose();
          }}
        />
      </div>
    </div>
  );
}
