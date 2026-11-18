import { useEffect, useState } from "react";
import { parseRange } from "@oip/hmi-runtime";
import type { FormatTransform, MapEntry, MapTransform, ScriptTransform, Transform, TransformStep } from "@oip/hmi-runtime";
import { literalText, parseLiteral, showValue } from "./bindingModel";
import { ScriptEditor } from "./ScriptEditor";

/**
 * Ordered transform chain under a binding: map, format, and script steps. Each step shows the value going in and
 * coming out, computed with hmi-runtime `transformSteps`, so the preview matches what a session applies.
 */

export const SCRIPT_NOT_CONNECTED = "Script runs on the gateway; not connected here.";

const KIND_LABEL: Record<Transform["kind"], string> = { map: "Map", format: "Format", expression: "Expression", script: "Script" };

export function newTransform(kind: Transform["kind"]): Transform {
  if (kind === "map") return { kind: "map", entries: [{ input: { type: "value", value: "" }, output: { type: "value", value: "" } }] };
  if (kind === "format") return { kind: "format", type: "numeric", mode: "pattern", pattern: "#,##0.00" };
  if (kind === "expression") return { kind: "expression", expression: "{value}" };
  return { kind: "script", hook: "", source: "return value" };
}

export function moveItem<T>(items: readonly T[], from: number, to: number): T[] {
  if (to < 0 || to >= items.length) return [...items];
  const next = [...items];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item!);
  return next;
}

export function TransformEditor({
  transforms,
  steps,
  inputValue,
  onChange,
}: {
  transforms: readonly Transform[];
  /** Live preview per step; empty when the binding has no value yet. */
  steps: readonly TransformStep[];
  /** The binding value before the first transform, when there is one. */
  inputValue?: { value: unknown } | null;
  onChange: (transforms: Transform[]) => void;
}) {
  const replace = (index: number, transform: Transform) => onChange(transforms.map((entry, at) => (at === index ? transform : entry)));
  return (
    <section className="oip-transforms" aria-label="Transforms">
      <h4>Transforms</h4>
      {transforms.length === 0 ? <p className="oip-binding-muted">No transforms. The bound value is used as is.</p> : null}
      <ol className="oip-transform-list">
        {transforms.map((transform, index) => {
          const step = steps[index];
          const label = `${KIND_LABEL[transform.kind]} transform ${index + 1}`;
          return (
            <li key={index} className="oip-transform" data-kind={transform.kind} aria-label={label}>
              <div className="oip-transform-head">
                <strong>
                  {index + 1}. {KIND_LABEL[transform.kind]}
                </strong>
                <span className="oip-transform-tools">
                  <button type="button" aria-label={`Move ${label} up`} disabled={index === 0} onClick={() => onChange(moveItem(transforms, index, index - 1))}>
                    ↑
                  </button>
                  <button
                    type="button"
                    aria-label={`Move ${label} down`}
                    disabled={index === transforms.length - 1}
                    onClick={() => onChange(moveItem(transforms, index, index + 1))}
                  >
                    ↓
                  </button>
                  <button type="button" aria-label={`Remove ${label}`} onClick={() => onChange(transforms.filter((_, at) => at !== index))}>
                    ×
                  </button>
                </span>
              </div>
              {transform.kind === "map" ? <MapFields transform={transform} onChange={(next) => replace(index, next)} /> : null}
              {transform.kind === "format" ? <FormatFields transform={transform} onChange={(next) => replace(index, next)} /> : null}
              {transform.kind === "expression" ? (
                <label>
                  Expression
                  <input
                    aria-label={`Expression for ${label}`}
                    value={transform.expression}
                    onChange={(event) => replace(index, { kind: "expression", expression: event.target.value })}
                  />
                </label>
              ) : null}
              {transform.kind === "script" ? <ScriptFields transform={transform} onChange={(next) => replace(index, next)} /> : null}
              <StepPreview step={step} hasInput={index === 0 ? Boolean(inputValue) : Boolean(steps[index - 1])} />
            </li>
          );
        })}
      </ol>
      <div className="oip-transform-add" role="group" aria-label="Add transform">
        {(["map", "format", "expression", "script"] as const).map((kind) => (
          <button key={kind} type="button" onClick={() => onChange([...transforms, newTransform(kind)])}>
            + {KIND_LABEL[kind]}
          </button>
        ))}
      </div>
    </section>
  );
}

