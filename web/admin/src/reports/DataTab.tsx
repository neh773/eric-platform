import type { DbConnection, NamedQueryInfo } from "../api";
import { CheckField, NumberField, Panel, SelectField, TextField } from "./controls";
import {
  PARAMETER_TYPES,
  asList,
  bindingTargets,
  bindingToTarget,
  defaultToText,
  isObj,
  newDataSource,
  parametersOf,
  sqlPlaceholders,
  targetToBinding,
  text,
  textToDefault,
  withParameters,
  type Json,
  type Obj,
  type ParameterDraft,
  type ParameterType,
  type ReportSpec,
} from "./reportModel";

/**
 * Data tab: parameters (type, default or expression) and the ordered data sources a report runs.
 * doc source: FEATURES REP-058, REP-034, REP-031.
 */
export function DataTab({ spec, setSpec, queries, connections }: {
  spec: ReportSpec;
  setSpec: (change: (spec: ReportSpec) => ReportSpec) => void;
  queries: NamedQueryInfo[];
  connections: DbConnection[];
}) {
  const parameters = parametersOf(spec);
  const sources = asList(spec.dataSources);

  const setParameter = (index: number, patch: Partial<ParameterDraft>) =>
    setSpec((s) => withParameters(s, parametersOf(s).map((p, i) => (i === index ? { ...p, ...patch } : p))));
  const setSource = (index: number, change: (source: Obj) => Obj) =>
    setSpec((s) => ({ ...s, dataSources: asList(s.dataSources).map((d, i) => (i === index ? change(d) : d)) }));

  return (
    <div className="report-tab" role="tabpanel" aria-label="Data">
      <Panel title="Parameters">
        {parameters.length === 0 ? <p className="hint">No parameters. A parameter lets a run, a schedule or the preview choose a value.</p> : null}
        {parameters.map((p, index) => (
          <ParameterRow
            key={index}
            index={index}
            parameter={p}
            onChange={(patch) => setParameter(index, patch)}
            onRemove={() => setSpec((s) => withParameters(s, parametersOf(s).filter((_, i) => i !== index)))}
          />
        ))}
        <button
          type="button"
          onClick={() =>
            setSpec((s) => {
              const taken = parametersOf(s).map((x) => x.name);
              let name = "param1";
              for (let i = 2; taken.includes(name); i++) name = `param${i}`;
              return withParameters(s, [...parametersOf(s), { name, type: "string" }]);
            })
          }
        >
          Add parameter
        </button>
      </Panel>
      <Panel title="Data sources">
        {sources.map((source, index) => (
          <SourceRow
            key={index}
            index={index}
            source={source}
            parameters={parameters}
            queries={queries}
            connections={connections}
            onChange={(change) => setSource(index, change)}
            onRemove={() => setSpec((s) => ({ ...s, dataSources: asList(s.dataSources).filter((_, i) => i !== index) }))}
            canRemove={sources.length > 1}
          />
        ))}
        <div className="inline-form">
          {(
            [
              ["Add named query", "namedQuery"],
              ["Add SQL query", "sql"],
              ["Add tag history", "tagHistory"],
              ["Add alarm journal", "alarmJournal"],
            ] as const
          ).map(([label, type]) => (
            <button
              key={type}
              type="button"
              onClick={() =>
                setSpec((s) => ({
                  ...s,
                  dataSources: [
                    ...asList(s.dataSources),
                    newDataSource(type, asList(s.dataSources).map((d) => text(d.name)), {
                      query: queries[0]?.name,
                      connection: connections[0]?.name,
                      range: parametersOf(s).find((x) => x.type === "timeRange")?.name,
                    }),
                  ],
                }))
              }
            >
              {label}
            </button>
          ))}
        </div>
      </Panel>
    </div>
  );
}

