import type { ComponentRegistry, ComponentVariant, JsonSchema, RegisteredComponent } from "@oip/hmi-runtime";
import {
  alarmStatusSchema,
  alarmTableSchema,
  buttonSchema,
  checkboxSchema,
  dropdownSchema,
  embeddedViewSchema,
  imageSchema,
  indicatorSchema,
  labelSchema,
  linearGaugeSchema,
  numericSchema,
  powerChartSchema,
  sliderSchema,
  tableSchema,
  textInputSchema,
  timeSeriesChartSchema,
  toggleSchema,
  trendSchema,
} from "./schemas";
import { AlarmStatus, Checkbox, Dropdown, EmbeddedViewPlaceholder, Image, Slider, Table } from "./extra";
import { AlarmStatusTable } from "./AlarmStatusTable";
import { PowerChart, TimeSeriesChart } from "./charts";
import { RadialGauge, TagBrowse, XyChart } from "./batchCharts";
import { Button, Indicator, Label, LinearGauge, NumericDisplay, TextInput, Toggle, TrendLine } from "./widgets";
import {
  dateTimeInputSchema,
  dateTimePickerSchema,
  iconSchema,
  linkSchema,
  multiStateButtonSchema,
  oneShotButtonSchema,
  fileUploadSchema,
  passwordFieldSchema,
  progressBarSchema,
  radioGroupSchema,
  simpleGaugeSchema,
  sparklineSchema,
  xyChartSchema,
  radialGaugeSchema,
  tagBrowseSchema,
  textAreaSchema,
} from "./schemas";
import { DateTimeInput, DateTimePicker, FileUpload, Link, MultiStateButton, OneShotButton, PasswordField, RadioGroup, TextArea } from "./inputs2";
import { Icon, ProgressBar, SimpleGauge, Sparkline } from "./display2";
// C-14
import { alarmJournalTableSchema } from "./schemas";
import { AlarmJournalTable } from "./alarmJournal";
import { horizontalMenuSchema, menuTreeSchema, treeSchema } from "./schemas";
import { HorizontalMenu, MenuTree, Tree } from "./navigation";
import {
  CylindricalTank,
  cylindricalTankSchema,
  Motor,
  motorSchema,
  Pump,
  pumpSchema,
  Sensor,
  sensorSchema,
  Valve,
  valveSchema,
  Vessel,
  vesselSchema,
} from "./symbols";
// C-15 embedding components
import { NumericEntryField, numericEntrySchema } from "./numericEntry";
import { Accordion, accordionSchema, Carousel, carouselSchema, FlexRepeater, flexRepeaterSchema } from "./containers";
// Display palette media (HMP-031, HMP-033, HMP-035, HMP-042)
import { Audio, audioSchema, Drawing, drawingSchema, PdfViewer, pdfViewerSchema, Video, videoSchema } from "./media";
import { AnalogIndicator, analogIndicatorSchema } from "./analogIndicator";
import { Form, formSchema } from "./form";
import { SvgComponent, svgComponentSchema } from "./svgComponent";
import { TemplateCanvas, templateCanvasSchema } from "./templateCanvas";
import { Pipe, pipeSchema } from "./pipe";
import {
  BarcodeScannerInput,
  barcodeScannerSchema,
  FormattedTextField,
  formattedTextFieldSchema,
  MomentaryButton,
  momentaryButtonSchema,
  Spinner,
  spinnerSchema,
  StateToggle,
  stateToggleSchema,
  Timer,
  timerSchema,
} from "./inputs3";
import { NavigationDrawer, navigationDrawerSchema } from "./drawer";
import { InlineFrame, inlineFrameSchema } from "./inlineFrame";
import { LeafletMap, leafletMapSchema } from "./leafletMap";
import { MapView, mapSchema } from "./map";
import { Dashboard, dashboardSchema } from "./dashboard";
import {
  LedDisplay,
  LinearScale,
  Markdown,
  PieChart,
  RangeSelector,
  Thermometer,
  ViewCanvasPlaceholder,
  ledSchema,
  linearScaleSchema,
  markdownSchema,
  pieChartSchema,
  rangeSelectorSchema,
  thermometerSchema,
  viewCanvasSchema,
} from "./batch2";

export function defaultsFromSchema(schema: JsonSchema): Record<string, unknown> {
  const props: Record<string, unknown> = {};
  if (schema.type !== "object" || !schema.properties) return props;
  for (const [key, child] of Object.entries(schema.properties)) {
    if (child.default !== undefined) props[key] = child.default;
    else if (child.type === "object") {
      const nested = defaultsFromSchema(child);
      if (Object.keys(nested).length > 0) props[key] = nested;
    }
  }
  return props;
}

