# Structures and gateway UDTs

ERIC is beta software. Use it only with hardware for testing purposes in safe conditions.

A Structured Text program can work with whole records instead of loose tags. A lift station is one record: a level,
a fault, a command, a few alarms. You can declare that record as an ST `STRUCT`, or use a UDT you already defined on
the gateway's **UDT Definitions** page. Either way, you bind each structure variable to a gateway UDT instance from a
list in Logic. You do not write an `AT` address or a mapping row for each member.

## Use a gateway UDT as an ST type

Every UDT definition on the gateway is available as an ST type. The type name is the last part of the UDT path:
`Types/LiftStation` is `LiftStation`. When two UDTs share that last part, the whole path is used with `/` turned
into `_` (`Types_LiftStation`). A name that is an ST keyword, or that starts with `ERIC_`, gets `_UDT` added.

The editor's completion list offers the UDT type names. Members keep their names, and their data types map like
this:

| Gateway type | ST type |
| --- | --- |
| Boolean | `BOOL` |
| Int32 | `DINT` |
| Int64 | `LINT` (not moved by the engine) |
| Float | `REAL` |
| Double | `LREAL` |
| String | `STRING` (not moved by the engine) |

A member that is another UDT becomes a nested structure. You can also declare your own `TYPE ... STRUCT` in the
program. Its fields are matched to the bound UDT's members by name, ignoring case.

## Pass a structure to a function block

Declare the parameter `VAR_IN_OUT` so the block reads and writes the caller's record:

```
FUNCTION_BLOCK FB_CentralInhibit
VAR_IN_OUT
    Central : LiftStation;
    OsA, OsB, OsC : LiftStation;
END_VAR
    Central.AlmHH := Central.Level >= 95.0;
    ...
END_FUNCTION_BLOCK

PROGRAM CollectionInterlock
VAR
    Cls1, Os1A, Os1B, Os1C : LiftStation;
    Cls2, Os2A, Os2B, Os2C : LiftStation;
    Grp1, Grp2 : FB_CentralInhibit;
END_VAR
    Grp1(Central := Cls1, OsA := Os1A, OsB := Os1B, OsC := Os1C);
    Grp2(Central := Cls2, OsA := Os2A, OsB := Os2B, OsC := Os2C);
END_PROGRAM
```

A `VAR_INPUT` structure parameter also works. The block gets a copy, so what it writes to that copy does not reach
the caller or the tags.

## Bind the variables to UDT instances

1. In Logic, choose **Check program**. The **UDT instances** section lists each structure variable in the program.
2. For each variable, pick the gateway instance in **UDT instance for** *name*, for example
   `[default]MiamiDade/CLS-1`.
3. The members table shows every member, its tag and its direction. **auto** is the usual choice: a member the
   program writes (directly, or through a `VAR_IN_OUT` call) is an output, and every other member is an input.
   Pick **in**, **out** or **none** to override one member.
4. Choose **Compile and deploy**. The binding is saved with the tag map.

The engine reads the input members from the tags at the start of each scan and writes the output members at the
end, like any mapped variable. A member with bad quality supplies its safe value (`false` or `0`).

## Watch members online

While the program runs, the runtime pane groups watched values per structure. Open **Members of** *name* **online**
to see each member's live value, for example `Os1A.InhibitCmd: true`.

## Problems and how they show

- In the **UDT instances** section, a red line under the variable names a problem with the binding, such as an
  instance path that is not a UDT instance.
- In the members table, a field of your own `STRUCT` with no member of the same name in the UDT says
  "the UDT has no member named ...". It is not moved. A member whose type the engine does not move (`STRING`,
  `LINT`) says so and is not moved either.
- **Check program** lists compiler problems by line and column, as for any program. A problem inside the UDT types
  the gateway adds to the program is reported on line 1 and starts with "In the gateway UDT types added to this
  program:". Fix the UDT on the UDT Definitions page.

## Limits in this build

- Arrays of structures are not supported. Declare one variable per station.
- Retain is not supported. A latch inside a function block starts off after a deploy or an engine restart.
- A program can bind up to the engine's address space for generated members: 1000 and up in each `%I` / `%Q`
  area, which leaves `0`-`999` for your own `AT` addresses.
