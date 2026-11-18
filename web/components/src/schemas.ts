import type { JsonSchema } from "@oip/hmi-runtime";

const text: JsonSchema = {
  type: "string",
  title: "Text",
  default: "Label",
};

export const labelSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Label",
  additionalProperties: false,
  properties: {
    text,
    align: {
      type: "string",
      title: "Align",
      enum: ["start", "center", "end"],
      default: "start",
    },
  },
};

export const numericSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Numeric",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
    prefix: { type: "string", title: "Prefix", default: "" },
    suffix: { type: "string", title: "Suffix", default: "" },
    announce: { type: "boolean", title: "Announce changes", default: true },
  },
};

export const buttonSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Button",
  additionalProperties: false,
  properties: {
    text: { type: "string", title: "Text", default: "Button" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const toggleSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Toggle",
  additionalProperties: false,
  properties: {
    value: { type: "boolean", title: "Value", default: false },
    label: { type: "string", title: "Label", default: "Toggle" },
  },
};

export const textInputSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Text input",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Value", default: "" },
    label: { type: "string", title: "Label", default: "Text" },
    placeholder: { type: "string", title: "Placeholder", default: "" },
    maxLength: { type: "integer", title: "Max length", minimum: 1, maximum: 500, default: 120 },
  },
};

export const indicatorSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Indicator",
  additionalProperties: false,
  properties: {
    value: { type: "boolean", title: "Value", default: false },
    label: { type: "string", title: "Label", default: "Status" },
    onText: { type: "string", title: "On text", default: "On" },
    offText: { type: "string", title: "Off text", default: "Off" },
  },
};

export const linearGaugeSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Linear gauge",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    label: { type: "string", title: "Label", default: "Level" },
    unit: { type: "string", title: "Unit", default: "" },
  },
};

export const tableSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Table",
  additionalProperties: false,
  properties: {
    filter: { type: "string", title: "Filter", default: "" },
    pageSize: { type: "integer", title: "Page size", minimum: 0, maximum: 500, default: 10 },
    page: { type: "integer", title: "Page", minimum: 0, default: 0 },
    rowKey: { type: "string", title: "Row key", default: "id" },
    selection: { type: "string", title: "Selection", default: "" },
  },
};

export const dropdownSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Dropdown",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Choose" },
    value: { type: "string", title: "Value", default: "" },
    placeholder: { type: "string", title: "Placeholder", default: "" },
    enabled: { type: "boolean", title: "Enabled", default: true },
    multi: { type: "boolean", title: "Multi select", default: false },
  },
};

export const sliderSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Slider",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Value" },
    value: { type: "number", title: "Value", default: 0 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    step: { type: "number", title: "Step", default: 1 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const checkboxSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Checkbox",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Check" },
    value: { type: "boolean", title: "Value", default: false },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const alarmStatusSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Alarm status",
  additionalProperties: false,
  properties: {
    filter: { type: "string", title: "Filter", default: "" },
  },
};

export const imageSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Image",
  additionalProperties: false,
  properties: {
    source: { type: "string", title: "Source", default: "" },
    alt: { type: "string", title: "Alt text", default: "Image" },
    fit: { type: "string", title: "Fit", enum: ["contain", "cover", "fill", "none"], default: "contain" },
  },
};

export const embeddedViewSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Embedded view",
  additionalProperties: false,
  properties: {
    path: { type: "string", title: "View path", default: "" },
    useDefaultViewWidth: { type: "boolean", title: "Use default view width", default: true },
    useDefaultViewHeight: { type: "boolean", title: "Use default view height", default: true },
  },
};

export const trendSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Trend",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    capacity: { type: "integer", title: "Points", minimum: 2, maximum: 240, default: 30 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    label: { type: "string", title: "Label", default: "Trend" },
  },
};

/** C-11 batch 1: input and display components. List props (options, states, points) are plain text
 * ("value:Label, value:Label" or "1, 2, 3") so the inspector can edit them; a binding may also supply an array.
 * Each schema cites the public page it was written against. The descriptions are our own wording.
 */

