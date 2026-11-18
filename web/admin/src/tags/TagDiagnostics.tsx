import { useEffect, useState } from "react";
import { errorMessage } from "../access";
import type { ApiClient } from "../api";
import { fetchDiagnostics, type TagDiagnostics, type TagLink } from "./tagClient";
import { formatTime } from "./TagTree";
import { formatValue } from "./tagModel";
import { LIVE_INTERVAL_MS } from "./useLiveValues";

/**
 * Tag diagnostics opened from the tag browser (TAG-003). Polls so a value written elsewhere shows up
 * while the view stays open; Refresh reads again immediately.
 */
export function TagDiagnosticsPanel({ client, path, onClose }: { client: ApiClient; path: string; onClose: () => void }) {
  const [data, setData] = useState<TagDiagnostics | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      try {
        const next = await fetchDiagnostics(client, path);
        if (cancelled) return;
        setData(next);
        setError(null);
      } catch (err) {
        if (!cancelled) setError(errorMessage(err, "Diagnostics are unavailable"));
      } finally {
        if (!cancelled) timer = setTimeout(() => void load(), LIVE_INTERVAL_MS);
      }
    };
    void load();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [client, path, reload]);

  return (
    <section className="stack-form tag-diagnostics" data-testid="tag-diagnostics" aria-label="Tag diagnostics">
      <h2>Tag diagnostics</h2>
      <p className="muted">{path}</p>
      <div className="inline-form">
        <button type="button" onClick={() => setReload((n) => n + 1)}>
          Refresh
        </button>
        <button type="button" className="quiet" onClick={onClose}>
          Back
        </button>
      </div>
      {error ? <p role="alert">{error}</p> : null}
      {!data && !error ? <p>Loading diagnostics.</p> : null}
      {data ? <DiagnosticsBody data={data} /> : null}
    </section>
  );
}

function DiagnosticsBody({ data }: { data: TagDiagnostics }) {
  const group = data.tagGroup;
  const deadband = data.deadband;
  return (
    <>
      <dl className="diag-fields">
        <dt>Value</dt>
        <dd data-testid="diag-value">{formatValue(data.value, data.quality)}</dd>
        <dt>Quality</dt>
        <dd data-testid="diag-quality">{data.quality}</dd>
        <dt>Timestamp</dt>
        <dd data-testid="diag-timestamp">{data.timestamp ? formatTime(data.timestamp) : ""}</dd>
        <dt>Type</dt>
        <dd>{data.type}</dd>
        <dt>Subscribers</dt>
        <dd data-testid="diag-subscribers">{data.subscriberCount}</dd>
        {data.message ? (
          <>
            <dt>Message</dt>
            <dd>{data.message}</dd>
          </>
        ) : null}
      </dl>
      <h3>References in</h3>
      <LinkList testId="diag-references-in" links={data.referencesIn} empty="Nothing reads this tag." />
      <h3>References out</h3>
      <LinkList testId="diag-references-out" links={data.referencesOut} empty="This tag does not read another tag." />
      <h3>Tag group</h3>
      <dl className="diag-fields" data-testid="diag-group">
        <dt>Name</dt>
        <dd>{group.name}{group.valid ? "" : " (not configured)"}</dd>
        <dt>Mode</dt>
        <dd>{group.mode}</dd>
        <dt>Rate</dt>
        <dd>{group.rateMillis} ms</dd>
        <dt>Last execution</dt>
        <dd data-testid="diag-last-execution">{group.lastExecution ? formatTime(group.lastExecution) : "Has not run"}</dd>
        <dt>Duration</dt>
        <dd data-testid="diag-duration">{group.durationMillis == null ? "—" : `${group.durationMillis.toFixed(3)} ms`}</dd>
        <dt>Overruns</dt>
        <dd data-testid="diag-overruns">{group.overruns}</dd>
      </dl>
      <h3>Deadband</h3>
      <dl className="diag-fields">
        <dt>Mode</dt>
        <dd>{deadband.mode}</dd>
        <dt>Limit</dt>
        <dd>{deadband.limit}</dd>
        <dt>Evaluated</dt>
        <dd data-testid="diag-evaluated">{deadband.evaluated}</dd>
        <dt>Published</dt>
        <dd data-testid="diag-published">{deadband.published}</dd>
      </dl>
    </>
  );
}

function LinkList({ testId, links, empty }: { testId: string; links: TagLink[]; empty: string }) {
  if (links.length === 0) return <p className="muted">{empty}</p>;
  return (
    <ul data-testid={testId}>
      {links.map((link) => (
        <li key={`${link.kind}:${link.path}`}>
          <span className="muted">{link.kind}</span> {link.path}
        </li>
      ))}
    </ul>
  );
}
