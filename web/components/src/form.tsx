import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent, type MouseEvent } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { readChoices, type Choice } from "./choices";
import { listError, readList } from "./containers";
import { readBoolean, readString } from "./read";

/**
 * Form (HMP-064): input fields built from a list, checked as a whole before they are submitted. Written from the
 * public component page; the code and wording are our own.
 * doc source: FEATURES HMP-064
 *
 * `fields` is a list (an array, or JSON text of one) of field definitions. The operator edits the fields; pressing the
 * submit button (or Enter in a field) checks every field. When any field is invalid the form shows each reason beside
 * its field, moves focus to the first invalid one and submits nothing: `values` and `submitCount` do not change and
 * the node's `onClick` actions do not run. When every field is valid, `values` becomes an object of the typed field
 * values (numbers for number fields, booleans for checkboxes), `submitCount` goes up by one, and then the click carries
 * on to the node's `onClick` actions. A bidirectional binding on `values` therefore receives only valid submissions,
 * and an `onPropertyChange` event on `submitCount` is the submit action. The form never posts to a URL.
 */

export const formSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Form",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Form" },
    fields: {
      type: "string",
      title: "Fields (JSON array of {name, label, type, required, min, max, minLength, maxLength, pattern, options, default, help, message}; or bind an array)",
      default: '[{"name":"name","label":"Name","type":"text","required":true},{"name":"quantity","label":"Quantity","type":"number","min":1}]',
    },
    values: { type: "object", title: "Values (written on a valid submit)" },
    submitText: { type: "string", title: "Submit button text", default: "Submit" },
    showReset: { type: "boolean", title: "Show reset button", default: false },
    resetText: { type: "string", title: "Reset button text", default: "Reset" },
    submitCount: { type: "integer", title: "Submit count", minimum: 0, default: 0 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export type FormFieldType = "text" | "number" | "email" | "password" | "textarea" | "checkbox" | "select" | "date";

export interface FormField {
  name: string;
  label: string;
  type: FormFieldType;
  required: boolean;
  min: number | null;
  max: number | null;
  minLength: number | null;
  maxLength: number | null;
  pattern: RegExp | null;
  options: Choice[];
  initial: unknown;
  help: string;
  message: string;
  placeholder: string;
}

const FIELD_TYPES = new Set<string>(["text", "number", "email", "password", "textarea", "checkbox", "select", "date"]);
const FIELD_NAME = /^[A-Za-z_][A-Za-z0-9_-]{0,63}$/;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
/** Longest text a pattern is tested against, so a hostile or careless pattern cannot run long. */
const PATTERN_INPUT_LIMIT = 2000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/** The usable fields of [raw]. An entry with no valid, unused name is left out; an unknown type is a text field. */
export function readFormFields(raw: unknown): FormField[] {
  const seen = new Set<string>();
  const fields: FormField[] = [];
  for (const entry of readList(raw)) {
    if (!isRecord(entry)) continue;
    const name = typeof entry.name === "string" ? entry.name : "";
    if (!FIELD_NAME.test(name) || seen.has(name)) continue;
    seen.add(name);
    let pattern: RegExp | null = null;
    if (typeof entry.pattern === "string" && entry.pattern !== "" && entry.pattern.length <= 200) {
      try {
        pattern = new RegExp(`^(?:${entry.pattern})$`);
      } catch {
        pattern = null;
      }
    }
    const type = typeof entry.type === "string" && FIELD_TYPES.has(entry.type) ? (entry.type as FormFieldType) : "text";
    fields.push({
      name,
      label: typeof entry.label === "string" && entry.label !== "" ? entry.label : name,
      type,
      required: entry.required === true,
      min: finite(entry.min),
      max: finite(entry.max),
      minLength: finite(entry.minLength),
      maxLength: finite(entry.maxLength),
      pattern,
      options: type === "select" ? readChoices(entry.options) : [],
      initial: entry.default,
      help: typeof entry.help === "string" ? entry.help : "",
      message: typeof entry.message === "string" ? entry.message : "",
      placeholder: typeof entry.placeholder === "string" ? entry.placeholder : "",
    });
  }
  return fields;
}

export type FormDraft = Record<string, string | boolean>;

/** The draft a form starts from: each field's value from [values], else its default, else empty (false for a checkbox). */
export function initialDraft(fields: readonly FormField[], values: unknown): FormDraft {
  const given = isRecord(values) ? values : {};
  const draft: FormDraft = {};
  for (const field of fields) {
    const source = field.name in given ? given[field.name] : field.initial;
    if (field.type === "checkbox") draft[field.name] = source === true;
    else if (typeof source === "string") draft[field.name] = source;
    else if (typeof source === "number" && Number.isFinite(source)) draft[field.name] = String(source);
    else draft[field.name] = "";
  }
  return draft;
}

export interface FormResult {
  errors: Record<string, string>;
  values: Record<string, unknown>;
}

/**
 * Checks every field of [draft]. `errors` holds one reason per invalid field (a field's own `message` wins over the
 * built-in one) and `values` the typed values: a number field gives a number, a checkbox a boolean, and an optional
 * field left empty is left out of `values`.
 */
export function validateForm(fields: readonly FormField[], draft: FormDraft): FormResult {
  const errors: Record<string, string> = {};
  const values: Record<string, unknown> = {};
  for (const field of fields) {
    const fail = (reason: string) => {
      errors[field.name] = field.message || reason;
    };
    const entry = draft[field.name];
    if (field.type === "checkbox") {
      const checked = entry === true;
      if (field.required && !checked) fail(`${field.label} must be checked`);
      values[field.name] = checked;
      continue;
    }
    const text = typeof entry === "string" ? entry : "";
    const empty = text.trim() === "";
    if (empty) {
      if (field.required) fail(`${field.label} is required`);
      continue;
    }
    if (field.type === "number") {
      const number = Number(text);
      if (!Number.isFinite(number)) fail(`${field.label} must be a number`);
      else if (field.min !== null && number < field.min) fail(`${field.label} must be at least ${field.min}`);
      else if (field.max !== null && number > field.max) fail(`${field.label} must be at most ${field.max}`);
      else values[field.name] = number;
      continue;
    }
    if (field.type === "select" && !field.options.some((option) => String(option.value) === text)) {
      fail(`${field.label} must be one of the choices`);
      continue;
    }
    if (field.type === "email" && !EMAIL.test(text.trim())) {
      fail(`${field.label} must be an email address`);
      continue;
    }
    if (field.minLength !== null && text.length < field.minLength) {
      fail(`${field.label} must be at least ${field.minLength} characters`);
      continue;
    }
    if (field.maxLength !== null && text.length > field.maxLength) {
      fail(`${field.label} must be at most ${field.maxLength} characters`);
      continue;
    }
    if (field.pattern && (text.length > PATTERN_INPUT_LIMIT || !field.pattern.test(text))) {
      fail(`${field.label} is not in the expected format`);
      continue;
    }
    values[field.name] = field.type === "select" ? (field.options.find((option) => String(option.value) === text)?.value ?? text) : text;
  }
  return { errors, values };
}

export function Form({ nodeId, props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Form");
  const enabled = readBoolean(props, "enabled", true);
  const submitText = readString(props, "submitText", "Submit");
  const resetText = readString(props, "resetText", "Reset");
  const showReset = readBoolean(props, "showReset", false);
  const count = typeof props.submitCount === "number" && Number.isFinite(props.submitCount) ? Math.max(0, Math.trunc(props.submitCount)) : 0;
  const fields = readFormFields(props.fields);
  const fieldsKey = JSON.stringify(readList(props.fields));
  const valuesKey = JSON.stringify(isRecord(props.values) ? props.values : {});
  const [draft, setDraft] = useState<FormDraft>(() => initialDraft(fields, props.values));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const root = useRef<HTMLFormElement>(null);
  const submit = useRef<HTMLButtonElement>(null);
  const prefix = useId();

  // A new field list or new values from a binding replace what the operator has typed.
  useEffect(() => {
    setDraft(initialDraft(fields, props.values));
    setErrors({});
  }, [fieldsKey, valuesKey]);

  const change = (name: string, value: string | boolean) => {
    setDraft((current) => ({ ...current, [name]: value }));
    setErrors((current) => {
      if (!(name in current)) return current;
      const next = { ...current };
      delete next[name];
      return next;
    });
  };

  const onSubmitClick = (event: MouseEvent<HTMLButtonElement>) => {
    if (!enabled) {
      event.stopPropagation();
      return;
    }
    const result = validateForm(fields, draft);
    setErrors(result.errors);
    const firstBad = fields.find((field) => result.errors[field.name]);
    if (firstBad) {
      // Nothing is submitted, and the click stops here so the node's click actions do not run.
      event.stopPropagation();
      root.current?.querySelector<HTMLElement>(`[data-field="${firstBad.name}"]`)?.focus();
      return;
    }
    onChange("values", result.values);
    onChange("submitCount", count + 1);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLFormElement>) => {
    const target = event.target as HTMLElement;
    if (event.key !== "Enter" || target.tagName === "TEXTAREA" || target.tagName === "BUTTON") return;
    event.preventDefault();
    submit.current?.click();
  };

  return (
    <form
      ref={root}
      className="oip-form"
      aria-label={label}
      noValidate
      onSubmit={(event: FormEvent) => event.preventDefault()}
      onKeyDown={onKeyDown}
    >
      {listError(props.fields) ? (
        <div className="oip-unknown" role="alert">
          Fields {listError(props.fields)}
        </div>
      ) : null}
      {fields.map((field) => {
        const id = `${prefix}-${nodeId}-${field.name}`;
        const error = errors[field.name];
        const describedBy = [error ? `${id}-error` : "", field.help ? `${id}-help` : ""].filter(Boolean).join(" ") || undefined;
        const common = {
          id,
          "data-field": field.name,
          disabled: !enabled,
          "aria-required": field.required || undefined,
          "aria-invalid": error ? true : undefined,
          "aria-describedby": describedBy,
        };
        const text = typeof draft[field.name] === "string" ? (draft[field.name] as string) : "";
        return (
          <div key={field.name} className="oip-form-field" data-type={field.type} data-invalid={error ? "true" : undefined}>
            {field.type === "checkbox" ? (
              <label className="oip-form-check" htmlFor={id}>
                <input {...common} type="checkbox" checked={draft[field.name] === true} onChange={(event) => change(field.name, event.target.checked)} />
                <span>{field.label}</span>
              </label>
            ) : (
              <>
                <label htmlFor={id}>
                  {field.label}
                  {field.required ? <span aria-hidden="true"> *</span> : null}
                </label>
                {field.type === "textarea" ? (
                  <textarea {...common} rows={3} value={text} placeholder={field.placeholder || undefined} onChange={(event) => change(field.name, event.target.value)} />
                ) : field.type === "select" ? (
                  <select {...common} value={text} onChange={(event) => change(field.name, event.target.value)}>
                    <option value="" />
                    {field.options.map((option) => (
                      <option key={String(option.value)} value={String(option.value)}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    {...common}
                    type={field.type === "number" ? "number" : field.type}
                    step={field.type === "number" ? "any" : undefined}
                    value={text}
                    placeholder={field.placeholder || undefined}
                    onChange={(event) => change(field.name, event.target.value)}
                  />
                )}
              </>
            )}
            {field.help ? (
              <small id={`${id}-help`} className="oip-form-help">
                {field.help}
              </small>
            ) : null}
            {error ? (
              <span id={`${id}-error`} className="oip-form-error" role="alert">
                {error}
              </span>
            ) : null}
          </div>
        );
      })}
      <div className="oip-form-actions">
        <button ref={submit} type="button" className="oip-button" disabled={!enabled} onClick={onSubmitClick}>
          {submitText}
        </button>
        {showReset ? (
          <button
            type="button"
            className="oip-button oip-form-reset"
            disabled={!enabled}
            onClick={(event) => {
              // Reset is local: it does not reach the node's click actions.
              event.stopPropagation();
              setDraft(initialDraft(fields, props.values));
              setErrors({});
            }}
          >
            {resetText}
          </button>
        ) : null}
      </div>
    </form>
  );
}
