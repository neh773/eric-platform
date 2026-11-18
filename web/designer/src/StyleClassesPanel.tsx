import { useState } from "react";
import { brandImageSource, isValidStyleClassName, STYLE_STATES, type CustomThemeSet, type ThemeBrand, type JsonSchema, type StyleClassSet, type StyleProps, type StyleState } from "@oip/hmi-runtime";
import { SchemaForm } from "./schemaForm";

/** Designer panel for the project's named style classes (M4.2): add, delete, and edit a class.
 *
 * Every edit replaces the project's class set at once and the live preview re-renders. Running /hmi
 * sessions pick the classes up from the gateway once the project is saved (gatewayStyleClasses.ts).
 */

const themeHint = "A color, or a theme variable such as var(--oip-color-accent)";

const styleSchema: JsonSchema = {
  type: "object",
  properties: {
    color: { type: "string", title: "Color", description: themeHint },
    background: { type: "string", title: "Background", description: themeHint },
    fontSize: { type: "number", title: "Font size", minimum: 1 },
    fontWeight: { type: "number", title: "Font weight", minimum: 100, maximum: 900 },
    padding: { type: "number", title: "Padding", minimum: 0 },
  },
};

/** Drops cleared text fields so an empty box means "not set by this class". */
function cleanStyle(value: unknown): StyleProps {
  const record = (value && typeof value === "object" ? value : {}) as Record<string, unknown>;
  const style: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(record)) {
    if (entry === undefined || (typeof entry === "string" && entry.trim() === "")) continue;
    style[key] = entry;
  }
  return style as StyleProps;
}

export interface StyleClassesPanelProps {
  styleClasses: StyleClassSet;
  onChange: (styleClasses: StyleClassSet) => void;
  themes?: CustomThemeSet;
  onThemes?: (themes: CustomThemeSet) => void;
}

export function StyleClassesPanel({ styleClasses, onChange, themes = {}, onThemes }: StyleClassesPanelProps) {
  const names = Object.keys(styleClasses).sort();
  const [selected, setSelected] = useState<string | null>(names[0] ?? null);
  const [draftName, setDraftName] = useState("");
  const [nameError, setNameError] = useState<string | null>(null);
  const active = selected && Object.hasOwn(styleClasses, selected) ? styleClasses[selected] : undefined;

  function addClass() {
    const name = draftName.trim();
    if (!isValidStyleClassName(name)) {
      setNameError("Start with a letter; use letters, digits, - or _");
      return;
    }
    if (Object.hasOwn(styleClasses, name)) {
      setNameError(`${name} already exists`);
      return;
    }
    onChange({ ...styleClasses, [name]: { style: {} } });
    setDraftName("");
    setNameError(null);
    setSelected(name);
  }

  function removeClass(name: string) {
    const next = { ...styleClasses };
    delete next[name];
    onChange(next);
    if (selected === name) setSelected(Object.keys(next).sort()[0] ?? null);
  }

  return (
    <section className="oip-style-classes" data-testid="style-classes-panel" aria-label="Style classes">
      <h2>Style classes</h2>
      <ul className="oip-style-class-list" role="listbox" aria-label="Style class list">
        {names.map((name) => (
          <li key={name} role="none">
            <button type="button" role="option" aria-selected={selected === name} onClick={() => setSelected(name)}>
              {name}
            </button>
            <button type="button" aria-label={`Delete ${name}`} onClick={() => removeClass(name)}>
              Delete
            </button>
          </li>
        ))}
      </ul>
      <form
        className="oip-style-class-add"
        onSubmit={(event) => {
          event.preventDefault();
          addClass();
        }}
      >
        <label>
          <span>New class name</span>
          <input
            aria-label="New class name"
            aria-invalid={nameError ? true : undefined}
            type="text"
            value={draftName}
            onChange={(event) => {
              setDraftName(event.target.value);
              setNameError(null);
            }}
          />
        </label>
        <button type="submit">Add class</button>
      </form>
      {nameError ? <p role="alert" className="oip-style-class-error">{nameError}</p> : null}
      {active && selected ? (
        <div className="oip-style-class-editor" data-editing-class={selected}>
          <SchemaForm
            key={selected}
            schema={{ ...styleSchema, title: selected }}
            value={active.style}
            onChange={(value) => onChange({ ...styleClasses, [selected]: { ...active, style: cleanStyle(value) } })}
          />
          {STYLE_STATES.map((state) => (
            <StateFields
              key={state}
              state={state}
              value={active.states?.[state]}
              onChange={(style) => {
                const states = { ...active.states, [state]: style };
                if (Object.keys(style).length === 0) delete states[state];
                onChange({
                  ...styleClasses,
                  [selected]: { ...active, states: Object.keys(states).length > 0 ? states : undefined },
                });
              }}
            />
          ))}
        </div>
      ) : (
        <p className="oip-inspector-empty">No style classes yet. Add one above.</p>
      )}
      {onThemes ? <ThemeFields themes={themes} onChange={onThemes} /> : null}
    </section>
  );
}

