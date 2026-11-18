import { createContext, useContext, useEffect, useMemo, useState } from "react";
import {
  emptyValue,
  fieldLabel,
  isSensitive,
  schemaItem,
  schemaType,
  validateAgainstSchema,
  type JsonSchema,
} from "./schema";

interface DraftApi {
  set: (path: string, message: string | null) => void;
}

const DraftApiContext = createContext<DraftApi | null>(null);

export interface SchemaFormProps {
  schema: JsonSchema;
  value: unknown;
  errors: Record<string, string>;
  onChange: (value: unknown) => void;
  onDrafts?: (drafts: Record<string, string>) => void;
}

export function SchemaForm({ schema, value, errors, onChange, onDrafts }: SchemaFormProps) {
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const api = useMemo<DraftApi>(
    () => ({
      set(path, message) {
        setDrafts((prev) => {
          if (message == null) {
            if (!(path in prev)) return prev;
            const next = { ...prev };
            delete next[path];
            return next;
          }
          if (prev[path] === message) return prev;
          return { ...prev, [path]: message };
        });
      },
    }),
    [],
  );
  useEffect(() => {
    onDrafts?.(drafts);
  }, [drafts, onDrafts]);

  return (
    <DraftApiContext.Provider value={api}>
      <div className="schema-form" data-testid="schema-form">
        <SchemaNode schema={schema} value={value} path="" errors={errors} onChange={onChange} />
      </div>
    </DraftApiContext.Provider>
  );
}

function SchemaNode({
  schema,
  value,
  path,
  errors,
  onChange,
}: {
  schema: JsonSchema;
  value: unknown;
  path: string;
  errors: Record<string, string>;
  onChange: (value: unknown) => void;
}) {
  const type = schemaType(schema);
  if (schema.properties || type === "object") {
    const record = value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
    const entries = Object.entries(schema.properties ?? {});
    const hidden = entries.some(([key, child]) => isSensitive(key, child));
    return (
      <fieldset className="schema-object">
        {schema.title ? <legend>{schema.title}</legend> : null}
        {schema.description ? <p className="schema-help">{schema.description}</p> : null}
        {hidden ? <p className="schema-secret">Stored credentials are kept with the resource and are not shown.</p> : null}
        {entries.map(([key, child]) => {
          if (isSensitive(key, child)) return null;
          const childPath = path ? `${path}.${key}` : key;
          return (
            <SchemaField
              key={key}
              name={key}
              path={childPath}
              schema={child}
              value={record[key] === undefined ? child.default : record[key]}
              errors={errors}
              onChange={(next) => onChange({ ...record, [key]: next })}
            />
          );
        })}
      </fieldset>
    );
  }
  return <SchemaField name={schema.title ?? "value"} path={path} schema={schema} value={value} errors={errors} onChange={onChange} />;
}

