import type { ReactNode } from "react";

/** View document and component contracts for the web HMI.
 * Model follows SPEC §7: a view is a JSON resource with params, a root container,
 * and components that carry props, placement, style, and bindings.
 * Container behavior encoded from public docs:
 * - coordinate: -
 * - flex: -
 * - column: -
 * - breakpoint: -
 * - split: -
 */

export type LayoutKind = "coordinate" | "flex" | "column" | "tab" | "breakpoint" | "split";

export type CoordinateMode = "fixed" | "percent";

export type FlexDirection = "row" | "row-reverse" | "column" | "column-reverse";

export type FlexWrap = "nowrap" | "wrap" | "wrap-reverse";

export type FlexJustify =
  | "flex-start"
  | "flex-end"
  | "center"
  | "space-between"
  | "space-around"
  | "space-evenly";

export type FlexAlign = "flex-start" | "flex-end" | "center" | "baseline" | "stretch";

export type FlexAlignContent =
  | "flex-start"
  | "flex-end"
  | "center"
  | "space-between"
  | "space-around"
  | "stretch";

export type BreakpointName = "sm" | "md" | "lg";

export interface Frame {
  x: number;
  y: number;
  width: number;
  height: number;
  /** Clockwise degrees. Coordinate children only. */
  rotate?: number;
}

export interface FlexChild {
  grow: number;
  shrink: number;
  /** CSS flex-basis. Numbers are pixels; strings pass through ("auto", "50%"). */
  basis: string | number;
  /** When false the child is removed from the flex layout, not merely hidden. */
  display: boolean;
}

export interface ColumnPlacement {
  name: BreakpointName;
  span: number;
  rowIndex: number;
  colIndex: number;
  order: number;
  height?: number;
}

export interface Breakpoint {
  name: BreakpointName;
  minWidth: number;
}

export interface ContainerProps {
  mode?: CoordinateMode;
  /** "x:y" aspect ratio used in percent mode. Empty or invalid disables it. */
  aspectRatio?: string;
  direction?: FlexDirection;
  wrap?: FlexWrap;
  justify?: FlexJustify;
  alignItems?: FlexAlign;
  alignContent?: FlexAlignContent;
  breakpoints?: Breakpoint[];
  gutters?: { vertical: number; horizontal: number };
  width?: number;
  height?: number;
  /** Active tab index. Tab containers only. */
  currentTab?: number;
  /** Tab labels. When omitted, each child's name is used. */
  tabs?: { label: string }[];
  /** Breakpoint containers: the switch size in pixels. At or above it the large child shows. */
  breakpoint?: number;
  /** Breakpoint containers: which measured size is compared with `breakpoint`. Default width. */
  determinant?: "width" | "height";
  /** Split containers: horizontal puts the panes side by side, vertical stacks them. Default horizontal. */
  orientation?: "horizontal" | "vertical";
  /** Split containers: the divider. */
  split?: SplitBar;
}

/** Split divider. `position` is the first pane's size: pixels, or a "NN%" string of the space beside the bar.
 * `minFirst` and `minSecond` are our names for the pane minimums (the public page lists no minimum fields).
 */
export interface SplitBar {
  position: number | string;
  size: number;
  visible: boolean;
  draggable: boolean;
  minFirst: number;
  minSecond: number;
}

/** Which child of a breakpoint container a node is. */
export type BreakpointSize = "small" | "large";

/** A component's style: the CSS subset in styleSubset.ts (HMP-093). Lengths are pixels. */
export interface StyleProps {
  color?: string;
  background?: string;
  fontSize?: number;
  fontWeight?: number;
  fontStyle?: string;
  fontFamily?: string;
  textAlign?: string;
  textDecoration?: string;
  textTransform?: string;
  lineHeight?: number;
  letterSpacing?: number;
  padding?: number;
  margin?: number;
  borderWidth?: number;
  borderStyle?: string;
  borderColor?: string;
  borderRadius?: number;
  opacity?: number;
  overflow?: string;
  cursor?: string;
}

/** A named, reusable style class (project-scoped). Values may use theme variables such as
 * `var(--oip-color-accent)`. Resolution and precedence: styleClasses.ts.
 */
/** Pointer and disabled states of a style class. Applied with CSS after the base style. */
export type StyleState = "hover" | "active" | "focus" | "disabled";

export interface StyleClassDefinition {
  style: StyleProps;
  states?: Partial<Record<StyleState, StyleProps>>;
}

