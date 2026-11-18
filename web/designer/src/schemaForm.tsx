import { useEffect, useState } from "react";
import type { JsonSchema } from "@oip/hmi-runtime";

export function validateSchemaValue(schema: JsonSchema, value: unknown): string | null {
  if (schema.enum && !schema.enum.some((candidate) => candidate === value)) {
    return `Must be one of ${schema.enum.map(String).join(", ")}`;
  }
  switch (schema.type) {
    case "string":
      return typeof value === "string" ? null : "Must be text";
    case "boolean":
      return typeof value === "boolean" ? null : "Must be true or false";
    case "number":
    case "integer": {
      if (typeof value !== "number" || !Number.isFinite(value)) return "Must be a number";
      if (schema.type === "integer" && !Number.isInteger(value)) return "Must be an integer";
      if (schema.minimum !== undefined && value < schema.minimum) return `Minimum ${schema.minimum}`;
      if (schema.maximum !== undefined && value > schema.maximum) return `Maximum ${schema.maximum}`;
      return null;
    }
    case "object": {
      if (value === null || typeof value !== "object" || Array.isArray(value)) return "Must be an object";
      const record = value as Record<string, unknown>;
      for (const key of schema.required ?? []) {
        if (record[key] === undefined) return `${key} is required`;
      }
      for (const [key, child] of Object.entries(schema.properties ?? {})) {
        if (record[key] === undefined) continue;
        const error = validateSchemaValue(child, record[key]);
        if (error) return `${child.title ?? key}: ${error}`;
      }
      return null;
    }
    default:
      return "Unsupported schema";
  }
}

export interface SchemaFormProps {
  schema: JsonSchema;
  value: unknown;
  onChange: (value: unknown) => void;
}

export function SchemaForm({ schema, value, onChange }: SchemaFormProps) {
  if (schema.type === "object" && !schema.properties) {
    return <JsonObjectField label={schema.title ?? "value"} schema={schema} value={value} onChange={onChange} />;
  }
  if (schema.type === "object") {
    const record = (value && typeof value === "object" && !Array.isArray(value) ? value : {}) as Record<string, unknown>;
    return (
      <fieldset className="oip-schema">
        {schema.title ? <legend>{schema.title}</legend> : null}
        {schema.description ? <p className="oip-schema-help">{schema.description}</p> : null}
        {Object.entries(schema.properties ?? {}).map(([key, child]) => (
          <SchemaField
            key={key}
            name={key}
            schema={child}
            value={record[key] === undefined ? child.default : record[key]}
            onChange={(next) => onChange({ ...record, [key]: next })}
          />
        ))}
      </fieldset>
    );
  }
  return <SchemaField name={schema.title ?? "value"} schema={schema} value={value} onChange={onChange} />;
}

function SchemaField({ name, schema, value, onChange }: { name: string; schema: JsonSchema; value: unknown; onChange: (value: unknown) => void }) {
  const label = schema.title ?? name;
  if (schema.type === "object") {
    return <SchemaForm schema={{ ...schema, title: label }} value={value} onChange={onChange} />;
  }
  if (schema.type === "boolean") {
    return (
      <label className="oip-schema-check">
        <input
          type="checkbox"
          checked={value === true}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span>{label}</span>
      </label>
    );
  }
  if (schema.enum) {
    return (
      <label className="oip-schema-field">
        <span>{label}</span>
        <select
          aria-label={label}
          value={value === undefined ? "" : String(value)}
          onChange={(event) => {
            const next = schema.enum?.find((candidate) => String(candidate) === event.target.value);
            if (next !== undefined) onChange(next);
          }}
        >
          {schema.enum.map((candidate) => (
            <option key={String(candidate)} value={String(candidate)}>
              {String(candidate)}
            </option>
          ))}
        </select>
      </label>
    );
  }
  if (schema.type === "number" || schema.type === "integer") {
    return <NumberField label={label} schema={schema} value={value} onChange={onChange} />;
  }
  if (schema.type === "string") {
    return (
      <label className="oip-schema-field">
        <span>{label}</span>
        <input
          aria-label={label}
          type="text"
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.target.value)}
        />
        {schema.description ? <small>{schema.description}</small> : null}
      </label>
    );
  }
  return <p role="alert">Unsupported schema for {label}</p>;
}

/**
 * An object property with no fixed keys (a drawing's element overrides): edited as JSON text. The text is applied
 * when it parses to an object; otherwise the field says why and the property keeps its value.
 */
function JsonObjectField({ label, schema, value, onChange }: { label: string; schema: JsonSchema; value: unknown; onChange: (value: unknown) => void }) {
  const shown = JSON.stringify(value && typeof value === "object" && !Array.isArray(value) ? value : {}, null, 1);
  const [text, setText] = useState(shown);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setText(shown);
    setError(null);
  }, [shown]);
  return (
    <label className="oip-schema-field">
      <span>{label}</span>
      <textarea
        aria-label={label}
        aria-invalid={error ? true : undefined}
        rows={4}
        spellCheck={false}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onBlur={() => {
          let next: unknown;
          try {
            next = JSON.parse(text);
          } catch {
            setError("Must be JSON");
            return;
          }
          const invalid = validateSchemaValue(schema, next);
          if (invalid) {
            setError(invalid);
            return;
          }
          setError(null);
          if (JSON.stringify(next) !== JSON.stringify(value)) onChange(next);
        }}
      />
      {schema.description ? <small>{schema.description}</small> : null}
      {error ? <span role="alert">{error}</span> : null}
    </label>
  );
}

function NumberField({ label, schema, value, onChange }: { label: string; schema: JsonSchema; value: unknown; onChange: (value: unknown) => void }) {
  const [text, setText] = useState(value === undefined || value === null ? "" : String(value));
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setText(value === undefined || value === null ? "" : String(value));
    setError(null);
  }, [value]);
  return (
    <label className="oip-schema-field">
      <span>{label}</span>
      <input
        aria-label={label}
        aria-invalid={error ? true : undefined}
        type="number"
        step={schema.type === "integer" ? 1 : "any"}
        min={schema.minimum}
        max={schema.maximum}
        value={text}
        onChange={(event) => {
          const raw = event.target.value;
          setText(raw);
          if (raw.trim() === "") {
            setError("Must be a number");
            return;
          }
          const numeric = schema.type === "integer" ? Number.parseInt(raw, 10) : Number(raw);
          const message = validateSchemaValue(schema, numeric);
          setError(message);
          if (!message) onChange(numeric);
        }}
      />
      {error ? <small role="alert">{error}</small> : schema.description ? <small>{schema.description}</small> : null}
    </label>
  );
}
