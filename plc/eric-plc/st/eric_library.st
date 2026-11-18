(* Process library. Apache-2.0. DT is the task period in seconds. *)

FUNCTION_BLOCK ERIC_AI_SCALE
VAR_INPUT
    RAW : LREAL; RAW_MIN : LREAL; RAW_MAX : LREAL; EU_MIN : LREAL; EU_MAX : LREAL; CLAMP : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL; BAD : BOOL;
END_VAR
VAR
    span : LREAL; eu : LREAL; lo : LREAL; hi : LREAL; raw_lo : LREAL; raw_hi : LREAL;
END_VAR
    span := RAW_MAX - RAW_MIN;
    IF span = 0.0 THEN
        OUT := EU_MIN;
        BAD := TRUE;
    ELSE
        eu := EU_MAX - EU_MIN;
        OUT := EU_MIN + (RAW - RAW_MIN) / span * eu;
        IF RAW_MIN < RAW_MAX THEN raw_lo := RAW_MIN; raw_hi := RAW_MAX; ELSE raw_lo := RAW_MAX; raw_hi := RAW_MIN; END_IF;
        BAD := (RAW < raw_lo) OR (RAW > raw_hi);
    END_IF;
    IF CLAMP THEN
        IF EU_MIN < EU_MAX THEN lo := EU_MIN; hi := EU_MAX; ELSE lo := EU_MAX; hi := EU_MIN; END_IF;
        IF OUT < lo THEN OUT := lo; END_IF;
        IF OUT > hi THEN OUT := hi; END_IF;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_ALARM_LIMITS
VAR_INPUT
    PV : LREAL; HH : LREAL; H : LREAL; L : LREAL; LL : LREAL; DEADBAND : LREAL; DELAY : LREAL; DT : LREAL;
END_VAR
VAR_OUTPUT
    HH_ACT : BOOL; H_ACT : BOOL; L_ACT : BOOL; LL_ACT : BOOL;
END_VAR
VAR
    hh_t : LREAL; h_t : LREAL; l_t : LREAL; ll_t : LREAL; dead : LREAL;
END_VAR
    IF DEADBAND < 0.0 THEN dead := 0.0; ELSE dead := DEADBAND; END_IF;
    IF HH_ACT THEN
        IF PV < (HH - dead) THEN HH_ACT := FALSE; END_IF;
    ELSIF PV >= HH THEN
        hh_t := hh_t + DT;
        IF DELAY <= 0.0 OR hh_t >= DELAY THEN HH_ACT := TRUE; hh_t := 0.0; END_IF;
    ELSE
        hh_t := 0.0;
    END_IF;
    IF H_ACT THEN
        IF PV < (H - dead) THEN H_ACT := FALSE; END_IF;
    ELSIF PV >= H THEN
        h_t := h_t + DT;
        IF DELAY <= 0.0 OR h_t >= DELAY THEN H_ACT := TRUE; h_t := 0.0; END_IF;
    ELSE
        h_t := 0.0;
    END_IF;
    IF L_ACT THEN
        IF PV > (L + dead) THEN L_ACT := FALSE; END_IF;
    ELSIF PV <= L THEN
        l_t := l_t + DT;
        IF DELAY <= 0.0 OR l_t >= DELAY THEN L_ACT := TRUE; l_t := 0.0; END_IF;
    ELSE
        l_t := 0.0;
    END_IF;
    IF LL_ACT THEN
        IF PV > (LL + dead) THEN LL_ACT := FALSE; END_IF;
    ELSIF PV <= LL THEN
        ll_t := ll_t + DT;
        IF DELAY <= 0.0 OR ll_t >= DELAY THEN LL_ACT := TRUE; ll_t := 0.0; END_IF;
    ELSE
        ll_t := 0.0;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_MOTOR
VAR_INPUT
    HAND : BOOL; AUTO : BOOL; HAND_CMD : BOOL; AUTO_CMD : BOOL; PERMISSIVE : BOOL; TRIP : BOOL;
    FEEDBACK : BOOL; FAIL_TIME : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    RUN : BOOL; FAIL_START : BOOL;
END_VAR
VAR
    cmd : BOOL; timer : LREAL;
END_VAR
    IF RESET THEN FAIL_START := FALSE; timer := 0.0; END_IF;
    IF TRIP OR NOT PERMISSIVE OR FAIL_START THEN
        cmd := FALSE;
    ELSIF HAND THEN
        cmd := HAND_CMD;
    ELSIF AUTO THEN
        cmd := AUTO_CMD;
    ELSE
        cmd := FALSE;
    END_IF;
    IF cmd AND NOT FEEDBACK AND NOT FAIL_START THEN
        timer := timer + DT;
        IF FAIL_TIME <= 0.0 OR timer >= FAIL_TIME THEN FAIL_START := TRUE; cmd := FALSE; END_IF;
    ELSE
        timer := 0.0;
    END_IF;
    RUN := cmd AND NOT FAIL_START;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_VALVE
