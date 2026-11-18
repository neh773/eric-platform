import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode, type RefObject } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { Icon } from "./display2";
import { iconGlyph } from "./icons";
import { linkTarget } from "./inputs2";
import { readBoolean, readString } from "./read";

/** C-18 navigation components: horizontal menu, menu tree and tree.
 * Written from the public pages below; the code, layout and wording are our own.
 *
 * Navigation palette: -
 * Horizontal menu:    -
 * Menu tree:          -
 * Tree:               -
 *
 * Activating a leaf item records its path in `selected`, announces it with a bubbling DOM event, and
 * moves the session to the item's own page-path `target` through the page runtime's `navigate` hook
 * (PageSession), like the link component. A page-path item never reloads the document. The click then
 * reaches the component node, so its `onClick` actions still run. Opening a submenu, drilling into a
 * menu tree level or expanding a tree branch stops the click so it does not trigger the node's actions.
 */

export interface NavItem {
  label: string;
  /** Page path (`/pumps`), http(s) URL, or empty. */
  target: string;
  icon: string;
  enabled: boolean;
  items: NavItem[];
}

/** Bubbling DOM events fired on the item element. `detail` carries the item path (index path such as `0/1`). */
export const navEvents = {
  select: "oip-item-select",
  expand: "oip-item-expand",
} as const;

export interface NavSelectDetail {
  path: string;
  label: string;
  target: string;
}

export interface NavExpandDetail {
  path: string;
  label: string;
  expanded: boolean;
}

function asItem(entry: unknown, depth: number): NavItem | null {
  if (typeof entry === "string") return entry.trim() ? { label: entry.trim(), target: "", icon: "", enabled: true, items: [] } : null;
  if (entry === null || typeof entry !== "object" || Array.isArray(entry)) return null;
  const record = entry as Record<string, unknown>;
  const label = [record.label, record.text].find((value): value is string => typeof value === "string" && value.trim() !== "");
  if (!label) return null;
  const target = [record.target, record.url, record.page].find((value): value is string => typeof value === "string") ?? "";
  const children = depth < 8 && Array.isArray(record.items) ? record.items : [];
  return {
    label: label.trim(),
    target: target.trim(),
    icon: typeof record.icon === "string" ? record.icon : "",
    enabled: record.enabled !== false,
    items: children.map((child) => asItem(child, depth + 1)).filter((child): child is NavItem => child !== null),
  };
}

/** Reads menu or tree items from a bound array (`[{ label | text, target | url, icon, enabled, items }]`)
 * or from inspector text: comma-separated entries of the form `Parent > Child = /page`. Entries that share
 * a parent label are merged under one parent, in first-seen order.
 */
export function parseNavItems(raw: unknown): NavItem[] {
  if (Array.isArray(raw)) return raw.map((entry) => asItem(entry, 0)).filter((item): item is NavItem => item !== null);
  if (typeof raw !== "string") return [];
  const root: NavItem[] = [];
  for (const part of raw.split(",")) {
    const entry = part.trim();
    if (!entry) continue;
    const equals = entry.indexOf("=");
    const trail = (equals < 0 ? entry : entry.slice(0, equals)).split(">").map((name) => name.trim()).filter(Boolean);
    const target = equals < 0 ? "" : entry.slice(equals + 1).trim();
    if (trail.length === 0) continue;
    let level = root;
    trail.forEach((name, index) => {
      let item = level.find((candidate) => candidate.label === name);
      if (!item) {
        item = { label: name, target: "", icon: "", enabled: true, items: [] };
        level.push(item);
      }
      if (index === trail.length - 1 && target) item.target = target;
      level = item.items;
    });
  }
  return root;
}

/** Item at an index path such as `0/1`, or undefined. */
export function itemAt(items: readonly NavItem[], path: string): NavItem | undefined {
  if (!path) return undefined;
  let level = items;
  let found: NavItem | undefined;
  for (const part of path.split("/")) {
    if (!/^\d+$/.test(part)) return undefined;
    found = level[Number(part)];
    if (!found) return undefined;
    level = found.items;
  }
  return found;
}

