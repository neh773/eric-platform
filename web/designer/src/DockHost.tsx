import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import {
  DOCKS,
  PANEL_TITLES,
  hidePanel,
  isDefaultLayout,
  defaultLayout,
  loadLayout,
  moveToDock,
  saveLayout,
  showPanel,
  toggleCollapsed,
  type DockId,
  type DockLayout,
  type PanelId,
} from "./dockLayout";

const DOCK_LABEL: Record<DockId, string> = { left: "Left dock", right: "Right dock", bottom: "Bottom dock" };

/** The Studio's layout state, loaded from this browser and saved on every change. */
export function useDockLayout(): [DockLayout, (next: DockLayout) => void] {
  const [layout, setLayout] = useState<DockLayout>(loadLayout);
  useEffect(() => saveLayout(layout), [layout]);
  return [layout, setLayout];
}

export interface DockHostProps {
  layout: DockLayout;
  onLayout: (next: DockLayout) => void;
  panels: Record<PanelId, ReactNode>;
  /** The canvas, in the centre. */
  children: ReactNode;
}

/** Left, right and bottom docks around the canvas. Each panel has a header to move, collapse or hide it. */
export function DockHost({ layout, onLayout, panels, children }: DockHostProps) {
  return (
    <div className="oip-body">
      {layout.left.length > 0 ? (
        <Dock dock="left" className="oip-dock-left" layout={layout} onLayout={onLayout} panels={panels} />
      ) : null}
      {children}
      {layout.right.length > 0 ? (
        <Dock dock="right" className="oip-side" layout={layout} onLayout={onLayout} panels={panels} />
      ) : null}
      {layout.bottom.length > 0 ? (
        <Dock dock="bottom" className="oip-dock-bottom" layout={layout} onLayout={onLayout} panels={panels} />
      ) : null}
    </div>
  );
}

function Dock({ dock, className, layout, onLayout, panels }: { dock: DockId; className: string; layout: DockLayout; onLayout: (next: DockLayout) => void; panels: Record<PanelId, ReactNode> }) {
  return (
    <div className={`oip-dock ${className}`} data-dock={dock} role="group" aria-label={DOCK_LABEL[dock]}>
      {layout[dock].map((id) => {
        const collapsed = layout.collapsed.includes(id);
        const title = PANEL_TITLES[id];
        return (
          <section key={id} className="oip-dock-panel" data-panel={id} data-collapsed={collapsed ? "yes" : "no"} aria-label={`${title} panel`}>
            <header className="oip-dock-header">
              <h2>{title}</h2>
              <div role="toolbar" aria-label={`${title} layout`}>
                {DOCKS.filter((target) => target !== dock).map((target) => (
                  <button key={target} type="button" title={`Dock ${title} ${target}`} aria-label={`Dock ${title} ${target}`} onClick={() => onLayout(moveToDock(layout, id, target))}>
                    {target === "left" ? "←" : target === "right" ? "→" : "↓"}
                  </button>
                ))}
                <button type="button" aria-expanded={!collapsed} aria-label={`${collapsed ? "Expand" : "Collapse"} ${title}`} title={`${collapsed ? "Expand" : "Collapse"} ${title}`} onClick={() => onLayout(toggleCollapsed(layout, id))}>
                  {collapsed ? "+" : "−"}
                </button>
                <button type="button" aria-label={`Hide ${title}`} title={`Hide ${title}`} onClick={() => onLayout(hidePanel(layout, id))}>
                  {"×"}
                </button>
              </div>
            </header>
            <div className="oip-dock-body" hidden={collapsed}>
              {panels[id]}
            </div>
          </section>
        );
      })}
    </div>
  );
}

/** Top-bar buttons that bring hidden panels back and reset the layout. Empty while the layout is the default. */
export function PanelRestore({ layout, onLayout }: { layout: DockLayout; onLayout: (next: DockLayout) => void }) {
  if (layout.hidden.length === 0 && isDefaultLayout(layout)) return null;
  return (
    <div className="oip-panel-restore" role="toolbar" aria-label="Panels">
      {layout.hidden.map((id) => (
        <button key={id} type="button" onClick={() => onLayout(showPanel(layout, id))}>
          {`Show ${PANEL_TITLES[id]}`}
        </button>
      ))}
      <button type="button" onClick={() => onLayout(defaultLayout())}>
        Reset layout
      </button>
    </div>
  );
}
