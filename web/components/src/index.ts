import "./components.css";

export { formatNumber, readBoolean, readNumber, readString } from "./read";
export {
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
export { AlarmStatus, Checkbox, Dropdown, Image, Slider, Table } from "./extra";
export { Button, Indicator, Label, LinearGauge, NumericDisplay, TextInput, Toggle, TrendLine } from "./widgets";
export { allowedComponentTypes, componentModules, componentRegistry, defaultsFromSchema } from "./registry";
// C-11 batch 1
import "./components2.css";
export {
  dateTimeInputSchema,
  dateTimePickerSchema,
  iconSchema,
  linkSchema,
  multiStateButtonSchema,
  oneShotButtonSchema,
  passwordFieldSchema,
  progressBarSchema,
  radioGroupSchema,
  simpleGaugeSchema,
  sparklineSchema,
  textAreaSchema,
} from "./schemas";
export { DateTimeInput, DateTimePicker, FileUpload, Link, linkTarget, MultiStateButton, OneShotButton, PasswordField, RadioGroup, TextArea } from "./inputs2";
export { Icon, ProgressBar, SimpleGauge, Sparkline } from "./display2";
export { iconGlyph, iconNames, iconSet } from "./icons";
export type { IconGlyph, IconName, IconShape } from "./icons";
export { AlarmStatusTable } from "./AlarmStatusTable";
export { PowerChart, TimeSeriesChart, parsePens } from "./charts";
export { GatewayClient, GatewayError, GatewayProvider, useGatewayClient } from "./gateway";
export type { GatewayClientOptions } from "./gateway";
export { alarmApi, filterAlarms, parseAlarms, sortAlarms } from "./alarms";
export type { AlarmEvent } from "./alarms";
// C-14
export { alarmJournalTableSchema } from "./schemas";
export { AlarmJournalTable } from "./alarmJournal";
export { alarmJournalApi, journalQuery, parseJournalPage } from "./alarmJournalApi";
export type { JournalEvent, JournalPage } from "./alarmJournalApi";
// C-18 symbols and navigation
import "./navigation.css";
export { horizontalMenuSchema, menuTreeSchema, treeSchema } from "./schemas";
export { HorizontalMenu, itemAt, MenuTree, navEvents, parseNavItems, readExpanded, Tree, visibleRows } from "./navigation";
export type { NavExpandDetail, NavItem, NavSelectDetail } from "./navigation";
export * from "./symbols";
export { ReportBarChart, ReportPreview, ReportTable, ReportTimeSeriesChart, REPORT_PALETTE, contentWidth } from "./report/ReportPreview";
export type { ReportPreviewProps } from "./report/ReportPreview";
export { DEFAULT_TIMESTAMP_PATTERN, formatCell, formatReportNumber, formatTimestamp } from "./report/format";
export type { CellFormat, FormatKind } from "./report/format";
export { DEFAULT_MARGIN, effectiveComponents, LAYOUT_VERSION, LayoutError, MAX_MARGIN, MIN_MARGIN, PAGE_SIZES, parseLayout } from "./report/layout";
export type { BarSpec, ComponentSpec, PageMargins, PageSizeName, ReportLayout, TableSpec, TimeSeriesSpec } from "./report/layout";
export { numericAxis, timeAxis } from "./report/axes";
export type * from "./report/types";
// C-15 containers and embedding
import "./containers.css";
export {
  Accordion,
  accordionSchema,
  breakpointContainerSchema,
  Carousel,
  carouselSchema,
  clampPane,
  containerModules,
  FlexRepeater,
  flexRepeaterSchema,
  listError,
  readAccordionItems,
  readList,
  readSlides,
  repeaterInstances,
  splitContainerSchema,
  stepPane,
  toggleAccordion,
} from "./containers";
export type { AccordionItem, ContainerModule, FlexPosition, RepeaterInstance, Slide } from "./containers";
export { formatNumericEntry, NumericEntryField, numericEntrySchema, parseNumericEntry } from "./numericEntry";
export type { NumericEntryOptions, NumericEntryType, ParsedEntry } from "./numericEntry";
// Display palette media (HMP-031, HMP-033, HMP-035, HMP-042)
import "./media.css";
export { Audio, audioSchema, buildDrawing, Drawing, drawingAttribute, drawingSchema, mediaSource, PdfViewer, pdfFrameSource, pdfViewerSchema, Video, videoSchema } from "./media";
// Moving analog indicator, form, navigation drawer, inline frame (HMP-043, HMP-064, HMP-109, HMP-024)
import "./hmiPatterns.css";
export { AnalogIndicator, analogBands, analogIndicatorSchema, analogState, readLimits } from "./analogIndicator";
export type { AnalogBand, AnalogLimits, AnalogState } from "./analogIndicator";
export { Form, formSchema, initialDraft, readFormFields, validateForm } from "./form";
export type { FormDraft, FormField, FormFieldType, FormResult } from "./form";
export { NavigationDrawer, navigationDrawerSchema } from "./drawer";
export { FRAME_PERMISSIONS, frameSandbox, InlineFrame, inlineFrameSchema } from "./inlineFrame";
// HMP-045
export { MapView, mapSchema, project, unproject, tileAddress, readLayers, readMarkers, readShapes } from "./map";
export type { MapLayer, MapMarker, MapShape } from "./map";
// HMP-019
export { Dashboard, dashboardSchema, addItem, moveItem, readAvailable, readLayout, resizeItem } from "./dashboard";
export type { DashboardItem, DashboardWidget } from "./dashboard";
// Imported SVG components (HMV-151)
export { SvgComponent, svgComponentSchema, svgParts } from "./svgComponent";
export { importSvg, SVG_IMPORT_MAX_BYTES } from "./svgImport";
export type { SvgElementInfo, SvgElementKind, SvgImport } from "./svgImport";
// Template canvas (HMV-133)
export { canvasInstances, gridFrame, TemplateCanvas, templateCanvasSchema } from "./templateCanvas";
export type { CanvasFrame, CanvasInstance } from "./templateCanvas";
// Flow pipes (HMP-128)
export { parsePipeEnd, parsePipePoints, Pipe, pipeGeometry, pipePadding, pipeSchema, resolvePipeRoute, scenePort } from "./pipe";
export type { PipeEndRef, PipeGeometry, PipePoint, ScenePort } from "./pipe";
export { portInFrame, portsFor } from "./ports";
export type { FrameBox, PortExit, SymbolPort } from "./ports";
export { orthogonalRoute, routePipe } from "./pipeRoute";
export type { RoutePoint } from "./pipeRoute";
// Input batch 3 (HMV-013, HMV-028, HMV-053, HMV-059, HMV-073, HMP-057)
export {
  BarcodeScannerInput,
  barcodeScannerSchema,
  checkPattern,
  FormattedTextField,
  formattedTextFieldSchema,
  matchesMask,
  MomentaryButton,
  momentaryButtonSchema,
  Spinner,
  spinnerSchema,
  StateToggle,
  stateToggleSchema,
  stepValue,
  Timer,
  timerSchema,
} from "./inputs3";
