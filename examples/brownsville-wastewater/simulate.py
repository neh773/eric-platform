import math
import time

# One tick is a few seconds of process time, so a 15-minute trend shows a
# handful of wet-well cycles instead of a sawtooth.
DT_MIN = 4.0 / 60.0
GAL_PER_FT = 7.48052

STATIONS = [
    ("CinderFlat", "NorthBasin", 380.0, 70.0, 2),
    ("SaltCedar", "NorthBasin", 460.0, 80.0, 2),
    ("MileMarker", "NorthBasin", 310.0, 64.0, 2),
    ("OldLevee", "CentralBasin", 540.0, 90.0, 3),
    ("CanalStreet", "CentralBasin", 420.0, 74.0, 2),
    ("EastGate", "CentralBasin", 360.0, 68.0, 2),
    ("WestDrain", "CentralBasin", 610.0, 96.0, 3),
    ("NorthDitch", "PortBasin", 290.0, 60.0, 2),
    ("SouthFlume", "PortBasin", 480.0, 82.0, 2),
    ("MidtownVault", "PortBasin", 350.0, 66.0, 2),
    ("RailroadSpur", "PortBasin", 570.0, 88.0, 3),
    ("BayouEnd", "PortBasin", 440.0, 76.0, 2),
]
TANKS = [
    ("NorthBasin", "ResacaBend", 240.0),
    ("CentralBasin", "ResacaBend", 280.0),
    ("PortBasin", "LagunaWorks", 320.0),
]
PLANTS = ["ResacaBend", "LagunaWorks"]
PUMPS = ["P1", "P2", "P3"]


def qval(q):
    try:
        value = q.value
    except:
        value = q
    return value


def fnum(q, fallback):
    value = qval(q)
    if value is None:
        return fallback
    try:
        return float(value)
    except:
        return fallback


def flag(q):
    value = qval(q)
    return bool(value)


def clamped(value, low, high):
    if value < low:
        return low
    if value > high:
        return high
    return value