function StepPreview({ step, hasInput }: { step: TransformStep | undefined; hasInput: boolean }) {
  if (!step) {
    return <p className="oip-step" data-status="none">{hasInput ? "Not evaluated" : "No value to preview"}</p>;
  }
  if (step.status === "not_connected") {
    return (
      <p className="oip-step" data-status="not_connected" role="status">
        <span>In {showValue(step.input)}</span> <span className="oip-step-note">{SCRIPT_NOT_CONNECTED}</span>
      </p>
    );
  }
  if (step.status === "skipped") return <p className="oip-step" data-status="skipped">Skipped: an earlier step did not finish</p>;
  if (step.status === "error") {
    return (
      <p className="oip-step" data-status="error" role="alert">
        <span>In {showValue(step.input)}</span> <span>Error: {step.error}</span>
      </p>
    );
  }
  return (
    <p className="oip-step" data-status="ok" aria-label="Step preview">
      <span>In {showValue(step.input)}</span> <span aria-hidden="true">→</span> <span>Out {showValue(step.output)}</span>
    </p>
  );
}

/** One-line literal input that keeps what is typed and reports the parsed value. */
export function LiteralInput({ label, value, onChange }: { label: string; value: unknown; onChange: (value: unknown) => void }) {
  const [text, setText] = useState(literalText(value));
  useEffect(() => {
    // Compare as JSON text: arrays and objects are new values on every parse.
    setText((current) => (JSON.stringify(parseLiteral(current)) === JSON.stringify(value) ? current : literalText(value)));
  }, [value]);
  return (
    <input
      aria-label={label}
      type="text"
      value={text}
      onChange={(event) => {
        setText(event.target.value);
        onChange(parseLiteral(event.target.value));
      }}
    />
  );
}

function rangeText(input: Extract<MapEntry["input"], { type: "range" }>): string {
  return `${input.minInclusive ? "[" : "("}${input.min ?? ""},${input.max ?? ""}${input.maxInclusive ? "]" : ")"}`;
}

function RangeInput({ label, input, onChange }: { label: string; input: Extract<MapEntry["input"], { type: "range" }>; onChange: (input: MapEntry["input"]) => void }) {
  const [text, setText] = useState(rangeText(input));
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setText((current) => {
      try {
        const parsed = parseRange(current);
        if (rangeText({ type: "range", ...parsed }) === rangeText(input)) return current;
      } catch {
        // keep showing the model value below
      }
      return rangeText(input);
    });
  }, [input]);
  return (
    <span className="oip-range">
      <input
        aria-label={label}
        aria-invalid={error ? true : undefined}
        type="text"
        value={text}
        placeholder="(0,25]"
        onChange={(event) => {
          setText(event.target.value);
          try {
            onChange({ type: "range", ...parseRange(event.target.value) });
            setError(null);
          } catch (failure) {
            setError(failure instanceof Error ? failure.message : String(failure));
          }
        }}
      />
      {error ? <small role="alert">{error}</small> : null}
    </span>
  );
}

function MapFields({ transform, onChange }: { transform: MapTransform; onChange: (transform: MapTransform) => void }) {
  const setEntry = (index: number, entry: MapEntry) => onChange({ ...transform, entries: transform.entries.map((current, at) => (at === index ? entry : current)) });
  return (
    <div className="oip-map">
      {transform.entries.map((entry, index) => {
        const row = `Row ${index + 1}`;
        return (
          <div key={index} className="oip-map-row" role="group" aria-label={row}>
            <select
              aria-label={`${row} input type`}
              value={entry.input.type}
              onChange={(event) => {
                const type = event.target.value;
                const input: MapEntry["input"] =
                  type === "range"
                    ? { type: "range", min: 0, max: 100, minInclusive: true, maxInclusive: false }
                    : type === "expression"
                      ? { type: "expression", expression: "{value} > 0" }
                      : { type: "value", value: "" };
                setEntry(index, { ...entry, input });
              }}
            >
              <option value="value">Value</option>
              <option value="range">Range</option>
              <option value="expression">Expression</option>
            </select>
            {entry.input.type === "value" ? (
              <LiteralInput label={`${row} input`} value={entry.input.value} onChange={(value) => setEntry(index, { ...entry, input: { type: "value", value } })} />
            ) : entry.input.type === "range" ? (
              <RangeInput label={`${row} range`} input={entry.input} onChange={(input) => setEntry(index, { ...entry, input })} />
            ) : (
              <input
                aria-label={`${row} input expression`}
                type="text"
                value={entry.input.expression}
                onChange={(event) => setEntry(index, { ...entry, input: { type: "expression", expression: event.target.value } })}
              />
            )}
            <span aria-hidden="true">→</span>
            <select
              aria-label={`${row} output type`}
              value={entry.output.type}
              onChange={(event) =>
                setEntry(index, {
                  ...entry,
                  output: event.target.value === "expression" ? { type: "expression", expression: "{value}" } : { type: "value", value: "" },
                })
              }
            >
              <option value="value">Value</option>
              <option value="expression">Expression</option>
            </select>
            {entry.output.type === "value" ? (
              <LiteralInput label={`${row} output`} value={entry.output.value} onChange={(value) => setEntry(index, { ...entry, output: { type: "value", value } })} />
            ) : (
              <input
                aria-label={`${row} output expression`}
                type="text"
                value={entry.output.expression}
                onChange={(event) => setEntry(index, { ...entry, output: { type: "expression", expression: event.target.value } })}
              />
            )}
            <button type="button" aria-label={`Remove ${row.toLowerCase()}`} onClick={() => onChange({ ...transform, entries: transform.entries.filter((_, at) => at !== index) })}>
              ×
            </button>
          </div>
        );
      })}
      <div className="oip-map-foot">
        <button
          type="button"
          onClick={() => onChange({ ...transform, entries: [...transform.entries, { input: { type: "value", value: "" }, output: { type: "value", value: "" } }] })}
        >
          + Row
        </button>
        <label className="oip-schema-check">
          <input
            type="checkbox"
            checked={transform.hasFallback === true}
            onChange={(event) => {
              const next: MapTransform = { ...transform };
              if (event.target.checked) {
                next.hasFallback = true;
                next.fallback = transform.fallback ?? null;
              } else {
                delete next.hasFallback;
                delete next.fallback;
              }
              onChange(next);
            }}
          />
          <span>Fallback</span>
        </label>
        {transform.hasFallback ? <LiteralInput label="Fallback value" value={transform.fallback} onChange={(fallback) => onChange({ ...transform, fallback })} /> : null}
      </div>
    </div>
  );
}

