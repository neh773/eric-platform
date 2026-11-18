# Trends

A trend plots tag history on the view. It is a time chart, not a bar, pie, or XY plot. Those other charts are on the wishlist.

## Add a trend

1. Place a chart component on the view.
2. Bind its pens to history tag paths. A pen that uses `{name}` takes that part of the page address, so one station page can plot that station's level.
3. Set the time window. A short window shows the last few minutes. A long window shows the shift.
4. Give level and flow their own axes when the units differ. One axis for both turns the trace into a sawtooth.
5. Open the runtime page. The trace follows the historian. If the trace is empty, the tag has no history samples yet. Turn history on for that tag in the gateway, then wait for a few samples.

## Touch

On a touch screen the chart can be dragged and the window can be pinched where the component supports it. A setpoint the operator may change is a numeric entry bound to the setpoint tag, placed next to the chart. The chart itself shows the value. It does not write it unless that entry is there.

## Power user

History samples come from `GET` on the history route for the tag path. A pen binding can be a property binding to a view parameter named `pens`, which is how a faceplate draws a different set of pens for each symbol that opened it. See [Faceplates](faceplates.md).
