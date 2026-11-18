import { useState } from "react";
import type { KeyboardEvent, ReactNode } from "react";

export interface SideTab {
  id: string;
  label: string;
  content: ReactNode;
}

/** Compact tabs for the Studio's right column. Every panel stays mounted (hidden), so its state survives a tab switch. */
export function SideTabs({ tabs, selected, onSelect }: { tabs: SideTab[]; selected?: string; onSelect?: (id: string) => void }) {
  const [current, setCurrent] = useState(tabs[0]?.id ?? "");
  const requested = selected ?? current;
  const active = tabs.some((tab) => tab.id === requested) ? requested : (tabs[0]?.id ?? "");

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const index = tabs.findIndex((tab) => tab.id === active);
    let next = index;
    if (event.key === "ArrowRight" || event.key === "ArrowDown") next = (index + 1) % tabs.length;
    else if (event.key === "ArrowLeft" || event.key === "ArrowUp") next = (index - 1 + tabs.length) % tabs.length;
    else return;
    event.preventDefault();
    const id = tabs[next]!.id;
    setCurrent(id);
    onSelect?.(id);
    event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  }

  return (
    <div className="oip-side-tabs">
      <div role="tablist" aria-label="Studio panels" onKeyDown={onKeyDown}>
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`oip-side-tab-${tab.id}`}
            aria-selected={tab.id === active}
            aria-controls={`oip-side-panel-${tab.id}`}
            tabIndex={tab.id === active ? 0 : -1}
            onClick={() => {
              setCurrent(tab.id);
              onSelect?.(tab.id);
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>
      {tabs.map((tab) => (
        <div
          key={tab.id}
          role="tabpanel"
          id={`oip-side-panel-${tab.id}`}
          aria-labelledby={`oip-side-tab-${tab.id}`}
          hidden={tab.id !== active}
        >
          {tab.content}
        </div>
      ))}
    </div>
  );
}
