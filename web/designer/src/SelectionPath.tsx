import type { ViewDocument, ViewNode } from "@oip/hmi-runtime";
import { ancestorsOf } from "./project";

/** The Inspector header's breadcrumb: the containers above the selection, each one click away. */
export function SelectionPath({ view, node, onSelect }: { view: ViewDocument; node: ViewNode; onSelect?: (id: string) => void }) {
  const above = ancestorsOf(view.spec.root, node.id);
  return (
    <nav className="oip-selection-path" aria-label="Selection path">
      <ol>
        {above.map((container) => (
          <li key={container.id}>
            <button type="button" data-node-id={container.id} disabled={!onSelect} onClick={() => onSelect?.(container.id)}>
              {container.name}
            </button>
          </li>
        ))}
        <li>
          <span aria-current="location">{node.name}</span>
        </li>
      </ol>
    </nav>
  );
}
