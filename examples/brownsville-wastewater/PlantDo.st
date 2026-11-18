PROGRAM PlantDo
VAR
    Loop : ERIC_PID;
    Out AT %QL0 : LREAL;
    Sp : LREAL := 2.0;
    Pv : LREAL := 1.4;
END_VAR
(* Aeration dissolved-oxygen loop scanned by the soft-PLC.
   The collection mass balance lives in the gateway timer, which is what
   keeps every station, basin and plant on one clock. This block is the
   plant PID the engine actually scans. *)
Loop(
    SP := Sp,
    PV := Pv,
    KP := 1.2,
    TI := 8.0,
    TD := 0.0,
    N := 10.0,
    OUT_MIN := 20.0,
    OUT_MAX := 100.0,
    DIRECT := TRUE,
    DT := 0.1
);
Out := Loop.OUT;
END_PROGRAM
