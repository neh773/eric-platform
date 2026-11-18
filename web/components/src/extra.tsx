import { useEffect, useId, useMemo, useState } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { readBoolean, readNumber, readString } from "./read";

/** Table, dropdown, slider, checkbox, alarm status, and image.
 * The alarm table filters and acknowledges the rows it is given. It is not connected to an alarm service.
 */

interface Column {
  field: string;
  header: string;
  sortable: boolean;
  editable: boolean;
}

interface AlarmRow {
  id: string;
  displayPath: string;
  source: string;
  state: string;
  priority: string;
  activeTime: string;
}

export function Table({ props, onChange }: HmiComponentProps) {
  const columns = readColumns(props.columns);
  const incoming = readRecords(props.data);
  const serialized = JSON.stringify(incoming);
  const [rows, setRows] = useState(incoming);
  const [filter, setFilter] = useState(readString(props, "filter"));
  const [page, setPage] = useState(Math.max(0, Math.round(readNumber(props, "page", 0))));
  const [sort, setSort] = useState<{ field: string; direction: "asc" | "desc" } | null>(null);
  const [selection, setSelection] = useState<string>(readString(props, "selection"));
  const pageSize = Math.max(0, Math.round(readNumber(props, "pageSize", 10)));
  const rowKey = readString(props, "rowKey", "id");
  useEffect(() => setRows(readRecords(JSON.parse(serialized) as unknown)), [serialized]);

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    const matched = needle
      ? rows.filter((row) => columns.some((column) => String(row[column.field] ?? "").toLowerCase().includes(needle)))
      : rows;
    if (!sort) return matched;
    const direction = sort.direction === "asc" ? 1 : -1;
    return [...matched].sort((left, right) => compareCell(left[sort.field], right[sort.field]) * direction);
  }, [rows, columns, filter, sort]);
  const pageCount = pageSize > 0 ? Math.max(1, Math.ceil(filtered.length / pageSize)) : 1;
  const safePage = Math.min(page, pageCount - 1);
  const visible = pageSize > 0 ? filtered.slice(safePage * pageSize, safePage * pageSize + pageSize) : filtered;

  function commitRows(next: Record<string, unknown>[]) {
    setRows(next);
    onChange("data", next);
  }

  return (
    <div className="oip-table">
      <label>
        Filter
        <input
          aria-label="Filter table"
          value={filter}
          onChange={(event) => {
            setFilter(event.target.value);
            setPage(0);
            onChange("filter", event.target.value);
          }}
        />
      </label>
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.field} aria-sort={ariaSort(sort, column)} scope="col">
                {column.sortable ? (
                  <button
                    type="button"
                    onClick={() => {
                      const next = nextSort(sort, column.field);
                      setSort(next);
                      onChange("sortField", next?.field ?? "");
                      onChange("sortDirection", next?.direction ?? "");
                    }}
                  >
                    {column.header}
                  </button>
                ) : (
                  column.header
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visible.map((row, index) => {
            const key = String(row[rowKey] ?? safePage * Math.max(pageSize, 1) + index);
            return (
              <tr
                key={key}
                aria-selected={selection === key}
                onClick={() => {
                  setSelection(key);
                  onChange("selection", key);
                }}
              >
                {columns.map((column) => (
                  <td key={column.field}>
                    {column.editable ? (
                      <input
                        aria-label={`${column.header} ${key}`}
                        value={String(row[column.field] ?? "")}
                        onClick={(event) => event.stopPropagation()}
                        onChange={(event) => {
                          const next = rows.map((candidate) =>
                            String(candidate[rowKey] ?? "") === key ? { ...candidate, [column.field]: event.target.value } : candidate,
                          );
                          commitRows(next);
                        }}
                      />
                    ) : (
                      String(row[column.field] ?? "")
                    )}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
      {pageSize > 0 ? (
        <div className="oip-table-pager">
          <button
            type="button"
            onClick={() => {
              const next = Math.max(0, safePage - 1);
              setPage(next);
              onChange("page", next);
            }}
            disabled={safePage === 0}
          >
            Previous page
          </button>
          <span>{`Page ${safePage + 1} of ${pageCount}`}</span>
          <button
            type="button"
            onClick={() => {
              const next = Math.min(pageCount - 1, safePage + 1);
              setPage(next);
              onChange("page", next);
            }}
            disabled={safePage >= pageCount - 1}
          >
            Next page
          </button>
        </div>
      ) : null}
    </div>
  );
}

export function Dropdown({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Choose");
  const placeholder = readString(props, "placeholder", "");
  const enabled = readBoolean(props, "enabled", true);
  const multi = readBoolean(props, "multi", false);
  const options = readOptions(props.options);
  const listId = useId();
  if (multi) {
    const selected = new Set(Array.isArray(props.value) ? props.value.map(String) : []);
    return (
      <div className="oip-dropdown" role="group" aria-label={label}>
        <span id={listId}>{label}</span>
        <div role="listbox" aria-multiselectable="true" aria-labelledby={listId} aria-disabled={!enabled}>
          {options.map((option) => {
            const on = selected.has(option.value);
            return (
              <button
                key={option.value}
                type="button"
                role="option"
                aria-selected={on}
                disabled={!enabled}
                onClick={() => {
                  const next = new Set(selected);
                  if (on) next.delete(option.value);
                  else next.add(option.value);
                  onChange("value", [...next]);
                }}
              >
                {option.label}
              </button>
            );
          })}
        </div>
      </div>
    );
  }
  const value = props.value == null ? "" : String(props.value);
  return (
    <label className="oip-dropdown">
      {label}
      <select
        value={value}
        disabled={!enabled}
        onChange={(event) => onChange("value", event.target.value)}
      >
        {placeholder ? <option value="">{placeholder}</option> : null}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export function Slider({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Value");
  const min = readNumber(props, "min", 0);
  const max = readNumber(props, "max", 100);
  const step = readNumber(props, "step", 1);
  const value = readNumber(props, "value", min);
  const enabled = readBoolean(props, "enabled", true);
  return (
    <label className="oip-slider">
      {label}
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={!enabled}
        aria-valuemin={min}
        aria-valuemax={max}
        aria-valuenow={value}
        onChange={(event) => onChange("value", Number(event.target.value))}
      />
    </label>
  );
}

export function Checkbox({ props, onChange }: HmiComponentProps) {
  const label = readString(props, "label", "Check");
  const enabled = readBoolean(props, "enabled", true);
  return (
    <label className="oip-check">
      <input
        type="checkbox"
        checked={readBoolean(props, "value", false)}
        disabled={!enabled}
        onChange={(event) => onChange("value", event.target.checked)}
      />
      <span>{label}</span>
    </label>
  );
}

export function AlarmStatus({ props, onChange }: HmiComponentProps) {
  const incoming = readAlarms(props.rows);
  const serialized = JSON.stringify(incoming);
  const [rows, setRows] = useState(incoming);
  const [filter, setFilter] = useState(readString(props, "filter"));
  useEffect(() => setRows(readAlarms(JSON.parse(serialized) as unknown)), [serialized]);
  const needle = filter.trim().toLowerCase();
  const visible = needle
    ? rows.filter((row) => `${row.displayPath} ${row.source} ${row.state} ${row.priority}`.toLowerCase().includes(needle))
    : rows;

  function update(id: string, state: string) {
    const next = rows.map((row) => (row.id === id ? { ...row, state } : row));
    setRows(next);
    onChange("rows", next);
  }

  return (
    <div className="oip-alarm" data-source="unconnected">
      <label>
        Filter alarms
        <input
          aria-label="Filter alarms"
          value={filter}
          onChange={(event) => {
            setFilter(event.target.value);
            onChange("filter", event.target.value);
          }}
        />
      </label>
      <table>
        <thead>
          <tr>
            <th scope="col">Path</th>
            <th scope="col">Source</th>
            <th scope="col">State</th>
            <th scope="col">Priority</th>
            <th scope="col">Active</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((row) => (
            <tr key={row.id}>
              <td>{row.displayPath}</td>
              <td>{row.source}</td>
              <td>{row.state}</td>
              <td>{row.priority}</td>
              <td>{row.activeTime}</td>
              <td>
                <button type="button" onClick={() => update(row.id, "Acknowledged")}>{`Acknowledge ${row.displayPath}`}</button>
                <button type="button" onClick={() => update(row.id, "Shelved")}>{`Shelve ${row.displayPath}`}</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** http(s), site-relative, and `data:image/*` only. Other schemes are not requested. */
export function allowedImageSource(value: string): string | null {
  const source = value.trim();
  if (!source) return null;
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(source);
  if (!scheme) {
    if (source.startsWith("//") || source.startsWith("\\\\")) return null;
    return source;
  }
  const name = scheme[1]!.toLowerCase();
  if (name === "http" || name === "https") return source;
  if (name === "data" && /^data:image\/[a-z0-9.+-]+[;,]/i.test(source)) return source;
  return null;
}

export function Image({ props }: HmiComponentProps) {
  const source = allowedImageSource(readString(props, "source"));
  const alt = readString(props, "alt", "Image");
  const fit = readFit(props.fit);
  if (!source) {
    return <div className="oip-image" role="img" aria-label={alt} data-fit={fit} />;
  }
  return <img className="oip-image" src={source} alt={alt} data-fit={fit} />;
}

export function EmbeddedViewPlaceholder() {
  return null;
}

function readColumns(value: unknown): Column[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const record = entry as Record<string, unknown>;
    const field = typeof record.field === "string" ? record.field : "";
    if (!field) return [];
    return [{
      field,
      header: typeof record.header === "string" ? record.header : field,
      sortable: record.sortable !== false,
      editable: record.editable === true,
    }];
  });
}

function readRecords(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is Record<string, unknown> => entry !== null && typeof entry === "object" && !Array.isArray(entry));
}

function readOptions(value: unknown): { value: string; label: string }[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const record = entry as Record<string, unknown>;
    if (record.value == null) return [];
    const optionValue = String(record.value);
    return [{ value: optionValue, label: typeof record.label === "string" ? record.label : optionValue }];
  });
}

function readAlarms(value: unknown): AlarmRow[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const record = entry as Record<string, unknown>;
    const id = record.id == null ? "" : String(record.id);
    if (!id) return [];
    return [{
      id,
      displayPath: typeof record.displayPath === "string" ? record.displayPath : id,
      source: typeof record.source === "string" ? record.source : "",
      state: typeof record.state === "string" ? record.state : "Active",
      priority: typeof record.priority === "string" ? record.priority : "",
      activeTime: typeof record.activeTime === "string" ? record.activeTime : "",
    }];
  });
}

function readFit(value: unknown): "contain" | "cover" | "fill" | "none" {
  if (value === "cover" || value === "fill" || value === "none" || value === "contain") return value;
  return "contain";
}

function compareCell(left: unknown, right: unknown): number {
  if (typeof left === "number" && typeof right === "number") return left - right;
  return String(left ?? "").localeCompare(String(right ?? ""));
}

function ariaSort(sort: { field: string; direction: "asc" | "desc" } | null, column: Column): "none" | "ascending" | "descending" | undefined {
  if (!column.sortable) return undefined;
  if (!sort || sort.field !== column.field) return "none";
  return sort.direction === "asc" ? "ascending" : "descending";
}

function nextSort(sort: { field: string; direction: "asc" | "desc" } | null, field: string) {
  if (!sort || sort.field !== field) return { field, direction: "asc" as const };
  if (sort.direction === "asc") return { field, direction: "desc" as const };
  return null;
}
