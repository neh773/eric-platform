(* ERIC_PID is an Apache-2.0 PID. Derivative acts on PV, through a
   first-order filter, so a setpoint step does not kick the output.
   DIRECT = TRUE means error = SP - PV (output rises when PV is below SP).
   DIRECT = FALSE means error = PV - SP.
   TI <= 0 turns integration off. TD <= 0 or N <= 0 turns the derivative off.
   N is the filter factor: filter time = TD / N.
   DT is the cycle time in seconds (the task period, or a measured interval).
   While the output is driven into a limit, the integrator is set to the
   value that reproduces the clamped output (back-calculation). It integrates
   only when the error is not pushing further into that limit.
   Manual mode tracks the integrator so leaving manual does not jump.
   A gain, time, filter, or action change on an automatic scan keeps the
   previous output for that scan. *)
FUNCTION_BLOCK ERIC_PID
VAR_INPUT
    SP : LREAL;
    PV : LREAL;
    MANUAL : BOOL;
    MAN_OUT : LREAL;
    KP : LREAL;
    TI : LREAL;
    TD : LREAL;
    N : LREAL;
    OUT_MIN : LREAL;
    OUT_MAX : LREAL;
    DIRECT : BOOL;
    DT : LREAL;
    RESET : BOOL;
END_VAR
VAR_OUTPUT
    OUT : LREAL;
    ERROR : LREAL;
    LIMITED_HIGH : BOOL;
    LIMITED_LOW : BOOL;
END_VAR
VAR
    integral : LREAL;
    d_filt : LREAL;
    pv_prev : LREAL;
    out_prev : LREAL;
    kp_prev : LREAL;
    ti_prev : LREAL;
    td_prev : LREAL;
    n_prev : LREAL;
    direct_prev : BOOL;
    manual_prev : BOOL;
    started : BOOL;
    sign : LREAL;
    p_term : LREAL;
    d_term : LREAL;
    raw_d : LREAL;
    tf : LREAL;
    alpha : LREAL;
    unlimited : LREAL;
    hold : BOOL;
    drive_high : BOOL;
    drive_low : BOOL;
END_VAR
    IF DIRECT THEN
        sign := 1.0;
    ELSE
        sign := -1.0;
    END_IF;
    ERROR := sign * (SP - PV);

    IF NOT started THEN
        started := TRUE;
        pv_prev := PV;
        d_filt := 0.0;
        integral := 0.0;
        out_prev := 0.0;
        kp_prev := KP;
        ti_prev := TI;
        td_prev := TD;
        n_prev := N;
        direct_prev := DIRECT;
        manual_prev := MANUAL;
    END_IF;

    d_term := 0.0;
    IF (NOT RESET) AND DT > 0.0 AND TD > 0.0 AND N > 0.0 THEN
        raw_d := (PV - pv_prev) / DT;
        tf := TD / N;
        alpha := DT / (tf + DT);
        d_filt := d_filt + alpha * (raw_d - d_filt);
        d_term := -KP * TD * sign * d_filt;
    ELSE
        d_filt := 0.0;
    END_IF;
    pv_prev := PV;
    p_term := KP * ERROR;

    hold := (NOT RESET) AND (NOT MANUAL) AND ((manual_prev AND (NOT MANUAL)) OR ((NOT manual_prev) AND ((KP <> kp_prev) OR (TI <> ti_prev) OR (TD <> td_prev) OR (N <> n_prev) OR (DIRECT <> direct_prev))));

    IF RESET THEN
        integral := 0.0;
        d_filt := 0.0;
        d_term := 0.0;
        IF MANUAL THEN
            unlimited := MAN_OUT;
        ELSE
            unlimited := p_term;
        END_IF;
    ELSIF MANUAL THEN
        unlimited := MAN_OUT;
    ELSIF hold THEN
        integral := out_prev - p_term - d_term;
        unlimited := out_prev;
    ELSE
        unlimited := p_term + integral + d_term;
    END_IF;

    IF unlimited > OUT_MAX THEN
        OUT := OUT_MAX;
    ELSIF unlimited < OUT_MIN THEN
        OUT := OUT_MIN;
    ELSE
        OUT := unlimited;
    END_IF;
    LIMITED_HIGH := OUT >= OUT_MAX;
    LIMITED_LOW := OUT <= OUT_MIN;
    IF MANUAL AND (NOT RESET) THEN
        integral := OUT - p_term - d_term;
    END_IF;

    IF (NOT RESET) AND (NOT MANUAL) AND (NOT hold) AND TI > 0.0 AND DT > 0.0 THEN
        drive_high := (unlimited >= OUT_MAX) AND (ERROR > 0.0);
        drive_low := (unlimited <= OUT_MIN) AND (ERROR < 0.0);
        IF drive_high OR drive_low THEN
            integral := OUT - p_term - d_term;
        ELSE
            integral := integral + (KP * ERROR * DT / TI);
            IF OUT <> unlimited THEN
                integral := integral + (OUT - unlimited);
            END_IF;
        END_IF;
    ELSIF (NOT MANUAL) AND (TI <= 0.0) AND (NOT hold) AND (NOT RESET) THEN
        integral := 0.0;
    END_IF;

    out_prev := OUT;
    kp_prev := KP;
    ti_prev := TI;
    td_prev := TD;
    n_prev := N;
    direct_prev := DIRECT;
    manual_prev := MANUAL;
END_FUNCTION_BLOCK
