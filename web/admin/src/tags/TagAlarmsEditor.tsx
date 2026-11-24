import { useState } from "react";

/**
 * Alarms on one tag as a form (UI audit): add, edit and remove alarms without writing JSON. Each alarm keeps any
 * property this form does not show (pipelines, labels, shelving...) so nothing is lost on save. A setpoint can be a
 * number or another tag, the usual way to alarm against a controller's own limit register.
 */

export const ALARM_MODES = [
  { value: "AboveValue", label: "Above setpoint" },
  { value: "BelowValue", label: "Below setpoint" },
  { value: "Equality", label: "Equal to setpoint" },
  { value: "OutsideValues", label: "Outside low/high setpoints" },
  { value: "Bit", label: "Bit is set" },
  { value: "OutOfEngRange", label: "Outside engineering range" },
] as const;

export const ALARM_PRIORITIES = ["Diagnostic", "Low", "Medium", "High", "Critical"] as const;

type Alarm = Record<string, unknown>;

export function alarmList(raw: unknown): Alarm[] {
  return Array.isArray(raw) ? raw.filter((a): a is Alarm => !!a && typeof a === "object" && !Array.isArray(a)) : [];
}

/** Problems a user must fix before the alarms can be saved, keyed by alarm index. */
export function alarmProblems(alarms: Alarm[]): Record<number, string> {
  const out: Record<number, string> = {};
  const names = new Set<string>();
  alarms.forEach((a, i) => {
    const name = typeof a.name === "string" ? a.name.trim() : "";
    if (!name) out[i] = "Give the alarm a name.";
    else if (names.has(name)) out[i] = `Another alarm on this tag is already named ${name}.`;
    else if (needsSetpoint(String(a.mode)) && a.setpointA === undefined) out[i] = "Enter a setpoint.";
    else if (a.mode === "OutsideValues" && a.setpointB === undefined) out[i] = "Enter the high setpoint.";
    else if (a.mode === "Bit" && a.bitPosition === undefined) out[i] = "Enter the bit position.";
    names.add(name);
  });
  return out;
}

function needsSetpoint(mode: string): boolean {
  return mode === "AboveValue" || mode === "BelowValue" || mode === "Equality" || mode === "OutsideValues";
}

function set(alarm: Alarm, key: string, value: unknown): Alarm {
  const next = { ...alarm };
  if (value === undefined || value === "") delete next[key];
  else next[key] = value;
  return next;
}

function numberOrUndefined(text: string): number | undefined {
  if (text.trim() === "") return undefined;
  const n = Number(text);
  return Number.isFinite(n) ? n : undefined;
}

export function TagAlarmsEditor({ alarms, onChange }: { alarms: unknown; onChange: (next: Alarm[] | undefined) => void }) {
  const list = alarmList(alarms);
  const problems = alarmProblems(list);
  const update = (index: number, next: Alarm) => onChange(list.map((a, i) => (i === index ? next : a)));
  const add = () =>
    onChange([...list, { name: list.length === 0 ? "High" : `Alarm${list.length + 1}`, mode: "AboveValue", priority: "Medium", setpointA: 0 }]);
  return (
    <div className="tag-alarms">
      {list.length === 0 ? <p className="muted">No alarms are configured on this tag.</p> : null}
      {list.map((alarm, index) => (
        <AlarmFields
          key={index}
          index={index}
          alarm={alarm}
          problem={problems[index]}
          onChange={(next) => update(index, next)}
          onRemove={() => {
            const rest = list.filter((_, i) => i !== index);
            onChange(rest.length > 0 ? rest : undefined);
          }}
        />
      ))}
      <button type="button" onClick={add}>
        Add alarm
      </button>
    </div>
  );
}

function SetpointInput({ label, value, onChange }: { label: string; value: unknown; onChange: (next: unknown) => void }) {
  const isTag = !!value && typeof value === "object" && typeof (value as Record<string, unknown>).tag === "string";
  const [source, setSource] = useState<"value" | "tag">(isTag ? "tag" : "value");
  return (
    <span className="inline-form">
      <label>
        {label} from
        <select
          aria-label={`${label} source`}
          value={source}
          onChange={(event) => {
            const next = event.target.value as "value" | "tag";
            setSource(next);
            onChange(next === "tag" ? { tag: "" } : undefined);
          }}
        >
          <option value="value">a fixed value</option>
          <option value="tag">another tag</option>
        </select>
      </label>
      {source === "value" ? (
        <label>
          {label}
          <input
            aria-label={label}
            type="number"
            step="any"
            value={typeof value === "number" ? String(value) : ""}
            onChange={(event) => onChange(numberOrUndefined(event.target.value))}
          />
        </label>
      ) : (
        <label>
          {label} tag
          <input
            aria-label={`${label} tag`}
            placeholder="[default]Folder/Limit"
            value={isTag ? String((value as Record<string, unknown>).tag) : ""}
            onChange={(event) => onChange({ tag: event.target.value })}
          />
        </label>
      )}
    </span>
  );
}