/** Project-level registry of named style classes, keyed by class name. */
export type StyleClassSet = Record<string, StyleClassDefinition>;

/** A project theme: one built-in theme plus token overrides. */
export interface CustomTheme {
  extends: "daylight" | "nightwatch";
  tokens: Record<string, string>;
  /** Brand of the theme (HMP-085): its colors are the tokens above; this adds the name, logo and graphic. */
  brand?: ThemeBrand;
}

/**
 * Session branding carried by a project theme (HMP-085). A session on the theme shows a brand bar above the page with
 * the logo, the name and a background graphic. Images are http(s), site-relative or `data:image/*` sources.
 * doc source: FEATURES HMP-085
 */
export interface ThemeBrand {
  name?: string;
  logo?: string;
  logoAlt?: string;
  graphic?: string;
}

export type CustomThemeSet = Record<string, CustomTheme>;

export type TagValue = string | number | boolean | null;

export type Quality = "good" | "uncertain" | "bad";

export type QualityCode =
  | "good"
  | "uncertain"
  | "uncertain_initial"
  | "bad"
  | "bad_not_connected";

/** Direct or indirect tag binding.
 * Tag and property bindings are the ones that can be bidirectional.
 */
export interface TagBinding {
  kind: "tag";
  mode: "direct" | "indirect";
  /** Direct path, or an indirect pattern with `{name}` placeholders. */
  path: string;
  /** Indirect placeholders. `param` reads a view param; `prop` reads another component prop; `custom` reads a view custom property. */
  references?: Record<string, BindingRef>;
  bidirectional?: boolean;
  /** Default true. A disabled binding does not subscribe or write. */
  enabled?: boolean;
  /** When true, this binding's quality does not put the quality overlay on the component (HMP-111). */
  overlayOptOut?: boolean;
  /** When true, an initial `uncertain_initial` value is ignored (public "Publish Initial Uncertain Value" option). */
  publishInitialUncertain?: boolean;
  transforms?: Transform[];
}

/** Property binding. Same-view only; another view is reached through a view parameter.
 */
export interface PropertyBinding {
  kind: "property";
  source: PropertySource;
  bidirectional?: boolean;
  enabled?: boolean;
  /** When true, this binding's quality does not put the quality overlay on the component (HMP-111). */
  overlayOptOut?: boolean;
  transforms?: Transform[];
}

export type PropertySource =
  | { scope: "prop"; nodeId: string; prop: string }
  | { scope: "param"; name: string }
  | { scope: "custom"; name: string }
  | { scope: "session"; path: string }
  | { scope: "page"; path: string };

/** Expression binding. Read-only: the public binding page allows bidirectional only on tag and property bindings.
 */
export interface ExpressionBinding {
  kind: "expression";
  expression: string;
  /**
   * Poll interval in milliseconds (HMP-118): the expression re-evaluates on this interval as well as when a value it
   * reads changes. Absent or 0 is no poll; otherwise at least {@link MIN_EXPRESSION_POLL_MS}.
   */
  pollMs?: number;
  enabled?: boolean;
  /** When true, this binding's quality does not put the quality overlay on the component (HMP-111). */
  overlayOptOut?: boolean;
  transforms?: Transform[];
}

/**
 * Expression structure binding (HMP-112): several named expressions evaluated into one object, so one binding fills a
 * structured property such as the bands of an analog indicator. Read-only. An expression that fails gives `null` for
 * its key; the others still evaluate. The object's quality is the worst quality among the tags any expression reads.
 * doc source: FEATURES HMP-112
 */
export interface ExpressionStructureBinding {
  kind: "expression-structure";
  /** Key to expression. Keys are plain names (letters, digits, `_`, `-`). Insertion order is the object's key order. */
  expressions: Record<string, string>;
  /** Poll interval in milliseconds, as for {@link ExpressionBinding}. */
  pollMs?: number;
  enabled?: boolean;
  overlayOptOut?: boolean;
  transforms?: Transform[];
}

/** Shortest expression binding poll interval (HMP-118). */
export const MIN_EXPRESSION_POLL_MS = 100;

/** Named-query binding. Execution is delegated to a NamedQuerySource; this runtime does not embed SQL.
 * Read-only. Return format follows the public options (auto, json, dataset, scalar).
 */