try:
    now = time.time()
    hour = (now % 86400.0) / 3600.0
    diurnal = 0.60 + 0.40 * math.sin((hour - 8.0) * 2.0 * math.pi / 24.0)
    storm = 2.6 if (int(now) % 240) < 28 else 1.0
    victim = int(now / 45.0) % len(STATIONS)

    paths = []
    for name, downstream, base, area, count in STATIONS:
        root = "[default]Brownsville/Stations/" + name
        for suffix in ("/Level", "/LeadIndex", "/LeadOn", "/LeadOff", "/LagOn", "/LagOff", "/StandbyOn", "/StandbyOff"):
            paths.append(root + suffix)
        for pump in PUMPS:
            for suffix in ("/Run", "/Fault", "/Hoa", "/Runtime", "/Starts"):
                paths.append(root + "/" + pump + suffix)
    for name, plant, area in TANKS:
        root = "[default]Brownsville/Tanks/" + name
        for suffix in ("/Level", "/OutletValve"):
            paths.append(root + suffix)
    for name in PLANTS:
        root = "[default]Brownsville/Plants/" + name
        paths.append(root + "/Do")
        paths.append(root + "/DoSp")
        paths.append(root + "/BlowerSpeed")

    got = system.tag.readBlocking(paths)
    cursor = 0
    out_paths = []
    out_values = []

    def put(path, value):
        out_paths.append(path)
        out_values.append(value)

    into_tank = {}
    for name, plant, area in TANKS:
        into_tank[name] = 0.0

    for index in range(len(STATIONS)):
        name, downstream, base, area, count = STATIONS[index]
        root = "[default]Brownsville/Stations/" + name
        level = fnum(got[cursor], 6.0)
        cursor += 1
        lead = int(fnum(got[cursor], 0))
        cursor += 1
        lead_on = fnum(got[cursor], 7.5)
        cursor += 1
        lead_off = fnum(got[cursor], 4.0)
        cursor += 1
        lag_on = fnum(got[cursor], 10.5)
        cursor += 1
        lag_off = fnum(got[cursor], 6.5)
        cursor += 1
        standby_on = fnum(got[cursor], 13.0)
        cursor += 1
        standby_off = fnum(got[cursor], 9.0)
        cursor += 1
        if lead_on <= lead_off:
            lead_on, lead_off = 7.5, 4.0
        pumps = []
        for pump in PUMPS:
            run = flag(got[cursor])
            cursor += 1
            fault = flag(got[cursor])
            cursor += 1
            hoa = int(fnum(got[cursor], 2))
            cursor += 1
            runtime = fnum(got[cursor], 0.0)
            cursor += 1
            starts = int(fnum(got[cursor], 0))
            cursor += 1
            pumps.append({"run": run, "fault": fault, "hoa": hoa, "runtime": runtime, "starts": starts})

        phase = math.sin(now / 17.0 + index)
        inflow = base * diurnal * storm * (0.92 + 0.08 * phase)
        power = not (storm > 2.0 and index == victim)
        comms = index != victim
        generator = (not power) or (storm > 2.0 and index == (victim + 3) % len(STATIONS))
        running_now = 0
        for pump_index in range(count):
            if pumps[pump_index]["run"] and pumps[pump_index]["hoa"] == 2:
                running_now += 1
        if level >= standby_on and count >= 3:
            need = 3
        elif level >= lag_on and count >= 2:
            need = 2
        elif level >= lead_on:
            need = 1
        elif level <= lead_off:
            need = 0
        elif level <= lag_off:
            need = 1 if running_now >= 1 else 0
        elif level <= standby_off:
            need = 2 if running_now >= 2 else running_now
        else:
            need = running_now

        runs = [False, False, False]
        faults = [False, False, False]
        if power:
            order = []
            step = 0
            while step < count:
                order.append((lead + step) % count)
                step += 1
            hand = []
            for pump_index in range(count):
                # A fault stays until the faceplate clears it, except a short pulse on the rotating station.
                faulted = pumps[pump_index]["fault"]
                if index == victim and pump_index == 1 and int(now) % 180 < 2:
                    faulted = True
                faults[pump_index] = faulted
                if faulted or pumps[pump_index]["hoa"] == 0:
                    continue
                if pumps[pump_index]["hoa"] == 1:
                    runs[pump_index] = True
                    hand.append(pump_index)
            auto_need = need - len(hand)
            if auto_need < 0:
                auto_need = 0
            chosen = []
            for pump_index in order:
                if pumps[pump_index]["hoa"] == 2 and not faults[pump_index] and pumps[pump_index]["run"]:
                    if len(chosen) < auto_need:
                        chosen.append(pump_index)
            if len(chosen) < auto_need:
                for pump_index in order:
                    if pumps[pump_index]["hoa"] == 2 and not faults[pump_index] and pump_index not in chosen:
                        chosen.append(pump_index)
                        if len(chosen) >= auto_need:
                            break
            for pump_index in chosen:
                runs[pump_index] = True
            if need == 0 and running_now > 0:
                lead = (lead + 1) % count

        flow = 0.0
        for pump_index in range(3):
            pump = pumps[pump_index]
            run = runs[pump_index] if pump_index < count else False
            faulted = faults[pump_index] if pump_index < count else False
            speed = 100.0 if run else 0.0
            amps = (26.0 + speed * 0.18) if run else 0.0
            runtime = pump["runtime"] + (DT_MIN / 60.0 if run else 0.0)
            starts = pump["starts"] + (1 if run and not pump["run"] else 0)
            if run:
                flow += 650.0
            prefix = root + "/" + PUMPS[pump_index]
            put(prefix + "/Run", run)
            put(prefix + "/Fault", faulted)
            put(prefix + "/Speed", speed)
            put(prefix + "/Amps", amps)
            put(prefix + "/Runtime", runtime)
            put(prefix + "/Starts", starts)
        gallons = (inflow - flow) * DT_MIN
        level = clamped(level + gallons / (area * GAL_PER_FT), 0.0, 18.0)
        pressure = (6.0 + flow / 85.0) if flow > 0.0 else 0.0
        put(root + "/Level", level)
        put(root + "/Volume", level * area * GAL_PER_FT)
        put(root + "/Inflow", inflow)
        put(root + "/Flow", flow)
        put(root + "/DischargePressure", pressure)
        put(root + "/LeadIndex", lead)
        put(root + "/PowerOk", power)
        put(root + "/Generator", generator)
        put(root + "/CommsOk", comms)
        put("[default]Brownsville/Mains/" + name + "/Flow", flow)
        put("[default]Brownsville/Mains/" + name + "/Pressure", pressure)
        into_tank[downstream] = into_tank.get(downstream, 0.0) + flow

    into_plant = {"ResacaBend": 0.0, "LagunaWorks": 0.0}
    for index in range(len(TANKS)):
        name, plant, area = TANKS[index]
        root = "[default]Brownsville/Tanks/" + name
        level = fnum(got[cursor], 8.0)
        cursor += 1
        outlet = flag(got[cursor])
        cursor += 1
        inflow = into_tank.get(name, 0.0)
        # The port basin outlet closes through a storm so the overflow alarm can appear.
        if name == "PortBasin" and storm > 2.0:
            outlet = False
        outflow = 2400.0 if outlet and level > 6.0 else 0.0
        if name == "CentralBasin":
            outflow = 2800.0 if outlet and level > 6.0 else 0.0
        level = clamped(level + (inflow - outflow) * DT_MIN / (area * GAL_PER_FT), 0.0, 16.0)
        put(root + "/Level", level)
        put(root + "/Volume", level * area * GAL_PER_FT)
        put(root + "/Inflow", inflow)
        put(root + "/Outflow", outflow)
        put(root + "/OutletValve", outlet)
        into_plant[plant] = into_plant.get(plant, 0.0) + outflow

    for name in PLANTS:
        root = "[default]Brownsville/Plants/" + name
        do = fnum(got[cursor], 2.0)
        cursor += 1
        sp = fnum(got[cursor], 2.0)
        cursor += 1
        speed = fnum(got[cursor], 45.0)
        cursor += 1
        influent = into_plant.get(name, 0.0)
        if sp < 0.5:
            sp = 2.0
        speed = clamped(speed + 6.0 * (sp - do) - (influent - 1500.0) / 800.0, 20.0, 100.0)
        target = 0.4 + speed / 35.0 - influent / 9000.0
        do = clamped(do + 0.2 * (target - do), 0.2, 6.0)
        residual = clamped(1.55 - influent / 9000.0, 0.1, 2.0)
        turbidity = 1.05 + influent / 3200.0
        put(root + "/InfluentFlow", influent)
        put(root + "/BarScreenDp", 0.25 + influent / 4000.0)
        put(root + "/BarScreenRun", influent > 10.0)
        put(root + "/GritFlow", influent * 0.995)
        put(root + "/PrimaryFlow", influent * 0.97)
        put(root + "/PrimaryLevel", clamped(7.2 + influent / 5000.0, 6.0, 12.0))
        put(root + "/Do", do)
        put(root + "/BlowerRun", speed > 25.0)
        put(root + "/BlowerSpeed", speed)
        put(root + "/AirFlow", speed * 42.0)
        put(root + "/SecondaryLevel", clamped(9.0 + influent / 8000.0, 8.0, 13.0))
        put(root + "/RasFlow", influent * 0.45)
        put(root + "/WasFlow", influent * 0.018)
        put(root + "/UvRun", True)
        put(root + "/ChlorineResidual", residual)
        put(root + "/EffluentFlow", influent * 0.99)
        put(root + "/Turbidity", turbidity)
        put(root + "/Ph", 7.15 - influent / 80000.0)
        put(root + "/PlantAlarm", turbidity > 4.0 or do < 1.0 or residual < 0.4)

    total = 0.0
    for name, downstream, base, area, count in STATIONS:
        total += base * diurnal * storm
    put("[default]Brownsville/Storm", storm)
    put("[default]Brownsville/Diurnal", diurnal)
    put("[default]Brownsville/TotalInflow", total)
    put("[default]Brownsville/ScriptOk", True)
    put("[default]Brownsville/ScriptError", "")
    system.tag.writeBlocking(out_paths, out_values)
except Exception as ex:
    system.tag.writeBlocking(["[default]Brownsville/ScriptOk", "[default]Brownsville/ScriptError"], [False, str(ex)])
