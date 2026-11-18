import { useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { AdminPageProps } from "./pages";
import { LOG_DOWNLOAD_PATH, LOG_LEVELS, redactFields, type LogQuery } from "./statusApi";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * Gateway log tail with logger, level, and text filters; per-logger level changes that apply without
 * a restart; and a download of the structured log file.
 *
 * Concepts from the public diagnostics logs page (filtering the recent log, changing a logger's level
 * at runtime, downloading the log). Wording, layout, and behavior details are our own.
 * (FEATURES GAT-016, GAT-019).
 */

export const LOG_FOLLOW_MS = 3_000;
const DEFAULT_LIMIT = 200;

export function LogsPage({ section, client, session }: AdminPageProps) {
  const api = useStatusApi(client);
  const canRead = session ? allows(session, "gateway.read") : true;
  const canWrite = allows(session, "gateway.write");

  const [draft, setDraft] = useState<Required<LogQuery>>({ logger: "", minLevel: "", q: "", limit: DEFAULT_LIMIT });
  const [query, setQuery] = useState<Required<LogQuery>>(draft);
  const [follow, setFollow] = useState(true);

  const records = useLoad(() => api.logs(query), [api, query], {
    intervalMs: follow ? LOG_FOLLOW_MS : undefined,
    enabled: Boolean(client),
    fallback: "Could not read the log",
  });
  const levels = useLoad(() => api.logLevels(), [api], { enabled: Boolean(client), fallback: "Could not read log levels" });

  const [levelLogger, setLevelLogger] = useState("");
  const [levelValue, setLevelValue] = useState<string>("DEBUG");
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [writeError, setWriteError] = useState<string | null>(null);

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    const limit = Number.isFinite(draft.limit) ? Math.min(2_000, Math.max(1, Math.trunc(draft.limit))) : DEFAULT_LIMIT;
    setQuery({ ...draft, logger: draft.logger.trim(), limit });
  }

  async function changeLevel(event: FormEvent) {
    event.preventDefault();
    const logger = levelLogger.trim();
    if (!logger || pending) return;
    setPending(true);
    setNotice(null);
    setWriteError(null);
    try {
      const result = await api.setLogLevel(logger, levelValue);
      setNotice(`${result.logger} now logs at ${result.level}.`);
      levels.reload();
      records.reload();
    } catch (err) {
      setWriteError(errorMessage(err, "Could not change the level"));
    } finally {
      setPending(false);
    }
  }

  const knownLoggers = Array.from(
    new Set([...(records.data ?? []).map((row) => row.logger), ...Object.keys(levels.data?.levels ?? {})]),
  ).sort();

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {!canRead ? (
        <p role="status" data-testid="logs-no-read">
          Your roles do not include gateway.read, so the log is hidden.
        </p>
      ) : null}
      {records.error ? <p role="alert">{records.error}</p> : null}

      <form className="inline-form" aria-label="Log filters" onSubmit={applyFilters}>
        <label>
          Logger
          <input
            type="text"
            value={draft.logger}
            placeholder="org.oip.http"
            list="log-loggers"
            onChange={(event) => setDraft({ ...draft, logger: event.target.value })}
          />
        </label>
        <label>
          Lowest level
          <select value={draft.minLevel} onChange={(event) => setDraft({ ...draft, minLevel: event.target.value })}>
            <option value="">Any</option>
            {LOG_LEVELS.map((level) => (
              <option key={level} value={level}>
                {level}
              </option>
            ))}
          </select>
        </label>
        <label>
          Text
          <input type="search" value={draft.q} onChange={(event) => setDraft({ ...draft, q: event.target.value })} />
        </label>
        <label>
          Rows
          <input
            type="number"
            min={1}
            max={2000}
            value={draft.limit}
            onChange={(event) => setDraft({ ...draft, limit: Number(event.target.value) })}
          />
        </label>
        <button type="submit">Apply filters</button>
        <label>
          <input type="checkbox" checked={follow} onChange={(event) => setFollow(event.target.checked)} /> Follow
        </label>
        <a href={LOG_DOWNLOAD_PATH} download="gateway-log.jsonl">
          Download log file
        </a>
      </form>
      <datalist id="log-loggers">
        {knownLoggers.map((name) => (
          <option key={name} value={name} />
        ))}
      </datalist>

      {records.data ? (
        <table className="data-table" aria-label="Log records" data-testid="log-records">
          <thead>
            <tr>
              <th>Time</th>
              <th>Level</th>
              <th>Logger</th>
              <th>Message</th>
              <th>Fields</th>
            </tr>
          </thead>
          <tbody>
            {records.data.length === 0 ? (
              <tr>
                <td colSpan={5}>No records match these filters.</td>
              </tr>
            ) : null}
            {[...records.data].reverse().map((row, index) => (
              <tr key={`${row.ts}-${index}`} data-level={row.level}>
                <td>{row.ts}</td>
                <td>{row.level}</td>
                <td>{row.logger}</td>
                <td>{row.message}</td>
                <td>
                  {redactFields(row.fields)
                    .map(([key, value]) => `${key}=${value}`)
                    .join(" ")}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      <section aria-labelledby="log-levels-title">
        <h2 id="log-levels-title">Logger levels</h2>
        <p>A level set here applies to the next record from that logger and its children. No restart is needed.</p>
        {levels.error ? <p role="alert">{levels.error}</p> : null}
        {levels.data ? (
          <table className="data-table" aria-label="Logger levels" data-testid="log-levels">
            <thead>
              <tr>
                <th>Logger</th>
                <th>Level</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>(default)</td>
                <td>{levels.data.defaultLevel}</td>
              </tr>
              {Object.entries(levels.data.levels)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([name, level]) => (
                  <tr key={name}>
                    <td>{name}</td>
                    <td>{level}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        ) : null}
        {canWrite ? (
          <form className="inline-form" aria-label="Change logger level" onSubmit={(event) => void changeLevel(event)}>
            <label>
              Logger name
              <input type="text" value={levelLogger} list="log-loggers" required onChange={(event) => setLevelLogger(event.target.value)} />
            </label>
            <label>
              New level
              <select value={levelValue} onChange={(event) => setLevelValue(event.target.value)}>
                {LOG_LEVELS.map((level) => (
                  <option key={level} value={level}>
                    {level}
                  </option>
                ))}
              </select>
            </label>
            <button type="submit" disabled={pending || !levelLogger.trim()}>
              Set level
            </button>
          </form>
        ) : null}
        {notice ? <p role="status">{notice}</p> : null}
        {writeError ? <p role="alert">{writeError}</p> : null}
      </section>
    </article>
  );
}
