# Write, check, and deploy

## Write a program

1. In Logic, type a name under Programs and choose **New program**.
2. The editor opens a source buffer for that name.
3. A program starts with `PROGRAM` and the name, then `VAR` for variables, then the statements, then `END_PROGRAM`.

A small program that copies one input to one output:

```
PROGRAM Mirror
VAR
    Level AT %ID0 : LREAL;
    Echo AT %QD0 : LREAL;
END_VAR
    Echo := Level;
END_PROGRAM
```

`%ID0` and `%QD0` are the addresses you also put on the tag map. The map is what connects those addresses to gateway tags. See [Tags](tags.md).

## Check

1. Choose **Check program**.
2. The editor saves the source and runs the compiler.
3. Problems are listed as line, column, and message. Fix the line it names and check again.
4. An empty problem list means the compiler accepted the source. It does not mean the program is running.

## Deploy

1. Save the tag map if you changed it.
2. Choose **Compile and deploy**.
3. If the compiler rejects the source, the status says compile failed and the problems stay listed. The previous running program is left as it was.
4. If deploy succeeds, the runtime pane shows the engine state. Choose **Start** if it is stopped.

Deploy replaces the program the engine is running. It is not an online change of one rung. Expect the task to load the new program as a whole.

## Rename and delete

Rename asks for the new name and then opens it. Delete removes that program from the gateway. Deleting the program that is deployed does not by itself prove the engine has stopped. Choose **Stop** first if you want the engine idle.
