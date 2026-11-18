import type { ReactNode } from "react";

/** Small labelled controls shared by the report workspace tabs. Each is a wrapping label with the same accessible name. */

export function TextField({ label, value, onChange, list, placeholder, disabled }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  list?: string;
  placeholder?: string;
  disabled?: boolean;
}) {
  return (
    <label>
      {label}
      <input aria-label={label} value={value} list={list} placeholder={placeholder} disabled={disabled} onChange={(event) => onChange(event.target.value)} />
    </label>
  );
}

export function NumberField({ label, value, onChange, min, max, step }: {
  label: string;
  value: number | undefined;
  onChange: (value: number | undefined) => void;
  min?: number;
  max?: number;
  step?: number;
}) {
  return (
    <label>
      {label}
      <input
        aria-label={label}
        type="number"
        value={value ?? ""}
        min={min}
        max={max}
        step={step}
        onChange={(event) => onChange(event.target.value === "" ? undefined : Number(event.target.value))}
      />
    </label>
  );
}

export function CheckField({ label, checked, onChange }: { label: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return (
    <label className="check-field">
      <input aria-label={label} type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      {label}
    </label>
  );
}

export function SelectField({ label, value, options, onChange, blank }: {
  label: string;
  value: string;
  options: readonly string[] | readonly { value: string; label: string }[];
  onChange: (value: string) => void;
  /** Text of an empty first option; omit for none. */
  blank?: string;
}) {
  return (
    <label>
      {label}
      <select aria-label={label} value={value} onChange={(event) => onChange(event.target.value)}>
        {blank !== undefined ? <option value="">{blank}</option> : null}
        {options.map((option) => {
          const item = typeof option === "string" ? { value: option, label: option } : option;
          return (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          );
        })}
      </select>
    </label>
  );
}

export function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <fieldset className="report-panel">
      <legend>{title}</legend>
      {children}
    </fieldset>
  );
}

export function errorText(error: unknown, fallback: string): string {
  if (error && typeof error === "object" && "errors" in error && Array.isArray((error as { errors: unknown }).errors)) {
    const errors = (error as { errors: unknown[] }).errors.filter((item): item is string => typeof item === "string");
    const message = error instanceof Error ? error.message : fallback;
    if (errors.length > 0) return `${message}: ${errors.join("; ")}`;
  }
  return error instanceof Error ? error.message : fallback;
}
