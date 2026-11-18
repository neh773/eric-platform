import {
  classApplicationOrder,
  findNode,
  unknownClassNames,
  type StyleClassSet,
  type ViewDocument,
  type ViewNode,
} from "@oip/hmi-runtime";

/** Sets the style classes a component or container references. An empty list removes the field. */
export function setNodeStyleClasses(view: ViewDocument, id: string, classNames: readonly string[]): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node) throw new Error(`node ${id} not found`);
  if (classNames.length === 0) delete node.styleClasses;
  else node.styleClasses = [...classNames];
  return next;
}

/** Inspector section that picks the selected component's style classes.
 * Classes apply alphabetically and a later name wins, so there is no ordering control
 * (-).
 * A referenced name the project no longer defines is listed as missing and can be removed. */
export function StyleClassPicker({
  node,
  styleClasses,
  onChange,
}: {
  node: ViewNode;
  styleClasses: StyleClassSet;
  onChange: (names: string[]) => void;
}) {
  const names = Object.keys(styleClasses).sort();
  const current = node.styleClasses ?? [];
  const missing = unknownClassNames(current, styleClasses);
  const applied = classApplicationOrder(current).filter((name) => !missing.includes(name));

  function toggle(name: string) {
    onChange(current.includes(name) ? current.filter((entry) => entry !== name) : [...current, name]);
  }

  return (
    <fieldset className="oip-style-class-picker" data-testid="style-class-picker">
      <legend>Style classes</legend>
      {names.length === 0 ? <p className="oip-inspector-empty">No style classes defined yet.</p> : null}
      <ul>
        {names.map((name) => (
          <li key={name}>
            <label>
              <input
                type="checkbox"
                aria-label={`Use style class ${name}`}
                checked={current.includes(name)}
                onChange={() => toggle(name)}
              />
              <span>{name}</span>
            </label>
          </li>
        ))}
        {missing.map((name) => (
          <li key={`missing-${name}`} data-missing-class={name}>
            <span>{name} (missing)</span>
            <button type="button" aria-label={`Remove missing class ${name}`} onClick={() => toggle(name)}>
              Remove
            </button>
          </li>
        ))}
      </ul>
      {applied.length > 1 ? (
        <p className="oip-schema-help" data-testid="style-class-order">
          Applied in order {applied.join(", ")}; later names win.
        </p>
      ) : null}
    </fieldset>
  );
}