VAR_INPUT
    HAND : BOOL; AUTO : BOOL; HAND_OPEN : BOOL; HAND_CLOSE : BOOL; AUTO_OPEN : BOOL;
    PERMISSIVE : BOOL; TRIP : BOOL; OPEN_FB : BOOL; CLOSE_FB : BOOL;
    FAIL_TIME : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    OPEN_OUT : BOOL; CLOSE_OUT : BOOL; FAIL_OPEN : BOOL; FAIL_CLOSE : BOOL;
END_VAR
VAR
    want_open : BOOL; open_t : LREAL; close_t : LREAL;
END_VAR
    IF RESET THEN FAIL_OPEN := FALSE; FAIL_CLOSE := FALSE; open_t := 0.0; close_t := 0.0; END_IF;
    IF TRIP OR NOT PERMISSIVE THEN
        want_open := FALSE;
        OPEN_OUT := FALSE;
        CLOSE_OUT := FALSE;
    ELSIF HAND THEN
        want_open := HAND_OPEN AND NOT HAND_CLOSE;
        OPEN_OUT := want_open AND NOT FAIL_OPEN;
        CLOSE_OUT := HAND_CLOSE AND NOT HAND_OPEN AND NOT FAIL_CLOSE;
    ELSIF AUTO THEN
        want_open := AUTO_OPEN;
        OPEN_OUT := AUTO_OPEN AND NOT FAIL_OPEN;
        CLOSE_OUT := NOT AUTO_OPEN AND NOT FAIL_CLOSE;
    ELSE
        OPEN_OUT := FALSE;
        CLOSE_OUT := FALSE;
    END_IF;
    IF OPEN_OUT AND NOT OPEN_FB THEN
        open_t := open_t + DT;
        IF FAIL_TIME <= 0.0 OR open_t >= FAIL_TIME THEN FAIL_OPEN := TRUE; OPEN_OUT := FALSE; END_IF;
    ELSE
        open_t := 0.0;
    END_IF;
    IF CLOSE_OUT AND NOT CLOSE_FB THEN
        close_t := close_t + DT;
        IF FAIL_TIME <= 0.0 OR close_t >= FAIL_TIME THEN FAIL_CLOSE := TRUE; CLOSE_OUT := FALSE; END_IF;
    ELSE
        close_t := 0.0;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_RAMP
VAR_INPUT
    IN : LREAL; RATE : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL;
END_VAR
VAR
    started : BOOL; step : LREAL; diff : LREAL;
END_VAR
    IF RESET OR NOT started THEN
        OUT := IN;
        started := TRUE;
    ELSE
        IF RATE < 0.0 OR DT < 0.0 THEN step := 0.0; ELSE step := RATE * DT; END_IF;
        diff := IN - OUT;
        IF diff > step THEN OUT := OUT + step;
        ELSIF diff < -step THEN OUT := OUT - step;
        ELSE OUT := IN;
        END_IF;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_FILTER
VAR_INPUT
    IN : LREAL; T : LREAL; N : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL; AVG : LREAL;
END_VAR
VAR
    lag : LREAL; alpha : LREAL; count : INT; have : INT; idx : INT; i : INT; sum : LREAL;
    buf : ARRAY [0..15] OF LREAL; started : BOOL;
END_VAR
    IF RESET OR NOT started THEN
        lag := IN;
        started := TRUE;
        have := 0;
        idx := 0;
    ELSIF T <= 0.0 OR DT <= 0.0 THEN
        lag := IN;
    ELSE
        alpha := DT / (T + DT);
        IF alpha < 0.0 THEN alpha := 0.0; END_IF;
        IF alpha > 1.0 THEN alpha := 1.0; END_IF;
        lag := lag + alpha * (IN - lag);
    END_IF;
    count := 1;
    WHILE count < 16 AND INT_TO_LREAL(count + 1) <= N DO
        count := count + 1;
    END_WHILE;
    buf[idx] := lag;
    idx := idx + 1;
    IF idx >= count THEN idx := 0; END_IF;
    IF have < count THEN have := have + 1; END_IF;
    sum := 0.0;
    i := 0;
    WHILE i < have DO
        sum := sum + buf[i];
        i := i + 1;
    END_WHILE;
    IF have > 0 THEN AVG := sum / INT_TO_LREAL(have); ELSE AVG := lag; END_IF;
    OUT := lag;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_TOTALIZER
