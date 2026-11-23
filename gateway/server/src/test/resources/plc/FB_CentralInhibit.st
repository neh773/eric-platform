(* FB_CentralInhibit — design intent in IEC 61131-3 Structured Text.
   One function block handles one central collection lift station and the
   three outstations that feed it. Call the block twice in the program to
   cover all eight stations.

   This file shows the natural form: a station structure passed IN_OUT.
   ERIC's Structured Text dialect does not document STRUCT or VAR_IN_OUT,
   and gateway UDTs are not ST structures. Use CentralInhibit.eric.st in
   the Logic editor. See ../st-limitations.md.
*)

TYPE
    ST_LiftStation : STRUCT
        Level              : LREAL; (* Wet well level, % of well depth *)
        CommOk             : BOOL;  (* TRUE while communications are good *)
        PumpFault          : BOOL;  (* TRUE if any duty pump is faulted *)
        InhibitCmd         : BOOL;  (* Stop / inhibit command to this station *)
        OverrideActive     : BOOL;  (* Outstation allowed to run at its own High-High *)
        AlmHH              : BOOL;  (* Wet well at or above High-High *)
        AlmCommLoss        : BOOL;
        AlmPumpFault       : BOOL;
        AlmCannotRelieve   : BOOL;  (* Override requested, but the pumps cannot run *)
        AlmCannotEvacuate  : BOOL;  (* Central inhibit is active and a central pump is faulted *)
        AlmPumpIntoOverflow : BOOL; (* Alarm: Pumping into overflow condition *)
    END_STRUCT;
END_TYPE

FUNCTION_BLOCK FB_CentralInhibit
VAR_IN_OUT
    Central : ST_LiftStation;
    OS1     : ST_LiftStation;
    OS2     : ST_LiftStation;
    OS3     : ST_LiftStation;
END_VAR
VAR_INPUT
    Enable        : BOOL;  (* FALSE releases all inhibit commands *)
    Central_HH    : LREAL; (* Set inhibit latch, e.g. 95.0 % *)
    Central_Reset : LREAL; (* Clear inhibit latch, e.g. 85.0 % (High) *)
    OS_HH         : LREAL; (* Outstation High-High, e.g. 95.0 % *)
    OS_Reset      : LREAL; (* Release outstation override, e.g. 85.0 % *)
END_VAR
VAR_OUTPUT
    InhibitActive : BOOL;  (* Latched: this central is holding its outstations *)
END_VAR
VAR
    HhConfirm : TON;
    ConfirmIn : BOOL;
