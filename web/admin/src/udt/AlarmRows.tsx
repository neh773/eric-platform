import { useEffect, useState } from "react";
import { ALARM_MODES, ALARM_PRIORITIES, numText, patchSpec, str, toNumber, type Spec } from "./udtModel";

/**
 * Alarm rows (0.1.1): add, edit and remove alarms. Shared by UDT members (UDT Definitions) and by a single tag
 * (Tags → tag editor → Alarms). Each row is one entry of the tag's `alarms` array.
 */
export function AlarmRows({ alarms, onChange, emptyText }: { alarms: Spec[]; onChange: (next: Spec[]) => void; emptyText: string }) {
  function patchAlarm(index: number, patch: Spec) {
    onChange(alarms.map((a, i) => (i === index ? patchSpec(a, patch) : a)));
  }
  return (
    <>
      {alarms.length === 0 ? <p className="muted">{emptyText}</p> : null}
      {alarms.map((alarm, i) => (
        <div className="form-grid alarm-row" key={i} role="group" aria-label={`Alarm ${str(alarm.name) || i + 1}`}>
          <label>
            Alarm name
            <input value={str(alarm.name)} onChange={(event) => patchAlarm(i, { name: event.target.value })} />
          </label>
          <label>
            Condition
            <select aria-label="Condition" value={str(alarm.mode) || "AboveValue"} onChange={(event) => patchAlarm(i, { mode: event.target.value })}>
              {ALARM_MODES.map((m) => (
                <option key={m} value={m}>
                  {alarmModeLabel(m)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Priority
            <select aria-label="Priority" value={str(alarm.priority) || "Medium"} onChange={(event) => patchAlarm(i, { priority: event.target.value })}>
              {ALARM_PRIORITIES.map((p) => (
                <option key={p}>{p}</option>
              ))}
            </select>
          </label>
          {alarm.mode !== "OutOfEngRange" && alarm.mode !== "Bit" ? (
            <NumberField label={alarm.mode === "OutsideValues" ? "Low setpoint" : "Setpoint"} value={alarm.setpointA} onChange={(v) => patchAlarm(i, { setpointA: v })} />
          ) : null}
          {alarm.mode === "OutsideValues" ? <NumberField label="High setpoint" value={alarm.setpointB} onChange={(v) => patchAlarm(i, { setpointB: v })} /> : null}
          {alarm.mode === "Bit" ? <NumberField label="Bit" value={alarm.bitPosition} onChange={(v) => patchAlarm(i, { bitPosition: v })} /> : null}
          <NumberField label="Deadband" value={alarm.deadband} onChange={(v) => patchAlarm(i, { deadband: v })} />
          <NumberField label="On delay (s)" value={alarm.timeOnDelaySeconds} onChange={(v) => patchAlarm(i, { timeOnDelaySeconds: v })} />
          <label className="check-field">
            <input type="checkbox" checked={alarm.enabled !== false} onChange={(event) => patchAlarm(i, { enabled: event.target.checked ? undefined : false })} />
            Enabled
          </label>
          <button type="button" onClick={() => onChange(alarms.filter((_, j) => j !== i))}>
            Remove alarm {str(alarm.name) || i + 1}
          </button>
        </div>
      ))}
      <button type="button" onClick={() => onChange([...alarms, { name: "", mode: "AboveValue", priority: "Medium" }])}>
        Add alarm
      </button>
    </>
  );
}

export function NumberField({ label, name, value, onChange }: { label: string; name?: string; value: unknown; onChange: (value: number | undefined) => void }) {
  const [text, setText] = useState(numText(value));
  useEffect(() => {
    if (toNumber(text) !== value) setText(numText(value));
  }, [value]);
  return (
    <label>
      {label}
      <input
        name={name}
        type="number"
        step="any"
        value={text}
        onChange={(event) => {
          setText(event.target.value);
          onChange(toNumber(event.target.value));
        }}
      />
    </label>
  );
}

function alarmModeLabel(mode: string): string {
  switch (mode) {
    case "AboveValue":
      return "Above setpoint";
    case "BelowValue":
      return "Below setpoint";
    case "Equality":
      return "Equal to setpoint";
    case "Bit":
      return "Bit is set";
    case "OutOfEngRange":
      return "Outside engineering range";
    case "OutsideValues":
      return "Outside low/high setpoints";
    default:
      return mode;
  }
}