/**
 * Variants (HMP-115): named looks a node picks with `variant`. Each is a style from the CSS subset, applied under the
 * node's style classes and its own style, so a class or an inline property still wins.
 */
const LABEL_VARIANTS: ComponentVariant[] = [
  { name: "heading", title: "Heading", style: { fontSize: 20, fontWeight: 600 } },
  { name: "caption", title: "Caption", style: { fontSize: 11, textTransform: "uppercase", letterSpacing: 0.5 } },
];
const BUTTON_VARIANTS: ComponentVariant[] = [
  { name: "primary", title: "Primary", style: { background: "var(--oip-color-accent)", color: "var(--oip-color-accent-ink)", borderRadius: 6 } },
  {
    name: "secondary",
    title: "Secondary",
    style: { background: "var(--oip-color-surface)", color: "var(--oip-color-accent)", borderStyle: "solid", borderWidth: 1, borderColor: "var(--oip-color-accent)", borderRadius: 6 },
  },
  { name: "danger", title: "Danger", style: { background: "var(--oip-color-alarm)", color: "#ffffff", borderRadius: 6 } },
];
const NUMERIC_VARIANTS: ComponentVariant[] = [{ name: "readout", title: "Large readout", style: { fontSize: 28, fontWeight: 600, textAlign: "right" } }];