END_VAR

    (* --- Central communications, enable, and High-High latch with hysteresis --- *)
    Central.AlmCommLoss := NOT Central.CommOk;
    Central.AlmHH := Central.Level >= Central_HH;
    Central.AlmPumpFault := Central.PumpFault;

    ConfirmIn := Enable AND Central.CommOk AND (Central.Level >= Central_HH);
    HhConfirm(IN := ConfirmIn, PT := T#2s);

    IF (NOT Enable) OR (NOT Central.CommOk) OR (Central.Level < Central_Reset) THEN
        InhibitActive := FALSE;
    ELSIF HhConfirm.Q THEN
        InhibitActive := TRUE;
    END_IF;

    Central.AlmCannotEvacuate := InhibitActive AND Central.PumpFault;
    Central.InhibitCmd := FALSE;
    Central.OverrideActive := FALSE;
    Central.AlmCannotRelieve := FALSE;

    (* --- Outstation 1 --- *)
    OS1.AlmCommLoss := NOT OS1.CommOk;
    OS1.AlmHH := OS1.Level >= OS_HH;
    OS1.AlmPumpFault := OS1.PumpFault;
    IF NOT InhibitActive THEN
        OS1.InhibitCmd := FALSE;
        OS1.OverrideActive := FALSE;
    ELSE
        IF OS1.Level >= OS_HH THEN
            OS1.OverrideActive := TRUE;
        ELSIF OS1.Level < OS_Reset THEN
            OS1.OverrideActive := FALSE;
        END_IF;
        IF NOT OS1.CommOk THEN
            OS1.OverrideActive := FALSE;
        END_IF;
        OS1.InhibitCmd := NOT OS1.OverrideActive;
    END_IF;
    OS1.AlmCannotRelieve := OS1.OverrideActive AND OS1.PumpFault;
    OS1.AlmPumpIntoOverflow := InhibitActive AND OS1.OverrideActive;
    OS1.AlmCannotEvacuate := FALSE;

    (* --- Outstation 2 --- *)
    OS2.AlmCommLoss := NOT OS2.CommOk;
    OS2.AlmHH := OS2.Level >= OS_HH;
    OS2.AlmPumpFault := OS2.PumpFault;
    IF NOT InhibitActive THEN
        OS2.InhibitCmd := FALSE;
        OS2.OverrideActive := FALSE;
    ELSE
        IF OS2.Level >= OS_HH THEN
            OS2.OverrideActive := TRUE;
        ELSIF OS2.Level < OS_Reset THEN
            OS2.OverrideActive := FALSE;
        END_IF;
        IF NOT OS2.CommOk THEN
            OS2.OverrideActive := FALSE;
        END_IF;
        OS2.InhibitCmd := NOT OS2.OverrideActive;
    END_IF;
    OS2.AlmCannotRelieve := OS2.OverrideActive AND OS2.PumpFault;
    OS2.AlmPumpIntoOverflow := InhibitActive AND OS2.OverrideActive;
    OS2.AlmCannotEvacuate := FALSE;

    (* --- Outstation 3 --- *)
    OS3.AlmCommLoss := NOT OS3.CommOk;
    OS3.AlmHH := OS3.Level >= OS_HH;
    OS3.AlmPumpFault := OS3.PumpFault;
    IF NOT InhibitActive THEN
        OS3.InhibitCmd := FALSE;
        OS3.OverrideActive := FALSE;
    ELSE
        IF OS3.Level >= OS_HH THEN
            OS3.OverrideActive := TRUE;
        ELSIF OS3.Level < OS_Reset THEN
            OS3.OverrideActive := FALSE;
        END_IF;
        IF NOT OS3.CommOk THEN
            OS3.OverrideActive := FALSE;
        END_IF;
        OS3.InhibitCmd := NOT OS3.OverrideActive;
    END_IF;
    OS3.AlmCannotRelieve := OS3.OverrideActive AND OS3.PumpFault;
    OS3.AlmPumpIntoOverflow := InhibitActive AND OS3.OverrideActive;
    OS3.AlmCannotEvacuate := FALSE;

    (* Group summary: any outstation is pumping into a central at High-High. *)
    Central.AlmPumpIntoOverflow := OS1.AlmPumpIntoOverflow
        OR OS2.AlmPumpIntoOverflow
        OR OS3.AlmPumpIntoOverflow;

END_FUNCTION_BLOCK

PROGRAM CollectionInterlock
VAR
    Enable      : BOOL := TRUE;
    Central_HH  : LREAL := 95.0;
    Central_Reset : LREAL := 85.0;
    OS_HH       : LREAL := 95.0;
    OS_Reset    : LREAL := 85.0;

    Cls1 : ST_LiftStation;
    Os1A : ST_LiftStation;
    Os1B : ST_LiftStation;
    Os1C : ST_LiftStation;
    Cls2 : ST_LiftStation;
    Os2A : ST_LiftStation;
    Os2B : ST_LiftStation;
    Os2C : ST_LiftStation;

    Grp1 : FB_CentralInhibit;
    Grp2 : FB_CentralInhibit;
END_VAR

    (* Two calls, eight station structures. Bind members to I/O in a full project. *)
    Grp1(Central := Cls1, OS1 := Os1A, OS2 := Os1B, OS3 := Os1C,
         Enable := Enable,
         Central_HH := Central_HH, Central_Reset := Central_Reset,
         OS_HH := OS_HH, OS_Reset := OS_Reset);

    Grp2(Central := Cls2, OS1 := Os2A, OS2 := Os2B, OS3 := Os2C,
         Enable := Enable,
         Central_HH := Central_HH, Central_Reset := Central_Reset,
         OS_HH := OS_HH, OS_Reset := OS_Reset);

END_PROGRAM
