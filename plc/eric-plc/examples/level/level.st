(* Level loop. DT is this task's period, 100 ms. Tuning values are tags so
   they can be changed without editing the program. *)
PROGRAM LevelLoop
VAR
    SP AT %IL0 : LREAL;
    PV AT %IL1 : LREAL;
    ManOut AT %IL2 : LREAL;
    KP AT %IL3 : LREAL;
    TI AT %IL4 : LREAL;
    TD AT %IL5 : LREAL;
    FilterN AT %IL6 : LREAL;
    OutMin AT %IL7 : LREAL;
    OutMax AT %IL8 : LREAL;
    Manual AT %IX0.0 : BOOL;
    Direct AT %IX0.1 : BOOL;
    Reset AT %IX0.2 : BOOL;
    Out AT %QL20 : LREAL;
    Err AT %QL21 : LREAL;
    LimitedHigh AT %QX1.0 : BOOL;
    LimitedLow AT %QX1.1 : BOOL;
    Loop : ERIC_PID;
END_VAR
    Loop(
        SP := SP,
        PV := PV,
        MANUAL := Manual,
        MAN_OUT := ManOut,
        KP := KP,
        TI := TI,
        TD := TD,
        N := FilterN,
        OUT_MIN := OutMin,
        OUT_MAX := OutMax,
        DIRECT := Direct,
        DT := 0.1,
        RESET := Reset
    );
    Out := Loop.OUT;
    Err := Loop.ERROR;
    LimitedHigh := Loop.LIMITED_HIGH;
    LimitedLow := Loop.LIMITED_LOW;
END_PROGRAM