function childPath(parent: string, index: number): string {
  return parent ? `${parent}/${index}` : String(index);
}

/** True when `path` is `ancestor` or below it. */
function within(path: string, ancestor: string): boolean {
  return path === ancestor || path.startsWith(`${ancestor}/`);
}

export function emit<T>(element: Element, name: string, detail: T) {
  element.dispatchEvent(new CustomEvent<T>(name, { bubbles: true, detail }));
}

/** Click on a leaf item. Records the selection and fires the select event. A page path suppresses the
 * browser's own navigation and the click carries on to the node's `onClick` actions; an http(s) target is
 * left to the browser; a disabled item or a target with another scheme stops here.
 */
export function activateLeaf(
  event: MouseEvent<HTMLElement> | KeyboardEvent<HTMLElement>,
  item: NavItem,
  path: string,
  enabled: boolean,
  onChange: HmiComponentProps["onChange"],
  navigate: HmiComponentProps["navigate"],
): boolean {
  const target = item.target ? linkTarget(item.target) : null;
  if (!enabled || !item.enabled || target?.kind === "blocked") {
    event.preventDefault();
    event.stopPropagation();
    return false;
  }
  if (target?.kind !== "external") event.preventDefault();
  onChange("selected", path);
  emit<NavSelectDetail>(event.currentTarget, navEvents.select, { path, label: item.label, target: item.target });
  if (target?.kind === "page") navigate?.(target.path);
  return true;
}

export function ItemIcon({ name }: { name: string }) {
  if (!name || !iconGlyph(name)) return null;
  return (
    <span className="oip-nav-icon" aria-hidden="true">
      <Icon nodeId="" locale="" onChange={() => {}} props={{ name, strokeWidth: 2 }} />
    </span>
  );
}

export function Chevron({ open, side = "down" }: { open?: boolean; side?: "down" | "right" | "left" }) {
  const d = side === "left" ? "M10 3 L5 8 L10 13" : side === "right" ? "M6 3 L11 8 L6 13" : "M3 6 L8 11 L13 6";
  return (
    <svg className="oip-nav-chevron" viewBox="0 0 16 16" aria-hidden="true" data-open={open || undefined}>
      <path d={d} />
    </svg>
  );
}

export function hrefFor(item: NavItem, enabled: boolean, pageHref: HmiComponentProps["pageHref"]): string | undefined {
  if (!enabled || !item.enabled || !item.target) return undefined;
  const target = linkTarget(item.target);
  if (target.kind === "page") return pageHref ? pageHref(target.path) : target.path;
  return target.kind === "external" ? target.url : undefined;
}

export function externalProps(item: NavItem) {
  return item.target && linkTarget(item.target).kind === "external" ? { rel: "noopener noreferrer" } : {};
}

/** Enter and Space activate a leaf as a click does, including leaves without an `href`. */
export function onLeafKey(event: KeyboardEvent<HTMLElement>) {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  event.stopPropagation();
  event.currentTarget.click();
}

/** Moves focus once the next render has mounted the element, for keys that also open or close a level. */
function usePendingFocus(root: RefObject<HTMLElement | null>) {
  const pending = useRef<string[] | null>(null);
  // A key can request focus without changing what is open (Right on an open submenu); the counter makes sure
  // a render, and so the effect, still follows.
  const [, rerender] = useState(0);
  useEffect(() => {
    if (pending.current === null) return;
    const selectors = pending.current;
    pending.current = null;
    for (const selector of selectors) {
      const element = root.current?.querySelector<HTMLElement>(selector);
      if (element) {
        element.focus();
        return;
      }
    }
  });
  /** The first selector that matches after the next render gets focus. */
  return (...selectors: string[]) => {
    pending.current = selectors;
    rerender((count) => count + 1);
  };
}

const focusable = (element: HTMLElement) => !(element as HTMLButtonElement).disabled && element.getAttribute("aria-disabled") !== "true";