export interface QueryBinding {
  kind: "query";
  path: string;
  parameters?: Record<string, QueryParameter>;
  returnFormat?: QueryReturnFormat;
  /** Poll interval in milliseconds. Omitted or 0 runs when inputs change, not on a timer. */
  pollMs?: number;
  enabled?: boolean;
  /** When true, this binding's quality does not put the quality overlay on the component (HMP-111). */
  overlayOptOut?: boolean;
  transforms?: Transform[];
}

export type QueryReturnFormat = "auto" | "json" | "dataset" | "scalar";

export type QueryParameter = { value: unknown } | { expression: string };

/** Aggregation modes the history route accepts. */
export const HISTORY_AGGREGATES = ["Average", "SimpleAverage", "Sum", "Minimum", "Maximum", "Range", "LastValue", "Count"] as const;

export type HistoryAggregateName = (typeof HISTORY_AGGREGATES)[number];

/** Same ceiling as the history route: a wider range is refused when the view is parsed. */
export const MAX_HISTORY_WINDOWS = 10_000;

/** Tag history binding. Read-only. The value is the aggregate windows for `[now - rangeMs, now]`.
 */
export interface HistoryBinding {
  kind: "history";
  path: string;
  /** Milliseconds back from the moment of the read. */
  rangeMs: number;
  /** Aggregation window in milliseconds. */
  intervalMs: number;
  /** One of {@link HISTORY_AGGREGATES}. Absent means Average. */
  aggregate?: HistoryAggregateName;
  /** Poll interval in milliseconds. Omitted or 0 reads when the binding changes, not on a timer. */
  pollMs?: number;
  enabled?: boolean;
  /** When true, this binding's quality does not put the quality overlay on the component (HMP-111). */
  overlayOptOut?: boolean;
  transforms?: Transform[];
}

/**
 * HTTP binding (HMP-114, doc source: FEATURES HMP-114). The gateway GETs `path` (a single relative path, with `query`
 * parameters) from the project's stored HTTP source named `source` and returns the JSON; `jsonPath` (dotted names and
 * array indexes, `data.items.0.temp`) picks the part that is bound. The browser never sees the upstream address or any
 * credential. A failed request, a non-JSON answer or a missing `jsonPath` is an error state: bad quality, no value.
 * Read-only. `pollMs` of 0 or absent fetches once; otherwise at least {@link MIN_HTTP_POLL_MS}.
 */
export interface HttpBinding {
  kind: "http";
  source: string;
  path: string;
  query?: Record<string, string | number | boolean>;
  jsonPath?: string;
  pollMs?: number;
  enabled?: boolean;
  overlayOptOut?: boolean;
  transforms?: Transform[];
}

/** Shortest HTTP binding poll interval. */
export const MIN_HTTP_POLL_MS = 1000;

export type Binding = TagBinding | PropertyBinding | ExpressionBinding | ExpressionStructureBinding | QueryBinding | HistoryBinding | HttpBinding;

export type Transform = MapTransform | FormatTransform | ScriptTransform | ExpressionTransform;

/** Expression transform. `{value}` is the incoming value. The expression subset is the same one expression bindings use.
 * A failure stops the chain. The transform is not reversed when a bidirectional binding writes.
 */
export interface ExpressionTransform {
  kind: "expression";
  expression: string;
}

/** Map transform. Entries are tested from top to bottom; the first match wins.
 */
export interface MapTransform {
  kind: "map";
  entries: MapEntry[];
  /** Present when a fallback was authored, even if the fallback value is null. */
  fallback?: unknown;
  hasFallback?: boolean;
}

export interface MapEntry {
  input: MapInput;
  output: MapOutput;
}

export type MapInput =
  | { type: "value"; value: unknown }
  | { type: "range"; min: number | null; max: number | null; minInclusive: boolean; maxInclusive: boolean }
  | { type: "expression"; expression: string };

export type MapOutput = { type: "value"; value: unknown } | { type: "expression"; expression: string };

/** Format transform. The result is always a string.
 */
export interface FormatTransform {
  kind: "format";
  type: "numeric" | "datetime";
  mode: "pattern" | "integer" | "number" | "percent" | "currency" | "date" | "time" | "datetime";
  pattern?: string;
  style?: "full" | "long" | "medium" | "short";
  timeZone?: string;
  currency?: string;
}

/** Script transform hook. The host runs the named hook; this runtime does not embed Python.
 * Arguments mirror the public template: value, quality, timestamp.
 */