const NUMERIC_MODES: Array<FormatTransform["mode"]> = ["pattern", "integer", "number", "percent", "currency"];
const DATE_MODES: Array<FormatTransform["mode"]> = ["pattern", "date", "time", "datetime"];

function FormatFields({ transform, onChange }: { transform: FormatTransform; onChange: (transform: FormatTransform) => void }) {
  const modes = transform.type === "datetime" ? DATE_MODES : NUMERIC_MODES;
  return (
    <div className="oip-binding-grid">
      <label>
        <span>Format type</span>
        <select
          aria-label="Format type"
          value={transform.type}
          onChange={(event) =>
            onChange(
              event.target.value === "datetime"
                ? { kind: "format", type: "datetime", mode: "pattern", pattern: "yyyy-MM-dd HH:mm:ss" }
                : { kind: "format", type: "numeric", mode: "pattern", pattern: "#,##0.00" },
            )
          }
        >
          <option value="numeric">Numeric</option>
          <option value="datetime">Date-time</option>
        </select>
      </label>
      <label>
        <span>Format</span>
        <select aria-label="Format mode" value={transform.mode} onChange={(event) => onChange({ ...transform, mode: event.target.value as FormatTransform["mode"] })}>
          {modes.map((mode) => (
            <option key={mode} value={mode}>
              {mode}
            </option>
          ))}
        </select>
      </label>
      {transform.mode === "pattern" ? (
        <label className="oip-binding-wide">
          <span>Pattern</span>
          <input aria-label="Pattern" type="text" value={transform.pattern ?? ""} onChange={(event) => onChange({ ...transform, pattern: event.target.value })} />
        </label>
      ) : null}
      {transform.mode === "currency" ? (
        <label>
          <span>Currency</span>
          <input aria-label="Currency" type="text" value={transform.currency ?? "USD"} onChange={(event) => onChange({ ...transform, currency: event.target.value })} />
        </label>
      ) : null}
      {transform.type === "datetime" && transform.mode !== "pattern" ? (
        <label>
          <span>Style</span>
          <select aria-label="Style" value={transform.style ?? "medium"} onChange={(event) => onChange({ ...transform, style: event.target.value as FormatTransform["style"] })}>
            {(["full", "long", "medium", "short"] as const).map((style) => (
              <option key={style} value={style}>
                {style}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {transform.type === "datetime" ? (
        <label>
          <span>Time zone</span>
          <input
            aria-label="Time zone"
            type="text"
            placeholder="auto"
            value={transform.timeZone ?? ""}
            onChange={(event) => {
              const next = { ...transform };
              if (event.target.value) next.timeZone = event.target.value;
              else delete next.timeZone;
              onChange(next);
            }}
          />
        </label>
      ) : null}
    </div>
  );
}

function ScriptFields({ transform, onChange }: { transform: ScriptTransform; onChange: (transform: ScriptTransform) => void }) {
  return (
    <div className="oip-binding-grid">
      <label className="oip-binding-wide">
        <span>Hook name</span>
        <input aria-label="Hook name" type="text" value={transform.hook} placeholder="scaleLevel" onChange={(event) => onChange({ ...transform, hook: event.target.value })} />
      </label>
      <div className="oip-binding-wide">
        <ScriptEditor
          label="Script source"
          rows={4}
          hint="Body of transform(value, context)."
          value={transform.source ?? ""}
          onChange={(text) => {
            const next = { ...transform };
            if (text) next.source = text;
            else delete next.source;
            onChange(next);
          }}
        />
      </div>
    </div>
  );
}