/** Enabled menu items directly inside a menubar or menu list. */
function menuItemsOf(list: Element | null): HTMLElement[] {
  if (!list) return [];
  return Array.from(list.querySelectorAll<HTMLElement>(":scope > li > .oip-hmenu-item")).filter(focusable);
}

function parentOf(path: string): string {
  return path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
}

// ---------------------------------------------------------------------------------------------
// Horizontal menu

/** Keyboard: the menubar is one tab stop (roving tabindex). Left/Right/Home/End move along the bar,
 * Down opens a submenu and focuses its first item, Up/Down/Home/End move within a submenu, Right opens a
 * nested submenu, Left closes one level, Escape closes all, and Enter/Space activate.
 */
export function HorizontalMenu({ props, onChange, navigate, pageHref }: HmiComponentProps) {
  const label = readString(props, "label", "Main menu");
  const enabled = readBoolean(props, "enabled", true);
  const selected = readString(props, "selected");
  const align = props.align === "center" || props.align === "end" ? props.align : "start";
  const items = useMemo(() => parseNavItems(props.items), [props.items]);
  const [open, setOpen] = useState<string>("");
  const [activeTop, setActiveTop] = useState<string>("");
  const rootRef = useRef<HTMLElement>(null);
  const focusLater = usePendingFocus(rootRef);

  useEffect(() => {
    if (!open) return;
    const onDocument = (event: Event) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen("");
    };
    document.addEventListener("pointerdown", onDocument);
    return () => document.removeEventListener("pointerdown", onDocument);
  }, [open]);

  const topEnabled = items.map((item, index) => ({ path: String(index), ok: enabled && item.enabled })).filter((entry) => entry.ok);
  const tabStop = topEnabled.some((entry) => entry.path === activeTop) ? activeTop : topEnabled[0]?.path ?? "";

  const openAndFocusFirst = (path: string) => {
    setOpen(path);
    focusLater(`ul[data-parent="${path}"] > li > .oip-hmenu-item:not(:disabled):not([aria-disabled="true"])`);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    const element = event.target as HTMLElement;
    if (!element.classList.contains("oip-hmenu-item")) return;
    const path = element.dataset.path ?? "";
    const depth = path.split("/").length - 1;
    const hasChildren = element.getAttribute("aria-haspopup") === "menu";
    const siblings = menuItemsOf(element.closest("ul"));
    const index = siblings.indexOf(element);
    const move = (step: number | "first" | "last") => {
      if (siblings.length === 0) return;
      const next = step === "first" ? 0 : step === "last" ? siblings.length - 1 : (index + step + siblings.length) % siblings.length;
      const target = siblings[next];
      target?.focus();
      if (depth === 0 && target?.dataset.path) setActiveTop(target.dataset.path);
    };
    let handled = true;
    if (event.key === "Escape") {
      if (!open) return;
      setOpen("");
      focusLater(`[data-path="${path.split("/")[0]}"]`);
    } else if (depth === 0) {
      if (event.key === "ArrowRight") move(1);
      else if (event.key === "ArrowLeft") move(-1);
      else if (event.key === "Home") move("first");
      else if (event.key === "End") move("last");
      else if (event.key === "ArrowDown" && hasChildren) openAndFocusFirst(path);
      else handled = false;
    } else {
      if (event.key === "ArrowDown") move(1);
      else if (event.key === "ArrowUp") move(-1);
      else if (event.key === "Home") move("first");
      else if (event.key === "End") move("last");
      else if (event.key === "ArrowRight" && hasChildren) openAndFocusFirst(path);
      else if (event.key === "ArrowLeft") {
        // Close the submenu holding this item: the open path becomes the parent's own level.
        const parent = parentOf(path);
        setOpen(parentOf(parent));
        focusLater(`[data-path="${parent}"]`);
      } else handled = false;
    }
    if (handled) event.preventDefault();
  };

  const renderList = (list: readonly NavItem[], parent: string, depth: number): ReactNode =>
    list.map((item, index) => {
      const path = childPath(parent, index);
      const hasChildren = item.items.length > 0;
      const isOpen = hasChildren && within(open, path);
      const current = selected !== "" && within(selected, path);
      const disabled = !enabled || !item.enabled;
      const tabIndex = depth === 0 && path === tabStop ? 0 : -1;
      const content = (
        <>
          <ItemIcon name={item.icon} />
          <span className="oip-nav-label">{item.label}</span>
          {hasChildren ? <Chevron open={isOpen} side={depth === 0 ? "down" : "right"} /> : null}
        </>
      );
      return (
        <li key={path} role="none" className="oip-hmenu-entry">
          {hasChildren ? (
            <button
              type="button"
              role="menuitem"
              className="oip-hmenu-item"
              data-path={path}
              data-current={current || undefined}
              aria-haspopup="menu"
              aria-expanded={isOpen}
              disabled={disabled}
              tabIndex={tabIndex}
              onFocus={depth === 0 ? () => setActiveTop(path) : undefined}
              onClick={(event) => {
                event.stopPropagation();
                setOpen(isOpen ? parent : path);
                emit<NavExpandDetail>(event.currentTarget, navEvents.expand, { path, label: item.label, expanded: !isOpen });
              }}
            >
              {content}
            </button>
          ) : (
            <a
              role="menuitem"
              className="oip-hmenu-item"
              data-path={path}
              data-current={current || undefined}
              aria-current={current ? "page" : undefined}
              aria-disabled={disabled || undefined}
              tabIndex={disabled ? -1 : tabIndex}
              href={hrefFor(item, enabled, pageHref)}
              {...externalProps(item)}
              onFocus={depth === 0 ? () => setActiveTop(path) : undefined}
              onKeyDown={onLeafKey}
              onClick={(event) => {
                if (activateLeaf(event, item, path, enabled, onChange, navigate)) setOpen("");
              }}
            >
              {content}
            </a>
          )}
          {isOpen ? (
            <ul role="menu" className="oip-hmenu-menu" aria-label={item.label} data-depth={depth + 1} data-parent={path}>
              {renderList(item.items, path, depth + 1)}
            </ul>
          ) : null}
        </li>
      );
    });

  return (
    <nav ref={rootRef} className="oip-hmenu" aria-label={label} data-align={align} onKeyDown={onKeyDown}>
      <ul role="menubar" className="oip-hmenu-bar" aria-label={label}>
        {renderList(items, "", 0)}
      </ul>
    </nav>
  );
}