function SchemaField({
  name,
  path,
  schema,
  value,
  errors,
  onChange,
}: {
  name: string;
  path: string;
  schema: JsonSchema;
  value: unknown;
  errors: Record<string, string>;
  onChange: (value: unknown) => void;
}) {
  const label = fieldLabel(name, schema);
  const error = errors[path];
  if (isSensitive(name, schema)) {
    return <p className="schema-secret">A stored credential is kept with the resource and is not shown.</p>;
  }
  if (schema.enum) {
    return (
      <label className="schema-field">
        <span>{label}</span>
        <select
          aria-label={label}
          aria-invalid={error ? true : undefined}
          value={value === undefined || value === null ? "" : String(value)}
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
        <FieldHelp schema={schema} error={error} />
      </label>
    );
  }
  const type = schemaType(schema);
  if (type === "object" || schema.properties) {
    return <SchemaNode schema={{ ...schema, title: label }} value={value} path={path} errors={errors} onChange={onChange} />;
  }
  if (type === "array") {
    const itemSchema = schemaItem(schema);
    if (!itemSchema) {
      return <p role="alert">This list uses a schema shape the editor does not expand. The value is left unchanged.</p>;
    }
    const items = Array.isArray(value) ? value : [];
    return (
      <fieldset className="schema-array">
        <legend>{label}</legend>
        {schema.description ? <p className="schema-help">{schema.description}</p> : null}
        {items.map((item, index) => {
          const childPath = `${path}.${index}`;
          return (
            <div className="schema-item" key={childPath}>
              <SchemaField
                name={`${label} ${index + 1}`}
                path={childPath}
                schema={{ ...itemSchema, title: itemSchema.title ?? `${label} ${index + 1}` }}
                value={item}
                errors={errors}
                onChange={(next) => {
                  const copy = items.slice();
                  copy[index] = next;
                  onChange(copy);
                }}
              />
              <button
                type="button"
                onClick={() => onChange(items.filter((_, itemIndex) => itemIndex !== index))}
              >
                Remove {label} {index + 1}
              </button>
            </div>
          );
        })}
        <button type="button" onClick={() => onChange([...items, emptyValue(itemSchema)])}>
          Add {label}
        </button>
        {error ? <small role="alert">{error}</small> : null}
      </fieldset>
    );
  }
  if (type === "boolean") {
    return (
      <label className="schema-check">
        <input
          type="checkbox"
          aria-label={label}
          checked={value === true}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span>{label}</span>
        <FieldHelp schema={schema} error={error} />
      </label>
    );
  }
  if (type === "number" || type === "integer") {
    return <NumberField label={label} path={path} schema={schema} value={value} error={error} onChange={onChange} />;
  }
  if (type === "string" || type === undefined) {
    return (
      <label className="schema-field">
        <span>{label}</span>
        <input
          aria-label={label}
          aria-invalid={error ? true : undefined}
          type="text"
          value={typeof value === "string" ? value : value === undefined || value === null ? "" : String(value)}
          onChange={(event) => onChange(event.target.value)}
        />
        <FieldHelp schema={schema} error={error} />
      </label>
    );
  }
  return <p role="alert">This field uses a schema shape the editor does not expand. The value is left unchanged.</p>;
}

function NumberField({
  label,
  path,
  schema,
  value,
  error,
  onChange,
}: {
  label: string;
  path: string;
  schema: JsonSchema;
  value: unknown;
  error?: string;
  onChange: (value: unknown) => void;
}) {
  const drafts = useContext(DraftApiContext);
  const [text, setText] = useState(value === undefined || value === null ? "" : String(value));
  const [local, setLocal] = useState<string | null>(null);
  useEffect(() => {
    setText(value === undefined || value === null ? "" : String(value));
    setLocal(null);
    drafts?.set(path, null);
  }, [value, path, drafts]);
  useEffect(() => () => drafts?.set(path, null), [drafts, path]);
  const shown = local ?? error;
  return (
    <label className="schema-field">
      <span>{label}</span>
      <input
        aria-label={label}
        aria-invalid={shown ? true : undefined}
        type="number"
        step={schemaType(schema) === "integer" ? 1 : "any"}
        min={schema.minimum}
        max={schema.maximum}
        value={text}
        onChange={(event) => {
          const raw = event.target.value;
          setText(raw);
          if (raw.trim() === "") {
            setLocal("Must be a number");
            drafts?.set(path, "Must be a number");
            return;
          }
          const numeric = Number(raw);
          const message = validateAgainstSchema(schema, numeric, path)[0]?.message ?? null;
          setLocal(message);
          drafts?.set(path, message);
          if (!message) onChange(numeric);
        }}
      />
      <FieldHelp schema={schema} error={shown} />
    </label>
  );
}

function FieldHelp({ schema, error }: { schema: JsonSchema; error?: string | null }) {
  if (error) return <small role="alert">{error}</small>;
  if (schema.description) return <small className="schema-help">{schema.description}</small>;
  return null;
}
