import { useState } from "react";
import type { ViewDocument } from "@oip/hmi-runtime";
import { derivedPages } from "./hmiProject";
import type { PageConfigEntry, PageConfigSpec } from "./persistence";

/**
 * Page configuration for the open project (one PageConfig resource). Without one, a session opens each view at
 * `/<view path>`. Customizing writes that resource on the next save.
 * doc source: FEATURES DES-011
 */
/** The page list with `/` opening [view]: the home view a session shows first. */
export function withHomeView(pages: readonly PageConfigEntry[], view: string): PageConfigEntry[] {
  const rest = pages.filter((page) => page.url !== "/");
  return [{ url: "/", title: "Home", primaryView: view, name: "home" }, ...rest];
}

function HomeViewPicker({ pages, views, onPick }: { pages: readonly PageConfigEntry[]; views: readonly ViewDocument[]; onPick: (view: string) => void }) {
  const current = pages.find((page) => page.url === "/")?.primaryView ?? "";
  return (
    <label className="oip-home-view">
      Home view (what <code>/</code> opens)
      <select aria-label="Home view" value={current} onChange={(event) => { if (event.target.value) onPick(event.target.value); }}>
        <option value="">{current ? "" : `Not set (opens ${views[0]?.metadata.path ?? "the first view"})`}</option>
        {views.map((view) => <option key={view.metadata.path} value={view.metadata.path}>{view.metadata.path}</option>)}
      </select>
    </label>
  );
}

export function PageConfigPanel({
  spec,
  views,
  onChange,
}: {
  spec: PageConfigSpec | null;
  views: readonly ViewDocument[];
  onChange: (spec: PageConfigSpec) => void;
}) {
  if (!spec) {
    const derived = derivedPages(views);
    const derivedEntries: PageConfigEntry[] = derived.map((page) => ({ url: page.spec.url, title: page.spec.title, primaryView: page.spec.primaryView, name: page.metadata.name }));
    return (
      <section className="oip-pages" aria-label="Page configuration">
        <h3>Pages</h3>
        <HomeViewPicker pages={derivedEntries} views={views} onPick={(view) => onChange({ pages: withHomeView(derivedEntries, view) })} />
        <p>No page configuration is stored. A session opens one page per view.</p>
        <ul>
          {derived.map((page) => (
            <li key={page.spec.url}>
              {page.spec.url} → {page.spec.primaryView}
            </li>
          ))}
        </ul>
        <button
          type="button"
          onClick={() =>
            onChange({
              pages: derived.map((page) => ({
                url: page.spec.url,
                title: page.spec.title,
                primaryView: page.spec.primaryView,
                name: page.metadata.name,
              })),
            })
          }
        >
          Customize pages
        </button>
      </section>
    );
  }
  return <PageEditor spec={spec} views={views} onChange={onChange} />;
}

function PageEditor({
  spec,
  views,
  onChange,
}: {
  spec: PageConfigSpec;
  views: readonly ViewDocument[];
  onChange: (spec: PageConfigSpec) => void;
}) {
  const [url, setUrl] = useState("/");
  const [title, setTitle] = useState("");
  const [primaryView, setPrimaryView] = useState(views[0]?.metadata.path ?? "");

  function update(index: number, patch: Partial<PageConfigEntry>) {
    onChange({
      ...spec,
      pages: spec.pages.map((page, at) => (at === index ? { ...page, ...patch } : page)),
    });
  }

  function add() {
    const next = url.trim();
    if (!next.startsWith("/") || !primaryView) return;
    onChange({ ...spec, pages: [...spec.pages, { url: next, title: title.trim() || undefined, primaryView }] });
    setUrl("/");
    setTitle("");
  }

  return (
    <section className="oip-pages" aria-label="Page configuration">
      <h3>Pages</h3>
      <HomeViewPicker pages={spec.pages} views={views} onPick={(view) => onChange({ ...spec, pages: withHomeView(spec.pages, view) })} />
      <ul>
        {spec.pages.map((page, index) => (
          <li key={`${page.url}-${index}`}>
            <label>
              URL
              <input aria-label={`Page ${index + 1} URL`} value={page.url} onChange={(event) => update(index, { url: event.target.value })} />
            </label>
            <label>
              Title
              <input aria-label={`Page ${index + 1} title`} value={page.title ?? ""} onChange={(event) => update(index, { title: event.target.value })} />
            </label>
            <label>
              Primary view
              <select aria-label={`Page ${index + 1} view`} value={page.primaryView} onChange={(event) => update(index, { primaryView: event.target.value })}>
                {views.map((view) => (
                  <option key={view.metadata.path} value={view.metadata.path}>
                    {view.metadata.name}
                  </option>
                ))}
                {views.some((view) => view.metadata.path === page.primaryView) ? null : <option value={page.primaryView}>{page.primaryView}</option>}
              </select>
            </label>
            <button type="button" onClick={() => onChange({ ...spec, pages: spec.pages.filter((_, at) => at !== index) })}>
              Remove page {index + 1}
            </button>
          </li>
        ))}
      </ul>
      <form
        aria-label="Add page"
        onSubmit={(event) => {
          event.preventDefault();
          add();
        }}
      >
        <label>
          URL
          <input aria-label="New page URL" value={url} onChange={(event) => setUrl(event.target.value)} />
        </label>
        <label>
          Title
          <input aria-label="New page title" value={title} onChange={(event) => setTitle(event.target.value)} />
        </label>
        <label>
          Primary view
          <select aria-label="New page view" value={primaryView} onChange={(event) => setPrimaryView(event.target.value)}>
            {views.map((view) => (
              <option key={view.metadata.path} value={view.metadata.path}>
                {view.metadata.name}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">Add page</button>
      </form>
    </section>
  );
}