// ---------------------------------------------------------------------------------------------
// Menu tree (drill-down)

export function MenuTree({ props, onChange, navigate, pageHref }: HmiComponentProps) {
  const label = readString(props, "label", "Menu");
  const enabled = readBoolean(props, "enabled", true);
  const selected = readString(props, "selected");
  const backLabel = readString(props, "backLabel", "Back");
  const items = useMemo(() => parseNavItems(props.items), [props.items]);
  const [level, setLevel] = useState("");
  const rootRef = useRef<HTMLElement>(null);
  const focusLater = usePendingFocus(rootRef);
  const parent = itemAt(items, level);
  // A level that no longer exists (items changed) falls back to the top.
  const shownPath = parent ? level : "";
  const shown = parent ? parent.items : items;

  /** Drilling in names the opened item and focuses the new level's first item; stepping back names the
   * level being left and focuses the item that opened it.
   */
  const goTo = (next: string, element: HTMLElement, item: NavItem, expanded: boolean) => {
    setLevel(next);
    if (expanded) focusLater('.oip-menutree-list > li > .oip-menutree-item:not(:disabled):not([aria-disabled="true"])', ".oip-menutree-back");
    else focusLater(`[data-path="${shownPath}"]`, ".oip-menutree-list > li > .oip-menutree-item");
    emit<NavExpandDetail>(element, navEvents.expand, { path: expanded ? next : shownPath, label: item.label, expanded });
  };

  return (
    <nav ref={rootRef} className="oip-menutree" aria-label={label} data-level={shownPath}>
      {parent ? (
        <button
          type="button"
          className="oip-menutree-back"
          disabled={!enabled}
          onClick={(event) => {
            event.stopPropagation();
            goTo(parentOf(shownPath), event.currentTarget, parent, false);
          }}
        >
          <Chevron side="left" />
          <span className="oip-nav-label">{backLabel}</span>
          <span className="oip-menutree-parent">{parent.label}</span>
        </button>
      ) : null}
      <ul className="oip-menutree-list" aria-label={parent ? parent.label : label}>
        {shown.map((item, index) => {
          const path = childPath(shownPath, index);
          const hasChildren = item.items.length > 0;
          const current = selected !== "" && within(selected, path);
          const disabled = !enabled || !item.enabled;
          const content = (
            <>
              <ItemIcon name={item.icon} />
              <span className="oip-nav-label">{item.label}</span>
              {hasChildren ? <Chevron side="right" /> : null}
            </>
          );
          return (
            <li key={path}>
              {hasChildren ? (
                <button
                  type="button"
                  className="oip-menutree-item"
                  data-path={path}
                  data-current={current || undefined}
                  disabled={disabled}
                  aria-label={`${item.label}, ${item.items.length} items`}
                  onClick={(event) => {
                    event.stopPropagation();
                    goTo(path, event.currentTarget, item, true);
                  }}
                >
                  {content}
                </button>
              ) : (
                <a
                  className="oip-menutree-item"
                  data-path={path}
                  data-current={current || undefined}
                  aria-current={current ? "page" : undefined}
                  aria-disabled={disabled || undefined}
                  tabIndex={disabled ? -1 : 0}
                  href={hrefFor(item, enabled, pageHref)}
                  {...externalProps(item)}
                  onKeyDown={onLeafKey}
                  onClick={(event) => activateLeaf(event, item, path, enabled, onChange, navigate)}
                >
                  {content}
                </a>
              )}
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

// ---------------------------------------------------------------------------------------------
// Tree

export function readExpanded(raw: unknown): string[] {
  const parts = Array.isArray(raw) ? raw : typeof raw === "string" ? raw.split(",") : [];
  const out = new Set<string>();
  for (const part of parts) {
    if (typeof part !== "string") continue;
    const path = part.trim();
    if (/^\d+(\/\d+)*$/.test(path)) out.add(path);
  }
  return [...out];
}

interface VisibleRow {
  item: NavItem;
  path: string;
  depth: number;
  parent: string;
}

/** Rows in display order: an item's children follow it only when it is expanded. */
export function visibleRows(items: readonly NavItem[], expanded: ReadonlySet<string>, parent = "", depth = 1): VisibleRow[] {
  const rows: VisibleRow[] = [];
  items.forEach((item, index) => {
    const path = childPath(parent, index);
    rows.push({ item, path, depth, parent });
    if (item.items.length > 0 && expanded.has(path)) rows.push(...visibleRows(item.items, expanded, path, depth + 1));
  });
  return rows;
}

export function Tree({ props, onChange, navigate }: HmiComponentProps) {
  const label = readString(props, "label", "Tree");
  const enabled = readBoolean(props, "enabled", true);
  const showLines = readBoolean(props, "showLines", true);
  const selected = readString(props, "selected");
  const items = useMemo(() => parseNavItems(props.items), [props.items]);
  const expandedKey = readExpanded(props.expanded).join(",");
  const [expanded, setExpanded] = useState<string[]>(() => readExpanded(props.expanded));
  const [focus, setFocus] = useState<string>("");
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    setExpanded(expandedKey ? expandedKey.split(",") : []);
  }, [expandedKey]);

  const openSet = useMemo(() => new Set(expanded), [expanded]);
  const rows = useMemo(() => visibleRows(items, openSet), [items, openSet]);
  const focusPath = rows.some((row) => row.path === focus) ? focus : rows.some((row) => row.path === selected) ? selected : rows[0]?.path ?? "";

  const focusRow = (path: string) => {
    setFocus(path);
    listRef.current?.querySelector<HTMLElement>(`[data-path="${path}"]`)?.focus();
  };

  const toggle = (row: VisibleRow, element: Element, open?: boolean) => {
    if (!enabled || row.item.items.length === 0) return;
    const isOpen = openSet.has(row.path);
    const next = open ?? !isOpen;
    if (next === isOpen) return;
    const list = next ? [...expanded, row.path] : expanded.filter((path) => !within(path, row.path));
    setExpanded(list);
    onChange("expanded", list.join(","));
    emit<NavExpandDetail>(element, navEvents.expand, { path: row.path, label: row.item.label, expanded: next });
  };

  const select = (row: VisibleRow, event: MouseEvent<HTMLElement> | KeyboardEvent<HTMLElement>) => {
    setFocus(row.path);
    if (!enabled || !row.item.enabled) {
      event.stopPropagation();
      return;
    }
    onChange("selected", row.path);
    emit<NavSelectDetail>(event.currentTarget, navEvents.select, { path: row.path, label: row.item.label, target: row.item.target });
    // A tree item with a page-path target navigates; any other target (or none) only selects.
    const target = row.item.target ? linkTarget(row.item.target) : null;
    if (target?.kind === "page") navigate?.(target.path);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>, row: VisibleRow, index: number) => {
    const isOpen = openSet.has(row.path);
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      const next = rows[index + (event.key === "ArrowDown" ? 1 : -1)];
      if (next) focusRow(next.path);
    } else if (event.key === "Home") {
      if (rows[0]) focusRow(rows[0].path);
    } else if (event.key === "End") {
      const last = rows[rows.length - 1];
      if (last) focusRow(last.path);
    } else if (event.key === "ArrowRight") {
      if (row.item.items.length > 0 && !isOpen) toggle(row, event.currentTarget, true);
      else if (isOpen) focusRow(childPath(row.path, 0));
    } else if (event.key === "ArrowLeft") {
      if (isOpen) toggle(row, event.currentTarget, false);
      else if (row.parent) focusRow(row.parent);
    } else if (event.key === "Enter" || event.key === " ") {
      // Keyboard selection is a click on the row, so the node's onClick actions run as for a mouse click.
      event.preventDefault();
      event.currentTarget.click();
      return;
    } else {
      return;
    }
    event.preventDefault();
  };

  return (
    <ul ref={listRef} role="tree" className="oip-tree" aria-label={label} aria-disabled={!enabled || undefined} data-lines={showLines || undefined}>
      {rows.map((row, index) => {
        const hasChildren = row.item.items.length > 0;
        const isOpen = hasChildren && openSet.has(row.path);
        const isSelected = selected === row.path;
        return (
          <li
            key={row.path}
            role="treeitem"
            className="oip-tree-row"
            data-path={row.path}
            aria-level={row.depth}
            aria-expanded={hasChildren ? isOpen : undefined}
            aria-selected={isSelected}
            aria-disabled={!row.item.enabled || undefined}
            tabIndex={row.path === focusPath ? 0 : -1}
            style={{ paddingInlineStart: `${(row.depth - 1) * 18 + 4}px` }}
            onClick={(event) => select(row, event)}
            onKeyDown={(event) => onKeyDown(event, row, index)}
          >
            {hasChildren ? (
              <span
                className="oip-tree-twisty"
                aria-hidden="true"
                onClick={(event) => {
                  event.stopPropagation();
                  setFocus(row.path);
                  toggle(row, event.currentTarget.parentElement ?? event.currentTarget);
                }}
              >
                <Chevron side={isOpen ? "down" : "right"} />
              </span>
            ) : (
              <span className="oip-tree-twisty oip-tree-leaf" aria-hidden="true" />
            )}
            <ItemIcon name={row.item.icon} />
            <span className="oip-nav-label">{row.item.label}</span>
          </li>
        );
      })}
    </ul>
  );
}
