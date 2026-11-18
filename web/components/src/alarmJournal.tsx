import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { PRIORITIES, type Priority } from "./alarms";
import {
  JOURNAL_COLUMNS,
  JOURNAL_EVENT_TYPES,
  alarmJournalApi,
  clampLimit,
  eventTypeLabel,
  journalCellText,
  journalQuery,
  parseJournalColumns,
  parsePriority,
  searchJournal,
  type JournalEvent,
  type JournalEventType,
  type JournalRange,
} from "./alarmJournalApi";
import { useGatewayClient } from "./gateway";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Alarm journal table: a paged, filtered view of stored alarm events from GET /api/v1/alarms/journal.
 *
 * Realtime mode looks back `realtimeMinutes` from now and polls every `refreshRate` ms; historical mode
 * queries a fixed start/end once per change. Source, display path, priority range and event type filter on
 * the gateway; the search box filters the loaded page in the browser. Reuses the alarm status table styles.
 */

const EVENT_FILTERS: { key: JournalEventType; prop: string }[] = [
  { key: "active", prop: "showActive" },
  { key: "clear", prop: "showCleared" },
  { key: "ack", prop: "showAcked" },
  { key: "shelve", prop: "showShelved" },
  { key: "unshelve", prop: "showUnshelved" },
];

const PAGE_SIZES = [25, 50, 100, 250, 500];

/** Pause after the last keystroke in a date input before it is queried. */
export const DATE_DEBOUNCE_MS = 600;

/**
 * Local state seeded from a prop that follows later prop changes (a binding or the inspector), while the
 * user can still change it in between. `key` identifies the prop value; it defaults to the value itself.
 */
function usePropState<T>(prop: T, key: unknown = prop): [T, (next: T) => void] {
  const [value, setValue] = useState(prop);
  const [lastKey, setLastKey] = useState(key);
  if (!Object.is(lastKey, key)) {
    setLastKey(key);
    setValue(prop);
  }
  return [value, setValue];
}