export interface ScriptTransform {
  kind: "script";
  hook: string;
  /** Authored script text, kept with the view for the gateway's script host. The browser runtime never runs it. */
  source?: string;
}

export type BindingRef =
  | { source: "param"; name: string }
  | { source: "prop"; nodeId: string; prop: string }
  | { source: "custom"; name: string };

/** Component events run their actions from top to bottom without waiting for earlier actions to finish.
 */
export interface ComponentEvent {
  event: string;
  actions: Action[];
}

export type Action = ScriptAction | NavigationAction | PopupAction | TagWriteAction | MessageAction | LogoutAction | CallMethodAction;

/**
 * Calls a custom method (HMV-114): one a component or the view declares in `methods`. [target] is `view` for the view's
 * own method, a component id for that component's, or absent for the component the event is on (falling back to the
 * view's method of that name). [args] are the method's arguments by parameter name; a missing one takes the
 * parameter's default.
 */
export interface CallMethodAction {
  kind: "call-method";
  method: string;
  target?: string;
  args?: Record<string, unknown>;
  enabled?: boolean;
  stopPropagation?: boolean;
}

/** One declared parameter of a custom method. */
export interface MethodParam {
  name: string;
  /** Used when a call gives no value for the parameter. */
  default?: unknown;
}

/**
 * A custom method (HMV-114): a named list of actions with declared parameters. The actions run in order; in them,
 * `{{param}}` stands for the argument of that name in values (a tag write's value, the params of a navigation or
 * popup, a message payload, a popup title, a nested call's args). Script actions receive the arguments as
 * `event.payload` and the event name `method.<name>`.
 */
export interface MethodDef {
  title?: string;
  params?: MethodParam[];
  actions: Action[];
}

export type MethodMap = Record<string, MethodDef>;

/** Where a message goes (HMP-117): the sending view, every view of the page, or the whole session. */
export type MessageScope = "view" | "page" | "session";

/** Sends a session message: every handler for [messageType] that listens at [scope] runs with [payload]. */
export interface MessageAction {
  kind: "message";
  messageType: string;
  scope: MessageScope;
  payload?: Record<string, unknown>;
  enabled?: boolean;
  stopPropagation?: boolean;
}

/** Signs the session's user out; the session returns to sign-in. */
export interface LogoutAction {
  kind: "logout";
  enabled?: boolean;
  stopPropagation?: boolean;
}

/**
 * A component's message handler (HMP-117): a gateway script run when a message of [messageType] arrives at one of
 * [scopes]. The script is an event script; `event.name` is the message type and `event.payload` the message payload.
 */
export interface MessageHandler {
  messageType: string;
  scopes: MessageScope[];
  script: ScriptAction;
}

export interface ScriptAction {
  kind: "script";
  hook: string;
  /** Python body of `runAction(event, component)`, saved with the view. Without it the gateway runs the project's hook of this name. */
  source?: string;
  enabled?: boolean;
  stopPropagation?: boolean;
}

export interface NavigationAction {
  kind: "navigation";
  mode: "page" | "view" | "url" | "browser";
  page?: string;
  view?: string;
  url?: string;
  params?: Record<string, unknown>;
  newTab?: boolean;
  direction?: "back" | "forward";
  enabled?: boolean;
  stopPropagation?: boolean;
}

/** Popup action. Omitted flags use the `system.screen.openPopup` defaults:
 * showCloseIcon true, draggable true, resizable false, modal false, overlayDismiss false, viewportBound false.
 */
export interface PopupAction {
  kind: "popup";
  mode: "open" | "close" | "toggle";
  view?: string;
  id: string;
  params?: Record<string, unknown>;
  title?: string;
  /** Close icon. Default true (`showCloseIcon`). */
  showClose?: boolean;
  /** Default true. */
  draggable?: boolean;
  /** Default false. */
  resizable?: boolean;
  /** Default false. */
  modal?: boolean;
  /** Modal overlay click closes the popup. Default false (`overlayDismiss`). */
  backgroundDismissible?: boolean;
  /** Shift and shrink the popup into the viewport. Default false. */
  viewportBound?: boolean;
  position?: { top?: number; left?: number; width?: number; height?: number };
  enabled?: boolean;
  stopPropagation?: boolean;
}

