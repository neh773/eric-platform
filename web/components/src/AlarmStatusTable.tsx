import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import {
  ALARM_COLUMNS,
  DEFAULT_SORT,
  PRIORITIES,
  alarmApi,
  cellText,
  filterAlarms,
  nextAlarmSort,
  parseColumns,
  parseShelvingTimes,
  sortAlarms,
  stateKey,
  type AlarmEvent,
  type AlarmSort,
  type Priority,
  type StateKey,
} from "./alarms";
import { useGatewayClient } from "./gateway";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Live alarm status table backed by the gateway alarm REST API. Polls, because the gateway has no
 * push channel yet.
 *
 * Shelved alarms are not returned by GET /api/v1/alarms, so the Shelved view lists the sources this
 * table shelved in this browser session, until their shelve time runs out.
 */

const STATE_FILTERS: { key: StateKey; prop: string; label: string }[] = [
  { key: "activeUnacked", prop: "showActiveUnacked", label: "Active, unacknowledged" },
  { key: "activeAcked", prop: "showActiveAcked", label: "Active, acknowledged" },
  { key: "clearedUnacked", prop: "showClearedUnacked", label: "Cleared, unacknowledged" },
  { key: "clearedAcked", prop: "showClearedAcked", label: "Cleared, acknowledged" },
];

const STATE_DEFAULTS: Record<StateKey, boolean> = {
  activeUnacked: true,
  activeAcked: true,
  clearedUnacked: true,
  clearedAcked: false,
};

const PRIORITY_DEFAULTS: Record<Priority, boolean> = {
  Diagnostic: false,
  Low: true,
  Medium: true,
  High: true,
  Critical: true,
};

interface ShelvedEntry {
  source: string;
  displayPath: string;
  until: number;
}

