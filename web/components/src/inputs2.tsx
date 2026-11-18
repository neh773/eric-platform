import { useEffect, useId, useRef, useState } from "react";
import type { MouseEvent } from "react";
import { linkTarget, type HmiComponentProps, type LinkTarget } from "@oip/hmi-runtime";
import { readChoices, sameValue } from "./choices";
import { readBoolean, readNumber, readString } from "./read";
import { useGatewayClient } from "./gateway";

/** C-11 batch 1 input components. Behavior is written from the public component pages below; the code,
 * labels and descriptions are our own. Writable props go through `onChange`, which the view renderer
 * turns into a tag or property write when the binding on that prop is bidirectional.
 *
 * Date-time input:   -
 * Date-time picker:  -
 * Password field:    -
 * Radio group:       -
 * Text area:         -
 * One-shot button:   -
 * Multi-state button: -
 * Link:              -
 *
 * Date-time values are epoch milliseconds, shown in the browser's local time zone.
 */

type DateMode = "datetime" | "date" | "time";

function pad(value: number, width = 2): string {
  return String(value).padStart(width, "0");
}

function readEpoch(props: Record<string, unknown>, key: string): number | null {
  const value = props[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function readMode(value: unknown): DateMode {
  return value === "date" || value === "time" ? value : "datetime";
}

/** Epoch ms to the string a native date/time input expects, in local time. */
export function toInputString(epoch: number | null, mode: DateMode): string {
  if (epoch === null) return "";
  const date = new Date(epoch);
  if (Number.isNaN(date.getTime())) return "";
  const day = `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (mode === "date") return day;
  if (mode === "time") return time;
  return `${day}T${time}`;
}

/** Parses a native input string back to epoch ms. A time-only edit keeps the date of `previous` (or today). */
export function fromInputString(text: string, mode: DateMode, previous: number | null): number | null {
  if (mode === "time") {
    const match = /^(\d{2}):(\d{2})/.exec(text);
    if (!match) return null;
    const base = new Date(previous ?? Date.now());
    return new Date(base.getFullYear(), base.getMonth(), base.getDate(), Number(match[1]), Number(match[2])).getTime();
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(text);
  if (!match) return null;
  if (mode === "datetime" && match[4] === undefined) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]) - 1, Number(match[3])];
  const date = new Date(year, month, day, Number(match[4] ?? 0), Number(match[5] ?? 0));
  if (date.getMonth() !== month || date.getDate() !== day) return null;
  return date.getTime();
}

export function DateTimeInput({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Date and time");
  const mode = readMode(props.mode);
  const enabled = readBoolean(props, "enabled", true);
  const value = readEpoch(props, "value");
  const min = readEpoch(props, "min");
  const max = readEpoch(props, "max");
  const fieldId = useId();
  const [draft, setDraft] = useState(toInputString(value, mode));
  const [invalid, setInvalid] = useState(false);
  useEffect(() => {
    setDraft(toInputString(value, mode));
    setInvalid(false);
  }, [value, mode]);
  return (
    <div className="oip-datetime" data-mode={mode}>
      <label htmlFor={fieldId}>{label}</label>
      <input
        id={fieldId}
        type={mode === "datetime" ? "datetime-local" : mode}
        value={draft}
        disabled={!enabled}
        min={min === null || mode === "time" ? undefined : toInputString(min, mode)}
        max={max === null || mode === "time" ? undefined : toInputString(max, mode)}
        aria-invalid={invalid || undefined}
        onChange={(event) => {
          const text = event.target.value;
          setDraft(text);
          const next = fromInputString(text, mode, value);
          const outOfRange = next !== null && ((min !== null && next < min) || (max !== null && next > max));
          setInvalid(text !== "" && (next === null || outOfRange));
          if (next === null || outOfRange) return;
          onChange("value", next);
        }}
      />
    </div>
  );
}

function startOfMonth(epoch: number): { year: number; month: number } {
  const date = new Date(epoch);
  return { year: date.getFullYear(), month: date.getMonth() };
}

/** Six weeks of dates covering the month, starting on `weekStart` (0 = Sunday). */
export function monthGrid(year: number, month: number, weekStart: number): Date[] {
  const first = new Date(year, month, 1);
  const offset = (first.getDay() - weekStart + 7) % 7;
  return Array.from({ length: 42 }, (_, index) => new Date(year, month, 1 - offset + index));
}

export function DateTimePicker({ props, locale, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Pick a date");
  const showTime = readBoolean(props, "showTime", true);
  const enabled = readBoolean(props, "enabled", true);
  const weekStart = Math.min(6, Math.max(0, Math.round(readNumber(props, "weekStart", 1))));
  const value = readEpoch(props, "value");
  const [view, setView] = useState(() => startOfMonth(value ?? Date.now()));
  const timeId = useId();
  useEffect(() => {
    if (value !== null) setView(startOfMonth(value));
  }, [value]);
  const selected = value === null ? null : new Date(value);
  const days = monthGrid(view.year, view.month, weekStart);
  const monthName = new Intl.DateTimeFormat(locale, { month: "long", year: "numeric" }).format(new Date(view.year, view.month, 1));
  const dayName = new Intl.DateTimeFormat(locale, { weekday: "short" });
  const fullDay = new Intl.DateTimeFormat(locale, { year: "numeric", month: "long", day: "numeric" });
  const hours = selected ? selected.getHours() : 0;
  const minutes = selected ? selected.getMinutes() : 0;
  const shift = (delta: number) => setView((current) => startOfMonth(new Date(current.year, current.month + delta, 1).getTime()));
  return (
    <div className="oip-picker" role="group" aria-label={label} aria-disabled={!enabled || undefined}>
      <div className="oip-picker-head">
        <button type="button" aria-label="Previous month" disabled={!enabled} onClick={() => shift(-1)}>
          ‹
        </button>
        <span aria-live="polite">{monthName}</span>
        <button type="button" aria-label="Next month" disabled={!enabled} onClick={() => shift(1)}>
          ›
        </button>
      </div>
      <div className="oip-picker-grid" role="grid" aria-label={monthName}>
        <div role="row" className="oip-picker-row">
          {days.slice(0, 7).map((day) => (
            <span role="columnheader" key={`h${day.getDay()}`}>
              {dayName.format(day)}
            </span>
          ))}
        </div>
        {[0, 1, 2, 3, 4, 5].map((week) => (
          <div role="row" className="oip-picker-row" key={week}>
            {days.slice(week * 7, week * 7 + 7).map((day) => {
              const isSelected =
                selected !== null &&
                day.getFullYear() === selected.getFullYear() &&
                day.getMonth() === selected.getMonth() &&
                day.getDate() === selected.getDate();
              return (
                <span role="gridcell" key={day.getTime()} aria-selected={isSelected}>
                  <button
                    type="button"
                    aria-label={fullDay.format(day)}
                    aria-pressed={isSelected}
                    data-outside={day.getMonth() !== view.month || undefined}
                    disabled={!enabled}
                    onClick={() =>
                      onChange("value", new Date(day.getFullYear(), day.getMonth(), day.getDate(), hours, minutes).getTime())
                    }
                  >
                    {day.getDate()}
                  </button>
                </span>
              );
            })}
          </div>
        ))}
      </div>
      {showTime ? (
        <div className="oip-picker-time">
          <label htmlFor={timeId}>Time</label>
          <input
            id={timeId}
            type="time"
            disabled={!enabled}
            value={selected ? `${pad(hours)}:${pad(minutes)}` : ""}
            onChange={(event) => {
              const next = fromInputString(event.target.value, "time", value ?? new Date(view.year, view.month, 1).getTime());
              if (next !== null) onChange("value", next);
            }}
          />
        </div>
      ) : null}
    </div>
  );
}

/** The value lives only in the input's `value` property. It is never a React `value`/`defaultValue`
 * prop (React would mirror that into the `value` attribute) and never text content.
 */
export function PasswordField({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Password");
  const placeholder = readString(props, "placeholder");
  const enabled = readBoolean(props, "enabled", true);
  const value = readString(props, "value", "");
  const input = useRef<HTMLInputElement>(null);
  const focused = useRef(false);
  const fieldId = useId();
  useEffect(() => {
    if (input.current && !focused.current) input.current.value = value;
  }, [value]);
  const commit = () => {
    const next = input.current?.value ?? "";
    if (next !== value) onChange("value", next);
  };
  return (
    <div className="oip-password">
      <label htmlFor={fieldId}>{label}</label>
      <input
        ref={input}
        id={fieldId}
        type="password"
        autoComplete="current-password"
        spellCheck={false}
        placeholder={placeholder}
        disabled={!enabled}
        onFocus={() => {
          focused.current = true;
        }}
        onBlur={() => {
          focused.current = false;
          commit();
        }}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          }
        }}
      />
    </div>
  );
}

export function RadioGroup({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Choose one");
  const enabled = readBoolean(props, "enabled", true);
  const orientation = props.orientation === "column" ? "column" : "row";
  const options = readChoices(props.options);
  const name = useId();
  const current = props.value;
  return (
    <div className="oip-radio" role="radiogroup" aria-label={label} aria-disabled={!enabled || undefined} data-orientation={orientation}>
      <span className="oip-radio-title" aria-hidden="true">
        {label}
      </span>
      {options.map((option) => (
        <label key={String(option.value)}>
          <input
            type="radio"
            name={name}
            value={String(option.value)}
            checked={current !== undefined && current !== null && sameValue(current, option.value)}
            disabled={!enabled}
            onChange={() => onChange("value", option.value)}
          />
          <span>{option.label}</span>
        </label>
      ))}
    </div>
  );
}

export function TextArea({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Notes");
  const value = readString(props, "value", "");
  const placeholder = readString(props, "placeholder");
  const rows = Math.min(40, Math.max(1, Math.round(readNumber(props, "rows", 4))));
  const maxLength = Math.max(1, Math.round(readNumber(props, "maxLength", 2000)));
  const deferUpdates = readBoolean(props, "deferUpdates", true);
  const enabled = readBoolean(props, "enabled", true);
  const [draft, setDraft] = useState(value);
  const [focused, setFocused] = useState(false);
  const fieldId = useId();
  useEffect(() => {
    if (!focused) setDraft(value);
  }, [value, focused]);
  return (
    <div className="oip-textarea">
      <label htmlFor={fieldId}>{label}</label>
      <textarea
        id={fieldId}
        value={draft}
        rows={rows}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={!enabled}
        onFocus={() => setFocused(true)}
        onBlur={() => {
          setFocused(false);
          if (draft !== value) onChange("value", draft);
        }}
        onChange={(event) => {
          setDraft(event.target.value);
          if (!deferUpdates) onChange("value", event.target.value);
        }}
      />
    </div>
  );
}

/** Writes `writeValue` once, then stays disabled until the bound `value` reads back as the written value
 * or `timeoutMs` passes, whichever comes first.
 */
export function OneShotButton({ props, onChange }: HmiComponentProps) {
  const enabled = readBoolean(props, "enabled", true);
  const text = readString(props, "text", "Start");
  const writingText = readString(props, "writingText", "Writing...");
  const timeoutMs = Math.max(100, Math.round(readNumber(props, "timeoutMs", 5000)));
  const writeValue = "writeValue" in props ? props.writeValue : 1;
  const [pending, setPending] = useState<{ value: unknown } | null>(null);
  useEffect(() => {
    if (pending && sameValue(props.value, pending.value)) setPending(null);
  }, [pending, props.value]);
  useEffect(() => {
    if (!pending) return undefined;
    const timer = window.setTimeout(() => setPending(null), timeoutMs);
    return () => window.clearTimeout(timer);
  }, [pending, timeoutMs]);
  const busy = pending !== null;
  return (
    <button
      type="button"
      className="oip-button oip-oneshot"
      disabled={!enabled || busy}
      aria-busy={busy || undefined}
      data-state={busy ? "pending" : "ready"}
      onClick={() => {
        if (!enabled || busy) return;
        setPending({ value: writeValue });
        onChange("value", writeValue);
      }}
    >
      {busy ? writingText : text}
    </button>
  );
}

/** One button per state; exactly the state whose value matches `value` is pressed. Pressing a state writes its value. */
export function MultiStateButton({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Mode");
  const enabled = readBoolean(props, "enabled", true);
  const orientation = props.orientation === "column" ? "column" : "row";
  const states = readChoices(props.states, true);
  const active = states.find((state) => props.value !== undefined && props.value !== null && sameValue(props.value, state.value));
  return (
    <div className="oip-multistate" role="group" aria-label={label} data-orientation={orientation} data-value={active ? String(active.value) : ""}>
      {states.map((state) => {
        const on = state === active;
        return (
          <button
            key={String(state.value)}
            type="button"
            aria-pressed={on}
            disabled={!enabled}
            onClick={() => {
              if (!on) onChange("value", state.value);
            }}
          >
            {state.label}
          </button>
        );
      })}
    </div>
  );
}

/** The HMI URL filter now lives in the runtime, which the navigation action shares (review-4 F3). */
export { linkTarget, type LinkTarget };

/** A page-path link does not reload the document: it moves the session through the page runtime's
 * `navigate` hook (PageSession), and its click still bubbles to the node so `onClick` actions run as well.
 */
export function Link({ props, navigate, pageHref }: HmiComponentProps) {
  const text = readString(props, "text", "Link");
  const enabled = readBoolean(props, "enabled", true);
  const newTab = readBoolean(props, "newTab", false);
  const target = linkTarget(readString(props, "url", "/"));
  const href = target.kind === "page" ? (pageHref ? pageHref(target.path) : target.path) : target.kind === "external" ? target.url : undefined;
  const onClick = (event: MouseEvent<HTMLAnchorElement>) => {
    if (!enabled || target.kind === "blocked") {
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (target.kind === "page") {
      event.preventDefault();
      navigate?.(target.path);
    }
  };
  return (
    <a
      className="oip-link"
      href={enabled ? href : undefined}
      role="link"
      aria-disabled={!enabled || target.kind === "blocked" || undefined}
      data-target={target.kind}
      target={target.kind === "external" && newTab ? "_blank" : undefined}
      rel={target.kind === "external" ? "noopener noreferrer" : undefined}
      tabIndex={enabled ? undefined : -1}
      onClick={onClick}
    >
      {text}
    </a>
  );
}

/** Sends the chosen file to a project script hook over `POST /api/v1/hmi/projects/{project}/upload/{hook}` (HMP-047).
 * The gateway checks the size, the content type and the signed-in user and hands the file to the handler; this
 * component only picks the file, refuses one over `maxBytes` before sending, and shows the outcome.
 */
export function FileUpload({ nodeId, props }: HmiComponentProps) {
  const client = useGatewayClient();
  const project = readString(props, "project");
  const hook = readString(props, "hook");
  const view = readString(props, "view");
  const accept = readString(props, "accept");
  const maxBytes = Math.max(0, Math.round(readNumber(props, "maxBytes", 0)));
  const enabled = readBoolean(props, "enabled", true) && project !== "" && hook !== "";
  const [state, setState] = useState<{ kind: "idle" } | { kind: "sending"; name: string } | { kind: "done"; name: string } | { kind: "failed"; message: string }>({ kind: "idle" });
  const input = useRef<HTMLInputElement>(null);
  async function send(file: File) {
    if (maxBytes > 0 && file.size > maxBytes) {
      setState({ kind: "failed", message: `${file.name} is larger than ${maxBytes} bytes` });
      return;
    }
    setState({ kind: "sending", name: file.name });
    const query = new URLSearchParams({ node: nodeId });
    if (view) query.set("view", view);
    try {
      await client.postFile(`/api/v1/hmi/projects/${encodeURIComponent(project)}/upload/${encodeURIComponent(hook)}?${query}`, file, file.name);
      setState({ kind: "done", name: file.name });
    } catch (error) {
      setState({ kind: "failed", message: error instanceof Error ? error.message : "The upload failed" });
    }
  }
  const busy = state.kind === "sending";
  return (
    <div className="oip-file-upload">
      <input
        ref={input}
        type="file"
        hidden
        accept={accept || undefined}
        data-testid="file-upload-input"
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (file) void send(file);
        }}
      />
      <button type="button" className="oip-button" disabled={!enabled || busy} aria-busy={busy || undefined} onClick={() => input.current?.click()}>
        {busy ? `Sending ${state.name}...` : readString(props, "text", "Choose a file")}
      </button>
      {state.kind === "done" ? <span role="status">{`${state.name} sent`}</span> : null}
      {state.kind === "failed" ? <span role="alert">{state.message}</span> : null}
    </div>
  );
}