VAR_INPUT
    RATE : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    TOTAL : LREAL;
END_VAR
    IF RESET THEN
        TOTAL := 0.0;
    ELSIF DT > 0.0 THEN
        TOTAL := TOTAL + RATE * DT;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_RUNTIME
VAR_INPUT
    RUN : BOOL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    HOURS : LREAL; STARTS : LREAL;
END_VAR
VAR
    prev : BOOL;
END_VAR
    IF RESET THEN
        HOURS := 0.0;
        STARTS := 0.0;
        prev := FALSE;
    ELSE
        IF RUN AND NOT prev THEN STARTS := STARTS + 1.0; END_IF;
        IF RUN AND DT > 0.0 THEN HOURS := HOURS + DT / 3600.0; END_IF;
        prev := RUN;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_HYSTERESIS
VAR_INPUT
    IN : LREAL; ON_LEVEL : LREAL; OFF_LEVEL : LREAL;
END_VAR
VAR_OUTPUT
    OUT : BOOL;
END_VAR
VAR
    started : BOOL;
END_VAR
    IF NOT started THEN
        OUT := IN >= ON_LEVEL;
        started := TRUE;
    ELSIF OUT THEN
        IF IN <= OFF_LEVEL THEN OUT := FALSE; END_IF;
    ELSE
        IF IN >= ON_LEVEL THEN OUT := TRUE; END_IF;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_SELECT
VAR_INPUT
    A : LREAL; B : LREAL; HIGH : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL; USE_B : BOOL;
END_VAR
    IF HIGH THEN
        IF A >= B THEN OUT := A; USE_B := FALSE; ELSE OUT := B; USE_B := TRUE; END_IF;
    ELSE
        IF A <= B THEN OUT := A; USE_B := FALSE; ELSE OUT := B; USE_B := TRUE; END_IF;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_SPLIT_RANGE
VAR_INPUT
    IN : LREAL; SPLIT : LREAL;
END_VAR
VAR_OUTPUT
    OUT_A : LREAL; OUT_B : LREAL;
END_VAR
VAR
    x : LREAL; split_at : LREAL;
END_VAR
    x := IN;
    IF x < 0.0 THEN x := 0.0; END_IF;
    IF x > 100.0 THEN x := 100.0; END_IF;
    split_at := SPLIT;
    IF split_at <= 0.0 OR split_at >= 100.0 THEN split_at := 50.0; END_IF;
    IF x <= split_at THEN
        OUT_A := x / split_at * 100.0;
        OUT_B := 0.0;
    ELSE
        OUT_A := 100.0;
        OUT_B := (x - split_at) / (100.0 - split_at) * 100.0;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_TPO
VAR_INPUT
    IN : LREAL; CYCLE : LREAL; DT : LREAL;
END_VAR
VAR_OUTPUT
    OUT : BOOL;
END_VAR
VAR
    elapsed : LREAL; pct : LREAL;
END_VAR
    IF CYCLE <= 0.0 OR DT <= 0.0 THEN
        OUT := FALSE;
    ELSE
        pct := IN;
        IF pct < 0.0 THEN pct := 0.0; END_IF;
        IF pct > 100.0 THEN pct := 100.0; END_IF;
        elapsed := elapsed + DT;
        IF elapsed >= CYCLE THEN elapsed := elapsed - CYCLE; END_IF;
        IF elapsed >= CYCLE THEN elapsed := 0.0; END_IF;
        OUT := (pct >= 100.0) OR (pct > 0.0 AND elapsed < (CYCLE * pct / 100.0));
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_LEADLAG
VAR_INPUT
    IN : LREAL; T_LEAD : LREAL; T_LAG : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL;
END_VAR
VAR
    started : BOOL; prev : LREAL; lead : LREAL; lag : LREAL;
END_VAR
    IF RESET OR NOT started THEN
        OUT := IN;
        prev := IN;
        started := TRUE;
    ELSIF DT <= 0.0 OR T_LAG <= 0.0 THEN
        OUT := IN;
        prev := IN;
    ELSE
        lead := T_LEAD;
        IF lead < 0.0 THEN lead := 0.0; END_IF;
        lag := T_LAG;
        OUT := (IN * (DT + lead) - prev * lead + OUT * lag) / (DT + lag);
        prev := IN;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_DEADTIME
VAR_INPUT
    IN : LREAL; TIME : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL; READY : BOOL;
END_VAR
VAR
    buf : ARRAY [0..63] OF LREAL;
    idx : INT; filled : INT; need : INT; from : INT; i : INT; n : LREAL;
