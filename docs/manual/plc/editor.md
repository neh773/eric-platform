# Structured Text editor

The editor is the Logic section of Control Canvas.

## Open it

1. Open Control Canvas and sign in.
2. Open the project.
3. Choose **Logic** in the workspace switch at the top. **Screens** is the HMI editor. **Logic** is the program editor.

On Windows, a banner stays at the top of Logic while the gateway is running on Windows. It cannot be closed. It says the soft-PLC is not a real-time controller. Leave it there.

## The three panes

- **Programs**, on the left. The list of programs, New program, Rename, and Delete.
- **Structured Text**, in the middle. The source, Insert symbol, Check program, and Compile and deploy.
- **Tag mapping and runtime**, on the right. The map, Start, Stop, status, and watched variables.

## Insert a keyword

The Insert list holds the keywords and block names the editor knows: `PROGRAM`, `VAR`, `IF`, the integer and real types, `TON`, `TOF`, `TP`, `ERIC_PID`, and the other process blocks. Choose one and choose **Insert symbol**. It is typed into the source. It does not compile by itself.

## Power user

Programs are stored by the gateway under the logic API. `GET /api/v1/logic/programs` lists names. Saving sends the source text. Check runs the bundled RuSTy compiler and returns line and column diagnostics. Deploy compiles and loads the result into `eric-plc`.
