import { mergeStyle, parseStyleObject, styleToCssText } from "./styleSubset";
import type { StyleClassDefinition, StyleClassSet, StyleProps, StyleState } from "./types";

export const STYLE_STATES: readonly StyleState[] = ["hover", "active", "focus", "disabled"];

/** Named style classes: resolution and precedence.
 *
 * Public behavior followed (-):
 * - A component references style classes by name; several can apply at once.
 * - "Multiple Style Classes are applied to a component in alphabetical order", and classes further
 *   along in the alphabet override earlier ones. Properties a later class does not set carry over.
 *   The order the names are listed on the component does not matter.
 * - "The inline style properties override any properties in the Style Class."
 * - A deleted or renamed class is no longer applied; the component keeps its inline style.
 *   A reference to a name that is not in the project is skipped, never an error.
 *
 * Themes sit underneath: the theme gives each component its default look, a class overrides that,
 * and inline style overrides the class. Theme-specific values come from theme variables, not from
 * per-theme copies of a class. A class value such as `var(--oip-color-alarm)` resolves against the
 * active session theme in the browser, so switching `session.theme` restyles it without re-resolving
 * (-
 * "When using a Theme Color in a Style Class, the variable must be wrapped in the var() method").
 */

/** Letters, digits, `_` and `-`, starting with a letter. Kept CSS-identifier safe. */
const CLASS_NAME = /^[A-Za-z][A-Za-z0-9_-]*$/;

export function isValidStyleClassName(name: string): boolean {
  return CLASS_NAME.test(name);
}

function mergeDefined(base: StyleProps, patch: StyleProps | undefined): StyleProps {
  return mergeStyle(base, patch);
}

/** The order classes are applied in: unique names, alphabetical (code-point order), last wins. */
export function classApplicationOrder(classNames: readonly string[] | undefined): string[] {
  if (!classNames) return [];
  return [...new Set(classNames)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}

/**
 * Effective style for a node.
 *
 * Precedence, lowest to highest:
 *   1. referenced classes found in `registry`, applied alphabetically (later names win per property);
 *   2. the node's own inline `style`, which wins over every class.
 * The theme's component defaults sit below both and are not part of this result.
 */
export function resolveStyle(
  classNames: readonly string[] | undefined,
  registry: StyleClassSet | undefined,
  instanceStyle: StyleProps | undefined,
): StyleProps {
  let result: StyleProps = {};
  if (registry) {
    for (const name of classApplicationOrder(classNames)) {
      const definition = Object.hasOwn(registry, name) ? registry[name] : undefined;
      if (!definition) continue;
      result = mergeDefined(result, definition.style);
    }
  }
  return mergeDefined(result, instanceStyle);
}

/** Names a node references that the registry does not define. The Designer flags these. */
export function unknownClassNames(classNames: readonly string[] | undefined, registry: StyleClassSet | undefined): string[] {
  if (!classNames) return [];
  return classNames.filter((name) => !registry || !Object.hasOwn(registry, name));
}

export class StyleClassError extends Error {}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input);
}

function parseStates(input: unknown, where: string): Partial<Record<StyleState, StyleProps>> {
  if (!isRecord(input)) throw new StyleClassError(`${where} must be an object`);
  const states: Partial<Record<StyleState, StyleProps>> = {};
  for (const [name, value] of Object.entries(input)) {
    if (!STYLE_STATES.includes(name as StyleState)) throw new StyleClassError(`${where}.${name} is not a style state`);
    states[name as StyleState] = parseStyleProps(value, `${where}.${name}`);
  }
  return states;
}

/** CSS for pointer and disabled states. Classes are emitted in alphabetical order so a later name wins. */
export function styleClassCss(registry: StyleClassSet | undefined): string {
  if (!registry) return "";
  const lines: string[] = [];
  for (const name of classApplicationOrder(Object.keys(registry))) {
    if (!isValidStyleClassName(name)) continue;
    const states = registry[name]?.states;
    if (!states) continue;
    for (const state of STYLE_STATES) {
      const text = styleToCssText(states[state]);
      if (!text) continue;
      const selector = state === "disabled"
        ? `.oip-sc-${name}:disabled, .oip-sc-${name}[data-disabled="true"]`
        : `.oip-sc-${name}:${state}`;
      lines.push(`${selector} { ${text} }`);
    }
  }
  return lines.join("\n");
}

/** Class attribute for a node that references [names]. Invalid names are left out. */
export function nodeStyleClassName(names: readonly string[] | undefined): string {
  const extra = (names ?? []).filter(isValidStyleClassName).map((name) => `oip-sc-${name}`);
  return ["oip-node", ...extra].join(" ");
}

function parseStyleProps(input: unknown, where: string): StyleProps {
  try {
    return parseStyleObject(input, where);
  } catch (error) {
    throw new StyleClassError(error instanceof Error ? error.message : String(error));
  }
}

/** Validates a project's style-class set, for example one received from the Designer. */
export function parseStyleClassSet(input: unknown): StyleClassSet {
  if (!isRecord(input)) throw new StyleClassError("styleClasses must be an object");
  const set: StyleClassSet = {};
  for (const [name, value] of Object.entries(input)) {
    if (!isValidStyleClassName(name)) throw new StyleClassError(`style class name "${name}" is not valid`);
    if (!isRecord(value)) throw new StyleClassError(`styleClasses.${name} must be an object`);
    const definition: StyleClassDefinition = { style: parseStyleProps(value.style, `styleClasses.${name}.style`) };
    if (value.states !== undefined) definition.states = parseStates(value.states, `styleClasses.${name}.states`);
    set[name] = definition;
  }
  return set;
}