export const componentModules: RegisteredComponent[] = [
  { type: "label", title: "Label", schema: labelSchema, Component: Label, category: "display", position: { width: 160, height: 40 }, variants: LABEL_VARIANTS },
  { type: "numeric", title: "Numeric", schema: numericSchema, Component: NumericDisplay, category: "display", position: { width: 160, height: 40 }, variants: NUMERIC_VARIANTS },
  { type: "button", title: "Button", schema: buttonSchema, Component: Button, category: "input", position: { width: 160, height: 40 }, variants: BUTTON_VARIANTS },
  { type: "toggle", title: "Toggle", schema: toggleSchema, Component: Toggle, category: "input", position: { width: 160, height: 40 } },
  { type: "text-input", title: "Text input", schema: textInputSchema, Component: TextInput, category: "input", position: { width: 160, height: 40 } },
  // M4.6 (HMP-055)
  { type: "numeric-entry", title: "Numeric entry field", schema: numericEntrySchema, Component: NumericEntryField, category: "input", position: { width: 160, height: 40 } },
  { type: "indicator", title: "Indicator", schema: indicatorSchema, Component: Indicator, category: "display", position: { width: 160, height: 40 } },
  { type: "linear-gauge", title: "Linear gauge", schema: linearGaugeSchema, Component: LinearGauge, category: "chart", position: { width: 200, height: 56 } },
  { type: "trend", title: "Trend", schema: trendSchema, Component: TrendLine, category: "chart", position: { width: 320, height: 160 } },
  { type: "table", title: "Table", schema: tableSchema, Component: Table, category: "display", position: { width: 360, height: 200 } },
  { type: "dropdown", title: "Dropdown", schema: dropdownSchema, Component: Dropdown, category: "input", position: { width: 160, height: 40 } },
  { type: "slider", title: "Slider", schema: sliderSchema, Component: Slider, category: "input", position: { width: 160, height: 40 } },
  { type: "checkbox", title: "Checkbox", schema: checkboxSchema, Component: Checkbox, category: "input", position: { width: 160, height: 40 } },
  { type: "alarm-status", title: "Alarm status", schema: alarmStatusSchema, Component: AlarmStatus, category: "display", position: { width: 160, height: 40 } },
  { type: "image", title: "Image", schema: imageSchema, Component: Image, category: "display", position: { width: 160, height: 120 } },
  // Display palette media (HMP-031, HMP-033, HMP-035, HMP-042)
  { type: "drawing", title: "Drawing", schema: drawingSchema, Component: Drawing, category: "display", position: { width: 160, height: 160 } },
  { type: "svg-component", title: "SVG component", schema: svgComponentSchema, Component: SvgComponent, category: "display", position: { width: 160, height: 160 } },
  { type: "pipe", title: "Pipe", schema: pipeSchema, Component: Pipe, category: "display", position: { width: 144, height: 104 } },
  { type: "pdf-viewer", title: "PDF viewer", schema: pdfViewerSchema, Component: PdfViewer, category: "display", position: { width: 420, height: 540 } },
  { type: "audio", title: "Audio", schema: audioSchema, Component: Audio, category: "display", position: { width: 300, height: 56 } },
  { type: "video-player", title: "Video player", schema: videoSchema, Component: Video, category: "display", position: { width: 480, height: 270 } },
  { type: "inline-frame", title: "Inline frame", schema: inlineFrameSchema, Component: InlineFrame, category: "display", position: { width: 480, height: 320 } },
  { type: "map", title: "Map", schema: mapSchema, Component: MapView, category: "display", position: { width: 480, height: 320 } },
  { type: "leaflet-map", title: "Leaflet map", schema: leafletMapSchema, Component: LeafletMap, category: "display", position: { width: 640, height: 480 } },
  { type: "analog-indicator", title: "Moving analog indicator", schema: analogIndicatorSchema, Component: AnalogIndicator, category: "display", position: { width: 240, height: 56 } },
  { type: "form", title: "Form", schema: formSchema, Component: Form, category: "input", position: { width: 280, height: 220 } },
  { type: "navigation-drawer", title: "Navigation drawer", schema: navigationDrawerSchema, Component: NavigationDrawer, category: "navigation", position: { width: 40, height: 40 } },
  { type: "alarm-status-table", title: "Alarm status table", schema: alarmTableSchema, Component: AlarmStatusTable, category: "display", position: { width: 480, height: 240 } },
  { type: "time-series-chart", title: "Time series chart", schema: timeSeriesChartSchema, Component: TimeSeriesChart, category: "chart", position: { width: 400, height: 220 } },
  { type: "power-chart", title: "Power chart", schema: powerChartSchema, Component: PowerChart, category: "chart", position: { width: 480, height: 260 } },
  { type: "embedded-view", title: "Embedded view", schema: embeddedViewSchema, Component: EmbeddedViewPlaceholder, category: "container", position: { width: 320, height: 200 } },
  // C-11 batch 1
  { type: "simple-gauge", title: "Simple gauge", schema: simpleGaugeSchema, Component: SimpleGauge, category: "chart", position: { width: 160, height: 160 } },
  { type: "xy-chart", title: "XY chart", schema: xyChartSchema, Component: XyChart, category: "chart", position: { width: 320, height: 200 } },
  { type: "radial-gauge", title: "Radial gauge", schema: radialGaugeSchema, Component: RadialGauge, category: "chart", position: { width: 200, height: 140 } },
  { type: "tag-browse", title: "Tag browse", schema: tagBrowseSchema, Component: TagBrowse, category: "display", position: { width: 220, height: 240 } },
  { type: "pie-chart", title: "Pie chart", schema: pieChartSchema, Component: PieChart, category: "chart", position: { width: 200, height: 180 } },
  { type: "range-selector", title: "Range selector", schema: rangeSelectorSchema, Component: RangeSelector, category: "chart", position: { width: 280, height: 80 } },
  { type: "led", title: "LED display", schema: ledSchema, Component: LedDisplay, category: "display", position: { width: 140, height: 40 } },
  { type: "linear-scale", title: "Linear scale", schema: linearScaleSchema, Component: LinearScale, category: "display", position: { width: 200, height: 40 } },
  { type: "thermometer", title: "Thermometer", schema: thermometerSchema, Component: Thermometer, category: "display", position: { width: 80, height: 160 } },
  { type: "markdown", title: "Markdown", schema: markdownSchema, Component: Markdown, category: "display", position: { width: 240, height: 80 } },
  { type: "view-canvas", title: "View canvas", schema: viewCanvasSchema, Component: ViewCanvasPlaceholder, category: "container", position: { width: 320, height: 200 } },
  { type: "progress-bar", title: "Progress bar", schema: progressBarSchema, Component: ProgressBar, category: "display", position: { width: 160, height: 40 } },
  { type: "icon", title: "Icon", schema: iconSchema, Component: Icon, category: "display", position: { width: 40, height: 40 } },
  { type: "sparkline", title: "Sparkline", schema: sparklineSchema, Component: Sparkline, category: "chart", position: { width: 160, height: 40 } },
  { type: "datetime-input", title: "Date-time input", schema: dateTimeInputSchema, Component: DateTimeInput, category: "input", position: { width: 160, height: 40 } },
  { type: "datetime-picker", title: "Date-time picker", schema: dateTimePickerSchema, Component: DateTimePicker, category: "input", position: { width: 280, height: 300 } },
  { type: "password-field", title: "Password field", schema: passwordFieldSchema, Component: PasswordField, category: "input", position: { width: 160, height: 40 } },
  { type: "radio-group", title: "Radio group", schema: radioGroupSchema, Component: RadioGroup, category: "input", position: { width: 160, height: 96 } },
  { type: "text-area", title: "Text area", schema: textAreaSchema, Component: TextArea, category: "input", position: { width: 240, height: 96 } },
  { type: "one-shot-button", title: "One-shot button", schema: oneShotButtonSchema, Component: OneShotButton, category: "input", position: { width: 160, height: 40 } },
  { type: "file-upload", title: "File upload", schema: fileUploadSchema, Component: FileUpload, category: "input", position: { width: 200, height: 40 } },
  { type: "multi-state-button", title: "Multi-state button", schema: multiStateButtonSchema, Component: MultiStateButton, category: "input", position: { width: 160, height: 40 } },
  { type: "link", title: "Link", schema: linkSchema, Component: Link, category: "navigation", position: { width: 160, height: 40 } },
  // C-14
  { type: "alarm-journal-table", title: "Alarm journal table", schema: alarmJournalTableSchema, Component: AlarmJournalTable, category: "display", position: { width: 480, height: 240 } },
  // C-15 embedding components
  { type: "flex-repeater", title: "Flex repeater", schema: flexRepeaterSchema, Component: FlexRepeater, category: "container", position: { width: 320, height: 200 } },
  { type: "dashboard", title: "Dashboard", schema: dashboardSchema, Component: Dashboard, category: "container", position: { width: 640, height: 400 } },
  { type: "template-canvas", title: "Template canvas", schema: templateCanvasSchema, Component: TemplateCanvas, category: "container", position: { width: 480, height: 320 } },
  { type: "carousel", title: "Carousel", schema: carouselSchema, Component: Carousel, category: "container", position: { width: 320, height: 200 } },
  { type: "accordion", title: "Accordion", schema: accordionSchema, Component: Accordion, category: "container", position: { width: 320, height: 240 } },
  // Input batch 3 (HMV-013, HMV-028, HMV-053, HMV-059, HMV-073, HMP-057)
  { type: "momentary-button", title: "Momentary button", schema: momentaryButtonSchema, Component: MomentaryButton, category: "input", position: { width: 160, height: 40 } },
  { type: "state-toggle", title: "State toggle", schema: stateToggleSchema, Component: StateToggle, category: "input", position: { width: 200, height: 40 } },
  { type: "formatted-text-field", title: "Formatted text field", schema: formattedTextFieldSchema, Component: FormattedTextField, category: "input", position: { width: 160, height: 56 } },
  { type: "spinner", title: "Spinner", schema: spinnerSchema, Component: Spinner, category: "input", position: { width: 120, height: 40 } },
  { type: "timer", title: "Timer", schema: timerSchema, Component: Timer, category: "input", position: { width: 40, height: 40 } },
  { type: "barcode-scanner-input", title: "Barcode scanner input", schema: barcodeScannerSchema, Component: BarcodeScannerInput, category: "input", position: { width: 200, height: 48 } },
  // C-18 symbols and navigation
  { type: "pump", title: "Pump", schema: pumpSchema, Component: Pump, category: "display", position: { width: 80, height: 80 } },
  { type: "valve", title: "Valve", schema: valveSchema, Component: Valve, category: "display", position: { width: 80, height: 80 } },
  { type: "motor", title: "Motor", schema: motorSchema, Component: Motor, category: "display", position: { width: 80, height: 80 } },
  { type: "vessel", title: "Vessel", schema: vesselSchema, Component: Vessel, category: "display", position: { width: 120, height: 160 } },
  { type: "sensor", title: "Sensor", schema: sensorSchema, Component: Sensor, category: "display", position: { width: 80, height: 80 } },
  { type: "cylindrical-tank", title: "Cylindrical tank", schema: cylindricalTankSchema, Component: CylindricalTank, category: "display", position: { width: 120, height: 160 } },
  { type: "horizontal-menu", title: "Horizontal menu", schema: horizontalMenuSchema, Component: HorizontalMenu, category: "navigation", position: { width: 400, height: 40 } },
  { type: "menu-tree", title: "Menu tree", schema: menuTreeSchema, Component: MenuTree, category: "navigation", position: { width: 220, height: 300 } },
  { type: "tree", title: "Tree", schema: treeSchema, Component: Tree, category: "navigation", position: { width: 220, height: 300 } },
];

export function componentRegistry(): ComponentRegistry {
  const map = new Map(componentModules.map((module) => [module.type, module]));
  return {
    get(type: string) {
      return map.get(type);
    },
    list() {
      return componentModules;
    },
  };
}

export function allowedComponentTypes(): Set<string> {
  return new Set(componentModules.map((module) => module.type));
}