export function AlarmStatusTable({ props, locale, onChange }: HmiComponentProps) {
  const client = useGatewayClient();
  const refreshRate = Math.max(250, Math.round(readNumber(props, "refreshRate", 2000)));
  const live = readBoolean(props, "live", true);
  const columns = useMemo(() => parseColumns(props.columns), [props.columns]);
  const shelvingTimes = useMemo(() => parseShelvingTimes(props.shelvingTimes), [props.shelvingTimes]);
  const sourceFilter = readString(props, "sourceFilter");
  const displayPathFilter = readString(props, "displayPathFilter");
  const enableAck = readBoolean(props, "enableAcknowledge", true);
  const enableShelve = readBoolean(props, "enableShelve", true);
  const enableUnshelve = readBoolean(props, "enableUnshelve", true);

  const [events, setEvents] = useState<AlarmEvent[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [pollError, setPollError] = useState("");
  const [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState(readString(props, "filter"));
  const [states, setStates] = useState<Record<StateKey, boolean>>(() => {
    const out = { ...STATE_DEFAULTS };
    for (const entry of STATE_FILTERS) out[entry.key] = readBoolean(props, entry.prop, STATE_DEFAULTS[entry.key]);
    return out;
  });
  const [priorities, setPriorities] = useState<Record<Priority, boolean>>(() => {
    const out = { ...PRIORITY_DEFAULTS };
    for (const name of PRIORITIES) out[name] = readBoolean(props, `show${name}`, PRIORITY_DEFAULTS[name]);
    return out;
  });
  const [sort, setSort] = useState<AlarmSort>(DEFAULT_SORT);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [notes, setNotes] = useState("");
  const [pickedMinutes, setShelveMinutes] = useState(shelvingTimes[0]!);
  const shelveMinutes = shelvingTimes.includes(pickedMinutes) ? pickedMinutes : shelvingTimes[0]!;
  const [view, setView] = useState<"active" | "shelved">("active");
  const [shelved, setShelved] = useState<ShelvedEntry[]>([]);
  const [tick, setTick] = useState(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    try {
      const next = await alarmApi.list(client, sourceFilter, displayPathFilter);
      if (!mounted.current) return;
      setEvents(next);
      setLoaded(true);
      setPollError("");
      const now = Date.now();
      setShelved((current) => current.filter((entry) => entry.until > now));
    } catch (error) {
      if (!mounted.current) return;
      setPollError(`Could not load alarms: ${message(error)}`);
    }
  }, [client, sourceFilter, displayPathFilter]);

  useEffect(() => {
    if (!live) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const loop = async () => {
      await refresh();
      if (!stopped) timer = setTimeout(() => void loop(), refreshRate);
    };
    void loop();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [live, refresh, refreshRate, tick]);

  const visible = useMemo(
    () => sortAlarms(filterAlarms(events, { states, priorities, search }), sort),
    [events, states, priorities, search, sort],
  );
  const visibleIds = useMemo(() => new Set(visible.map((event) => event.eventId)), [visible]);
  const chosen = visible.filter((event) => selected.has(event.eventId));
  const ackable = chosen.filter((event) => !event.acknowledged);

  useEffect(() => {
    setSelected((current) => {
      const kept = new Set([...current].filter((id) => visibleIds.has(id)));
      return kept.size === current.size ? current : kept;
    });
  }, [visibleIds]);

  async function act(label: string, run: () => Promise<void>, done: string) {
    setBusy(true);
    setActionError("");
    setNotice("");
    try {
      await run();
      if (!mounted.current) return;
      setNotice(done);
      setSelected(new Set());
      setTick((value) => value + 1);
    } catch (error) {
      if (mounted.current) setActionError(`${label} failed: ${message(error)}`);
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  function toggleSelected(id: string, on: boolean) {
    const next = new Set(selected);
    if (on) next.add(id);
    else next.delete(id);
    setSelected(next);
    onChange("selectedEventIds", [...next]);
  }

  const allSelected = visible.length > 0 && visible.every((event) => selected.has(event.eventId));

  return (
    <div className="oip-alarm-table" data-source="gateway" data-view={view}>
      <div className="oip-alarm-tabs" role="tablist" aria-label="Alarm views">
        <button type="button" role="tab" aria-selected={view === "active"} onClick={() => setView("active")}>
          {`Active alarms (${visible.length})`}
        </button>
        <button type="button" role="tab" aria-selected={view === "shelved"} onClick={() => setView("shelved")}>
          {`Shelved (${shelved.length})`}
        </button>
      </div>

      {pollError ? <p role="alert" className="oip-alarm-error">{pollError}</p> : null}
      {actionError ? <p role="alert" className="oip-alarm-error">{actionError}</p> : null}
      {notice ? <p role="status" className="oip-alarm-notice">{notice}</p> : null}

      {view === "active" ? (
        <>
          <div className="oip-alarm-toolbar">
            <label>
              Search
              <input
                aria-label="Search alarms"
                type="search"
                value={search}
                onChange={(event) => {
                  setSearch(event.target.value);
                  onChange("filter", event.target.value);
                }}
              />
            </label>
            <fieldset>
              <legend>States</legend>
              {STATE_FILTERS.map((entry) => (
                <label key={entry.key}>
                  <input
                    type="checkbox"
                    checked={states[entry.key]}
                    onChange={(event) => {
                      setStates({ ...states, [entry.key]: event.target.checked });
                      onChange(entry.prop, event.target.checked);
                    }}
                  />
                  {entry.label}
                </label>
              ))}
            </fieldset>
            <fieldset>
              <legend>Priorities</legend>
              {PRIORITIES.map((name) => (
                <label key={name}>
                  <input
                    type="checkbox"
                    checked={priorities[name]}
                    onChange={(event) => {
                      setPriorities({ ...priorities, [name]: event.target.checked });
                      onChange(`show${name}`, event.target.checked);
                    }}
                  />
                  {name}
                </label>
              ))}
            </fieldset>
          </div>

          <div className="oip-alarm-actions">
            {enableAck ? (
              <>
                <label>
                  Notes
                  <input aria-label="Acknowledgement notes" value={notes} onChange={(event) => setNotes(event.target.value)} />
                </label>
                <button
                  type="button"
                  disabled={busy || ackable.length === 0}
                  onClick={() =>
                    void act(
                      "Acknowledge",
                      async () => {
                        await alarmApi.acknowledge(client, ackable.map((event) => event.eventId), notes);
                        setNotes("");
                      },
                      `Acknowledged ${ackable.length} alarm${ackable.length === 1 ? "" : "s"}.`,
                    )
                  }
                >
                  Acknowledge
                </button>
              </>
            ) : null}
            {enableShelve ? (
              <>
                <label>
                  Shelve for
                  <select
                    aria-label="Shelve duration"
                    value={shelveMinutes}
                    onChange={(event) => setShelveMinutes(Number(event.target.value))}
                  >
                    {shelvingTimes.map((minutes) => (
                      <option key={minutes} value={minutes}>
                        {durationLabel(minutes)}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  type="button"
                  disabled={busy || chosen.length === 0}
                  onClick={() => {
                    const bySource = new Map(chosen.map((event) => [event.source, event.displayPath || event.source]));
                    const minutes = shelveMinutes;
                    void act(
                      "Shelve",
                      async () => {
                        await alarmApi.shelve(client, [...bySource.keys()], minutes);
                        const until = Date.now() + minutes * 60_000;
                        setShelved((current) => [
                          ...current.filter((entry) => !bySource.has(entry.source)),
                          ...[...bySource].map(([source, displayPath]) => ({ source, displayPath, until })),
                        ]);
                      },
                      `Shelved ${bySource.size} alarm${bySource.size === 1 ? "" : "s"} for ${durationLabel(minutes)}.`,
                    );
                  }}
                >
                  Shelve
                </button>
              </>
            ) : null}
          </div>

          <table>
            <thead>
              <tr>
                <th scope="col">
                  <input
                    type="checkbox"
                    aria-label="Select all alarms"
                    checked={allSelected}
                    onChange={(event) => {
                      const next = event.target.checked ? new Set(visible.map((alarm) => alarm.eventId)) : new Set<string>();
                      setSelected(next);
                      onChange("selectedEventIds", [...next]);
                    }}
                  />
                </th>
                {columns.map((column) => (
                  <th key={column} scope="col" aria-sort={sort.column === column ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}>
                    <button type="button" onClick={() => setSort(nextAlarmSort(sort, column))}>
                      {ALARM_COLUMNS[column]}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {visible.map((event) => (
                <tr
                  key={event.eventId}
                  data-event-id={event.eventId}
                  data-state={stateKey(event)}
                  data-priority={event.priority.toLowerCase()}
                  aria-selected={selected.has(event.eventId)}
                >
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Select ${event.displayPath || event.source}`}
                      checked={selected.has(event.eventId)}
                      onChange={(change) => toggleSelected(event.eventId, change.target.checked)}
                    />
                  </td>
                  {columns.map((column) => (
                    <td key={column} data-column={column}>
                      {cellText(event, column, locale)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {loaded && visible.length === 0 ? <p className="oip-alarm-empty">No alarms match the filters.</p> : null}
        </>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Display path</th>
              <th scope="col">Source path</th>
              <th scope="col">Shelved until</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {shelved.map((entry) => (
              <tr key={entry.source} data-state="shelved">
                <td>{entry.displayPath}</td>
                <td>{entry.source}</td>
                <td>{new Intl.DateTimeFormat(locale, { timeStyle: "medium" }).format(entry.until)}</td>
                <td>
                  {enableUnshelve ? (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() =>
                        void act(
                          "Unshelve",
                          async () => {
                            await alarmApi.unshelve(client, [entry.source]);
                            setShelved((current) => current.filter((item) => item.source !== entry.source));
                          },
                          `Unshelved ${entry.displayPath}.`,
                        )
                      }
                    >
                      {`Unshelve ${entry.displayPath}`}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export function durationLabel(minutes: number): string {
  if (minutes % 60 === 0) {
    const hours = minutes / 60;
    return `${hours} hour${hours === 1 ? "" : "s"}`;
  }
  return `${minutes} minute${minutes === 1 ? "" : "s"}`;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
