import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { errorMessage } from "./access";
import type { AuditPageResult, AuditQuery } from "./api";
import type { AdminPageProps } from "./pages";

/**
 * Query the gateway audit log. Filters are actor, action, target, and a time range.
 * (FEATURES SEC-001, SEC-003).
 */
export function AuditPage({ section, client }: AdminPageProps) {
  const [draft, setDraft] = useState({ actor: "", action: "", target: "", from: "", to: "" });
  const [applied, setApplied] = useState(draft);
  const [limit, setLimit] = useState(25);
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<AuditPageResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    const query: AuditQuery = {
      actor: applied.actor.trim(),
      action: applied.action.trim(),
      target: applied.target.trim(),
      from: toMillis(applied.from),
      to: toMillis(applied.to),
      limit,
      offset,
    };
    client
      .queryAudit(query)
      .then((next) => {
        if (cancelled) return;
        setPage(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setPage(null);
        setError(errorMessage(err, "Could not load the audit log"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, applied, limit, offset]);

  function apply(event: FormEvent) {
    event.preventDefault();
    setOffset(0);
    setApplied(draft);
  }

  const total = page?.total ?? 0;
  const shownFrom = total === 0 ? 0 : offset + 1;
  const shownTo = page ? offset + page.items.length : 0;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">Who did what, and when. The log is append-only.</p>
      {error ? <p role="alert">{error}</p> : null}
      <form className="inline-form" aria-label="Audit filters" onSubmit={apply}>
        <label>
          Actor
          <input value={draft.actor} onChange={(event) => setDraft((current) => ({ ...current, actor: event.target.value }))} />
        </label>
        <label>
          Action
          <input value={draft.action} onChange={(event) => setDraft((current) => ({ ...current, action: event.target.value }))} />
        </label>
        <label>
          Target
          <input value={draft.target} onChange={(event) => setDraft((current) => ({ ...current, target: event.target.value }))} />
        </label>
        <label>
          From
          <input
            type="datetime-local"
            value={draft.from}
            onChange={(event) => setDraft((current) => ({ ...current, from: event.target.value }))}
          />
        </label>
        <label>
          To
          <input type="datetime-local" value={draft.to} onChange={(event) => setDraft((current) => ({ ...current, to: event.target.value }))} />
        </label>
        <button type="submit">Apply filters</button>
      </form>
      {!page && !error ? <p>Loading audit entries.</p> : null}
      {page ? (
        <>
          <p data-testid="audit-total">
            {total === 0 ? "No audit entries." : `Showing ${shownFrom}–${shownTo} of ${total}`}
          </p>
          {page.items.length > 0 ? (
            <div className="table-scroll">
              <table className="data-table audit-table" aria-label="Audit entries">
                <colgroup>
                  <col className="audit-col-time" />
                  <col className="audit-col-actor" />
                  <col className="audit-col-action" />
                  <col />
                  <col className="audit-col-status" />
                  <col />
                </colgroup>
                <thead>
                  <tr>
                    <th>Time</th>
                    <th>Actor</th>
                    <th>Action</th>
                    <th>Target</th>
                    <th>Status</th>
                    <th>Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((entry) => (
                    <tr key={entry.seq}>
                      <Cell value={entry.timestamp} />
                      <Cell value={entry.actor} />
                      <Cell value={entry.action} />
                      <Cell value={entry.target} wrap />
                      <Cell value={entry.status} />
                      <Cell value={entry.detail} wrap />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
          <div className="inline-form">
            <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - limit))}>
              Previous page
            </button>
            <button type="button" disabled={offset + limit >= total} onClick={() => setOffset(offset + limit)}>
              Next page
            </button>
            <label>
              Page size
              <input
                type="number"
                min={1}
                max={100}
                value={limit}
                onChange={(event) => {
                  const next = Number(event.target.value);
                  if (!Number.isInteger(next) || next < 1 || next > 100) return;
                  setLimit(next);
                  setOffset(0);
                }}
              />
            </label>
          </div>
        </>
      ) : null}
    </article>
  );
}

/**
 * One audit value. Short columns stay on one line and end in an ellipsis; target and detail wrap, breaking long
 * paths anywhere. Either way the title carries the whole value, so a clipped one can still be read.
 */
function Cell({ value: text, wrap = false }: { value: string; wrap?: boolean }) {
  return (
    <td className={wrap ? "cell-wrap" : "cell-clip"} title={text || undefined}>
      {wrap ? withBreaks(text) : text}
    </td>
  );
}

/** Line-break opportunities after `/`, `,`, `;` and `=`, so paths and key lists wrap between their parts. */
function withBreaks(text: string): ReactNode[] {
  return text.split(/(?<=[/,;=])/).flatMap((part, index) => (index === 0 ? [part] : [<wbr key={index} />, part]));
}

function toMillis(value: string): number | null {
  if (!value) return null;
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : null;
}
