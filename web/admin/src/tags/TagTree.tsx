import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { errorMessage } from "../access";
import type { ApiClient } from "../api";
import { ACCESS_DENIED, browse, type BrowseRow, type TagValue } from "./tagClient";
import { formatTagPath, formatValue, isGood, parseTagPath } from "./tagModel";
import { useLiveValues } from "./useLiveValues";

export const PAGE_SIZE = 100;

interface FolderState {
  nodes: BrowseRow[];
  more: boolean;
  loading: boolean;
  error: string | null;
}

export interface TreeSelection {
  path: string;
  row: BrowseRow | null;
}

/**
 * Provider and folder tree with live value, quality and timestamp per tag. Folders load one page at a
 * time (POST /api/v1/tags/browse, `offset`/`limit`); "Load more" fetches the next page. Values refresh
 * every second through POST /api/v1/tags/read as the signed-in caller. A tag the caller may not read
 * shows "Access denied" and no value.
 */
export function TagTree({
  client,
  providers,
  selected,
  onSelect,
  onDiagnose,
  reloadToken,
  reveal,
}: {
  client: ApiClient;
  providers: readonly string[];
  selected: string | null;
  onSelect: (selection: TreeSelection) => void;
  /** Open tag diagnostics for a tag path (TAG-003). */
  onDiagnose?: (path: string) => void;
  reloadToken: number;
  /** Expand every folder above this path (a tag just created or moved). */
  reveal?: string | null;
}) {
  const roots = useMemo(() => providers.map((name) => formatTagPath(name, "")), [providers]);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(roots.slice(0, 1)));
  const [folders, setFolders] = useState<Map<string, FolderState>>(() => new Map());

  /**
   * The request each folder is waiting on. A refresh (offset 0) replaces it and the older answer is
   * dropped; a "Load more" while anything is in flight for that folder is ignored (double clicks).
   */
  const inFlight = useRef(new Map<string, number>());
  const requests = useRef(0);

  const load = useCallback(
    async (path: string, offset: number) => {
      if (offset > 0 && inFlight.current.has(path)) return;
      const ticket = ++requests.current;
      inFlight.current.set(path, ticket);
      const current = () => inFlight.current.get(path) === ticket;
      setFolders((prev) => {
        const next = new Map(prev);
        const current = prev.get(path);
        next.set(path, { nodes: offset === 0 ? [] : (current?.nodes ?? []), more: false, loading: true, error: null });
        return next;
      });
      try {
        const page = await browse(client, path, offset, PAGE_SIZE);
        if (!current()) return;
        inFlight.current.delete(path);
        setFolders((prev) => {
          const next = new Map(prev);
          const before = offset === 0 ? [] : (prev.get(path)?.nodes ?? []);
          next.set(path, { nodes: [...before, ...page.nodes], more: page.more, loading: false, error: null });
          return next;
        });
      } catch (err) {
        if (!current()) return;
        inFlight.current.delete(path);
        setFolders((prev) => {
          const next = new Map(prev);
          next.set(path, { nodes: prev.get(path)?.nodes ?? [], more: false, loading: false, error: errorMessage(err, "Browse failed") });
          return next;
        });
      }
    },
    [client],
  );

  useEffect(() => {
    setExpanded((prev) => (prev.size === 0 && roots[0] ? new Set([roots[0]]) : prev));
  }, [roots]);

  useEffect(() => {
    if (!reveal) return;
    const chain = ancestors(reveal);
    setExpanded((prev) => (chain.every((p) => prev.has(p)) ? prev : new Set([...prev, ...chain])));
  }, [reveal]);

  // Load an expanded folder the first time it is opened.
  useEffect(() => {
    for (const path of expanded) if (!folders.has(path)) void load(path, 0);
  }, [expanded, folders, load]);

  // Reload every expanded folder when the token changes (after a create, save, move or delete).
  const lastToken = useRef(reloadToken);
  useEffect(() => {
    if (lastToken.current === reloadToken) return;
    lastToken.current = reloadToken;
    for (const path of expanded) void load(path, 0);
  }, [reloadToken, expanded, load]);

  function toggle(path: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  const rows = useMemo(() => {
    const out: { row: BrowseRow; depth: number }[] = [];
    const walk = (path: string, depth: number) => {
      for (const row of folders.get(path)?.nodes ?? []) {
        out.push({ row, depth });
        if (row.hasChildren && expanded.has(row.path)) walk(row.path, depth + 1);
      }
    };
    for (const root of roots) if (expanded.has(root)) walk(root, 1);
    return out;
  }, [folders, expanded, roots]);

  const livePaths = useMemo(() => rows.filter(({ row }) => row.kind === "tag").map(({ row }) => row.path), [rows]);
  const live = useLiveValues(client, livePaths);

  const renderFolderTail = (path: string, depth: number) => {
    const state = folders.get(path);
    if (!state) return null;
    return (
      <>
        {state.loading ? (
          <tr>
            <td colSpan={4} style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}>
              Loading.
            </td>
          </tr>
        ) : null}
        {state.error ? (
          <tr>
            <td colSpan={4} role="alert" style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}>
              {state.error}
            </td>
          </tr>
        ) : null}
        {state.more && !state.loading ? (
          <tr>
            <td colSpan={4} style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}>
              <button type="button" className="quiet" onClick={() => void load(path, state.nodes.length)}>
                Load more in {path}
              </button>
            </td>
          </tr>
        ) : null}
        {!state.loading && !state.error && state.nodes.length === 0 ? (
          <tr>
            <td colSpan={4} className="empty" style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}>
              Empty
            </td>
          </tr>
        ) : null}
      </>
    );
  };

  const body: ReactNode[] = [];
  const emit = (path: string, depth: number) => {
    for (const row of folders.get(path)?.nodes ?? []) {
      body.push(
        <TreeRow
          key={row.path}
          row={row}
          depth={depth}
          value={live.values.get(row.path)}
          expanded={expanded.has(row.path)}
          selected={selected === row.path}
          onToggle={() => toggle(row.path)}
          onSelect={() => onSelect({ path: row.path, row })}
          onDiagnose={onDiagnose}
        />,
      );
      if (row.hasChildren && expanded.has(row.path)) {
        emit(row.path, depth + 1);
        body.push(<Fragment key={`${row.path}#tail`}>{renderFolderTail(row.path, depth + 1)}</Fragment>);
      }
    }
  };
  for (const root of roots) {
    const open = expanded.has(root);
    body.push(
      <tr key={root} data-testid="tag-row" data-path={root} data-kind="provider" aria-selected={selected === root}>
        <td>
          <button type="button" className="tree-toggle" aria-label={`${open ? "Collapse" : "Expand"} ${root}`} onClick={() => toggle(root)}>
            {open ? "▾" : "▸"}
          </button>
          <button type="button" className="tree-name" onClick={() => onSelect({ path: root, row: null })}>
            {root}
          </button>
        </td>
        <td colSpan={3} className="muted">
          Tag provider
        </td>
      </tr>,
    );
    if (open) {
      emit(root, 1);
      body.push(<Fragment key={`${root}#tail`}>{renderFolderTail(root, 1)}</Fragment>);
    }
  }

  return (
    <div className="tag-tree" data-testid="tag-tree">
      {live.error ? <p role="status">Live values paused: {live.error}</p> : null}
      <table className="data-table" aria-label="Tags">
        <thead>
          <tr>
            <th>Name</th>
            <th>Value</th>
            <th>Quality</th>
            <th>Timestamp</th>
          </tr>
        </thead>
        <tbody>{body}</tbody>
      </table>
    </div>
  );
}

