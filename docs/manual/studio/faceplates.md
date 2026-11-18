# Faceplates

A faceplate is an ordinary view opened as a popup. The symbol's click action is a popup action. Its `params` are the tag folder or tag path for that symbol, so one faceplate view serves every pump, valve, or instrument.

`{name}` inside the popup id, title, or a string param is filled from the view that was clicked. A station page whose `name` param is `CinderFlat` opens `pump-CinderFlat-P1` with that station's tags.

## What opens

| Faceplate | Opened from | What it shows |
| --- | --- | --- |
| Pump | a pump symbol | Running, fault, HOA, amps, speed, run hours, starts, and a trend of amps, speed, and flow. Hand runs the pump. Off stops it. |
| Valve | a valve symbol | Open or closed, and a command that writes the valve. A station isolation valve follows its pump. |
| Instrument | LIT, FIT, PIT, AIT | The live value, the configured alarm limits, and a trend. Those limits are the alarm configuration, not separate tags, so the faceplate does not write them. |
| Wet well | a wet well | Level, inflow, discharge, and editable lead and lag setpoints, with a trend. |
| Basin | a basin | Level, inflow, outflow, and a trend. |
| Station summary | a station on the city or network page | Level, flow, which pumps are running, comms, a short trend, and a link to the station page. |
| Dissolved oxygen | the aeration blower | Process value, an editable setpoint, blower speed, and a trend of setpoint, value, and output. The demo loop's tuning is fixed. |

On a wide screen the popup is a draggable window and several can be open. Below 760px wide it becomes a sheet across the bottom of the screen. Close dismisses it. Escape closes the top one.

The Brownsville drawings keep the symbol, its tag, and one value. HOA, faults, and setpoints are on the faceplate, not on the piping.
