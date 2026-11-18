import { useRef, useState } from "react";
import type { ReportCatalog, ReportCatalogComponent, ReportCatalogProperty } from "../api";
import { CheckField, NumberField, Panel, SelectField, TextField } from "./controls";
import {
  asList,
  availableDataKeys,
  componentsOf,
  insertDataKey,
  isObj,
  setPath,
  text,
  unknownDataKeys,
  withComponents,
  type Json,
  type Obj,
  type ReportSpec,
  type ResultLike,
} from "./reportModel";

/**
 * Design tab: the component palette (built from the gateway's component catalog), the page outline and canvas, and an
 * inspector for the selected component. Text blocks offer the data keys the report provides; tables edit their columns.
 * doc source: FEATURES REP-058, REP-013, REP-020, REP-051.
 */
export function DesignTab({ spec, setSpec, catalog, lastResult }: {
  spec: ReportSpec;
  setSpec: (change: (spec: ReportSpec) => ReportSpec) => void;
  catalog: ReportCatalog | null;
  lastResult: ResultLike | null;
}) {
  const [selected, setSelected] = useState(0);
  const components = componentsOf(spec);
  const current = components[selected];
  const entry = catalog?.components.find((c) => c.type === current?.type);
  const datasets = asList(spec.dataSources).map((d) => text(d.name));

  function add(item: ReportCatalogComponent) {
    const fresh: Obj = { ...(item.defaults as Obj) };
    if (item.needsDataset && datasets[0]) fresh.dataset = datasets[0];
    setSpec((s) => withComponents(s, (list) => [...list, fresh]));
    setSelected(components.length);
  }

  function update(change: (component: Obj) => Obj) {
    setSpec((s) => withComponents(s, (list) => list.map((c, i) => (i === selected ? change(c) : c))));
  }

  function move(delta: number) {
    const target = selected + delta;
    if (target < 0 || target >= components.length) return;
    setSpec((s) =>
      withComponents(s, (list) => {
        const next = list.slice();
        const [item] = next.splice(selected, 1);
        next.splice(target, 0, item!);
        return next;
      }),
    );
    setSelected(target);
  }

  function remove() {
    setSpec((s) => withComponents(s, (list) => list.filter((_, i) => i !== selected)));
    setSelected(0);
  }

  return (
    <div className="report-tab" role="tabpanel" aria-label="Design">
      <div className="inline-form" role="toolbar" aria-label="Components">
        {(catalog?.components ?? []).map((item) => (
          <button key={item.type} type="button" title={item.description} onClick={() => add(item)}>
            Add {item.label.toLowerCase()}
          </button>
        ))}
      </div>
      <div className="report-canvas-body">
        <ol aria-label="Outline">
          {components.map((item, index) => (
            <li key={index}>
              <button type="button" aria-current={index === selected ? "true" : undefined} onClick={() => setSelected(index)}>
                {text(item.type)}: {text(item.title)}
              </button>
            </li>
          ))}
        </ol>
        <section aria-label="Page" data-testid="report-page" className="report-page">
          {components.map((item, index) => (
            <div key={index} data-component={text(item.type)} data-selected={index === selected ? "true" : undefined}>
              <strong>{text(item.title)}</strong>
              {item.type === "text" ? <p>{text(item.text)}</p> : <p>{text(item.type)} {text(item.dataset)}</p>}
            </div>
          ))}
        </section>
        {current && entry ? (
          <form aria-label="Component" className="report-inspector" onSubmit={(event) => event.preventDefault()}>
            {entry.properties.map((property) => (
              <PropertyEditor
                key={`${selected}-${property.name}`}
                property={property}
                component={current}
                datasets={datasets}
                spec={spec}
                lastResult={lastResult}
                onChange={(value) => update((c) => setPath(c, property.name, value))}
              />
            ))}
            <div className="inline-form">
              <button type="button" onClick={() => move(-1)} disabled={selected === 0}>Move up</button>
              <button type="button" onClick={() => move(1)} disabled={selected >= components.length - 1}>Move down</button>
              <button type="button" onClick={remove}>Remove</button>
            </div>
          </form>
        ) : null}
      </div>
    </div>
  );
}