function TreeRow({
  row,
  depth,
  value,
  expanded,
  selected,
  onToggle,
  onSelect,
  onDiagnose,
}: {
  row: BrowseRow;
  depth: number;
  value: TagValue | undefined;
  expanded: boolean;
  selected: boolean;
  onToggle: () => void;
  onSelect: () => void;
  onDiagnose?: (path: string) => void;
}) {
  const live = value ?? (row.quality !== undefined ? { path: row.path, value: row.value, quality: row.quality, timestamp: row.timestamp ?? "" } : undefined);
  const tagLike = row.kind === "tag";
  // A tag row without a value in browse is one the caller may not read (TagApi.browse).
  const quality = live?.quality ?? (tagLike ? ACCESS_DENIED : undefined);
  const denied = quality === ACCESS_DENIED;
  return (
    <tr data-testid="tag-row" data-path={row.path} data-kind={row.kind} aria-selected={selected} className={selected ? "selected" : undefined}>
      <td style={{ paddingLeft: `${depth * 1.1}rem` }}>
        {row.hasChildren ? (
          <button type="button" className="tree-toggle" aria-label={`${expanded ? "Collapse" : "Expand"} ${row.name}`} onClick={onToggle}>
            {expanded ? "▾" : "▸"}
          </button>
        ) : (
          <span className="tree-toggle" aria-hidden="true" />
        )}
        <button type="button" className="tree-name" onClick={onSelect}>
          {row.name}
        </button>
        {tagLike && onDiagnose ? (
          <button
            type="button"
            className="quiet"
            data-testid="tag-diagnostics-open"
            aria-label={`Diagnostics for ${row.path}`}
            onClick={(event) => {
              event.stopPropagation();
              onDiagnose(row.path);
            }}
          >
            Diagnostics
          </button>
        ) : null}
        {row.kind === "udt_instance" ? <small className="muted"> UDT instance</small> : null}
        {row.kind === "udt_definition" ? <small className="muted"> UDT definition</small> : null}
        {row.dataType && tagLike ? <small className="muted"> {row.dataType}</small> : null}
      </td>
      <td data-testid="tag-value">{tagLike && !denied ? formatValue(live?.value, quality) : denied ? <em>Access denied</em> : ""}</td>
      <td data-testid="tag-quality" data-good={isGood(quality) ? "true" : "false"}>
        {quality ?? ""}
      </td>
      <td>{live?.timestamp && !denied ? formatTime(live.timestamp) : ""}</td>
    </tr>
  );
}

/** The provider root and every folder above `full`, outermost first. */
export function ancestors(full: string): string[] {
  const parsed = parseTagPath(full);
  const segments = parsed.path ? parsed.path.split("/") : [];
  const out = [formatTagPath(parsed.provider, "")];
  for (let i = 1; i < segments.length; i++) out.push(formatTagPath(parsed.provider, segments.slice(0, i).join("/")));
  return out;
}

export function formatTime(iso: string): string {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return iso;
  return new Date(ms).toLocaleTimeString();
}