function StateFields({ state, value, onChange }: { state: StyleState; value: StyleProps | undefined; onChange: (style: StyleProps) => void }) {
  const title = state[0]!.toUpperCase() + state.slice(1);
  return (
    <details className="oip-style-state">
      <summary>{title}</summary>
      <SchemaForm schema={{ ...styleSchema, title }} value={value ?? {}} onChange={(next) => onChange(cleanStyle(next))} />
    </details>
  );
}

function ThemeFields({ themes, onChange }: { themes: CustomThemeSet; onChange: (themes: CustomThemeSet) => void }) {
  const [name, setName] = useState("");
  const names = Object.keys(themes).sort();
  return (
    <section className="oip-themes" aria-label="Themes">
      <h3>Themes</h3>
      <p className="oip-inspector-empty">A theme extends Light (daylight) or Dark (nightwatch) and overrides its colors.</p>
      {names.map((themeName) => {
        const theme = themes[themeName]!;
        return (
          <div key={themeName} className="oip-theme" data-theme-name={themeName}>
            <strong>{themeName}</strong>
            <label>
              Extends
              <select
                aria-label={`Base for ${themeName}`}
                value={theme.extends}
                onChange={(event) => onChange({ ...themes, [themeName]: { ...theme, extends: event.target.value === "nightwatch" ? "nightwatch" : "daylight" } })}
              >
                <option value="daylight">Light</option>
                <option value="nightwatch">Dark</option>
              </select>
            </label>
            {(
              [
                ["color-alarm", "Alarm color"],
                ["color-accent", "Accent color"],
                ["color-canvas", "Background color"],
              ] as const
            ).map(([token, label]) => (
              <label key={token}>
                {label}
                <input
                  aria-label={`${label} for ${themeName}`}
                  value={theme.tokens[token] ?? ""}
                  onChange={(event) => {
                    const tokens = { ...theme.tokens };
                    if (event.target.value.trim() === "") delete tokens[token];
                    else tokens[token] = event.target.value;
                    onChange({ ...themes, [themeName]: { ...theme, tokens } });
                  }}
                />
              </label>
            ))}
            <BrandFields
              themeName={themeName}
              brand={theme.brand}
              onChange={(brand) => {
                const { brand: _previous, ...rest } = theme;
                onChange({ ...themes, [themeName]: brand ? { ...rest, brand } : rest });
              }}
            />
            <button type="button" onClick={() => {
              const next = { ...themes };
              delete next[themeName];
              onChange(next);
            }}>
              Remove {themeName}
            </button>
          </div>
        );
      })}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          const next = name.trim();
          if (!isValidStyleClassName(next) || next === "daylight" || next === "nightwatch" || themes[next]) return;
          onChange({ ...themes, [next]: { extends: "daylight", tokens: {} } });
          setName("");
        }}
      >
        <label>
          New theme name
          <input aria-label="New theme name" value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <button type="submit">Add theme</button>
      </form>
    </section>
  );
}

const BRAND_FIELDS = [
  { key: "name", label: "Brand name" },
  { key: "logo", label: "Logo image" },
  { key: "logoAlt", label: "Logo description" },
  { key: "graphic", label: "Header graphic" },
] as const;

/** Brand of one theme (HMP-085). A logo or graphic that is not an allowed image source is refused with a reason. */
function BrandFields({ themeName, brand, onChange }: { themeName: string; brand: ThemeBrand | undefined; onChange: (brand: ThemeBrand | undefined) => void }) {
  const [problem, setProblem] = useState<string | null>(null);
  return (
    <fieldset className="oip-brand-fields">
      <legend>Brand</legend>
      {BRAND_FIELDS.map(({ key, label }) => (
        <label key={key}>
          {label}
          <input
            aria-label={`${label} for ${themeName}`}
            value={brand?.[key] ?? ""}
            onChange={(event) => {
              const value = event.target.value;
              if ((key === "logo" || key === "graphic") && value.trim() !== "" && !brandImageSource(value)) {
                setProblem(`${label} must be an http(s), site-relative or data:image source`);
                return;
              }
              setProblem(null);
              const next: ThemeBrand = { ...brand };
              if (value.trim() === "") delete next[key];
              else next[key] = value.trim();
              onChange(Object.keys(next).length > 0 ? next : undefined);
            }}
          />
        </label>
      ))}
      {problem ? <p role="alert">{problem}</p> : null}
    </fieldset>
  );
}
