# Brownsville collection (fictional example)

This is an optional example. It is not part of the gateway, and it is not loaded until you import it. See `docs/manual/examples/brownsville.md`.

```
./import.ps1
```

A demonstration wastewater collection and treatment system for a made-up Brownsville. Site names, flows, and equipment are invented. Nothing here is a real utility, and no vendor names are used.

Twelve lift stations in four districts pump through force mains into three equalization basins, which feed two treatment plants:

| District | Stations | Downstream |
| --- | --- | --- |
| North Levee | CinderFlat, SaltCedar | NorthBasin |
| West Drain | MileMarker, OldLevee, WestDrain | NorthBasin or CentralBasin |
| Midtown | CanalStreet, EastGate, MidtownVault | CentralBasin or PortBasin |
| Harbor and South Flats | NorthDitch, SouthFlume, RailroadSpur, BayouEnd | PortBasin |

NorthBasin and CentralBasin flow to ResacaBend. PortBasin flows to LagunaWorks.

## What is running

The gateway timer `brownsville-sim` (`simulate.py`) runs the collection every two seconds. Each tick is four seconds of process time, so a trend moves at about twice real time.

- Inflow follows a diurnal curve, with a storm about every four minutes.
- Wet wells fill and draw down. Pumps run lead, lag, and standby from the level setpoints on the station screen, and the lead pump alternates after each cycle.
- One station at a time takes a pump fault. During a storm that station can fill to a high-high alarm, and PortBasin can reach its overflow alarm.
- Basin outflow is the plant influent. Dissolved oxygen, blower speed, chlorine residual, turbidity, and pH follow that flow.
- The soft-PLC program `PlantDo` is deployed on this gateway and scans an aeration PID (`ERIC_PID`). The city mass balance stays in the gateway timer so every station, basin, and plant shares one clock.

Levels and flows are historized on the local provider.

## Open it

The gateway is http://127.0.0.1:8090. Sign in as `admin`. The password is the `ERIC_ADMIN_PASSWORD` value already stored in the ERIC Gateway service environment. It is not printed here.

| Screen | Address |
| --- | --- |
| City | http://127.0.0.1:8090/hmi/brownsville/ |
| Lift station (the name is the station) | http://127.0.0.1:8090/hmi/brownsville/station/CinderFlat |
| Equalization basin | http://127.0.0.1:8090/hmi/brownsville/tank/NorthBasin |
| Treatment plant | http://127.0.0.1:8090/hmi/brownsville/plant/ResacaBend |
| Headworks | http://127.0.0.1:8090/hmi/brownsville/headworks |
| Aeration | http://127.0.0.1:8090/hmi/brownsville/aeration |
| Clarifiers | http://127.0.0.1:8090/hmi/brownsville/clarifiers |
| Disinfection | http://127.0.0.1:8090/hmi/brownsville/disinfection |
| Alarms | http://127.0.0.1:8090/hmi/brownsville/alarms |
| Trends | http://127.0.0.1:8090/hmi/brownsville/trends |

The same project opens in Control Canvas with:

```
"C:\Program Files\Control Canvas\Control Canvas.exe" --gateway http://127.0.0.1:8090
```

The designer in a browser is http://127.0.0.1:8090/designer. Choose the `brownsville` project.

A left-hand menu is on every screen. The lift station, basin, and plant screens are one template each; the page address selects the equipment.

## Load it again

`build.ps1` creates the project, the tag types, the instances, the timer, the historian, the screens, and the PID program on a gateway that is already running:

```
powershell -NoProfile -ExecutionPolicy Bypass -File examples/brownsville-wastewater/import.ps1
```

`export/` is the config the gateway saved: tag types, the timer, history settings, and the HMI. `shoot.ps1` writes screenshots next to itself, under `screenshots/`.