export function AlarmJournalTable({ props, locale, onChange }: HmiComponentProps) {
  const client = useGatewayClient();
  const live = readBoolean(props, "live", true);
  const refreshRate = Math.max(1000, Math.round(readNumber(props, "refreshRate", 5000)));
  const columns = useMemo(() => parseJournalColumns(props.columns), [props.columns]);
  const sourceFilter = readString(props, "sourceFilter");
  const displayPathFilter = readString(props, "displayPathFilter");
  const enableDetails = readBoolean(props, "enableDetails", true);
  const propMode = readString(props, "rangeMode", "realtime") === "historical" ? "historical" : "realtime";
  const propMinutes = Math.max(1, Math.round(readNumber(props, "realtimeMinutes", 480)));
  const propStart = readString(props, "startTime");
  const propEnd = readString(props, "endTime");
  const propEventTypes = {} as Record<JournalEventType, boolean>;
  for (const entry of EVENT_FILTERS) propEventTypes[entry.key] = readBoolean(props, entry.prop, true);

  // Every prop seeds local state and replaces it again whenever the prop (or its binding) changes.
  const [mode, setMode] = usePropState<JournalRange["mode"]>(propMode);
  const [minutes, setMinutes] = usePropState(propMinutes);
  const [start, setStart] = usePropState(propStart);
  const [end, setEnd] = usePropState(propEnd);
  const [minPriority, setMinPriority] = usePropState<Priority>(parsePriority(props.minPriority, "Low"));
  const [maxPriority, setMaxPriority] = usePropState<Priority>(parsePriority(props.maxPriority, "Critical"));
  const [eventTypes, setEventTypes] = usePropState(propEventTypes, JSON.stringify(propEventTypes));
  const [search, setSearch] = usePropState(readString(props, "filter"));
  const [newestFirst, setNewestFirst] = usePropState(readBoolean(props, "newestFirst", true));
  const [pageSize, setPageSize] = usePropState(clampLimit(readNumber(props, "pageSize", 100)));
  const [offset, setOffset] = useState(0);

  // The date inputs edit drafts; a draft is queried (and written back) on blur, Enter, or a typing pause,
  // so a half-typed date neither queries the gateway nor flashes an error.
  const [startDraft, setStartDraft] = usePropState(start);
  const [endDraft, setEndDraft] = usePropState(end);
  const commitStart = useCallback(
    (value: string) => {
      if (value === start) return;
      setStart(value);
      onChange("startTime", value);
    },
    [start, setStart, onChange],
  );
  const commitEnd = useCallback(
    (value: string) => {
      if (value === end) return;
      setEnd(value);
      onChange("endTime", value);
    },
    [end, setEnd, onChange],
  );
  useEffect(() => {
    if (startDraft === start && endDraft === end) return;
    const timer = setTimeout(() => {
      commitStart(startDraft);
      commitEnd(endDraft);
    }, DATE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [startDraft, endDraft, start, end, commitStart, commitEnd]);

  const [events, setEvents] = useState<JournalEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState("");
  const [stale, setStale] = useState(false);
  const [expanded, setExpanded] = useState<number | null>(null);
  const mounted = useRef(true);
  const sequence = useRef(0);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Any filter change starts again from the first page.
  const filterKey = JSON.stringify([mode, minutes, start, end, sourceFilter, displayPathFilter, minPriority, maxPriority, eventTypes, newestFirst, pageSize]);
  const lastFilterKey = useRef(filterKey);
  if (lastFilterKey.current !== filterKey) {
    lastFilterKey.current = filterKey;
    if (offset !== 0) setOffset(0);
  }

  const refresh = useCallback(async () => {
    const query = journalQuery({
      range: { mode, minutes, start, end },
      sourceFilter,
      displayPathFilter,
      minPriority,
      maxPriority,
      eventTypes,
      offset,
      limit: pageSize,
      newestFirst,
    });
    const ticket = ++sequence.current;
    if (query === null) {
      setEvents([]);
      setTotal(0);
      setLoaded(true);
      setStale(false);
      setError("");
      return;
    }
    if ("error" in query) {
      // The rows on screen no longer match the filters, so drop them rather than show them under the error.
      setEvents([]);
      setTotal(0);
      setLoaded(false);
      setStale(false);
      setError(query.error);
      return;
    }
    try {
      const page = await alarmJournalApi.query(client, query.path);
      if (!mounted.current || ticket !== sequence.current) return;
      if (offset > 0 && offset >= page.total) {
        // The journal shrank under the current page: move to the last page that still has rows.
        setEvents([]);
        setTotal(page.total);
        setOffset(page.total === 0 ? 0 : Math.floor((page.total - 1) / pageSize) * pageSize);
        return;
      }
      setEvents(page.events);
      setTotal(page.total);
      setLoaded(true);
      setStale(false);
      setError("");
    } catch (failure) {
      if (!mounted.current || ticket !== sequence.current) return;
      // Keep the last good page (a poll may recover), but mark it as out of date.
      setStale(true);
      setError(`Could not load the alarm journal: ${failure instanceof Error ? failure.message : String(failure)}`);
    }
  }, [client, mode, minutes, start, end, sourceFilter, displayPathFilter, minPriority, maxPriority, eventTypes, offset, pageSize, newestFirst]);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = live && mode === "realtime";
    const loop = async () => {
      await refresh();
      if (!stopped && poll) timer = setTimeout(() => void loop(), refreshRate);
    };
    void loop();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [live, mode, refresh, refreshRate]);

  const visible = useMemo(() => searchJournal(events, search, locale), [events, search, locale]);
  const firstRow = total === 0 ? 0 : offset + 1;
  const lastRow = Math.min(total, offset + events.length);
  const page = Math.floor(offset / pageSize) + 1;
  const pages = Math.max(1, Math.ceil(total / pageSize));

  function toggleDetails(event: JournalEvent) {
    if (!enableDetails) return;
    const next = expanded === event.id ? null : event.id;
    setExpanded(next);
    onChange("selectedEventId", next === null ? null : event.eventId);
  }

  return (
    <div className="oip-alarm-table oip-alarm-journal" data-source="gateway" data-mode={mode} data-stale={stale || undefined}>
      {error ? (
        <p role="alert" className="oip-alarm-error">
          {stale && events.length > 0 ? `${error} Showing the last loaded rows.` : error}
        </p>
      ) : null}

      <div className="oip-alarm-toolbar">
        <label>
          Range
          <select
            value={mode}
            onChange={(event) => {
              const next = event.target.value === "historical" ? "historical" : "realtime";
              setMode(next);
              onChange("rangeMode", next);
            }}
          >
            <option value="realtime">Recent</option>
            <option value="historical">Between dates</option>
          </select>
        </label>
        {mode === "realtime" ? (
          <label>
            Last (minutes)
            <input
              type="number"
              min={1}
              value={minutes}
              onChange={(event) => {
                const next = Math.max(1, Math.round(Number(event.target.value) || 1));
                setMinutes(next);
                onChange("realtimeMinutes", next);
              }}
            />
          </label>
        ) : (
          <>
            <label>
              Start time
              <input
                value={startDraft}
                placeholder="2026-01-01T00:00:00Z"
                onChange={(event) => setStartDraft(event.target.value)}
                onBlur={() => commitStart(startDraft)}
                onKeyDown={(key) => {
                  if (key.key === "Enter") commitStart(startDraft);
                }}
              />
            </label>
            <label>
              End time
              <input
                value={endDraft}
                placeholder="(now)"
                onChange={(event) => setEndDraft(event.target.value)}
                onBlur={() => commitEnd(endDraft)}
                onKeyDown={(key) => {
                  if (key.key === "Enter") commitEnd(endDraft);
                }}
              />
            </label>
          </>
        )}
        <label>
          Search
          <input
            type="search"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              onChange("filter", event.target.value);
            }}
          />
        </label>
        <fieldset>
          <legend>Events</legend>
          {EVENT_FILTERS.map((entry) => (
            <label key={entry.key}>
              <input
                type="checkbox"
                checked={eventTypes[entry.key]}
                onChange={(event) => {
                  setEventTypes({ ...eventTypes, [entry.key]: event.target.checked });
                  onChange(entry.prop, event.target.checked);
                }}
              />
              {JOURNAL_EVENT_TYPES[entry.key]}
            </label>
          ))}
        </fieldset>
        <label>
          Lowest priority
          <select
            value={minPriority}
            onChange={(event) => {
              const next = parsePriority(event.target.value, "Low");
              setMinPriority(next);
              onChange("minPriority", next);
            }}
          >
            {PRIORITIES.map((name) => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
        </label>
        <label>
          Highest priority
          <select
            value={maxPriority}
            onChange={(event) => {
              const next = parsePriority(event.target.value, "Critical");
              setMaxPriority(next);
              onChange("maxPriority", next);
            }}
          >
            {PRIORITIES.map((name) => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
        </label>
      </div>

      <table>
        <thead>
          <tr>
            {columns.map((column) =>
              column === "eventTime" ? (
                <th key={column} scope="col" aria-sort={newestFirst ? "descending" : "ascending"}>
                  <button
                    type="button"
                    onClick={() => {
                      setNewestFirst(!newestFirst);
                      onChange("newestFirst", !newestFirst);
                    }}
                  >
                    {JOURNAL_COLUMNS[column]}
                  </button>
                </th>
              ) : (
                <th key={column} scope="col">{JOURNAL_COLUMNS[column]}</th>
              ),
            )}
          </tr>
        </thead>
        <tbody>
          {visible.map((event) => (
            <Fragment key={event.id}>
              <tr
                data-journal-id={event.id}
                data-event-id={event.eventId}
                data-event-type={event.eventType}
                data-priority={event.priority.toLowerCase()}
                aria-expanded={enableDetails ? expanded === event.id : undefined}
                tabIndex={enableDetails ? 0 : undefined}
                onClick={() => toggleDetails(event)}
                onKeyDown={(key) => {
                  if (key.key === "Enter" || key.key === " ") {
                    key.preventDefault();
                    toggleDetails(event);
                  }
                }}
              >
                {columns.map((column) => (
                  <td key={column} data-column={column}>
                    {journalCellText(event, column, locale)}
                  </td>
                ))}
              </tr>
              {enableDetails && expanded === event.id ? (
                <tr className="oip-alarm-journal-details" data-details-for={event.id}>
                  <td colSpan={columns.length}>
                    <dl aria-label={`Details for ${event.displayPath || event.source}`}>
                      {detailEntries(event, locale).map(([name, value]) => (
                        <Fragment key={name}>
                          <dt>{name}</dt>
                          <dd>{value}</dd>
                        </Fragment>
                      ))}
                    </dl>
                  </td>
                </tr>
              ) : null}
            </Fragment>
          ))}
        </tbody>
      </table>
      {loaded && visible.length === 0 ? <p className="oip-alarm-empty">No journal events match the filters.</p> : null}

      <div className="oip-alarm-actions oip-alarm-pager" role="navigation" aria-label="Journal pages">
        <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - pageSize))}>
          Previous
        </button>
        <span role="status" aria-live="polite">{`Rows ${firstRow}–${lastRow} of ${total} (page ${page} of ${pages})`}</span>
        <button type="button" disabled={offset + pageSize >= total} onClick={() => setOffset(offset + pageSize)}>
          Next
        </button>
        <label>
          Rows per page
          <select
            value={pageSize}
            onChange={(event) => {
              const next = clampLimit(Number(event.target.value));
              setPageSize(next);
              onChange("pageSize", next);
            }}
          >
            {[...new Set([...PAGE_SIZES, pageSize])].sort((a, b) => a - b).map((size) => (
              <option key={size} value={size}>{size}</option>
            ))}
          </select>
        </label>
      </div>
    </div>
  );
}

function detailEntries(event: JournalEvent, locale: string): [string, string][] {
  const out: [string, string][] = [
    ["Event", eventTypeLabel(event.eventType)],
    ["Event time", journalCellText(event, "eventTime", locale)],
    ["Event id", event.eventId],
    ["Source path", event.source],
    ["Display path", event.displayPath],
    ["Priority", event.priority],
  ];
  if (event.actor) out.push(["User", event.actor]);
  for (const [name, value] of Object.entries(event.data)) {
    if (value == null || name === "actor" || name === "ackUser") continue;
    out.push([name, name === "shelveExpiry" ? journalCellText(event, "shelvedUntil", locale) : String(value)]);
  }
  return out;
}

