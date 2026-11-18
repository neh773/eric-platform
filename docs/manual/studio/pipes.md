# Pipes

A pipe is a single line between equipment, not a rectangle drawn by hand. In a fixed-coordinate view, each pump, valve, tank, vessel, motor and sensor has named ports. A pipe names the port it leaves and the port it arrives at, and Control Canvas routes it with right-angle bends.

## Ports

| Component | Ports |
| --- | --- |
| Pump | `suction`, `discharge`. `direction: left` mirrors them. |
| Valve | `in`, `out`. `orientation: vertical` rotates them. |
| Cylindrical tank | `inlet`, `outlet`, `overflow`, `bottom`. |
| Vessel | `inlet`, `outlet`, `overflow`, `bottom`. A clarifier or wet well uses these. |
| Motor | `shaft`. |
| Sensor | `tap`, at the bottom of the stem. |

The port sits on the symbol artwork. A non-square frame letterboxes the symbol, and the port follows the artwork.

## Drawing

On the Add tab, **Draw pipe**. Click the first port (it highlights), click any corners you want to keep, then click the port it runs to. Double-click or Enter finishes. The pipe stores `from` and `to` as `componentId.port`. Corners between the ends are `waypoints`.

Dragging a pump or valve re-routes every pipe that names it. The ends stay on the ports. Drag a middle joint in the property editor to hold a corner; the ends snap back to the ports.

`from` and `to` can also be typed in the property editor. A free end uses `fromPoint` or `toPoint` (`{x, y}` in the view) when the line starts or ends at a tee rather than a symbol.

## How it draws

The line is one SVG path: constant width, round or mitered corners, and an optional outline. `lineStyle` is `process` (solid), `secondary` (thinner solid) or `signal` (dashed). `tee` draws a junction dot at `start`, `end` or `both`.

Pipes are painted under equipment, and they do not take clicks, so a pump under a crossing stays usable.

Bind `flow` to a numeric tag. A value above zero animates along the line; a negative value animates back toward `from`; zero holds still. A boolean binding still animates forward while true. The animation stops when the browser asks for reduced motion.

A pipe with no `from` or `to` still draws the `points` list, which is how older views keep working.
