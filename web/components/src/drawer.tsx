import { useEffect, useId, useMemo, useRef, type ReactNode } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { activateLeaf, externalProps, hrefFor, ItemIcon, onLeafKey, parseNavItems, type NavItem } from "./navigation";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Navigation drawer (HMP-109): the self-hiding navigation drawer pattern as one component. A menu button stays in
 * the component's frame; the drawer slides over the page from one edge and hides itself again once the operator
 * picks a destination, presses Escape, or presses outside it. Written from the public pattern page; the code and
 * wording are our own.
 * doc source: FEATURES HMP-109
 *
 * Items are those of the other navigation components (`Parent > Child = /page` text, or an array of
 * `{label, target, icon, enabled, items}`), and picking one goes through the same page navigation and link filter.
 * `open` is a two-way property, so a binding or a script can open the drawer and follow the operator closing it.
 * With `autoHide` off the drawer stays open until the operator closes it with its button.
 */

export const navigationDrawerSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Navigation drawer",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Navigation" },
    buttonLabel: { type: "string", title: "Menu button label", default: "Open navigation" },
    title: { type: "string", title: "Drawer title", default: "Menu" },
    items: { type: "string", title: "Items (Parent > Child = /page, comma separated; or bind an array)", default: "Home = /, Pumps = /pumps" },
    selected: { type: "string", title: "Selected item path (0/1)", default: "" },
    open: { type: "boolean", title: "Open", default: false },
    edge: { type: "string", title: "Edge", enum: ["left", "right"], default: "left" },
    width: { type: "integer", title: "Drawer width (px)", minimum: 160, maximum: 640, default: 260 },
    autoHide: { type: "boolean", title: "Hide after a choice", default: true },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export function NavigationDrawer({ nodeId, props, onChange, navigate, pageHref }: HmiComponentProps) {
  const label = readString(props, "label", "Navigation");
  const buttonLabel = readString(props, "buttonLabel", "Open navigation");
  const title = readString(props, "title", "Menu");
  const enabled = readBoolean(props, "enabled", true);
  const open = enabled && readBoolean(props, "open", false);
  const autoHide = readBoolean(props, "autoHide", true);
  const selected = readString(props, "selected");
  const edge = props.edge === "right" ? "right" : "left";
  const width = Math.min(640, Math.max(160, Math.round(readNumber(props, "width", 260))));
  const items = useMemo(() => parseNavItems(props.items), [props.items]);
  const drawerId = `${useId()}-${nodeId}-drawer`;
  const toggle = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLElement>(null);
  const wasOpen = useRef(false);

  const close = (returnFocus: boolean) => {
    onChange("open", false);
    if (returnFocus) toggle.current?.focus();
  };

  // Escape hides the drawer from anywhere on the page, and focus goes back to the menu button.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.stopPropagation();
      onChange("open", false);
      toggle.current?.focus();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onChange]);

  // Opening moves focus into the drawer.
  useEffect(() => {
    if (open && !wasOpen.current) {
      drawer.current?.querySelector<HTMLElement>(".oip-drawer-link:not([aria-disabled='true'])")?.focus();
    }
    wasOpen.current = open;
  }, [open]);

  const renderList = (list: readonly NavItem[], parent: string): ReactNode => (
    <ul className="oip-drawer-list" role={parent ? "group" : "list"}>
      {list.map((item, index) => {
        const path = parent ? `${parent}/${index}` : String(index);
        const disabled = !enabled || !item.enabled;
        if (item.items.length > 0) {
          return (
            <li key={path} className="oip-drawer-group">
              <span className="oip-drawer-heading">
                <ItemIcon name={item.icon} />
                <span className="oip-nav-label">{item.label}</span>
              </span>
              {renderList(item.items, path)}
            </li>
          );
        }
        const current = selected === path;
        return (
          <li key={path}>
            <a
              className="oip-drawer-link"
              data-path={path}
              data-current={current || undefined}
              aria-current={current ? "page" : undefined}
              aria-disabled={disabled || undefined}
              tabIndex={disabled ? -1 : 0}
              href={hrefFor(item, enabled, pageHref)}
              {...externalProps(item)}
              onKeyDown={onLeafKey}
              onClick={(event) => {
                if (activateLeaf(event, item, path, enabled, onChange, navigate) && autoHide) close(false);
              }}
            >
              <ItemIcon name={item.icon} />
              <span className="oip-nav-label">{item.label}</span>
            </a>
          </li>
        );
      })}
    </ul>
  );

  return (
    <div className="oip-drawer-host" data-open={open || undefined} data-edge={edge}>
      <button
        ref={toggle}
        type="button"
        className="oip-drawer-toggle"
        aria-label={buttonLabel}
        aria-expanded={open}
        aria-controls={open ? drawerId : undefined}
        disabled={!enabled}
        onClick={(event) => {
          // Opening and closing the drawer is not a click on the node's own actions.
          event.stopPropagation();
          onChange("open", !open);
        }}
      >
        <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
          <path d="M3 5h14M3 10h14M3 15h14" />
        </svg>
      </button>
      {open ? (
        <>
          <button type="button" className="oip-drawer-scrim" tabIndex={-1} aria-label="Close navigation" onClick={(event) => { event.stopPropagation(); close(true); }} />
          <nav ref={drawer} id={drawerId} className="oip-drawer" aria-label={label} data-edge={edge} style={{ width }}>
            <header className="oip-drawer-head">
              <span className="oip-drawer-title">{title}</span>
              <button type="button" className="oip-drawer-close" aria-label="Close drawer" onClick={(event) => { event.stopPropagation(); close(true); }}>
                ×
              </button>
            </header>
            {renderList(items, "")}
          </nav>
        </>
      ) : null}
    </div>
  );
}