function ParameterRow({ index, parameter, onChange, onRemove }: {
  index: number;
  parameter: ParameterDraft;
  onChange: (patch: Partial<ParameterDraft>) => void;
  onRemove: () => void;
}) {
  const n = index + 1;
  const mode = parameter.expression !== undefined ? "expression" : "default";
  return (
    <div className="report-row" role="group" aria-label={`Parameter ${n}`}>
      <TextField label={`Parameter ${n} name`} value={parameter.name} onChange={(v) => onChange({ name: v })} />
      <SelectField
        label={`Parameter ${n} type`}
        value={parameter.type}
        options={PARAMETER_TYPES}
        onChange={(v) => onChange({ type: v as ParameterType, default: undefined })}
      />
      <TextField label={`Parameter ${n} label`} value={parameter.label ?? ""} onChange={(v) => onChange({ label: v || undefined })} />
      <SelectField
        label={`Parameter ${n} value from`}
        value={mode}
        options={[{ value: "default", label: "Default value" }, { value: "expression", label: "Expression" }]}
        onChange={(v) => onChange(v === "expression" ? { expression: "now - 24h", default: undefined } : { expression: undefined })}
      />
      {mode === "expression" ? (
        <TextField label={`Parameter ${n} expression`} value={parameter.expression ?? ""} onChange={(v) => onChange({ expression: v })} />
      ) : (
        <TextField
          label={`Parameter ${n} default`}
          value={defaultToText(parameter.type, parameter.default)}
          placeholder={parameter.type === "timeRange" ? "24h" : parameter.type === "timestamp" ? "now" : ""}
          onChange={(v) => onChange({ default: textToDefault(parameter.type, v) })}
        />
      )}
      <CheckField label={`Parameter ${n} required`} checked={parameter.required === true} onChange={(v) => onChange({ required: v })} />
      <button type="button" onClick={onRemove}>
        Remove parameter {n}
      </button>
    </div>
  );
}

const PRIORITIES = ["diagnostic", "low", "medium", "high", "critical"] as const;

const AGGREGATES = ["Average", "SimpleAverage", "Sum", "Minimum", "Maximum", "Range", "LastValue", "Count"] as const;

