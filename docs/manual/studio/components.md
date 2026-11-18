# Component catalog

Every type registered in the Control Canvas palette is listed here. Add one from the **Add** tab. The main props are the ones the property editor writes into the component. Bindings and events are the same for all of them: see [Bindings](bindings.md) and [Events and scripts](events-scripts.md).

## Display

| Type | What it shows | Main props |
| --- | --- | --- |
| `label` | Text | `text`, style |
| `numeric` | A number | `value`, `format` |
| `indicator` | On or off | `value` |
| `alarm-status` | One alarm's state | alarm path |
| `image` | A picture | `source` |
| `drawing` | Shapes | drawing document |
| `svg-component` | An SVG | `source` |
| `pipe` | A pipe between ports | waypoints, `color` |
| `pdf-viewer` | A PDF | `source`, page |
| `audio` | A clip | `source` |
| `video-player` | A clip | `source` |
| `inline-frame` | Another page | `url` |
| `map` | A simple map | center, zoom |
| `leaflet-map` | A tile map | center, zoom, markers |
| `analog-indicator` | A moving bar | `value`, min, max |
| `led` | A segmented number | `value` |
| `linear-scale` | A scale | `value`, min, max |
| `thermometer` | A column | `value`, min, max |
| `markdown` | Markdown text | `text` |
| `progress-bar` | A fill | `value`, min, max |
| `icon` | One icon | `name` |
| `sparkline` | A short history | `historyPath` or samples |
| `alarm-status-table` | Live alarms | filters |
| `alarm-journal-table` | Journal rows | time range, filters |
| `tag-browse` | A tag tree | root path |
| `pump`, `valve`, `motor`, `sensor` | Equipment state | `state` or `value` |
| `vessel`, `cylindrical-tank` | A level | `level`, min, max |

## Input

| Type | What the operator does | Main props |
| --- | --- | --- |
| `button` | Clicks | `text`, events |
| `one-shot-button` | Pulses once | `text`, write target |
| `momentary-button` | Holds while pressed | `text` |
| `multi-state-button` | Cycles states | `states` |
| `toggle` | On or off | `value` |
| `state-toggle` | Named states | `states` |
| `checkbox` | A tick | `value` |
| `text-input` | A line of text | `value` |
| `text-area` | Several lines | `value` |
| `formatted-text-field` | A pattern | `format` |
| `password-field` | A secret | `value` (not shown) |
| `numeric-entry` | A number, with optional limits | `value`, min, max |
| `spinner` | Step a number | `value`, step |
| `dropdown` | Pick one | `options` |
| `radio-group` | Pick one | `options` |
| `slider` | Drag | `value`, min, max |
| `datetime-input` | Type a time | `value` |
| `datetime-picker` | Pick a time | `value` |
| `file-upload` | Send a file | handler |
| `barcode-scanner-input` | A scan | `value` |
| `form` | Several fields | fields |
| `timer` | A hidden interval | period |

## Charts, containers, navigation

| Type | Role | Main props |
| --- | --- | --- |
| `trend` | One pen | tag, range |
| `time-series-chart` | Several pens | pens, `rangeSeconds` |
| `power-chart` | Live pens | tags |
| `xy-chart` | X against Y | series |
| `pie-chart` | Shares | slices |
| `simple-gauge`, `radial-gauge`, `linear-gauge` | A gauge | `value`, min, max |
| `range-selector` | A time window | range |
| `embedded-view` | Another view | view path |
| `view-canvas` | A nested canvas | view path |
| `flex-repeater` | One view per row | instances |
| `dashboard` | Tiles | tiles |
| `template-canvas` | A template | template |
| `carousel` | Pages | children |
| `accordion` | Stacked panels | panels |
| `link` | A URL | `href` |
| `navigation-drawer` | A side menu | items |
| `horizontal-menu` | A top menu | items |
| `menu-tree` | A drill-down | items |
| `tree` | A selection tree | nodes |

Pipes, maps and trends have their own pages: [Pipes](pipes.md), [Map](map.md), [Trends](trends.md).