function AlarmFields({
  index,
  alarm,
  problem,
  onChange,
  onRemove,
}: {
  index: number;
  alarm: Alarm;
  problem?: string;
  onChange: (next: Alarm) => void;
  onRemove: () => void;
}) {
  const name = typeof alarm.name === "string" ? alarm.name : "";
  const mode = typeof alarm.mode === "string" ? alarm.mode : "AboveValue";
  const enabled = alarm.enabled === undefined ? true : alarm.enabled === true;
  return (
    <fieldset className="tag-alarm" role="group" aria-label={`Alarm ${name || index + 1}`}>
      <legend>{name || `Alarm ${index + 1}`}</legend>
      {problem ? <p role="alert">{problem}</p> : null}
      <label>
        Alarm name
        <input aria-label="Alarm name" value={name} onChange={(event) => onChange(set(alarm, "name", event.target.value))} />
      </label>
      <label className="oip-schema-check">
        <input
          type="checkbox"
          aria-label="Enabled"
          checked={enabled}
          onChange={(event) => onChange(set(alarm, "enabled", event.target.checked ? undefined : false))}
        />
        Enabled
      </label>
      <label>
        Condition
        <select aria-label="Condition" value={mode} onChange={(event) => onChange(set(alarm, "mode", event.target.value))}>
          {ALARM_MODES.map((m) => (
            <option key={m.value} value={m.value}>
              {m.label}
            </option>
          ))}
        </select>
      </label>
      {needsSetpoint(mode) ? (
        <SetpointInput
          label={mode === "OutsideValues" ? "Low setpoint" : "Setpoint"}
          value={alarm.setpointA}
          onChange={(v) => onChange(set(alarm, "setpointA", v))}
        />
      ) : null}
      {mode === "OutsideValues" ? (
        <SetpointInput label="High setpoint" value={alarm.setpointB} onChange={(v) => onChange(set(alarm, "setpointB", v))} />
      ) : null}
      {mode === "Bit" ? (
        <label>
          Bit position
          <input
            aria-label="Bit position"
            type="number"
            min={0}
            max={63}
            value={typeof alarm.bitPosition === "number" ? String(alarm.bitPosition) : ""}
            onChange={(event) => onChange(set(alarm, "bitPosition", numberOrUndefined(event.target.value)))}
          />
        </label>
      ) : null}
      <label>
        Priority
        <select aria-label="Priority" value={String(alarm.priority ?? "Medium")} onChange={(event) => onChange(set(alarm, "priority", event.target.value))}>
          {ALARM_PRIORITIES.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
      </label>
      <label>
        Deadband
        <input
          aria-label="Deadband"
          type="number"
          step="any"
          min={0}
          value={typeof alarm.deadband === "number" ? String(alarm.deadband) : ""}
          onChange={(event) => onChange(set(alarm, "deadband", numberOrUndefined(event.target.value)))}
        />
      </label>
      <label>
        On delay (s)
        <input
          aria-label="On delay (s)"
          type="number"
          min={0}
          step="any"
          value={typeof alarm.timeOnDelaySeconds === "number" ? String(alarm.timeOnDelaySeconds) : ""}
          onChange={(event) => onChange(set(alarm, "timeOnDelaySeconds", numberOrUndefined(event.target.value)))}
        />
      </label>
      <label>
        Off delay (s)
        <input
          aria-label="Off delay (s)"
          type="number"
          min={0}
          step="any"
          value={typeof alarm.timeOffDelaySeconds === "number" ? String(alarm.timeOffDelaySeconds) : ""}
          onChange={(event) => onChange(set(alarm, "timeOffDelaySeconds", numberOrUndefined(event.target.value)))}
        />
      </label>
      {(["activePipeline", "clearPipeline", "ackPipeline"] as const).map((key) => {
        const label = { activePipeline: "Active pipeline", clearPipeline: "Clear pipeline", ackPipeline: "Ack pipeline" }[key];
        return (
          <label key={key}>
            {label}
            <input
              aria-label={label}
              value={typeof alarm[key] === "string" ? String(alarm[key]) : ""}
              placeholder="notification pipeline name (optional)"
              onChange={(event) => onChange(set(alarm, key, event.target.value.trim()))}
            />
          </label>
        );
      })}
      <label>
        Acknowledge
        <select aria-label="Acknowledge" value={String(alarm.ackMode ?? "Manual")} onChange={(event) => onChange(set(alarm, "ackMode", event.target.value))}>
          <option value="Manual">Operator acknowledges</option>
          <option value="Auto">Automatically when it clears</option>
          <option value="Unused">No acknowledgement</option>
        </select>
      </label>
      <label className="oip-binding-wide">
        Notes
        <input aria-label="Notes" value={typeof alarm.notes === "string" ? alarm.notes : ""} onChange={(event) => onChange(set(alarm, "notes", event.target.value))} />
      </label>
      <button type="button" className="danger" onClick={onRemove}>
        Remove alarm {name || index + 1}
      </button>
    </fieldset>
  );
}
