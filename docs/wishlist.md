# Wishlist

Items the soft release does not include. Each line says what it is, why a plant would care, a rough effort, and the status. Effort is a size, not a date: small is a focused change, medium crosses a few modules, large is a new subsystem. Token cost is the same scale for anyone budgeting an agent to build it.

Nothing here is promised. How an item gets paid for is described in [funding-model.md](funding-model.md). Paying funds that item's design, tests, and manual page. It does not buy a private fork of the name ERIC. You can also send a pull request instead of paying.

## I/O and drivers

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| Direct I/O on the PLC engine: EtherCAT, and Modbus from the engine rather than the gateway | A soft-PLC that only sees gateway tags cannot close a fast loop on a local rack. | Large | Not started |
| FINS driver | Omron controllers are common on existing plants. | Medium | Not started |
| MC protocol driver | Mitsubishi controllers, same reason. | Medium | Not started |
| Gateway-to-gateway proxy and store-and-forward across several hops | A remote station should keep history when the path to the central gateway drops, then forward it. | Large | Not started |

## Historian and databases

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| Edge database size settings in the installer | The installer should ask how much disk the local historian (the embedded SQLite file) may use and how many days to keep, next to the CPU and RAM question, so a small edge box never fills its disk. | Medium | Not started |
| Pruning choice when the local database is full | Let the user pick how old data is thinned when the size cap is reached: delete the oldest data, keep every other point, keep every 4th point, or another interval, per tag or for the whole historian. Thinning keeps a long but coarser record instead of losing whole days. | Medium | Not started |
| Disk-usage gauge for the local database | The admin page should show how full the local historian is against its cap and when pruning last ran. | Small | Not started |
| Point-and-click Postgres connection form | Adding an external PostgreSQL connection takes an API call today. A form on the Databases page, with a "forward history to this database" checkbox, keeps it no-code. | Small | Not started |

## PLC

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| Tag history written by the PLC task, linked to the historian | Today history is a gateway feature. A PLC program cannot request a pen. | Medium | Not started |
| Redundant gateways | A single PC is a single failure. | Large | Not started |
| Online change | Downloading a whole program to bump a constant stops the plant. | Large | Not started |
| Full IEC 61131-3 function and function-block set | Programs ported from another controller expect the standard blocks. | Large | Partial: ST, a PID, and a small process library |
| Timers, counters, rising and falling edge | Everyday ladder habits in ST form. | Medium | Partial |
| String and time functions | Recipes and schedules need them. | Medium | Not started |
| Arrays and structures as first-class ST types | A lift station is a structure, not forty loose tags. | Medium | Partial, through gateway UDTs rather than ST structs |
| Object-oriented extensions (methods, interfaces) | Large libraries stay maintainable. | Large | Not started |
| Retain and persistent variables | A setpoint should survive a restart. | Medium | Not started |
| Online monitoring and forcing | Commissioning without a second tool. | Large | Not started |
| Breakpoints | Finding a logic bug on the bench. | Medium | Not started |
| Simulation mode inside the PLC | Run the program with no I/O. | Medium | Not started. The Brownsville example simulates in a gateway timer instead. |
| SFC and FBD editors | Some plants specify those languages. | Large | Not planned for 0.1. ST is the language. |
| Library manager | Versioned block libraries per project. | Medium | Partial: one process library ships with the product |
| Motion | Drives and cams. | Large | Not started |
| Safety | A separate safety controller, not this PLC. | Out of scope | Will not be this product |

## Alarms

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| SMS and voice notification | A night alarm that only paints a screen is missed. | Medium | Not started |
| Graphical alarm pipeline editor | Pipelines are configured, not drawn. | Medium | Not started |
| Priority script functions | A script that pages only on a high priority. | Small | Not started |

## IEC 61131-3 coverage

What this build actually compiles and runs. "Supported" means the editor or the bundled standard library accepts it and the manual describes it. Anything else is not a promise.

| Area | In this build |
| --- | --- |
| Structured Text | Supported |
| Instruction list, ladder, function block diagram, sequential function chart | Not supported |
| `PROGRAM`, `FUNCTION_BLOCK` | Supported |
| `FUNCTION` as its own callable unit, methods, interfaces | Not supported |
| `VAR`, `VAR_INPUT`, `VAR_OUTPUT` | Supported |
| `VAR_IN_OUT`, `VAR_TEMP`, `VAR_GLOBAL`, `VAR_EXTERNAL`, `VAR_ACCESS` | Not a documented path |
| `BOOL`, `INT`, `DINT`, `REAL`, `LREAL` | Supported |
| `SINT`, `USINT`, `UINT`, `UDINT`, `LINT`, `ULINT`, `BYTE`, `WORD`, `DWORD`, `LWORD` | Not in the editor completion |
| `TIME` literals such as `T#200ms` on standard timers | Supported for the timer blocks |
| `DATE`, `TIME_OF_DAY`, `DATE_AND_TIME`, `STRING`, `WSTRING` | Not started |
| Arrays as a general program feature | Not a documented editor feature. Two process blocks use a fixed internal array. |
| `STRUCT`, enumerations | Not supported. Gateway user-defined types are not ST structures. |
| `TON`, `TOF`, `TP` | Supported, standard library |
| `CTU`, `CTD`, `CTUD` | Supported, standard library |
| `R_TRIG`, `F_TRIG`, `SR`, `RS` | Supported, standard library |
| Math and conversion functions in that library | Supported where the compiler accepts the name |
| String functions | Not started |
| Retain and persistent variables | Not supported |
| Online monitoring of watched variables | Partial |
| Forcing and unforcing watched variables | Partial |
| Breakpoints, single step | Not supported |
| Simulation mode inside the engine | Not supported |
| Online change | Not supported |
| Tasks | One cyclic task. No event task, no multiple tasks. |
| Library manager | Not supported. One process library is shipped as source. |
| Motion | Not supported |
| Safety task or safety PLC | Out of scope |

