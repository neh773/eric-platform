import { useMemo, useState } from "react";
import type { ComponentNode, ViewDocument } from "@oip/hmi-runtime";
import { importSvg, type SvgElementInfo } from "@oip/components";
import { SVG_PARTS, bindSvgPart, boundParam, paramNameProblem, setSvgPart, suggestParamName, unbindSvgPart, type SvgPart } from "./svgModel";

/**
 * The parts of an imported SVG component (HMV-151): every group, shape and text with its fill, stroke, visibility or
 * text, each of which can be bound to a view parameter. Setting a value here writes `fills.<id>` (and so on) on the
 * component; binding writes a property binding on that path, so the part follows the parameter.
 */
export function SvgPartsEditor({ view, node, onView }: { view: ViewDocument; node: ComponentNode; onView: (view: ViewDocument) => void }) {
  const markup = typeof node.props.svg === "string" ? node.props.svg : "";
  const parsed = useMemo(() => importSvg(document, markup), [markup]);
  const [binding, setBinding] = useState<{ part: SvgPart; id: string; name: string } | null>(null);
  if (!parsed.ok) {
    return (
      <section className="oip-svg-parts" aria-label="SVG parts">
        <h3>SVG parts</h3>
        <p className="oip-binding-error" role="alert">
          {parsed.reason}
        </p>
      </section>
    );
  }
  const read = (part: SvgPart, id: string): unknown => (node.props[part] as Record<string, unknown> | undefined)?.[id];

  return (
    <section className="oip-svg-parts" aria-label="SVG parts" data-testid="svg-parts">
      <h3>SVG parts</h3>
      {parsed.elements.length === 0 ? <p className="oip-binding-muted">This SVG has no groups, shapes or texts.</p> : null}
      <ul className="oip-svg-part-list">
        {parsed.elements.map((element) => (
          <li key={element.id} data-svg-part={element.id} style={{ paddingLeft: element.depth * 12 }}>
            <strong>{element.id}</strong> <span className="oip-binding-muted">{element.kind}</span>
            <div className="oip-binding-grid">
              {SVG_PARTS.filter(({ part }) => offered(element, part)).map(({ part, label }) => {
                const bound = boundParam(node, part, element.id);
                const value = read(part, element.id);
                return (
                  <div key={part} className="oip-svg-part" data-part={part}>
                    <span>{label}</span>
                    {bound ? (
                      <>
                        <code aria-label={`${label} of ${element.id} bound to`}>{`{${bound}}`}</code>
                        <button type="button" aria-label={`Unbind ${label} of ${element.id}`} onClick={() => onView(unbindSvgPart(view, node.id, part, element.id))}>
                          Unbind
                        </button>
                      </>
                    ) : (
                      <>
                        <PartInput
                          part={part}
                          element={element}
                          value={value}
                          label={`${label} of ${element.id}`}
                          onChange={(next) => onView(setSvgPart(view, node.id, part, element.id, next))}
                        />
                        <button
                          type="button"
                          aria-label={`Bind ${label} of ${element.id} to a parameter`}
                          onClick={() => setBinding({ part, id: element.id, name: suggestParamName(part, element.id) })}
                        >
                          Bind
                        </button>
                      </>
                    )}
                    {binding && binding.part === part && binding.id === element.id ? (
                      <div className="oip-svg-bind">
                        <input
                          aria-label={`Parameter name for ${label} of ${element.id}`}
                          type="text"
                          value={binding.name}
                          list={`oip-svg-params-${node.id}`}
                          onChange={(event) => setBinding({ ...binding, name: event.target.value })}
                        />
                        {paramNameProblem(binding.name) ? (
                          <span role="alert" className="oip-binding-error">
                            {paramNameProblem(binding.name)}
                          </span>
                        ) : null}
                        <button
                          type="button"
                          disabled={paramNameProblem(binding.name) !== null}
                          onClick={() => {
                            onView(bindSvgPart(view, node.id, part, element.id, binding.name, current(element, part, value)));
                            setBinding(null);
                          }}
                        >
                          Bind to parameter
                        </button>
                        <button type="button" onClick={() => setBinding(null)}>
                          Cancel
                        </button>
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          </li>
        ))}
      </ul>
      <datalist id={`oip-svg-params-${node.id}`}>
        {Object.keys(view.spec.params).map((name) => (
          <option key={name} value={name} />
        ))}
      </datalist>
    </section>
  );
}

function offered(element: SvgElementInfo, part: SvgPart): boolean {
  if (part === "texts") return element.kind === "text";
  if (part === "visibility") return true;
  return element.kind !== "text" || part === "fills";
}

function current(element: SvgElementInfo, part: SvgPart, value: unknown): string | boolean | undefined {
  if (typeof value === "string" || typeof value === "boolean") return value;
  if (part === "fills") return element.fill;
  if (part === "strokes") return element.stroke;
  if (part === "texts") return element.text;
  return true;
}

function PartInput({
  part,
  element,
  value,
  label,
  onChange,
}: {
  part: SvgPart;
  element: SvgElementInfo;
  value: unknown;
  label: string;
  onChange: (value: string | boolean | undefined) => void;
}) {
  if (part === "visibility") {
    return <input type="checkbox" aria-label={label} checked={value !== false && value !== "false"} onChange={(event) => onChange(event.target.checked ? undefined : false)} />;
  }
  const shown = typeof value === "string" ? value : (current(element, part, undefined) as string | undefined) ?? "";
  if (part === "texts") return <input type="text" aria-label={label} value={shown} onChange={(event) => onChange(event.target.value)} />;
  return (
    <input
      type="text"
      aria-label={label}
      value={shown}
      placeholder="#rrggbb"
      onChange={(event) => onChange(event.target.value === "" ? undefined : event.target.value)}
    />
  );
}