/** Writes one tag when the event fires.
 * The public action list routes tag writes through a script (`system.tag.write`). This action is the
 * explicit form FEATURES HMP-117 and this task ask for; see the PR assumptions.
 */
export interface TagWriteAction {
  kind: "tag-write";
  path: string;
  value: TagValue;
  enabled?: boolean;
  stopPropagation?: boolean;
}

export interface Placed {
  frame?: Frame;
  flex?: FlexChild;
  columns?: ColumnPlacement[];
  style?: StyleProps;
  /** Named style classes this node references. Applied alphabetically, not in list order (styleClasses.ts). */
  styleClasses?: string[];
  /** Label used when this node is a tab panel. */
  tabLabel?: string;
  /** Breakpoint container children: the layout this child is for. */
  size?: BreakpointSize;
  /** Who receives this node in a session (SEC-017); the gateway removes it, and all inside it, for anyone else. */
  security?: ViewSecurity;
}

export interface ContainerNode extends Placed {
  id: string;
  kind: "container";
  name: string;
  layout: LayoutKind;
  props: ContainerProps;
  children: ViewNode[];
}

export interface ComponentNode extends Placed {
  id: string;
  kind: "component";
  name: string;
  type: string;
  props: Record<string, unknown>;
  bindings?: Record<string, Binding>;
  events?: ComponentEvent[];
  /** Message handlers (HMP-117). */
  messageHandlers?: MessageHandler[];
  /** Name of one of the component's variants (HMP-115). */
  variant?: string;
  /** Custom category (HMP-127): the designer's own typed values, read by property bindings as `custom.<name>`. */
  custom?: Record<string, unknown>;
  /** Meta category (HMP-127). The node's name is the meta name. */
  meta?: ComponentMeta;
  /** Custom methods (HMV-114) other events, scripts and the view's methods can call by this component's id. */
  methods?: MethodMap;
}

/** A component's meta properties (HMP-127). */
export interface ComponentMeta {
  /** Default true. False removes the component from a session; the Studio shows it faded. */
  visible?: boolean;
  /** Text the browser shows on hover. */
  tooltip?: string;
}

export type ViewNode = ContainerNode | ComponentNode;

/** View parameter. Direction matches the input / output / in-out arrow on a view parameter.
 */
export interface ViewParam {
  value: unknown;
  /** Default `input`. `inout` is the two-headed arrow. */
  direction?: "input" | "output" | "inout";
  binding?: Binding;
}

/** Designer-authored view properties, separate from params and component props. */
export interface ViewCustom {
  value: unknown;
  binding?: Binding;
}

export interface ViewDocument {
  apiVersion: "oip.hmi/v1";
  kind: "View";
  metadata: { name: string; path: string };
  spec: {
    params: Record<string, ViewParam>;
    custom: Record<string, ViewCustom>;
    width: number;
    height: number;
    root: ContainerNode;
    events?: ComponentEvent[];
    /** Custom methods of the view (HMV-114). */
    methods?: MethodMap;
    /** View security (HMP-092, SEC-017): the roles or security levels a user needs to open the view. Absent: anyone may. */
    security?: ViewSecurity;
    /** Drop configuration (HMP-110): what a tag dropped on this view in the Studio becomes. */
    dropConfig?: DropRule[];
  };
}

/**
 * Who may open a view, or receive a component or container (HMP-092, SEC-017). The gateway applies it: a refused view
 * is sent by path only, and a refused node is removed from the document before the session receives it.
 */
export interface ViewSecurity {
  /** The user needs one of these roles. Empty: no role is needed. */
  roles: string[];
  /**
   * Security level paths (SEC-017): the user needs one of them, or every one with [requireAll]. Holding a level holds
   * every level above it, so `Authenticated/Roles` is met by any role level.
   */
  levels?: string[];
  requireAll?: boolean;
}

/**
 * One drop configuration rule (HMP-110). A tag whose data type matches [dataType] (`*` matches any) becomes either
 * a component of [component] with its [prop] bound to the tag, or, with [view], an embedded view of that path whose
 * input param [param] receives the tag path.
 */
export type DropRule =
  | { dataType: string; component: string; prop: string; bidirectional?: boolean }
  | { dataType: string; view: string; param: string };

export type DockEdge = "top" | "bottom" | "left" | "right";
export type DockDisplay = "visible" | "onDemand" | "auto";
export type DockContent = "push" | "cover" | "auto";
export type DockHandle = "show" | "hide" | "autoHide";
export type CornerPriority = "top-bottom" | "left-right";