function SourceRow({ index, source, parameters, queries, connections, onChange, onRemove, canRemove }: {
  index: number;
  source: Obj;
  parameters: ParameterDraft[];
  queries: NamedQueryInfo[];
  connections: DbConnection[];
  onChange: (change: (source: Obj) => Obj) => void;
  onRemove: () => void;
  canRemove: boolean;
}) {
  const n = index + 1;
  const type = text(source.type);
  const set = (key: string, value: Json | undefined) =>
    onChange((s) => {
      const next = { ...s };
      if (value === undefined || value === "") delete next[key];
      else next[key] = value;
      return next;
    });
  const targets = bindingTargets(parameters);
  const bindings = isObj(source.parameters) ? (source.parameters as Obj) : {};
  const bindingNames =
    type === "namedQuery"
      ? (queries.find((q) => q.name === text(source.query))?.parameters ?? []).filter((p) => p.mode !== "out").map((p) => p.name)
      : type === "sql"
        ? sqlPlaceholders(text(source.sql))
        : [];
  const setBinding = (key: string, target: string, literal: string) =>
    onChange((s) => ({ ...s, parameters: { ...(isObj(s.parameters) ? s.parameters : {}), [key]: targetToBinding(target, literal) } }));

  return (
    <div className="report-row" role="group" aria-label={`Data source ${n}`}>
      <TextField label={`Data source ${n} name`} value={text(source.name)} onChange={(v) => set("name", v)} />
      <p className="hint">{type === "namedQuery" ? "Named query" : type === "sql" ? "SQL query" : type === "alarmJournal" ? "Alarm journal" : "Tag history"}</p>
      {type === "namedQuery" ? (
        <SelectField
          label={`Data source ${n} query`}
          value={text(source.query)}
          options={[...new Set([...queries.map((q) => q.name), ...(text(source.query) ? [text(source.query)] : [])])]}
          blank="Choose a named query"
          onChange={(v) => set("query", v)}
        />
      ) : null}
      {type === "sql" ? (
        <>
          <SelectField
            label={`Data source ${n} connection`}
            value={text(source.connection)}
            options={[...new Set([...connections.map((c) => c.name), ...(text(source.connection) ? [text(source.connection)] : [])])]}
            blank="Choose a connection"
            onChange={(v) => set("connection", v)}
          />
          <label>
            {`Data source ${n} SQL`}
            <textarea aria-label={`Data source ${n} SQL`} rows={3} value={text(source.sql)} onChange={(event) => set("sql", event.target.value)} />
          </label>
        </>
      ) : null}
      {bindingNames.map((key) => {
        const current = bindings[key];
        const target = bindingToTarget(current);
        const literal = isObj(current) && "value" in current ? String(current.value ?? "") : "";
        return (
          <div className="inline-form" key={key}>
            <SelectField
              label={`Data source ${n} ${key} from`}
              value={target}
              options={targets}
              blank="Fixed value"
              onChange={(v) => setBinding(key, v, literal)}
            />
            {target === "" ? <TextField label={`Data source ${n} ${key} value`} value={literal} onChange={(v) => setBinding(key, "", v)} /> : null}
          </div>
        );
      })}
      {type === "tagHistory" ? (
        <>
          <label>
            {`Data source ${n} tags`}
            <textarea
              aria-label={`Data source ${n} tags`}
              rows={3}
              placeholder="One tag path per line"
              value={asList(source.tags).map((t) => text(t.path)).join("\n")}
              onChange={(event) => {
                const old = asList(source.tags);
                set("tags", event.target.value.split("\n").map((path, i) => ({ ...(old[i] ?? {}), path })) as Json);
              }}
            />
          </label>
          <SelectField label={`Data source ${n} mode`} value={text(source.mode, "raw")} options={["raw", "aggregate"]} onChange={(v) => set("mode", v)} />
          {source.mode === "aggregate" ? (
            <>
              <SelectField label={`Data source ${n} aggregate`} value={text(source.aggregate)} options={AGGREGATES} blank="Choose" onChange={(v) => set("aggregate", v)} />
              <NumberField label={`Data source ${n} interval (ms)`} value={typeof source.intervalMillis === "number" ? source.intervalMillis : undefined} min={1} onChange={(v) => set("intervalMillis", v)} />
            </>
          ) : null}
          <SelectField
            label={`Data source ${n} range`}
            value={isObj(source.range) && typeof source.range.param === "string" ? source.range.param : ""}
            options={parameters.filter((p) => p.type === "timeRange").map((p) => p.name)}
            blank="Last hour"
            onChange={(v) => set("range", v ? { param: v } : { value: { last: "1h" } })}
          />
        </>
      ) : null}
      {type === "alarmJournal" ? (
        <>
          <SelectField
            label={`Data source ${n} range`}
            value={isObj(source.range) && typeof source.range.param === "string" ? source.range.param : ""}
            options={parameters.filter((p) => p.type === "timeRange").map((p) => p.name)}
            blank="Last 24 hours"
            onChange={(v) => set("range", v ? { param: v } : { value: { last: "24h" } })}
          />
          <SelectField label={`Data source ${n} lowest priority`} value={text(source.minPriority)} options={PRIORITIES} blank="Any" onChange={(v) => set("minPriority", v)} />
          <SelectField label={`Data source ${n} highest priority`} value={text(source.maxPriority)} options={PRIORITIES} blank="Any" onChange={(v) => set("maxPriority", v)} />
          <TextField
            label={`Data source ${n} event types`}
            value={Array.isArray(source.eventTypes) ? source.eventTypes.map((t) => String(t)).join(", ") : ""}
            placeholder="active, clear, ack, shelve, unshelve"
            onChange={(v) => {
              const names = v.split(",").map((t) => t.trim()).filter((t) => t !== "");
              set("eventTypes", names.length > 0 ? names : undefined);
            }}
          />
          <TextField label={`Data source ${n} source filter`} value={text(source.source)} placeholder="Alarm source, * allowed" onChange={(v) => set("source", v)} />
          <SelectField label={`Data source ${n} order`} value={text(source.order, "desc")} options={[{ value: "desc", label: "Newest first" }, { value: "asc", label: "Oldest first" }]} onChange={(v) => set("order", v)} />
          <NumberField label={`Data source ${n} row limit`} value={typeof source.limit === "number" ? source.limit : undefined} min={1} max={1000} onChange={(v) => set("limit", v)} />
        </>
      ) : null}
      <button type="button" onClick={onRemove} disabled={!canRemove}>
        Remove data source {n}
      </button>
    </div>
  );
}