END_VAR
    IF RESET THEN
        idx := 0; filled := 0; READY := FALSE; OUT := 0.0;
        i := 0;
        WHILE i < 64 DO buf[i] := 0.0; i := i + 1; END_WHILE;
    END_IF;
    IF DT <= 0.0 OR TIME <= 0.0 THEN
        OUT := IN;
        READY := TRUE;
    ELSE
        n := TIME / DT;
        IF n < 1.0 THEN need := 1;
        ELSIF n > 64.0 THEN need := 64;
        ELSE need := LREAL_TO_INT(n);
        END_IF;
        IF need < 1 THEN need := 1; END_IF;
        buf[idx] := IN;
        idx := idx + 1;
        IF idx >= 64 THEN idx := 0; END_IF;
        IF filled < 64 THEN filled := filled + 1; END_IF;
        (* `need` scans of delay, not counting the sample just stored. *)
        READY := filled > need;
        IF filled <= need THEN
            OUT := 0.0;
        ELSE
            from := idx - 1 - need;
            WHILE from < 0 DO from := from + 64; END_WHILE;
            OUT := buf[from];
        END_IF;
    END_IF;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_SEQUENCER
VAR_INPUT
    START : BOOL; HOLD : BOOL; RESET : BOOL; TRANS : BOOL;
    T1 : LREAL; T2 : LREAL; T3 : LREAL; T4 : LREAL; DT : LREAL;
END_VAR
VAR_OUTPUT
    STEP : LREAL; ELAPSED : LREAL; DONE : BOOL;
END_VAR
VAR
    step_i : INT; dwell : LREAL;
END_VAR
    IF RESET THEN
        step_i := 0; ELAPSED := 0.0; DONE := FALSE;
    ELSIF step_i = 0 THEN
        IF START THEN step_i := 1; ELAPSED := 0.0; DONE := FALSE; END_IF;
    ELSIF NOT HOLD AND NOT DONE THEN
        IF DT > 0.0 THEN ELAPSED := ELAPSED + DT; END_IF;
        IF step_i = 1 THEN dwell := T1;
        ELSIF step_i = 2 THEN dwell := T2;
        ELSIF step_i = 3 THEN dwell := T3;
        ELSE dwell := T4;
        END_IF;
        IF dwell < 0.0 THEN dwell := 0.0; END_IF;
        IF TRANS AND ELAPSED >= dwell THEN
            IF step_i >= 4 THEN
                DONE := TRUE;
            ELSE
                step_i := step_i + 1;
                ELAPSED := 0.0;
            END_IF;
        END_IF;
    END_IF;
    STEP := INT_TO_LREAL(step_i);
END_FUNCTION_BLOCK

FUNCTION_BLOCK ERIC_ALTERNATOR
VAR_INPUT
    DEMAND : LREAL; AVAIL_1 : BOOL; AVAIL_2 : BOOL; ROTATE : BOOL; ROTATE_AFTER : LREAL; DT : LREAL; RESET : BOOL;
END_VAR
VAR_OUTPUT
    RUN_1 : BOOL; RUN_2 : BOOL; LEAD : LREAL;
END_VAR
VAR
    lead_1 : BOOL; prev : BOOL; hours : LREAL; started : BOOL;
END_VAR
    IF RESET OR NOT started THEN
        lead_1 := TRUE; hours := 0.0; prev := FALSE; started := TRUE;
    END_IF;
    IF ROTATE AND NOT prev THEN
        lead_1 := NOT lead_1;
        hours := 0.0;
    END_IF;
    prev := ROTATE;
    IF ROTATE_AFTER > 0.0 AND hours >= ROTATE_AFTER THEN
        lead_1 := NOT lead_1;
        hours := 0.0;
    END_IF;
    RUN_1 := FALSE;
    RUN_2 := FALSE;
    IF DEMAND >= 1.0 THEN
        IF lead_1 AND AVAIL_1 THEN RUN_1 := TRUE;
        ELSIF NOT lead_1 AND AVAIL_2 THEN RUN_2 := TRUE;
        ELSIF AVAIL_1 THEN RUN_1 := TRUE;
        ELSIF AVAIL_2 THEN RUN_2 := TRUE;
        END_IF;
    END_IF;
    IF DEMAND >= 2.0 THEN
        IF AVAIL_1 THEN RUN_1 := TRUE; END_IF;
        IF AVAIL_2 THEN RUN_2 := TRUE; END_IF;
    END_IF;
    IF lead_1 THEN LEAD := 1.0; ELSE LEAD := 2.0; END_IF;
    IF DT > 0.0 AND ((lead_1 AND RUN_1) OR ((NOT lead_1) AND RUN_2)) THEN
        hours := hours + DT / 3600.0;
    END_IF;
END_FUNCTION_BLOCK