/** - */
export const simpleGaugeSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Simple gauge",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    label: { type: "string", title: "Label", default: "Gauge" },
    unit: { type: "string", title: "Unit", default: "" },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
    sweep: { type: "integer", title: "Arc sweep (degrees)", minimum: 90, maximum: 300, default: 180 },
    thickness: { type: "integer", title: "Arc thickness", minimum: 2, maximum: 30, default: 10 },
    color: { type: "string", title: "Arc color (token or CSS color)", default: "accent" },
  },
};

/** - */
export const progressBarSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Progress bar",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    label: { type: "string", title: "Label", default: "Progress" },
    showValue: { type: "boolean", title: "Show value", default: true },
    valueFormat: { type: "string", title: "Value format", enum: ["percent", "value"], default: "percent" },
    indeterminate: { type: "boolean", title: "Indeterminate", default: false },
    color: { type: "string", title: "Bar color (token or CSS color)", default: "accent" },
  },
};

/** - */
export const iconSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Icon",
  additionalProperties: false,
  properties: {
    name: {
      type: "string",
      title: "Icon",
      enum: [
        "circle", "square", "triangle", "check", "cross", "plus", "minus",
        "arrow-up", "arrow-down", "arrow-left", "arrow-right", "home", "warning", "info",
        "power", "bell", "clock", "lock", "star", "sliders", "pump", "valve", "tank",
        "motor", "drop", "bolt", "thermometer",
      ],
      default: "circle",
    },
    color: { type: "string", title: "Color (token or CSS color, empty follows text)", default: "" },
    label: { type: "string", title: "Accessible label (empty is decorative)", default: "" },
    strokeWidth: { type: "number", title: "Stroke width", minimum: 0.5, maximum: 4, default: 2 },
  },
};

/** - */
export const sparklineSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Sparkline",
  additionalProperties: false,
  properties: {
    points: { type: "string", title: "Points (comma-separated, or bind an array)", default: "" },
    label: { type: "string", title: "Label", default: "Sparkline" },
    autoScale: { type: "boolean", title: "Auto scale", default: true },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    desiredLow: { type: "number", title: "Desired band low" },
    desiredHigh: { type: "number", title: "Desired band high" },
    showLast: { type: "boolean", title: "Mark last point", default: true },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
    color: { type: "string", title: "Line color (token or CSS color)", default: "accent" },
    historyPath: { type: "string", title: "History tag path (supports {name} from the page URL)", default: "" },
    setpoint: { type: "number", title: "Setpoint line" },
    high: { type: "number", title: "Alarm above this value" },
  },
};