/** One dock per edge. Size is height for top/bottom and width for left/right.
 */
export interface DockSpec {
  id: string;
  edge: DockEdge;
  view: string;
  size: number;
  display: DockDisplay;
  content: DockContent;
  handle: DockHandle;
  params?: Record<string, unknown>;
  resizable?: boolean;
  modal?: boolean;
  autoBreakpoint?: number;
  anchor?: "fixed" | "scrollable";
}

export interface PageDocument {
  apiVersion: "oip.hmi/v1";
  kind: "Page";
  metadata: { name: string };
  spec: {
    /** Page URL. Always starts with `/`. `:name` segments map to primary-view input params.
     */
    url: string;
    title?: string;
    primaryView: string;
    docks: DockSpec[];
    cornerPriority?: CornerPriority;
  };
}

export interface HmiProject {
  name: string;
  views: ViewDocument[];
  pages: PageDocument[];
  sharedDocks?: DockSpec[];
  cornerPriority?: CornerPriority;
  /** Project style classes available to every view in the session. */
  styleClasses?: StyleClassSet;
  /** Project themes, each extending a built-in theme. A session theme name may be one of these. */
  themes?: CustomThemeSet;
  /** Project defaults for a new session (empty theme means Dark / nightwatch, empty locale means en-US). */
  defaultTagProvider?: string;
  defaultDatabase?: string;
  sessionTheme?: string;
  sessionLocale?: string;
  /** Touchscreen mode (HMV-126): inputs open an on-screen keyboard. A session can also turn it on with `session.custom.touchscreen`. */
  touchscreenMode?: boolean;
  /** Terms by locale tag (HMP-125). The `translate` expression function reads the session's locale from here. */
  translations?: Record<string, Record<string, string>>;
  /** The gateway's default time zone (HMP-101): a new session starts in it. */
  gatewayTimeZone?: string;
  /**
   * Paths of views the session's user may not open (HMP-092). The gateway sends nothing else of them; the session
   * shows a refusal where one is used as a page, dock, popup or embedded view.
   */
  deniedViews?: string[];
}

export interface JsonSchema {
  $schema?: string;
  type?: "object" | "string" | "number" | "integer" | "boolean" | "array";
  title?: string;
  description?: string;
  properties?: Record<string, JsonSchema>;
  required?: string[];
  enum?: unknown[];
  minimum?: number;
  maximum?: number;
  default?: unknown;
  additionalProperties?: boolean;
}

export interface HmiComponentProps {
  nodeId: string;
  props: Record<string, unknown>;
  locale: string;
  onChange: (prop: string, value: unknown) => void;
  onEvent?: (name: string) => void;
  /** Moves the session to a page path such as `/pumps` or `/line/3?mode=auto`, through the same page
   * navigation the `onClick` navigation action uses. Absent outside a session.
   */
  navigate?: (path: string) => void;
  /** Browser URL for a page path, including the host's session base (`/pumps` becomes `/hmi/pumps`),
   * for `href` attributes so open-in-new-tab and copy-link land on the same page. Absent outside a session.
   */
  pageHref?: (path: string) => string;
  /** This component's frame in a coordinate parent, when it has one. A pipe uses it to draw in the parent's space. */
  placement?: { x: number; y: number; width: number; height: number };
}

export interface HmiComponentModule {
  type: string;
  title: string;
  schema: JsonSchema;
  Component: (props: HmiComponentProps) => ReactNode;
  /** Palette category (HMP-115). Every registered component declares one ([RegisteredComponent]). */
  category?: "chart" | "container" | "display" | "input" | "navigation";
  /** Size a new instance is placed at, in pixels (HMP-115). */
  position?: { width: number; height: number };
  /** Named looks of the component (HMP-115). A node picks one by name in `variant`. */
  variants?: ComponentVariant[];
}

/** A component in the shipped registry: category and placement size are required (HMP-115). */
export interface RegisteredComponent extends HmiComponentModule {
  category: "chart" | "container" | "display" | "input" | "navigation";
  position: { width: number; height: number };
}

/** A named variant: a style from the CSS subset applied under the node's style classes and own style. */
export interface ComponentVariant {
  name: string;
  title: string;
  style: StyleProps;
}

export interface ComponentRegistry {
  get(type: string): HmiComponentModule | undefined;
  list(): HmiComponentModule[];
}