function PropertyEditor({ property, component, datasets, spec, lastResult, onChange }: {
  property: ReportCatalogProperty;
  component: Obj;
  datasets: string[];
  spec: ReportSpec;
  lastResult: ResultLike | null;
  onChange: (value: Json | undefined) => void;
}) {
  const value = component[property.name];
  const knownColumns = columnsOf(lastResult, text(component.dataset));
  switch (property.kind) {
    case "string":
      return <TextField label={property.label} value={text(value)} onChange={(v) => onChange(v === "" ? undefined : v)} />;
    case "text":
      return <TextBlockField label={property.label} value={text(value)} spec={spec} lastResult={lastResult} onChange={(v) => onChange(v)} />;
    case "dataset":
      return <SelectField label={property.label} value={text(value)} options={datasets} blank="Choose a data source" onChange={(v) => onChange(v === "" ? undefined : v)} />;
    case "column":
      return (
        <>
          <TextField label={property.label} value={text(value)} list={`cols-${property.name}`} onChange={(v) => onChange(v)} />
          <datalist id={`cols-${property.name}`}>{knownColumns.map((c) => <option key={c} value={c} />)}</datalist>
        </>
      );
    case "columns":
      return (
        <TextField
          label={property.label}
          value={Array.isArray(value) ? value.map(String).join(", ") : ""}
          placeholder="Comma separated"
          onChange={(v) => onChange(v.split(",").map((x) => x.trim()).filter(Boolean))}
        />
      );
    case "number":
      return <NumberField label={property.label} value={typeof value === "number" ? value : undefined} onChange={(v) => onChange(v)} />;
    case "boolean":
      return <CheckField label={property.label} checked={typeof value === "boolean" ? value : property.default === true} onChange={(v) => onChange(v)} />;
    case "enum":
      return <SelectField label={property.label} value={text(value, text(property.default))} options={property.options ?? []} onChange={(v) => onChange(v)} />;
    case "yAxis":
      return <YAxisEditor value={isObj(value) ? value : {}} onChange={(v) => onChange(v)} />;
    case "tableColumns":
      return <ColumnsEditor value={normalizeColumns(value)} known={knownColumns} onChange={(v) => onChange(v)} />;
    default:
      return null;
  }
}

function columnsOf(result: ResultLike | null, dataset: string): string[] {
  return (result?.datasets ?? []).find((d) => d.name === dataset)?.columns?.map((c) => c.name) ?? [];
}

/** A text block with the data keys the report provides, inserted where the caret is. */
function TextBlockField({ label, value, spec, lastResult, onChange }: {
  label: string;
  value: string;
  spec: ReportSpec;
  lastResult: ResultLike | null;
  onChange: (value: string) => void;
}) {
  const area = useRef<HTMLTextAreaElement>(null);
  const [key, setKey] = useState("");
  const keys = availableDataKeys(spec, lastResult);
  const unknown = unknownDataKeys(value, keys);

  function insert() {
    if (!key) return;
    const el = area.current;
    const next = insertDataKey(value, key, el?.selectionStart ?? null, el?.selectionEnd ?? null);
    onChange(next.value);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(next.caret, next.caret);
    });
  }

  return (
    <>
      <label>
        {label}
        <textarea ref={area} aria-label={label} rows={4} value={value} onChange={(event) => onChange(event.target.value)} />
      </label>
      <div className="inline-form">
        <label>
          Data key
          <select aria-label="Data key" value={key} onChange={(event) => setKey(event.target.value)}>
            <option value="">Choose a data key</option>
            {(["built-in", "parameter", "data"] as const).map((group) => {
              const items = keys.filter((k) => k.group === group);
              return items.length === 0 ? null : (
                <optgroup key={group} label={group === "data" ? "Data (from the last preview)" : group === "parameter" ? "Parameters" : "Built-in"}>
                  {items.map((k) => <option key={k.key} value={k.key}>{k.key}</option>)}
                </optgroup>
              );
            })}
          </select>
        </label>
        <button type="button" onClick={insert} disabled={!key}>Insert data key</button>
      </div>
      {unknown.length > 0 ? <p className="hint" aria-live="polite">Not a known data key: {unknown.map((k) => `@${k}@`).join(", ")}. It prints as written.</p> : null}
    </>
  );
}

