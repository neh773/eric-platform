import { useMemo, useState } from "react";
import type { ViewDocument } from "@oip/hmi-runtime";
import { findInProject, groupByView, replaceHits, type FindHit } from "./findReplace";

/**
 * Project-wide find and replace (DES-007). Replacements go through [onReplaceViews], the Studio's normal view
 * commit, so the views become dirty and save with the usual Save. [Undo last replace] restores the views as they were.
 */
export function FindReplacePanel({
  views,
  onReplaceViews,
  onReveal,
}: {
  views: readonly ViewDocument[];
  onReplaceViews: (views: ViewDocument[]) => void;
  onReveal: (viewPath: string, nodeId: string | null) => void;
}) {
  const [text, setText] = useState("");
  const [replacement, setReplacement] = useState("");
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [undo, setUndo] = useState<{ views: ViewDocument[]; count: number } | null>(null);
  const query = useMemo(() => ({ text, caseSensitive }), [text, caseSensitive]);
  const hits = useMemo(() => findInProject(views, query), [views, query]);
  const groups = useMemo(() => groupByView(hits), [hits]);

  function apply(chosen: readonly FindHit[]) {
    const before: ViewDocument[] = [];
    const after: ViewDocument[] = [];
    for (const view of views) {
      if (!chosen.some((hit) => hit.viewPath === view.metadata.path)) continue;
      before.push(view);
      after.push(replaceHits(view, chosen, query, replacement));
    }
    if (after.length === 0) return;
    onReplaceViews(after);
    setUndo({ views: before, count: chosen.length });
  }

  return (
    <section className="oip-find" data-testid="find-replace" aria-label="Find and replace">
      <p className="oip-binding-muted">Searches names, property values, binding paths, expressions and script text in every view. `*` matches any text, `?` one character.</p>
      <label>
        Find
        <input value={text} onChange={(event) => setText(event.target.value)} spellCheck={false} />
      </label>
      <label>
        Replace with
        <input value={replacement} onChange={(event) => setReplacement(event.target.value)} spellCheck={false} />
      </label>
      <label>
        <input type="checkbox" checked={caseSensitive} onChange={(event) => setCaseSensitive(event.target.checked)} /> Match case
      </label>
      <p role="status" data-testid="find-count">
        {text === "" ? "Type to search." : `${hits.length} ${hits.length === 1 ? "match" : "matches"} in ${groups.size} ${groups.size === 1 ? "view" : "views"}`}
      </p>
      <button type="button" disabled={hits.length === 0} onClick={() => apply(hits)}>
        Replace all
      </button>
      {undo ? (
        <button
          type="button"
          onClick={() => {
            onReplaceViews(undo.views);
            setUndo(null);
          }}
        >
          Undo last replace ({undo.count})
        </button>
      ) : null}
      <ul aria-label="Matches" className="oip-find-results">
        {[...groups].map(([path, group]) => (
          <li key={path}>
            <strong>{path}</strong> ({group.length})
            <ul>
              {group.map((hit) => (
                <li key={hit.keys.join("/")}>
                  <button type="button" onClick={() => onReveal(hit.viewPath, hit.nodeId)}>
                    {hit.nodeName ? `${hit.nodeName} · ` : ""}
                    {hit.property}: {hit.value.length > 60 ? `${hit.value.slice(0, 60)}...` : hit.value}
                  </button>
                  <button type="button" aria-label={`Replace in ${hit.nodeName ?? hit.viewPath} ${hit.property}`} onClick={() => apply([hit])}>
                    Replace
                  </button>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </section>
  );
}
