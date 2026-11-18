import { useEffect, useId, useState } from "react";
import {
  cssTextToStyle,
  findNode,
  STYLE_PROPERTIES,
  styleToCssText,
  styleValueProblem,
  type StyleKey,
  type StyleProps,
  type StylePropertySpec,
  type ViewDocument,
} from "@oip/hmi-runtime";

/**
 * The Studio's style editor (HMP-093, P4-07): a node's own style properties, from the CSS subset in
 * `hmi-runtime/src/styleSubset.ts`. Each property set on the node has a field of its type (color text, pixels, a number
 * or a keyword list) and can be removed; "Add" offers the properties not set yet. The CSS box shows the style as
 * declaration text and takes pasted text: text with any declaration outside the subset is refused as a whole and the
 * reasons are listed, so a refused paste changes nothing. The style is saved with the view.
 *
 * Doc source: FEATURES HMP-093.
 */
export function StyleEditor({ style, onChange }: { style: StyleProps | undefined; onChange: (style: StyleProps) => void }) {
  const current = style ?? {};
  const set = STYLE_PROPERTIES.filter((spec) => current[spec.key] !== undefined);
  const unset = STYLE_PROPERTIES.filter((spec) => current[spec.key] === undefined);
  const [adding, setAdding] = useState<StyleKey | "">("");
  const cssText = styleToCssText(current);
  const [draft, setDraft] = useState(cssText);
  const [errors, setErrors] = useState<string[]>([]);
  const cssId = useId();

  // The box follows edits made in the fields; a refused paste stays in the box until it is fixed or the style changes.
  useEffect(() => {
    setDraft(cssText);
    setErrors([]);
  }, [cssText]);

  function put(key: StyleKey, value: unknown) {
    onChange({ ...current, [key]: value } as StyleProps);
  }

  function remove(key: StyleKey) {
    const next: Record<string, unknown> = { ...current };
    delete next[key];
    onChange(next as StyleProps);
  }

  function add() {
    if (!adding) return;
    const spec = STYLE_PROPERTIES.find((entry) => entry.key === adding);
    if (!spec) return;
    put(spec.key, starter(spec));
    setAdding("");
  }

  function applyCss() {
    const result = cssTextToStyle(draft);
    if (result.errors.length > 0) {
      setErrors(result.errors);
      return;
    }
    setErrors([]);
    onChange(result.style);
  }

  return (
    <fieldset className="oip-schema oip-style-editor" aria-label="Style" data-testid="style-editor">
      <legend>Style</legend>
      {set.length === 0 ? <p className="oip-binding-muted">No style properties set; the theme and style classes apply.</p> : null}
      {set.map((spec) => (
        <StyleField key={spec.key} spec={spec} value={current[spec.key]} onChange={(value) => put(spec.key, value)} onRemove={() => remove(spec.key)} />
      ))}
      <div className="oip-style-add">
        <select aria-label="Style property to add" value={adding} onChange={(event) => setAdding(event.target.value as StyleKey | "")}>
          <option value="">Add a style property…</option>
          {unset.map((spec) => (
            <option key={spec.key} value={spec.key}>
              {spec.title} ({spec.css})
            </option>
          ))}
        </select>
        <button type="button" onClick={add} disabled={!adding}>
          Add
        </button>
      </div>
      <label htmlFor={cssId} className="oip-style-css-label">
        Style CSS
      </label>
      <textarea
        id={cssId}
        className="oip-style-css"
        rows={3}
        spellCheck={false}
        aria-invalid={errors.length > 0 ? true : undefined}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
      />
      <button type="button" onClick={applyCss} disabled={draft === cssText && errors.length === 0}>
        Apply CSS
      </button>
      {errors.length > 0 ? (
        <div role="alert" className="oip-style-errors">
          <p>Refused; nothing was changed:</p>
          <ul>
            {errors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </fieldset>
  );
}

function starter(spec: StylePropertySpec): unknown {
  const kind = spec.value;
  if (kind.type === "color") return spec.key === "background" ? "var(--oip-color-surface)" : "#333333";
  if (kind.type === "enum") return kind.values[0];
  if (kind.type === "font") return "sans-serif";
  if (kind.type === "number") return spec.key === "fontWeight" ? 400 : kind.max <= 1 ? kind.max : 1;
  return spec.key === "fontSize" ? 14 : spec.key === "borderWidth" ? 1 : 0;
}

function StyleField({
  spec,
  value,
  onChange,
  onRemove,
}: {
  spec: StylePropertySpec;
  value: unknown;
  onChange: (value: unknown) => void;
  onRemove: () => void;
}) {
  const [text, setText] = useState(value === undefined ? "" : String(value));
  const [problem, setProblem] = useState<string | null>(null);
  useEffect(() => {
    setText(value === undefined ? "" : String(value));
    setProblem(null);
  }, [value]);
  const kind = spec.value;
  const label = `${spec.title}${kind.type === "length" ? " (px)" : ""}`;

  function commit(next: string) {
    setText(next);
    const parsed: unknown = kind.type === "length" || kind.type === "number" ? (next.trim() === "" ? NaN : Number(next)) : next.trim();
    const reason = styleValueProblem(spec, parsed);
    setProblem(reason);
    if (!reason) onChange(parsed);
  }

  return (
    <div className="oip-style-field" data-style-key={spec.key}>
      <label>
        <span>{label}</span>
        {kind.type === "enum" ? (
          <select value={text} onChange={(event) => commit(event.target.value)}>
            {kind.values.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        ) : (
          <input
            type={kind.type === "length" || kind.type === "number" ? "number" : "text"}
            step={kind.type === "number" && kind.max <= 10 ? "0.1" : undefined}
            value={text}
            aria-invalid={problem ? true : undefined}
            onChange={(event) => commit(event.target.value)}
          />
        )}
      </label>
      <button type="button" aria-label={`Remove ${spec.title}`} title={`Remove ${spec.css}`} onClick={onRemove}>
        ×
      </button>
      {problem ? (
        <p role="alert" className="oip-style-field-error">
          {problem}
        </p>
      ) : null}
    </div>
  );
}

/** The view with node [id]'s own style replaced; an empty style is removed. */
export function setNodeStyle(view: ViewDocument, id: string, style: StyleProps): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node) throw new Error(`node ${id} not found`);
  if (Object.keys(style).length > 0) node.style = style;
  else delete node.style;
  return next;
}