## HMI components still missing

Present today: pump, valve, tank, vessel, motor, and sensor symbols; pipes with named ports; trends; a Leaflet street map and a plain map; buttons; numeric entry; popups and faceplates.

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| Motor and VFD faceplate (speed, current, fault codes) | A motor symbol is not a drive faceplate. | Small | Not started |
| Conveyor | Packaging and discrete lines. | Small | Not started |
| Mixer and agitator | Tanks that are not just a level. | Small | Not started |
| Heat exchanger | Temperature plants. | Small | Not started |
| Compressor | Air and gas. | Small | Not started |
| Fan | HVAC and aeration beyond a speed number. | Small | Not started |
| Filter | A unit the example does not draw. | Small | Not started |
| Gauge or dial | A round instrument beside the numeric display. | Small | Not started |
| Sparkline | A one-pen trace inside a symbol. | Small | Not started |
| Bar graph | Level or percent without a time axis. | Small | Not started |
| Pie chart | A split of a total. | Small | Not started |
| XY chart | One process value against another, not against time. | Medium | Not started |
| Table | A grid of tags or alarm rows the author designs. | Medium | Not started |
| Report layout | A shift report on the HMI. | Medium | Not started |
| Alarm banner | A plant-wide strip, not only an alarm table. | Small | Not started |
| PDF or report viewer | Reading a generated report on the screen. | Medium | Not started |
| Video or camera | A remote site with a camera. | Medium | Not started |
| Recipe manager | Batch plants. | Large | Not started |
| User login on the HMI | The gateway has users. The HMI session sign-in is not an operator keypad login. | Medium | Partial |
| Keypad | Touch entry of a number without a keyboard. | Small | Not started |
| Tank farm | Many tanks from one symbol and a list. | Small | Not started |
| Pipe elbows and reducers as their own symbols | The pipe component bends in the route. Fittings would still help a crowded drawing. | Small | Not started |
| ISA symbol library, complete | The current set covers the example, not the whole standard. | Large | Partial |

## Tests that need a bigger machine

These are not failed tests. They were not run because a laptop cannot host them.

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| 100k to 1M tags | Scan time and memory. | Large, and it needs the hardware | Not run |
| Historian write and query throughput | A fast scan with history on. | Medium | Not run at scale |
| Many HMI clients at once | A control room of screens. | Medium | Not run |
| 1 ms PLC cycle on isolated cores and an RT kernel | The Windows soft-PLC cannot answer this. | Large | Not run |
| Protectli and other industrial PCs | The install on the hardware plants actually buy. | Medium | Not run |
| Soak tests over days | Leaks that an hour hides. | Medium | Not run |
| Failover | Depends on redundancy, which is not built. | Large | Blocked |

## Use-case scenarios

Each scenario is a scripted project, like the wastewater example, that exercises a slice of the product.

| Scenario | What it would test | Status |
| --- | --- | --- |
| Water and wastewater | Already started by the Brownsville example: lift stations, basins, PID aeration, map. | Example exists |
| Oil and gas | Well pads, tank battery, permissives, remote store-and-forward. | Not started |
| Food and beverage batch | Recipes, phases, holds. | Not started. Needs the recipe manager. |
| Packaging line | Discrete motors, jams, counts. | Not started |
| HVAC and building systems | Schedules, setpoints, many small air handlers. | Not started |
| Power and substation | Status bits, double-point switches, no control from the HMI by default. | Not started |
| Discrete manufacturing | Cells, stations, andon, counts. | Not started |

## Funding and support

The model is written for testers in [funding-model.md](funding-model.md) and in the manual. These four pieces are planned for after the beta. Together they are about 20% of a week of agent time, inside a 15–25% band. None of them are built.

| Item | Why it matters | Effort | Status |
| --- | --- | --- | --- |
| Funding page and payment webhooks | A wishlist issue needs a public progress bar and a way to take Stripe, GitHub Sponsors, and Open Collective payments against that goal. | About 4% of a week of agent time | Planned, not built |
| Paid support assistant | A customer buys credit, uploads logs or a project export, and gets a diagnosis from the manual and the source. It stops when the credit runs low and shows the running cost. Files stay private and are deleted after a retention period. | About 10% of a week of agent time | Planned, not built |
| Automated bug filer | When support finds a real defect, it opens a sanitized GitHub issue after the customer agrees, so the community can send a pull request or fund the fix. | About 3% of a week of agent time | Planned, not built |
| Pull-request review automation | A review checks safety and fit with the project before Nick Hepburn decides whether to merge. The CLA still applies. | About 4% of a week of agent time | Planned, not built |
