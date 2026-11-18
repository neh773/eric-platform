import { useId, useMemo, useState } from "react";
import type { HmiComponentModule } from "@oip/hmi-runtime";
import type { ContainerModule } from "@oip/components";
import { GROUP_TITLES, PALETTE_DRAG_TYPE, encodePaletteDrag, groupPalette, type PaletteItem } from "./paletteData";

/**
 * The Studio's component palette (HMP-002): a search box over chart, container, display, input and navigation groups.
 * Click an entry to add it to the open view, or drag it onto the canvas to place it where it is dropped.
 */
export function Palette({
  components,
  containers,
  onAdd,
}: {
  components: readonly HmiComponentModule[];
  containers: readonly ContainerModule[];
  onAdd: (item: PaletteItem) => void;
}) {
  const [query, setQuery] = useState("");
  const searchId = useId();
  const groups = useMemo(() => groupPalette(components, containers, query), [components, containers, query]);
  return (
    <div className="oip-palette" aria-label="Component palette" role="group">
      <label className="oip-palette-search" htmlFor={searchId}>
        <span>Search components</span>
        <input id={searchId} type="search" value={query} placeholder="gauge, menu, entry…" onChange={(event) => setQuery(event.target.value)} />
      </label>
      {groups.length === 0 ? (
        <p className="oip-palette-empty" role="status">
          No component matches &quot;{query.trim()}&quot;.
        </p>
      ) : null}
      {groups.map(({ group, items }) => (
        <section key={group} className="oip-library" aria-label={`${GROUP_TITLES[group]} components`} data-palette-group={group}>
          <p>
            {GROUP_TITLES[group]} <span className="oip-palette-count">{items.length}</span>
          </p>
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              draggable
              data-palette-item={item.kind === "component" ? item.type : item.layout}
              title={`Click to add, or drag onto the canvas`}
              onClick={() => onAdd(item)}
              onDragStart={(event) => {
                event.dataTransfer.setData(PALETTE_DRAG_TYPE, encodePaletteDrag(item));
                event.dataTransfer.effectAllowed = "copy";
              }}
            >
              Add {item.title.toLowerCase()}
            </button>
          ))}
        </section>
      ))}
    </div>
  );
}
