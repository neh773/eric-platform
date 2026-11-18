import type { StudioProject } from "./project";

/**
 * The project's views. A view the project inherits from an ancestor project (PRO-002) is marked with that project's
 * name; it stays read-only until it is overridden in this project. A protected view, or one another Designer is
 * editing, carries that mark (SEC-020).
 */
export function ProjectTree({
  project,
  activePath,
  onOpen,
  inherited = new Map(),
  marks = new Map(),
  onOpenResource,
}: {
  project: StudioProject;
  activePath: string;
  onOpen: (path: string) => void;
  /** Inherited views by path, with the ancestor project that stores each. */
  inherited?: ReadonlyMap<string, string>;
  /** Project security marks by view path (SEC-020), such as "protected" or "locked by alice". */
  marks?: ReadonlyMap<string, string>;
  /** Open another project resource the Studio edits (styles, scripts, pages, translation, settings). */
  onOpenResource?: (id: "styles" | "scripts" | "pages" | "translation" | "images" | "project" | "libraries" | "queries") => void;
}) {
  return (
    <nav className="oip-tree" aria-label="Project">
      <ul role="tree">
        <li role="treeitem" aria-expanded="true">
          <span className="oip-tree-project">{project.name}</span>
          <ul role="group">
            {project.views.map((view) => {
              const selected = view.metadata.path === activePath;
              const from = inherited.get(view.metadata.path);
              const mark = marks.get(view.metadata.path);
              return (
                <li key={view.metadata.path} role="none">
                  <button
                    type="button"
                    role="treeitem"
                    aria-selected={selected}
                    data-view-path={view.metadata.path}
                    data-inherited-from={from}
                    onClick={() => onOpen(view.metadata.path)}
                  >
                    {view.metadata.name}
                    {from ? (
                      <span className="oip-tree-inherited" title={`Inherited from project ${from}`}>
                        {" "}
                        (inherited from {from})
                      </span>
                    ) : null}
                    {mark ? (
                      <span className="oip-tree-access" data-access-mark={mark}>
                        {" "}
                        ({mark})
                      </span>
                    ) : null}
                  </button>
                </li>
              );
            })}
          </ul>
        </li>
      </ul>
      {onOpenResource ? (
        <ul aria-label="Project resources">
          {(
            [
              ["styles", "Style classes"],
              ["scripts", "Script hooks"],
              ["libraries", "Script libraries"],
              ["queries", "Named queries"],
              ["pages", "Pages"],
              ["translation", "Translation"],
              ["images", "Images"],
              ["project", "Project settings"],
            ] as const
          ).map(([id, label]) => (
            <li key={id}>
              <button type="button" onClick={() => onOpenResource(id)}>
                {label}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </nav>
  );
}
