(* Start/stop seal-in. The start button must stay true for 200 ms (TON from the
   RuSTy standard library) before the motor seals in. Stop breaks the seal.
   The analog input is a raw count, 0..32767, scaled to 0..1000. *)
PROGRAM SealIn
VAR
    StartPB AT %IX0.0 : BOOL;
    StopPB AT %IX0.1 : BOOL;
    LevelRaw AT %IW2 : INT;
    Motor AT %QX1.0 : BOOL;
    LevelOut AT %QW4 : INT;
    Seal : BOOL;
    Arm : TON;
END_VAR
    Arm(IN := StartPB, PT := T#200ms);
    Seal := (Arm.Q OR Seal) AND NOT StopPB;
    Motor := Seal;
    LevelOut := REAL_TO_INT(INT_TO_REAL(LevelRaw) * 1000.0 / 32767.0);
END_PROGRAM
