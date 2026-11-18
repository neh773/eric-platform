# PLC and gateway tags

The scan engine does not browse the gateway tag tree by itself. You map a program address to a tag path. On each scan the engine reads the inputs from those tags and writes the outputs back.

## Map a variable

1. In Logic, open Tag mapping.
2. Choose **Add mapping**.
3. Variable is the name in the program.
4. Address is the location, such as `%ID0` for a real input or `%QD0` for a real output. A boolean output often uses `%QX0.0`.
5. Tag is the gateway tag path.
6. Direction is `in` for a value the program reads, or `out` for a value the program writes.
7. Safe is the value used when the tag is bad or missing. `false` or `0` is the usual choice. Pick a safe value that leaves the equipment off.
8. Choose **Save mapping**, then **Compile and deploy**.

The program declares the same address:

```
VAR
    Level AT %ID0 : LREAL;
    Speed AT %QD0 : LREAL;
END_VAR
```

## Modbus

A Modbus device is a gateway connection, not a PLC instruction. Add the device on the Connections page, create a tag with a Modbus address, and put that tag path in the map. The engine then sees a number. It does not open a Modbus socket of its own. Direct I/O from the engine is on the wishlist.

## Direction

An `in` row is copied into the program at the start of the scan. An `out` row is copied to the tag at the end of the scan. Do not map the same tag as both the input and the output of one scan unless you mean the program to overwrite it.

## Power user

The saved file is JSON with `inputs` and `outputs`. Each item has `name`, `address`, `tag`, `type`, and `safe`. Edit as JSON in the mapping pane if the form is in the way, then save. `type` is `BOOL` or a numeric type. `safe` is a boolean for `BOOL` and a number otherwise.
