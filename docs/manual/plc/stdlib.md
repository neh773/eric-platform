# Standard library

The compiler ships with the IEC 61131-3 standard library as its own library file. ERIC does not reimplement those blocks. Check program will accept a block from that library and reject a name it does not know.

## Timers

| Block | What it does |
| --- | --- |
| `TON` | On-delay. `Q` becomes true after `IN` has stayed true for `PT`. |
| `TOF` | Off-delay. `Q` stays true for `PT` after `IN` goes false. |
| `TP` | Pulse. `Q` stays true for `PT` after `IN` rises, then clears. |

`PT` is a time. `ET` is the elapsed time. Call the block every scan. A timer that is not called does not advance.

```
VAR
    Arm : TON;
END_VAR
Arm(IN := Start, PT := T#200ms);
```

## Counters

| Block | What it does |
| --- | --- |
| `CTU` | Counts rising edges of `CU` up toward `PV`. `Q` is true at the preset. |
| `CTD` | Counts down from `PV`. |
| `CTUD` | Counts up and down. |

`R` resets an up counter. `LD` loads a down counter.

## Edge and bistable

| Block | What it does |
| --- | --- |
| `R_TRIG` | One scan of `Q` when `CLK` rises. |
| `F_TRIG` | One scan of `Q` when `CLK` falls. |
| `SR` | Set-dominant latch. `S1` sets, `R` resets. |
| `RS` | Reset-dominant latch. |

## Math, selection, and type conversion

The same library provides the usual numeric functions (`ABS`, `SQRT`, `MIN`, `MAX`, `LIMIT`, and the trigonometric functions the library includes) and the conversion functions the compiler already uses inside the process library, such as `INT_TO_LREAL` and `LREAL_TO_INT`.

## Strings and time

String functions and a full set of time-of-day functions are not part of the documented editor. If Check program rejects a name, that name is not in this build. The [wishlist](../wishlist.md) lists string and time functions as not started.

## Power user

The Windows installer places the library next to `plc.exe` under the PLC folder. It is LGPL and it is not linked into the gateway. The compiler is started as a separate program. A program that only needs `ERIC_PID` still compiles against that library because the process blocks are Structured Text on top of it.