function YAxisEditor({ value, onChange }: { value: Obj; onChange: (value: Json | undefined) => void }) {
  const set = (key: string, v: Json | undefined) => onChange(setPath(value, key, v === "" ? undefined : v));
  return (
    <Panel title="Y axis">
      <div className="inline-form">
        <NumberField label="Y axis minimum" value={typeof value.min === "number" ? value.min : undefined} onChange={(v) => set("min", v)} />
        <NumberField label="Y axis maximum" value={typeof value.max === "number" ? value.max : undefined} onChange={(v) => set("max", v)} />
        <TextField label="Y axis label" value={text(value.label)} onChange={(v) => set("label", v)} />
        <NumberField label="Y axis decimals" value={typeof value.decimals === "number" ? value.decimals : undefined} min={0} max={10} onChange={(v) => set("decimals", v)} />
      </div>
    </Panel>
  );
}

/** Table columns as objects, whether the layout wrote them as a bare field name or a full column. */
export function normalizeColumns(value: unknown): Obj[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((c): Obj[] => (typeof c === "string" ? [{ field: c }] : isObj(c) ? [c] : []));
}

const FORMATS = ["auto", "text", "number", "timestamp", "boolean"] as const;

function ColumnsEditor({ value, known, onChange }: { value: Obj[]; known: string[]; onChange: (value: Json) => void }) {
  const setColumn = (index: number, path: string, v: Json | undefined) =>
    onChange(value.map((c, i) => (i === index ? setPath(c, path, v === "" && path !== "field" ? undefined : v) : c)));
  return (
    <Panel title="Columns">
      <p className="hint">{value.length === 0 ? "Every column of the data source, in order." : `${value.length} column${value.length === 1 ? "" : "s"}.`}</p>
      <datalist id="table-columns">{known.map((c) => <option key={c} value={c} />)}</datalist>
      {value.map((column, index) => {
        const n = index + 1;
        const format = isObj(column.format) ? column.format : {};
        return (
          <div className="report-row" role="group" aria-label={`Column ${n}`} key={index}>
            <TextField label={`Column ${n} field`} value={text(column.field)} list="table-columns" onChange={(v) => setColumn(index, "field", v)} />
            <TextField label={`Column ${n} label`} value={text(column.label)} onChange={(v) => setColumn(index, "label", v)} />
            <TextField label={`Column ${n} unit`} value={text(column.unit)} onChange={(v) => setColumn(index, "unit", v)} />
            <SelectField label={`Column ${n} align`} value={text(column.align)} options={["left", "center", "right"]} blank="Automatic" onChange={(v) => setColumn(index, "align", v)} />
            <NumberField label={`Column ${n} width`} value={typeof column.width === "number" ? column.width : undefined} min={0.1} step={0.1} onChange={(v) => setColumn(index, "width", v)} />
            <SelectField label={`Column ${n} format`} value={text(format.type, "auto")} options={FORMATS} onChange={(v) => setColumn(index, "format.type", v === "auto" ? undefined : v)} />
            <NumberField label={`Column ${n} decimals`} value={typeof format.decimals === "number" ? format.decimals : undefined} min={0} max={10} onChange={(v) => setColumn(index, "format.decimals", v)} />
            <CheckField label={`Column ${n} digit grouping`} checked={format.grouping === true} onChange={(v) => setColumn(index, "format.grouping", v ? true : undefined)} />
            <TextField label={`Column ${n} time pattern`} value={text(format.pattern)} onChange={(v) => setColumn(index, "format.pattern", v)} />
            <button type="button" onClick={() => onChange(value.filter((_, i) => i !== index))}>Remove column {n}</button>
          </div>
        );
      })}
      <div className="inline-form">
        <button type="button" onClick={() => onChange([...value, { field: known[value.length] ?? "" }])}>Add column</button>
        {known.length > 0 ? <button type="button" onClick={() => onChange(known.map((field) => ({ field })))}>Use every column</button> : null}
        {value.length > 0 ? <button type="button" onClick={() => onChange([])}>Show all columns</button> : null}
      </div>
    </Panel>
  );
}