/** - */
export const dateTimeInputSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Date-time input",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value (epoch ms)" },
    label: { type: "string", title: "Label", default: "Date and time" },
    mode: { type: "string", title: "Mode", enum: ["datetime", "date", "time"], default: "datetime" },
    min: { type: "number", title: "Earliest (epoch ms)" },
    max: { type: "number", title: "Latest (epoch ms)" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const dateTimePickerSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Date-time picker",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value (epoch ms)" },
    label: { type: "string", title: "Label", default: "Pick a date" },
    showTime: { type: "boolean", title: "Show time", default: true },
    weekStart: { type: "integer", title: "First day of week (0 = Sunday)", minimum: 0, maximum: 6, default: 1 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const passwordFieldSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Password field",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Value", default: "" },
    label: { type: "string", title: "Label", default: "Password" },
    placeholder: { type: "string", title: "Placeholder", default: "" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const radioGroupSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Radio group",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Value", default: "" },
    label: { type: "string", title: "Label", default: "Choose one" },
    options: { type: "string", title: "Options (value:Label, comma-separated, or bind an array)", default: "a:Option A, b:Option B" },
    orientation: { type: "string", title: "Orientation", enum: ["row", "column"], default: "row" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const textAreaSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Text area",
  additionalProperties: false,
  properties: {
    value: { type: "string", title: "Value", default: "" },
    label: { type: "string", title: "Label", default: "Notes" },
    placeholder: { type: "string", title: "Placeholder", default: "" },
    rows: { type: "integer", title: "Rows", minimum: 1, maximum: 40, default: 4 },
    maxLength: { type: "integer", title: "Max length", minimum: 1, maximum: 10000, default: 2000 },
    deferUpdates: { type: "boolean", title: "Write on blur only", default: true },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const oneShotButtonSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "One-shot button",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    writeValue: { type: "number", title: "Value to write", default: 1 },
    text: { type: "string", title: "Text", default: "Start" },
    writingText: { type: "string", title: "Text while waiting", default: "Writing..." },
    timeoutMs: { type: "integer", title: "Re-enable after (ms)", minimum: 100, maximum: 600000, default: 5000 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/**
 * `project` and `hook` name the gateway script handler; the hook's own `upload` settings decide the size limit and the
 * allowed content types, and the gateway enforces them. `accept` and `maxBytes` here only stop an obviously wrong file early.
 */
export const fileUploadSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "File upload",
  additionalProperties: false,
  properties: {
    project: { type: "string", title: "Project", default: "" },
    hook: { type: "string", title: "Handler script hook", default: "" },
    view: { type: "string", title: "View path of the hook", default: "" },
    text: { type: "string", title: "Text", default: "Choose a file" },
    accept: { type: "string", title: "Accepted types (browser hint)", default: "" },
    maxBytes: { type: "integer", title: "Largest file (bytes, 0 for the handler's limit)", minimum: 0, default: 0 },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const multiStateButtonSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Multi-state button",
  additionalProperties: false,
  properties: {
    value: { type: "number", title: "Value", default: 0 },
    label: { type: "string", title: "Label", default: "Mode" },
    states: { type: "string", title: "States (value:Text, comma-separated, or bind an array)", default: "0:Off, 1:Hand, 2:Auto" },
    orientation: { type: "string", title: "Orientation", enum: ["row", "column"], default: "row" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const linkSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Link",
  additionalProperties: false,
  properties: {
    text: { type: "string", title: "Text", default: "Link" },
    url: { type: "string", title: "Target (page path like /pumps, or http(s) URL)", default: "/" },
    newTab: { type: "boolean", title: "Open external URL in new tab", default: false },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** Live alarm status table. List props (columns, shelving times) are comma-separated so the inspector can edit them.
 */
export const alarmTableSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Alarm status table",
  additionalProperties: false,
  properties: {
    live: { type: "boolean", title: "Poll the gateway", default: true },
    refreshRate: { type: "integer", title: "Refresh rate (ms)", minimum: 250, maximum: 60000, default: 2000 },
    columns: {
      type: "string",
      title: "Columns",
      description: "Comma-separated: state, priority, displayPath, source, label, activeTime, clearTime, ackTime, ackUser, ackNotes, value",
      default: "activeTime, displayPath, label, priority, state, ackTime",
    },
    filter: { type: "string", title: "Search", default: "" },
    sourceFilter: { type: "string", title: "Source path filter (* wildcard)", default: "" },
    displayPathFilter: { type: "string", title: "Display path filter (* wildcard)", default: "" },
    showActiveUnacked: { type: "boolean", title: "Show active, unacknowledged", default: true },
    showActiveAcked: { type: "boolean", title: "Show active, acknowledged", default: true },
    showClearedUnacked: { type: "boolean", title: "Show cleared, unacknowledged", default: true },
    showClearedAcked: { type: "boolean", title: "Show cleared, acknowledged", default: false },
    showDiagnostic: { type: "boolean", title: "Show Diagnostic", default: false },
    showLow: { type: "boolean", title: "Show Low", default: true },
    showMedium: { type: "boolean", title: "Show Medium", default: true },
    showHigh: { type: "boolean", title: "Show High", default: true },
    showCritical: { type: "boolean", title: "Show Critical", default: true },
    enableAcknowledge: { type: "boolean", title: "Allow acknowledge", default: true },
    enableShelve: { type: "boolean", title: "Allow shelve", default: true },
    enableUnshelve: { type: "boolean", title: "Allow unshelve", default: true },
    shelvingTimes: { type: "string", title: "Shelving times (minutes, comma-separated)", default: "5, 15, 30, 60" },
  },
};

/** - */
export const timeSeriesChartSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Time series chart",
  additionalProperties: false,
  properties: {
    title: { type: "string", title: "Title", default: "Time series" },
    value: { type: "number", title: "Live value" },
    valueName: { type: "string", title: "Live series name", default: "Value" },
    historyPens: { type: "string", title: "History pens (tag paths, comma-separated)", default: "" },
    penChoices: { type: "string", title: "Pens the operator can add (tag paths, comma-separated)", default: "" },
    historyRefreshSeconds: { type: "integer", title: "History refresh (s, 0 = once)", minimum: 0, maximum: 3600, default: 0 },
    historyLimit: { type: "integer", title: "History rows per pen", minimum: 1, maximum: 100000, default: 10000 },
    rangeSeconds: { type: "integer", title: "Visible range (s, 0 = all)", minimum: 0, maximum: 2592000, default: 300 },
    maxPoints: { type: "integer", title: "Max live points", minimum: 2, maximum: 10000, default: 1000 },
    renderer: { type: "string", title: "Render as", enum: ["line", "area", "scatter"], default: "line" },
    autoScale: { type: "boolean", title: "Auto scale", default: true },
    yMin: { type: "number", title: "Axis minimum", default: 0 },
    yMax: { type: "number", title: "Axis maximum", default: 100 },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
    showLegend: { type: "boolean", title: "Legend", default: true },
  },
};

/** - */
export const powerChartSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Power chart",
  additionalProperties: false,
  properties: {
    title: { type: "string", title: "Title", default: "Power chart" },
    pens: { type: "string", title: "Pens (tag paths, comma-separated)", default: "" },
    mode: { type: "string", title: "Mode", enum: ["realtime", "historical"], default: "realtime" },
    pollRate: { type: "integer", title: "Realtime refresh (ms)", minimum: 250, maximum: 60000, default: 1000 },
    rangeSeconds: { type: "integer", title: "Range (s)", minimum: 10, maximum: 2592000, default: 300 },
    startTime: { type: "string", title: "Historical start (ISO-8601)", default: "" },
    endTime: { type: "string", title: "Historical end (ISO-8601)", default: "" },
    maxPoints: { type: "integer", title: "Max points per pen", minimum: 2, maximum: 20000, default: 2000 },
    renderer: { type: "string", title: "Render as", enum: ["line", "area", "scatter"], default: "line" },
    autoScale: { type: "boolean", title: "Auto scale", default: true },
    yMin: { type: "number", title: "Axis minimum", default: 0 },
    yMax: { type: "number", title: "Axis maximum", default: 100 },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
  },
};

/** Alarm journal table (C-14). Flat props so the inspector can edit them; columns are comma-separated.
 */
export const alarmJournalTableSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Alarm journal table",
  additionalProperties: false,
  properties: {
    live: { type: "boolean", title: "Poll the gateway (recent range)", default: true },
    refreshRate: { type: "integer", title: "Refresh rate (ms)", minimum: 1000, maximum: 300000, default: 5000 },
    rangeMode: { type: "string", title: "Date range", enum: ["realtime", "historical"], default: "realtime" },
    realtimeMinutes: { type: "integer", title: "Recent range (minutes back from now)", minimum: 1, maximum: 525600, default: 480 },
    startTime: { type: "string", title: "Start (ISO-8601, between dates)", default: "" },
    endTime: { type: "string", title: "End (ISO-8601, empty = now)", default: "" },
    columns: {
      type: "string",
      title: "Columns",
      description: "Comma-separated: eventTime, eventType, priority, displayPath, source, label, eventId, eventValue, actor, ackNotes, shelvedUntil",
      default: "eventTime, displayPath, eventType, priority, eventValue, actor",
    },
    filter: { type: "string", title: "Search (loaded page)", default: "" },
    sourceFilter: { type: "string", title: "Source path filter (* wildcard)", default: "" },
    displayPathFilter: { type: "string", title: "Display path filter (* wildcard)", default: "" },
    minPriority: { type: "string", title: "Lowest priority", enum: ["Diagnostic", "Low", "Medium", "High", "Critical"], default: "Low" },
    maxPriority: { type: "string", title: "Highest priority", enum: ["Diagnostic", "Low", "Medium", "High", "Critical"], default: "Critical" },
    showActive: { type: "boolean", title: "Show active events", default: true },
    showCleared: { type: "boolean", title: "Show cleared events", default: true },
    showAcked: { type: "boolean", title: "Show acknowledged events", default: true },
    showShelved: { type: "boolean", title: "Show shelve events", default: true },
    showUnshelved: { type: "boolean", title: "Show unshelve events", default: true },
    newestFirst: { type: "boolean", title: "Newest first", default: true },
    pageSize: { type: "integer", title: "Rows per page", minimum: 1, maximum: 1000, default: 100 },
    enableDetails: { type: "boolean", title: "Row details on click", default: true },
  },
};

// C-18 navigation components. Item lists are text (`Parent > Child = /page`, comma-separated) or a bound array.

const navItemsTitle = "Items (Parent > Child = /page, comma-separated; or bind an array)";

/** - */
export const horizontalMenuSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Horizontal menu",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Main menu" },
    items: { type: "string", title: navItemsTitle, default: "Home = /, Pumps = /pumps" },
    selected: { type: "string", title: "Selected item path (0/1)", default: "" },
    align: { type: "string", title: "Alignment", enum: ["start", "center", "end"], default: "start" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const menuTreeSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Menu tree",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Menu" },
    items: { type: "string", title: navItemsTitle, default: "Home = /, Pumps = /pumps" },
    selected: { type: "string", title: "Selected item path (0/1)", default: "" },
    backLabel: { type: "string", title: "Back button text", default: "Back" },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

/** - */
export const treeSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Tree",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Tree" },
    items: { type: "string", title: "Items (Parent > Child, comma-separated; or bind an array)", default: "Plant > Area 1, Plant > Area 2" },
    selected: { type: "string", title: "Selected item path (0/1)", default: "" },
    expanded: { type: "string", title: "Expanded item paths (comma-separated)", default: "" },
    showLines: { type: "boolean", title: "Show guide lines", default: true },
    enabled: { type: "boolean", title: "Enabled", default: true },
  },
};

export const xyChartSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "XY chart",
  additionalProperties: false,
  properties: {
    title: { type: "string", title: "Title", default: "XY chart" },
    series: { type: "string", title: "Series (Name:x,y;x,y or Name@y2:x,y for the right axis)", default: "A:0,1;1,3;2,2" },
    xLabel: { type: "string", title: "X axis label", default: "" },
    yLabel: { type: "string", title: "Y axis label", default: "" },
  },
};

export const radialGaugeSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Radial gauge",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Gauge" },
    value: { type: "number", title: "Value", default: 40 },
    value2: { type: "number", title: "Second value", default: 70 },
    showSecond: { type: "boolean", title: "Show second needle", default: true },
    min: { type: "number", title: "Minimum", default: 0 },
    max: { type: "number", title: "Maximum", default: 100 },
    ranges: { type: "string", title: "Ranges (from:to:token)", default: "0:50:ok,50:80:warning,80:100:alarm" },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 0 },
  },
};

export const tagBrowseSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Tag browse",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Label", default: "Tags" },
    root: { type: "string", title: "Root path", default: "[default]" },
    value: { type: "string", title: "Selected path", default: "" },
  },
};
